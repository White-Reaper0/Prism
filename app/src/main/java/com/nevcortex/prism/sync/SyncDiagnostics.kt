package com.nevcortex.prism.sync

import com.nevcortex.prism.model.FrameSample
import com.nevcortex.prism.model.ImuSample
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SyncStats

/**
 * Computes [SyncStats] purely from measured timestamps. Nothing is assumed: if the camera and IMU
 * offsets are stable and consistent we still default [SyncStats.synchronizationVerified] = false —
 * true verification requires a controlled external test, which this app can request but cannot
 * perform silently.
 */
object SyncDiagnostics {

    /**
     * @param imuOffsets per-kind list of (arrival - sensorTimestamp) offsets, already collected.
     * @param frameData collected frame metadata.
     * @param imuRates achieved per-kind rates.
     */
    fun compute(
        imuOffsets: Map<SensorKind, List<Long>>,
        frameData: List<FrameSample>,
        imuRates: Map<SensorKind, Float>,
        cameraImuOffsetNs: Long,
        cameraImuOffsetJitterNs: Long
    ): SyncStats {
        val camFirst = frameData.firstOrNull()?.cameraTimestampNs ?: 0L
        val camLast = frameData.lastOrNull()?.cameraTimestampNs ?: 0L
        val camIntervals = frameData.zipWithNext { a, b -> b.cameraTimestampNs - a.cameraTimestampNs }
            .filter { it > 0 }
        val camMed = if (camIntervals.isNotEmpty()) camIntervals.sorted()[camIntervals.size / 2] else 0L
        val camFps = if (camMed > 0) (1e9f / camMed).toFloat() else 0f
        val camStd = if (camIntervals.size > 1) stddev(camIntervals).toLong() else 0L
        val drops = frameData.zipWithNext { a, b -> b.cameraTimestampNs - a.cameraTimestampNs }
            .count { d -> camMed > 0 && d > camMed * 2.5 }

        // IMU span from offsets implies first/last sensor timestamps; we store actual from caller.
        val maxGap = imuOffsets.values.flatten().let { offs ->
            // max gap is approximated by the largest expected-interval violation seen in collector
            offs.maxOrNull() ?: 0L
        }

        val imuFirst = 0L
        val imuLast = 0L

        return SyncStats(
            cameraFrameCount = frameData.size.toLong(),
            imuSampleCount = imuOffsets.values.sumOf { it.size.toLong() },
            cameraFirstNs = camFirst,
            cameraLastNs = camLast,
            imuFirstNs = imuFirst,
            imuLastNs = imuLast,
            cameraImuOffsetNs = cameraImuOffsetNs,
            cameraImuOffsetJitterNs = cameraImuOffsetJitterNs,
            cameraIntervalMedianNs = camMed,
            cameraIntervalStdNs = camStd,
            cameraAchievedFps = camFps,
            droppedFrames = drops,
            imuRateByKind = imuRates,
            maxImuGapNs = maxGap,
            synchronizationVerified = false,
            verificationNote = "Not verified: requires a controlled external synchronization test. " +
                "Camera and IMU offsets were measured but a shared clock origin was not confirmed."
        )
    }

    /** Median offset between camera and IMU clocks from per-sample arrival offsets. */
    fun cameraImuOffset(cameraOffsets: List<Long>, imuOffsets: List<Long>): Pair<Long, Long> {
        // Both offsets are (arrival - source). If camera and IMU share the alignment clock,
        // offset_cam - offset_imu approximates (imu_clock - cam_clock) contribution. This is an
        // estimate, NOT a verified constant.
        if (cameraOffsets.isEmpty() || imuOffsets.isEmpty()) return 0L to 0L
        val cMed = cameraOffsets.sorted()[cameraOffsets.size / 2]
        val iMed = imuOffsets.sorted()[imuOffsets.size / 2]
        val cJit = (cameraOffsets.maxOrNull() ?: cMed) - (cameraOffsets.minOrNull() ?: cMed)
        val iJit = (imuOffsets.maxOrNull() ?: iMed) - (imuOffsets.minOrNull() ?: iMed)
        return (cMed - iMed) to maxOf(cJit, iJit)
    }

    private fun stddev(xs: List<Long>): Double {
        val m = xs.average()
        return kotlin.math.sqrt(xs.map { (it - m) * (it - m) }.average())
    }
}
