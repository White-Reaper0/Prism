package com.nevcortex.prism.model

import com.nevcortex.prism.core.coords.Frame

/**
 * A single IMU-class measurement. We capture BOTH the source clock value ([sensorTimestampNs])
 * and the moment we observed the event in the alignment clock ([arrivalElapsedNs]). The gap
 * between them is the clock-domain diagnostic: if the sensor clock == alignment clock the gap is
 * small and stable; a large or drifting offset means the two clocks disagree and synchronization
 * must not be claimed.
 *
 * @param values raw [SensorEvent.values]; length depends on kind (3 for accel/gyro/mag/grav/lin,
 * 3..4 for rotation vectors). Never filtered here.
 * @param accuracy [SensorEvent.accuracy] (0..3) or -1 if unknown.
 */
data class ImuSample(
    val kind: SensorKind,
    val values: FloatArray,
    val accuracy: Int,
    val sensorTimestampNs: Long,
    val arrivalElapsedNs: Long,
    val frame: Frame = Frame.ANDROID_DEVICE,
    val sequence: Long            // monotonic per-stream sequence index for gap detection
) {
    val dim: Int get() = values.size

    /** Gap between arrival clock and source clock. Small & stable => same domain. */
    val clockOffsetNs: Long get() = arrivalElapsedNs - sensorTimestampNs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuSample) return false
        return kind == other.kind && values.contentEquals(other.values) &&
            accuracy == other.accuracy && sensorTimestampNs == other.sensorTimestampNs &&
            arrivalElapsedNs == other.arrivalElapsedNs && sequence == other.sequence
    }

    override fun hashCode(): Int {
        var h = kind.hashCode()
        h = 31 * h + values.contentHashCode()
        h = 31 * h + accuracy
        h = 31 * h + sensorTimestampNs.hashCode()
        h = 31 * h + arrivalElapsedNs.hashCode()
        h = 31 * h + sequence.hashCode()
        return h
    }
}

/** Per-stream live statistics computed from observed samples. These are measured, not requested. */
data class ImuStreamStats(
    val kind: SensorKind,
    val sampleCount: Long,
    /** Measured median inter-sample interval in ns. */
    val medianIntervalNs: Long,
    val minIntervalNs: Long,
    val maxIntervalNs: Long,
    val meanIntervalNs: Long,
    val intervalStdNs: Long,
    /** Achieved rate from median interval (Hz). */
    val achievedRateHz: Float,
    val expectedRateHz: Float,
    /** Clock offset stats: median, range. Large range => different clock domain. */
    val offsetMedianNs: Long,
    val offsetJitterNs: Long,
    val gapsDetected: Int,         // missing-sample count (interval >> expected)
    val lastAccuracy: Int,
    val clipping: Boolean,          // saturated accelerometer magnitude flagged
    val discontinuity: Boolean,    // timestamp went backwards/non-monotonic
    val moving: Boolean,            // heuristic stationary/moving state from magnitude
    val minValues: FloatArray,
    val maxValues: FloatArray,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long
) {
    companion object {
        fun empty(kind: SensorKind, expectedRateHz: Float) = ImuStreamStats(
            kind = kind,
            sampleCount = 0L,
            medianIntervalNs = 0L, minIntervalNs = 0L, maxIntervalNs = 0L, meanIntervalNs = 0L,
            intervalStdNs = 0L, achievedRateHz = 0f, expectedRateHz = expectedRateHz,
            offsetMedianNs = 0L, offsetJitterNs = 0L, gapsDetected = 0, lastAccuracy = -1,
            clipping = false, discontinuity = false, moving = false,
            minValues = FloatArray(3), maxValues = FloatArray(3),
            firstTimestampNs = 0L, lastTimestampNs = 0L
        )
    }
}
