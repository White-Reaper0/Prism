package com.nevcortex.prism.camera

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.nevcortex.prism.core.util.BoundedRingBuffer
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.core.util.Time
import com.nevcortex.prism.model.FrameSample
import com.nevcortex.prism.model.FrameSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ImageAnalysis.Analyzer that converts each frame into a [FrameSample] carrying the camera-HAL
 * timestamp AND the alignment-clock arrival time, then forwards it to the recorder channel.
 *
 * We DO NOT keep frame bitmaps in memory. For VIDEO mode the MP4 holds the pixels; here we only
 * emit metadata (timestamps, size, exposure) for the frame-timestamp manifest.
 *
 * Camera frame timestamps come from the camera HAL clock, which is not guaranteed to share an
 * origin with the IMU clock. The recorder + sync diagnostics measure the offset; we never claim
 * synchronization implicitly.
 */
class FrameCollector : ImageAnalysis.Analyzer {

    private var index = 0L
    private var lastTs = 0L
    private val intervals = BoundedRingBuffer<Long>(512)
    private val offsets = BoundedRingBuffer<Long>(256)
    private var firstTs = 0L
    private var lastArrival = 0L
    private var totalDrops = 0

    var recordChannel: Channel<FrameSample>? = null
        private set
    var recording = false
    var expectedTargetFps: Float = 20f

    /**
     * Optional live-stream consumer. Called on the analysis executor before the image is closed.
     * Implementations must be bounded and must return quickly; throwing here cannot break the
     * frame metadata path. Set to null to disable streaming.
     */
    @Volatile
    var frameListener: ((ImageProxy, FrameSample) -> Unit)? = null

    private val _stats = MutableStateFlow(FrameCollectorStats())
    val stats: StateFlow<FrameCollectorStats> = _stats.asStateFlow()

    fun setRecording(on: Boolean, targetFps: Float) {
        recording = on
        expectedTargetFps = targetFps
        if (on && recordChannel == null) {
            recordChannel = Channel(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        } else if (!on) {
            recordChannel?.close()
            recordChannel = null
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        try {
            val info = image.imageInfo
            val camTs = info.timestamp
            val arrival = Time.elapsedNanos()
            val d = camTs - lastTs
            if (lastTs != 0L) {
                if (d > 0) intervals.add(d) else Logger.w(TAG, "non-monotonic camera timestamp")
            }
            if (firstTs == 0L) firstTs = camTs
            lastTs = camTs
            lastArrival = arrival
            offsets.add(arrival - camTs)

            val sample = FrameSample(
                frameIndex = index++,
                cameraTimestampNs = camTs,
                arrivalElapsedNs = arrival,
                width = image.width,
                height = image.height,
                format = image.format,
                source = FrameSource.ANALYSIS,
                rotationDegrees = image.imageInfo.rotationDegrees,
                iso = 0,
                exposureNs = 0,
                aperture = 0f
            )

            if (recording) {
                val ch = recordChannel
                if (ch != null && ch.trySend(sample).isFailure) totalDrops++
            }

            // Live streaming is best-effort and never blocks or breaks metadata capture.
            frameListener?.let { listener ->
                runCatching { listener(image, sample) }
                    .onFailure { Logger.w(TAG, "live frame listener failed", it) }
            }

            // periodic stats
            if (index % 15 == 0L) emitStats()
        } finally {
            image.close()
        }
    }

    private fun emitStats() {
        val ivs = intervals.snapshot()
        val ofs = offsets.snapshot()
        val med = if (ivs.isNotEmpty()) sortedMed(ivs) else 0L
        val fps = if (med > 0) (1e9f / med).toFloat() else 0f
        // dropped-frame heuristic: frames whose interval exceeded 2.5x median are "missed"
        val drops = if (ivs.isNotEmpty() && med > 0) ivs.count { it > med * 2.5 } else 0
        _stats.value = FrameCollectorStats(
            frameCount = index,
            achievedFps = fps,
            intervalMedianNs = med,
            intervalStdNs = if (ivs.size > 1) stddev(ivs).toLong() else 0L,
            offsetMedianNs = if (ofs.isNotEmpty()) sortedMed(ofs) else 0L,
            offsetJitterNs = if (ofs.size > 1) (ofs.max() - ofs.min()) else 0L,
            droppedFrames = drops + totalDrops,
            firstNs = firstTs,
            lastNs = lastTs
        )
    }

    private fun sortedMed(xs: List<Long>): Long = xs.sorted()[xs.size / 2]
    private fun stddev(xs: List<Long>): Double {
        val m = xs.average()
        return kotlin.math.sqrt(xs.map { (it - m) * (it - m) }.average())
    }

    companion object { private const val TAG = "FrameCollector" }
}

data class FrameCollectorStats(
    val frameCount: Long = 0L,
    val achievedFps: Float = 0f,
    val intervalMedianNs: Long = 0L,
    val intervalStdNs: Long = 0L,
    val offsetMedianNs: Long = 0L,
    val offsetJitterNs: Long = 0L,
    val droppedFrames: Int = 0,
    val firstNs: Long = 0L,
    val lastNs: Long = 0L
)
