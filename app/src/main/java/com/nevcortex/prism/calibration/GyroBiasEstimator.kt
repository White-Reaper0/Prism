package com.nevcortex.prism.calibration

import com.nevcortex.prism.model.CalibrationProvenance
import com.nevcortex.prism.model.GyroCalibration
import kotlin.math.sqrt

/**
 * Dedicated stationary gyroscope zero-rate bias estimation.
 *
 * Pure Kotlin (no android.*) so the validation math is unit-testable on the JVM.
 *
 * Thresholds (rad/s units) — documented here and pinned by tests:
 *   MIN_SAMPLES            100        rejects absurdly short/empty collections
 *   MIN_DURATION_NS        45 s       actual first-to-last sample span must be ≥ this (a
 *                                     "60 s" request with heavy scheduling jitter still passes)
 *   MAX_GAP_NS             500 ms     consecutive-sample timestamps may not exceed this; such a
 *                                     gap counts as "excessive"
 *   MAX_GAP_FRACTION       1 %        more than 1 % of samples with excessive gaps rejects
 *   MAX_SAMPLE_NORM_RAD_S  0.5        any single sample above this means the phone moved
 *   MAX_AXIS_STD_RAD_S     0.10       per-axis std above this means the phone was not stable
 *
 * None of these are "trust the average" shortcuts: an ordinary recording is never a gyro bias.
 */
object GyroBiasEstimator {

    const val MIN_SAMPLES = 100
    const val MIN_DURATION_NS = 45_000_000_000L
    const val MAX_GAP_NS = 500_000_000L
    const val MAX_GAP_FRACTION = 0.01
    const val MAX_SAMPLE_NORM_RAD_S = 0.5f
    const val MAX_AXIS_STD_RAD_S = 0.10f

    /** One gyroscope sample with its original SensorEvent.timestamp. */
    data class GyroSample(
        val timestampNs: Long,
        val xRadS: Float,
        val yRadS: Float,
        val zRadS: Float
    )

    sealed class Outcome {
        data class Accepted(val calibration: GyroCalibration) : Outcome()

        /** Never persistable; carries the honest rejection reason for the UI. */
        data class Rejected(val reason: String) : Outcome()
    }

    fun analyze(
        samples: List<GyroSample>,
        createdAtMs: Long,
        method: String = "dedicated stationary gyroscope calibration (60 s)"
    ): Outcome {
        if (samples.size < MIN_SAMPLES) {
            return Outcome.Rejected("too few samples collected (${samples.size} < $MIN_SAMPLES)")
        }
        if (samples.any { !it.xRadS.isFinite() || !it.yRadS.isFinite() || !it.zRadS.isFinite() }) {
            return Outcome.Rejected("at least one sample contains a non-finite value")
        }

        val ordered = samples
        val duration = ordered.last().timestampNs - ordered.first().timestampNs
        if (duration < MIN_DURATION_NS) {
            return Outcome.Rejected(
                "insufficient collection duration (%.1f s < %d s)"
                    .format(duration / 1e9, MIN_DURATION_NS / 1_000_000_000L)
            )
        }

        for (i in 1 until ordered.size) {
            if (ordered[i].timestampNs < ordered[i - 1].timestampNs) {
                return Outcome.Rejected(
                    "timestamps are not monotonic (index $i: ${ordered[i].timestampNs} < ${ordered[i - 1].timestampNs})"
                )
            }
        }

        var excessiveGaps = 0
        var largestGapNs = 0L
        for (i in 1 until ordered.size) {
            val gap = ordered[i].timestampNs - ordered[i - 1].timestampNs
            if (gap > largestGapNs) largestGapNs = gap
            if (gap > MAX_GAP_NS) excessiveGaps++
        }
        val allowedGaps = maxOf(2, (ordered.size * MAX_GAP_FRACTION).toInt())
        if (excessiveGaps > allowedGaps) {
            return Outcome.Rejected(
                "timestamp stream has $excessiveGaps excessive gaps (> 500 ms, largest " +
                    "%.0f ms), allowed at most $allowedGaps".format(largestGapNs / 1e6)
            )
        }

        var maxNorm = 0f
        for (s in ordered) {
            val n = sqrt(s.xRadS * s.xRadS + s.yRadS * s.yRadS + s.zRadS * s.zRadS)
            if (n > maxNorm) maxNorm = n
        }
        if (maxNorm > MAX_SAMPLE_NORM_RAD_S) {
            return Outcome.Rejected(
                "phone motion detected (max |ω| = %.3f rad/s > %.2f rad/s); keep it still"
                    .format(maxNorm, MAX_SAMPLE_NORM_RAD_S)
            )
        }

        val mean = perAxisMean(ordered)
        val std = perAxisStd(ordered, mean)
        if (std.any { it > MAX_AXIS_STD_RAD_S }) {
            return Outcome.Rejected(
                "gyroscope not stable (per-axis std %.4f/%.4f/%.4f rad/s, limit %.2f rad/s)"
                    .format(std[0], std[1], std[2], MAX_AXIS_STD_RAD_S)
            )
        }
        if (mean.any { !it.isFinite() } || std.any { !it.isFinite() }) {
            return Outcome.Rejected("computed results contain invalid (non-finite) values")
        }

        val cal = GyroCalibration(
            createdAtMs = createdAtMs,
            provenance = CalibrationProvenance.ESTIMATED,
            method = method,
            durationNs = duration,
            sampleCount = ordered.size,
            biasRadS = mean,
            noiseStdRadS = std,
            stationary = true,
            note = "Device stationary for the whole window; |ω| max %.3f rad/s; largest "
                .format(maxNorm) + "gap %.0f ms; $excessiveGaps excessive gaps."
                .format(largestGapNs / 1e6)
        )
        return Outcome.Accepted(cal)
    }

    private fun perAxisMean(samples: List<GyroSample>): FloatArray {
        var ax = 0.0; var ay = 0.0; var az = 0.0
        for (smp in samples) { ax += smp.xRadS; ay += smp.yRadS; az += smp.zRadS }
        val n = samples.size.toDouble()
        return floatArrayOf((ax / n).toFloat(), (ay / n).toFloat(), (az / n).toFloat())
    }

    private fun perAxisStd(samples: List<GyroSample>, mean: FloatArray): FloatArray {
        // Sample (n-1) standard deviation, matching the rest of the IMU calibration code.
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        for (smp in samples) {
            val dx = smp.xRadS - mean[0]; vx += dx * dx
            val dy = smp.yRadS - mean[1]; vy += dy * dy
            val dz = smp.zRadS - mean[2]; vz += dz * dz
        }
        val denom = (samples.size - 1).toDouble()
        return floatArrayOf(
            sqrt(vx / denom).toFloat(),
            sqrt(vy / denom).toFloat(),
            sqrt(vz / denom).toFloat()
        )
    }
}
