package com.nevcortex.prism.calibration

import com.nevcortex.prism.model.CalibrationProvenance
import com.nevcortex.prism.model.CameraIntrinsics
import com.nevcortex.prism.model.GyroCalibration
import com.nevcortex.prism.model.ImuCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the calibration file format: NaN/±Infinity round through JSON null, old files
 * keep loading, writes are atomic, and one calibration can never destroy another.
 */
class CalibrationStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newStore(): CalibrationFiles = CalibrationFiles(folder.newFolder())

    private fun imu(accelBias: FloatArray = floatArrayOf(0.1f, Float.NaN, 0.3f),
                    gyro: GyroCalibration? = null) = ImuCalibration(
        createdAtMs = 1_000L,
        provenance = CalibrationProvenance.ESTIMATED,
        method = "test",
        durationNs = 4_000_000_000L,
        orientationCount = 2,
        accelerometerBiasMs2 = accelBias,
        accelerometerNoiseStdMs2 = floatArrayOf(0.01f, 0.02f, 0.03f),
        gyroscopeBiasRadS = floatArrayOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f),
        magnetometerBiasUt = floatArrayOf(Float.NaN, 2f, -3f),
        gyroscopeNoiseStdRadS = floatArrayOf(0f, Float.POSITIVE_INFINITY, 1f),
        gravityMagnitudeMs2 = 9.80665f,
        gravityMagnitudeStdMs2 = Float.NaN,
        driftNote = "n",
        uncertaintyNote = "u",
        dedicatedGyro = gyro
    )

    private fun camera() = CameraIntrinsics(
        createdAtMs = 2_000L,
        provenance = CalibrationProvenance.USER_PROVIDED,
        method = "manual",
        imageWidth = 1920,
        imageHeight = 1080,
        fx = 1500.5f, fy = Float.NaN, cx = 960f, cy = 540f,
        distortionCoeffs = floatArrayOf(0.01f, Float.NaN, -0.02f),
        reprojectionErrorPx = Float.NEGATIVE_INFINITY,
        uncertaintyNote = "note"
    )

    @Test
    fun `NaN plus infinity round-trips through JSON null`() {
        val store = newStore()
        store.saveImu(imu())
        val text = store.imuFile.readText()
        // Non-finite values must appear as literal nulls, never as NaN/Infinity tokens.
        assertTrue(text.contains("null"))
        assertTrue(!text.contains("NaN"))
        assertTrue(!text.contains("Infinity"))
        val loaded = store.loadImu()!!
        assertTrue(loaded.accelerometerBiasMs2[0].isFinite())
        assertTrue(loaded.accelerometerBiasMs2[1].isNaN())
        assertTrue(loaded.gyroscopeBiasRadS[0].isNaN())
        assertTrue(loaded.gyroscopeBiasRadS[1].isNaN())
        assertTrue(loaded.gravityMagnitudeMs2.isFinite())
        assertTrue(loaded.gravityMagnitudeStdMs2.isNaN())
        assertTrue(loaded.gyroscopeNoiseStdRadS[1].isNaN())
        assertTrue(loaded.magnetometerBiasUt[0].isNaN())
        assertTrue(loaded.magnetometerBiasUt[1].isFinite())
    }

    @Test
    fun `finite values round-trip exactly`() {
        val store = newStore()
        store.saveImu(imu())
        val loaded = store.loadImu()!!
        assertEquals(1_000L, loaded.createdAtMs)
        assertEquals(9.80665f, loaded.gravityMagnitudeMs2, 1e-4f)
        assertEquals(0.01f, loaded.accelerometerNoiseStdMs2[0], 1e-7f)
        assertEquals(0.3f, loaded.accelerometerBiasMs2[2], 1e-6f)
    }

    @Test
    fun `an existing imu json without the dedicated block still loads`() {
        val store = newStore()
        store.imuFile.writeText(
            """{
              "createdAtMs": 12345,
              "provenance": "ESTIMATED",
              "method": "multi-orientation mean",
              "durationNs": 9000000000,
              "orientationCount": 3,
              "accelerometerBiasMs2": [0.1, 0.2, 0.3],
              "accelerometerNoiseStdMs2": [0.01, 0.02, 0.03],
              "gyroscopeBiasRadS": [0.001, 0.002, 0.003],
              "gyroscopeNoiseStdRadS": [0.01, 0.01, 0.01],
              "magnetometerBiasUt": [1.0, 2.0, 3.0],
              "gravityMagnitudeMs2": 9.81,
              "gravityMagnitudeStdMs2": 0.02,
              "driftNote": "d",
              "uncertaintyNote": "u"
            }""".trimIndent()
        )
        val loaded = store.loadImu()!!
        assertEquals("multi-orientation mean", loaded.method)
        assertNull(loaded.dedicatedGyro)
        assertEquals(9.81f, loaded.gravityMagnitudeMs2, 1e-4f)
    }

    @Test
    fun `a dedicated gyro round-trips alongside the legacy fields`() {
        val store = newStore()
        val gyro = GyroCalibration(
            createdAtMs = 7_000L,
            provenance = CalibrationProvenance.ESTIMATED,
            method = "dedicated stationary gyroscope calibration (60 s)",
            durationNs = 59_000_000_000L,
            sampleCount = 12_000,
            biasRadS = floatArrayOf(0.011f, -0.021f, 0.031f),
            noiseStdRadS = floatArrayOf(0.001f, 0.002f, 0.0015f),
            stationary = true,
            note = "still"
        )
        store.saveImu(imu(gyro = gyro))
        val loaded = store.loadImu()!!
        val d = loaded.dedicatedGyro!!
        assertEquals(12_000, d.sampleCount)
        assertEquals(0.011f, d.biasRadS[0], 1e-6f)
        assertTrue(d.stationary)
        assertEquals("dedicated stationary gyroscope calibration (60 s)", d.method)
    }

    @Test
    fun `a failed save leaves the previous imu json untouched`() {
        val store = newStore()
        store.saveImu(imu())
        val before = store.imuFile.readText()
        // Force the atomic move to fail: a directory now occupies the temp path.
        File(store.imuFile.parentFile, store.imuFile.name + ".tmp").mkdir()
        try {
            store.saveImu(imu())
            fail("saveImu should have thrown")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(before, store.imuFile.readText())
        // And the temp artifact never corrupts the real file
        assertTrue(File(store.imuFile.parentFile, store.imuFile.name + ".tmp").isDirectory)
    }

    @Test
    fun `a failing camera save leaves the imu calibration intact`() {
        val store = newStore()
        store.saveImu(imu())
        val before = store.imuFile.readText()
        File(store.camFile.parentFile, store.camFile.name + ".tmp").mkdir()
        try {
            store.saveCamera(camera())
            fail("saveCamera should have thrown")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(before, store.imuFile.readText())
    }

    @Test
    fun `camera round-trip keeps finite and NaN distortion entries`() {
        val store = newStore()
        store.saveCamera(camera())
        val loaded = store.loadCamera()!!
        assertEquals(1920, loaded.imageWidth)
        assertEquals(1080, loaded.imageHeight)
        assertEquals(1500.5f, loaded.fx, 1e-3f)
        assertTrue(loaded.fy.isNaN())
        assertEquals(0.01f, loaded.distortionCoeffs[0], 1e-6f)
        assertTrue(loaded.distortionCoeffs[1].isNaN())
        assertTrue(loaded.reprojectionErrorPx.isNaN())   // -Infinity -> JSON null -> NaN
    }

    @Test
    fun `dedicated gyro merge keeps accelerometer data when adding gyro calibration`() {
        val base = imu(accelBias = floatArrayOf(0.5f, 0.6f, 0.7f))
        val gyro = GyroCalibration(
            createdAtMs = 8_000L,
            provenance = CalibrationProvenance.ESTIMATED,
            method = "dedicated stationary gyroscope calibration (60 s)",
            durationNs = 58_000_000_000L,
            sampleCount = 11_500,
            biasRadS = floatArrayOf(0.001f, 0.002f, 0.003f),
            noiseStdRadS = floatArrayOf(0.0005f, 0.0006f, 0.0007f),
            stationary = true,
            note = "still"
        )
        val merged = CalibrationMerge.withDedicatedGyro(base, gyro)
        assertEquals(0.5f, merged.accelerometerBiasMs2[0], 0f)
        assertEquals(0.003f, merged.gyroscopeBiasRadS[2], 1e-6f)
        assertEquals(gyro, merged.dedicatedGyro)
        assertEquals(base.durationNs + gyro.durationNs, merged.durationNs)
        assertEquals(base.accelerometerBiasMs2[2], merged.accelerometerBiasMs2[2], 0f)
    }

    @Test
    fun `an accelerometer-only save preserves an existing dedicated gyro calibration`() {
        val gyro = GyroCalibration(
            createdAtMs = 7_000L,
            provenance = CalibrationProvenance.ESTIMATED,
            method = "dedicated stationary gyroscope calibration (60 s)",
            durationNs = 58_000_000_000L,
            sampleCount = 11_500,
            biasRadS = floatArrayOf(0.001f, 0.002f, 0.003f),
            noiseStdRadS = floatArrayOf(0.0005f, 0.0006f, 0.0007f),
            stationary = true,
            note = "still"
        )
        val existing = imu(gyro = gyro)
        // A NEW multi-orientation save that does not know about the dedicated gyro block
        val accelerometerCal = imu(accelBias = floatArrayOf(9f, 8f, 7f)).copy(createdAtMs = 9_000L)
        val merged = CalibrationMerge.preserveDedicatedGyro(existing, accelerometerCal)
        assertEquals(gyro, merged.dedicatedGyro)
        assertEquals(9f, merged.accelerometerBiasMs2[0], 0f)
    }

    @Test
    fun `an empty imu file with NaN accelerometer fields round-trips as NaN`() {
        val store = newStore()
        val bare = imu(
            accelBias = floatArrayOf(Float.NaN, Float.NaN, Float.NaN),
            gyro = null
        ).copy(
            gravityMagnitudeMs2 = Float.NaN,
            gravityMagnitudeStdMs2 = Float.NaN
        )
        store.saveImu(bare)
        val loaded = store.loadImu()!!
        assertTrue(loaded.accelerometerBiasMs2.all { it.isNaN() })
        assertTrue(loaded.gravityMagnitudeMs2.isNaN())
        assertNull(loaded.dedicatedGyro)
    }
}
