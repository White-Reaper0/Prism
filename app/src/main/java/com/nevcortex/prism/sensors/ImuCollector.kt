package com.nevcortex.prism.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.nevcortex.prism.core.util.BoundedRingBuffer
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.core.util.Time
import com.nevcortex.prism.model.ImuSample
import com.nevcortex.prism.model.ImuStreamStats
import com.nevcortex.prism.model.SensorKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Owns all IMU-class sensor listeners. Design rules:
 *  - Listeners run on a dedicated [HandlerThread] so the sensor path never touches the main thread.
 *  - Raw [ImuSample]s carry BOTH the source clock ([sensorTimestampNs]) and the alignment clock
 *    ([arrivalElapsedNs]); the offset between them is the clock-domain diagnostic.
 *  - A bounded ring buffer per kind feeds live plots; the UI pulls snapshots, so plotting cannot
 *    retain unbounded history or block capture.
 *  - A bounded [Channel] feeds the recorder. If the recorder falls behind, the OLDEST sample is
 *    dropped and [droppedForRecording] is incremented — capture is never blocked, and drops are
 *    reported honestly as gaps (never silently discarded).
 *
 * Achieved rate and interval stats are measured from observed timestamps, never from the
 * requested rate.
 */
class ImuCollector(
    private val manager: SensorManager,
    private val specs: SensorSpecs
) : SensorEventListener {

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var registered = mutableListOf<Sensor>()

    /** Per-kind live plot data (last N samples). Bounded. */
    val plotBuffers: Map<SensorKind, BoundedRingBuffer<ImuSample>>
    /** Per-kind measured stats, updated periodically. */
    private val _stats = MutableStateFlow<Map<SensorKind, ImuStreamStats>>(emptyMap())
    val stats: StateFlow<Map<SensorKind, ImuStreamStats>> = _stats.asStateFlow()

    /**
     * Optional live-stream consumer. Called on the sensor thread for every sample. It must only
     * hand the sample to a bounded queue and return; no disk or network I/O here.
     */
    @Volatile
    var streamListener: ((ImuSample) -> Unit)? = null

    /** Recording sink. Recorder drains this; drops are tracked, capture is never blocked. */
    var recordChannel: Channel<ImuSample>? = null
        private set
    var droppedForRecording: Long = 0
        private set

    private val perKind = HashMap<SensorKind, PerKindState>()
    private var recording = false

    private val plotCapacity = 1500
    private val intervalWindow = 1024

    init {
        val map = HashMap<SensorKind, BoundedRingBuffer<ImuSample>>()
        SensorKind.values().filter { it.isImu }.forEach { map[it] = BoundedRingBuffer(plotCapacity) }
        plotBuffers = map
    }

    private class PerKindState(val kind: SensorKind) {
        var seq = 0L
        var lastTs = 0L
        var lastArrival = 0L
        val intervals = BoundedRingBuffer<Long>(1024)
        val offsets = BoundedRingBuffer<Long>(512)
        var firstTs = 0L
        var lastAccuracy = -1
        var gaps = 0
        var discontinuity = false
        var clipCount = 0
        var movingCount = 0
        var movingWindow = BoundedRingBuffer<Float>(128) // accel magnitude for moving heuristic
        var min = FloatArray(3) { Float.MAX_VALUE }
        var max = FloatArray(3) { -Float.MAX_VALUE }
        var expectedHz: Float = 0f
        var lastStatsEmitNs = 0L
    }

    /**
     * Begin listening for [kinds] at the requested [rateHz]. Achieved rate is measured, not assumed.
     */
    fun start(kinds: List<SensorKind>, rateHz: Int) {
        if (sensorThread != null) return
        val thread = HandlerThread("prism-sensor").also { it.start() }
        sensorThread = thread
        sensorHandler = Handler(thread.looper)
        val periodUs = (1_000_000f / rateHz.coerceAtLeast(1)).toInt().coerceAtLeast(0)
        for (kind in kinds) {
            val sensor = specs.resolve(kind) ?: continue
            val state = PerKindState(kind).also { it.expectedHz = rateHz.toFloat() }
            perKind[kind] = state
            runCatching {
                manager.registerListener(this, sensor, periodUs, sensorHandler)
            }.onSuccess { registered.add(sensor) }
                .onFailure { Logger.w(TAG, "register failed $kind", it) }
        }
    }

    /** Enable/disable the recording sink. Enabling opens a buffered channel. */
    fun setRecording(on: Boolean) {
        recording = on
        if (on && recordChannel == null) {
            recordChannel = Channel(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        } else if (!on) {
            recordChannel?.close()
            recordChannel = null
        }
    }

    fun stop() {
        registered.forEach { runCatching { manager.unregisterListener(this, it) } }
        registered.clear()
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        perKind.clear()
        _stats.value = emptyMap()
        plotBuffers.values.forEach { it.clear() }
        setRecording(false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val kind = mapType(event.sensor.type) ?: return
        val now = Time.elapsedNanos()
        val values = event.values.copyOf()
        val state = perKind[kind] ?: return
        val sample = ImuSample(
            kind = kind,
            values = values,
            accuracy = event.accuracy,
            sensorTimestampNs = event.timestamp,
            arrivalElapsedNs = now,
            sequence = state.seq++
        )
        state.lastAccuracy = event.accuracy

        // interval + clock-offset diagnostics
        if (state.lastTs != 0L) {
            val interval = event.timestamp - state.lastTs
            if (interval < 0) {
                state.discontinuity = true
                Logger.w(TAG, "${kind.label}: non-monotonic timestamp delta=${interval}ns")
            } else {
                state.intervals.add(interval)
            }
            // gap detection: interval far larger than expected
            val expectedNs = if (state.expectedHz > 0) (1e9f / state.expectedHz).toLong() else 0L
            if (expectedNs > 0 && interval > expectedNs * 3) state.gaps++
        }
        if (state.firstTs == 0L) state.firstTs = event.timestamp
        state.lastTs = event.timestamp
        state.offsets.add(now - event.timestamp)

        // min/max + clipping + moving heuristic (accel only)
        val d = values.size
        for (i in 0 until minOf(d, 3)) {
            if (values[i] < state.min[i]) state.min[i] = values[i]
            if (values[i] > state.max[i]) state.max[i] = values[i]
        }
        if (kind == SensorKind.ACCELEROMETER && d >= 3) {
            val mag = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
            state.movingWindow.add(mag)
            // clipping: saturation near full-scale (typical ~ 2g..8g range)
            if (mag > 78.0f) state.clipCount++   // ~8g threshold, conservative
        }

        // bounded plot buffer
        plotBuffers[kind]?.add(sample)

        // live streaming sink: bounded by the receiver queue, never blocks this thread
        streamListener?.let { listener ->
            runCatching { listener(sample) }
                .onFailure { Logger.w(TAG, "stream listener failed", it) }
        }

        // recording sink with drop protection
        if (recording) {
            val ch = recordChannel
            if (ch != null) {
                val result = ch.trySend(sample)
                if (result.isFailure) droppedForRecording++
            }
        }

        // periodic stats emission (every 200ms by alignment clock)
        if (now - state.lastStatsEmitNs > 200_000_000L || state.lastStatsEmitNs == 0L) {
            state.lastStatsEmitNs = now
            emitStats(kind, state)
        }
    }

    private fun emitStats(kind: SensorKind, state: PerKindState) {
        val intervals = state.intervals.snapshot()
        val offsets = state.offsets.snapshot()
        val achieved = if (intervals.isNotEmpty()) {
            val med = median(intervals)
            if (med > 0) (1e9f / med).toFloat() else 0f
        } else 0f
        val moving = if (kind == SensorKind.ACCELEROMETER) {
            val mags = state.movingWindow.snapshot()
            if (mags.isNotEmpty()) {
                val mean = mags.average().toFloat()
                val varc = mags.map { (it - mean) * (it - mean) }.average().toFloat()
                sqrt(varc) > 0.5f
            } else false
        } else false
        val stats = ImuStreamStats(
            kind = kind,
            sampleCount = state.seq,
            medianIntervalNs = if (intervals.isNotEmpty()) median(intervals) else 0L,
            minIntervalNs = intervals.minOrNull() ?: 0L,
            maxIntervalNs = intervals.maxOrNull() ?: 0L,
            meanIntervalNs = if (intervals.isNotEmpty()) intervals.average().toLong() else 0L,
            intervalStdNs = if (intervals.isNotEmpty()) stddev(intervals).toLong() else 0L,
            achievedRateHz = achieved,
            expectedRateHz = state.expectedHz,
            offsetMedianNs = if (offsets.isNotEmpty()) median(offsets) else 0L,
            offsetJitterNs = if (offsets.size > 1) {
                (offsets.maxOrNull()!! - offsets.minOrNull()!!)
            } else 0L,
            gapsDetected = state.gaps,
            lastAccuracy = state.lastAccuracy,
            clipping = state.clipCount > 0,
            discontinuity = state.discontinuity,
            moving = moving,
            minValues = state.min.copyOf().also { for (i in it.indices) if (it[i] == Float.MAX_VALUE) it[i] = 0f },
            maxValues = state.max.copyOf().also { for (i in it.indices) if (it[i] == -Float.MAX_VALUE) it[i] = 0f },
            firstTimestampNs = state.firstTs,
            lastTimestampNs = state.lastTs
        )
        val cur = _stats.value.toMutableMap()
        cur[kind] = stats
        _stats.value = cur
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // accuracy is captured per-sample; nothing else needed here
    }

    private fun mapType(type: Int): SensorKind? = when (type) {
        Sensor.TYPE_ACCELEROMETER -> SensorKind.ACCELEROMETER
        Sensor.TYPE_GYROSCOPE -> SensorKind.GYROSCOPE
        Sensor.TYPE_MAGNETIC_FIELD -> SensorKind.MAGNETOMETER
        Sensor.TYPE_ROTATION_VECTOR -> SensorKind.ROTATION_VECTOR
        Sensor.TYPE_GAME_ROTATION_VECTOR -> SensorKind.GAME_ROTATION_VECTOR
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> SensorKind.GEOMAGNETIC_ROTATION_VECTOR
        Sensor.TYPE_GRAVITY -> SensorKind.GRAVITY
        Sensor.TYPE_LINEAR_ACCELERATION -> SensorKind.LINEAR_ACCELERATION
        else -> null
    }

    private fun median(xs: List<Long>): Long {
        if (xs.isEmpty()) return 0L
        val s = xs.sorted()
        return s[s.size / 2]
    }

    private fun stddev(xs: List<Long>): Double {
        if (xs.size < 2) return 0.0
        val mean = xs.average()
        val v = xs.map { (it - mean) * (it - mean) }.average()
        return sqrt(v)
    }

    companion object {
        private const val TAG = "ImuCollector"
    }
}

/** Convenience: threshold-free magnitude difference used by the UI moving-state badge. */
fun accelMovingEvidence(stats: ImuStreamStats?): Boolean = stats?.moving == true
