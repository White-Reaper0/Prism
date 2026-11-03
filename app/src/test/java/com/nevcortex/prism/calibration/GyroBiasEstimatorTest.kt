package com.nevcortex.prism.calibration

import com.nevcortex.prism.model.CalibrationProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GyroBiasEstimatorTest {

    private fun stationarySamples(
        count: Int = 12_000,
        periodNs: Long = 5_000_000L,           // 200 Hz
        bias: Triple<Float, Float, Float> = Triple(0.010f, -0.020f, 0.030f),
        noiseAmplitude: Float = 0.002f
    ): List<GyroBiasEstimator.GyroSample> = List(count) { i ->
        val wobble = (if (i % 2 == 0) noiseAmplitude else -noiseAmplitude)
        GyroBiasEstimator.GyroSample(
            timestampNs = i * periodNs,
            xRadS = bias.first + wobble,
            yRadS = bias.second - wobble,
            zRadS = bias.third + wobble / 2
        )
    }

    @Test
    fun `stable stationary measurements are accepted with correct bias and noise`() {
        val outcome = GyroBiasEstimator.analyze(stationarySamples(), createdAtMs = 1L)
        assertTrue("expected Accepted, got $outcome", outcome is GyroBiasEstimator.Outcome.Accepted)
        val cal = (outcome as GyroBiasEstimator.Outcome.Accepted).calibration
        assertTrue(cal.stationary)
        assertEquals(12_000, cal.sampleCount)
        assertEquals(CalibrationProvenance.ESTIMATED, cal.provenance)
        assertTrue(cal.durationNs >= GyroBiasEstimator.MIN_DURATION_NS)
        assertEquals(0.010f, cal.biasRadS[0], 1e-3f)
        assertEquals(-0.020f, cal.biasRadS[1], 1e-3f)
        assertEquals(0.030f, cal.biasRadS[2], 1e-3f)
        assertTrue(cal.noiseStdRadS.all { it > 0f && it < GyroBiasEstimator.MAX_AXIS_STD_RAD_S })
    }

    @Test
    fun `constant samples produce zero noise and the constant bias`() {
        // More than 45 s of samples at 5 ms period, as required by the duration check.
        val samples = List(12_000) { i ->
            GyroBiasEstimator.GyroSample(i * 5_000_000L, 0.05f, -0.04f, 0.03f)
        }
        val outcome = GyroBiasEstimator.analyze(samples, createdAtMs = 1L)
        val cal = (outcome as GyroBiasEstimator.Outcome.Accepted).calibration
        assertEquals(0.05f, cal.biasRadS[0], 1e-6f)
        assertEquals(-0.04f, cal.biasRadS[1], 1e-6f)
        assertEquals(0.03f, cal.biasRadS[2], 1e-6f)
        assertEquals(0f, cal.noiseStdRadS[0], 1e-6f)
        assertEquals(0f, cal.noiseStdRadS[1], 1e-6f)
    }

    @Test
    fun `moving phone is rejected`() {
        val samples = stationarySamples().toMutableList()
        // One 0.8 rad/s jolt in the middle
        samples[6_000] = samples[6_000].copy(xRadS = 0.8f)
        val outcome = GyroBiasEstimator.analyze(samples, createdAtMs = 1L)
        assertTrue(outcome is GyroBiasEstimator.Outcome.Rejected)
        assertTrue((outcome as GyroBiasEstimator.Outcome.Rejected).reason.contains("motion"))
    }

    @Test
    fun `excessively noisy window is rejected by per-axis std`() {
        // Uniform-ish noise with std > 0.10 rad/s without ever triggering the single-sample check
        val samples = List(6_000) { i ->
            // std of an alternating ±a series is ~a; 0.125 exceeds the 0.10 rad/s instability limit
            val sign = if (i % 2 == 0) 0.125f else -0.125f
            GyroBiasEstimator.GyroSample(i * 10_000_000L, 0.01f + sign, -0.02f + sign, 0.03f - sign)
        }
        val outcome = GyroBiasEstimator.analyze(samples, createdAtMs = 1L)
        assertTrue("expected Rejected, got $outcome", outcome is GyroBiasEstimator.Outcome.Rejected)
    }

    @Test
    fun `too few samples are rejected`() {
        val samples = stationarySamples(count = 50)
        // stretch timestamps to simulate a full 60 s window with almost no data
        val stretched = samples.mapIndexed { i, s ->
            s.copy(timestampNs = i * 1_200_000_000L)   // 1.2 s apart, 59 s span
        }
        val outcome = GyroBiasEstimator.analyze(stretched, createdAtMs = 1L)
        assertTrue(outcome is GyroBiasEstimator.Outcome.Rejected)
        assertTrue((outcome as GyroBiasEstimator.Outcome.Rejected).reason.contains("too few"))
    }

    @Test
    fun `insufficient duration is rejected even with enough samples`() {
        val samples = List(5_000) { i ->
            GyroBiasEstimator.GyroSample(i * 2_000_000L, 0.01f, -0.02f, 0.03f)  // 10 s span
        }
        val outcome = GyroBiasEstimator.analyze(samples, createdAtMs = 1L)
        assertTrue(outcome is GyroBiasEstimator.Outcome.Rejected)
        assertTrue((outcome as GyroBiasEstimator.Outcome.Rejected).reason.contains("duration"))
    }

    @Test
    fun `non-monotonic timestamps are rejected`() {
        val samples = stationarySamples().toMutableList()
        // Swap two consecutive stamps
        val t = samples[5_000].timestampNs
        samples[5_000] = samples[5_000].copy(timestampNs = samples[5_001].timestampNs)
        samples[5_001] = samples[5_001].copy(timestampNs = t)
        val outcome = GyroBiasEstimator.analyze(samples, createdAtMs = 1L)
        assertTrue(outcome is GyroBiasEstimator.Outcome.Rejected)
        assertTrue((outcome as GyroBiasEstimator.Outcome.Rejected).reason.contains("monotonic"))
    }

    @Test
    fun `excessive timestamp gaps are rejected`() {
        val samples = stationarySamples().toMutableList()
        // Insert > 1 % of the window worth of 0.6 s gaps: 150 gaps of 600 ms
        var t = samples[0].timestampNs
        val rebuilt = ArrayList<GyroBiasEstimator.GyroSample>(samples.size)
        var j = 0
        for (i in 0 until 12_000) {
            rebuilt.add(samples[j].copy(timestampNs = t))
            t += if (i % 80 == 0) 600_000_000L else 5_000_000L
            j++
        }
        val outcome = GyroBiasEstimator.analyze(rebuilt, createdAtMs = 1L)
        assertTrue("expected Rejected, got $outcome", outcome is GyroBiasEstimator.Outcome.Rejected)
        assertTrue((outcome as GyroBiasEstimator.Outcome.Rejected).reason.contains("gap"))
    }

    @Test
    fun `a few small gaps are tolerated`() {
        val samples = stationarySamples().toMutableList()
        // Insert exactly 2 gaps of 600 ms — allowed (max(2, 1%))
        var t = samples[0].timestampNs
        val rebuilt = ArrayList<GyroBiasEstimator.GyroSample>(samples.size)
        var gapInserted = 0
        for (s in samples) {
            rebuilt.add(s.copy(timestampNs = t))
            t += if (gapInserted < 2 && rebuilt.size % 4_000 == 0) {
                gapInserted++
                600_000_000L
            } else 5_000_000L
        }
        val outcome = GyroBiasEstimator.analyze(rebuilt, createdAtMs = 1L)
        assertTrue("expected Accepted, got $outcome", outcome is GyroBiasEstimator.Outcome.Accepted)
    }

    @Test
    fun `non-finite samples are rejected`() {
        val samples = stationarySamples().toMutableList()
        samples[3_000] = samples[3_000].copy(yRadS = Float.NaN)
        assertTrue(GyroBiasEstimator.analyze(samples, createdAtMs = 1L) is GyroBiasEstimator.Outcome.Rejected)
    }

    @Test
    fun `standard deviation matches the sample (n-1) formula`() {
        // All magnitudes stay far below the 0.5 rad/s single-sample motion limit.
        val xs = floatArrayOf(0.10f, 0.30f, 0.20f, 0.25f, 0.15f, 0.05f, 0.22f, 0.18f, 0.12f, 0.28f)
        val samples = List(xs.size + 9_090) { i ->
            GyroBiasEstimator.GyroSample(
                i * 5_000_000L,
                xs.getOrElse(i) { 0.2f },
                -0.02f, 0.03f
            )
        }
        val outcome = GyroBiasEstimator.analyze(samples, createdAtMs = 1L)
        val cal = (outcome as GyroBiasEstimator.Outcome.Accepted).calibration
        // recompute expected std for axis x from the same sequence
        val seq = samples.map { it.xRadS.toDouble() }
        val mean = seq.average()
        val variance = seq.map { (it - mean) * (it - mean) }.sum() / (seq.size - 1)
        assertEquals(sqrt(variance).toFloat(), cal.noiseStdRadS[0], 1e-5f)
        assertTrue(abs(cal.biasRadS[0] - mean.toFloat()) < 1e-5f)
    }
}
