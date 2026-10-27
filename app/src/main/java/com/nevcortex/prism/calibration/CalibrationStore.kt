package com.nevcortex.prism.calibration

import android.content.Context
import com.nevcortex.prism.core.coords.MountingConfig
import com.nevcortex.prism.model.CalibrationBundle
import com.nevcortex.prism.model.CalibrationProvenance
import com.nevcortex.prism.model.CameraIntrinsics
import com.nevcortex.prism.model.ImuCalibration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent store for the currently-active calibration and the phone mounting configuration.
 * Calibration results are saved as JSON in [root]; the app loads the most recent on startup.
 *
 * Everything stored here is clearly tagged with its provenance (ESTIMATED vs FACTORY vs
 * USER_PROVIDED); the app never silently promotes an estimate to a verified calibration.
 */
class CalibrationStore(context: Context) {

    private val root: File = File(context.filesDir, "calibration").also { it.mkdirs() }
    private val imuFile = File(root, "imu.json")
    private val camFile = File(root, "camera.json")
    private val mountFile = File(root, "mounting.json")

    private var _imu: ImuCalibration? = null
    private var _camera: CameraIntrinsics? = null
    private var _mounting: MountingConfig = MountingConfig.IDENTITY

    val imu: ImuCalibration? get() = _imu
    val camera: CameraIntrinsics? get() = _camera
    val mounting: MountingConfig get() = _mounting

    init {
        _imu = loadImu()
        _camera = loadCamera()
        _mounting = loadMounting()
    }

    fun saveImu(cal: ImuCalibration) {
        _imu = cal
        imuFile.writeText(imuToJson(cal).toString(2))
    }

    fun saveCamera(cam: CameraIntrinsics) {
        _camera = cam
        camFile.writeText(cameraToJson(cam).toString(2))
    }

    fun saveMounting(m: MountingConfig) {
        _mounting = m
        mountFile.writeText(mountingToJson(m).toString(2))
    }

    fun current(): CalibrationBundle =
        CalibrationBundle(_imu, _camera, "mounting=${_mounting.name}")

    private fun loadImu(): ImuCalibration? {
        if (!imuFile.exists()) return null
        return runCatching {
            val j = JSONObject(imuFile.readText())
            ImuCalibration(
                createdAtMs = j.getLong("createdAtMs"),
                provenance = CalibrationProvenance.valueOf(j.getString("provenance")),
                method = j.getString("method"),
                durationNs = j.getLong("durationNs"),
                orientationCount = j.getInt("orientationCount"),
                accelerometerBiasMs2 = j.getJSONArray("accelerometerBiasMs2").toFloatArray(),
                accelerometerNoiseStdMs2 = j.getJSONArray("accelerometerNoiseStdMs2").toFloatArray(),
                gyroscopeBiasRadS = j.getJSONArray("gyroscopeBiasRadS").toFloatArray(),
                gyroscopeNoiseStdRadS = j.getJSONArray("gyroscopeNoiseStdRadS").toFloatArray(),
                magnetometerBiasUt = j.getJSONArray("magnetometerBiasUt").toFloatArray(),
                gravityMagnitudeMs2 = j.getDouble("gravityMagnitudeMs2").toFloat(),
                gravityMagnitudeStdMs2 = j.getDouble("gravityMagnitudeStdMs2").toFloat(),
                driftNote = j.getString("driftNote"),
                uncertaintyNote = j.getString("uncertaintyNote")
            )
        }.getOrElse { null }
    }

    private fun loadCamera(): CameraIntrinsics? {
        if (!camFile.exists()) return null
        return runCatching {
            val j = JSONObject(camFile.readText())
            CameraIntrinsics(
                createdAtMs = j.getLong("createdAtMs"),
                provenance = CalibrationProvenance.valueOf(j.getString("provenance")),
                method = j.getString("method"),
                imageWidth = j.getInt("imageWidth"),
                imageHeight = j.getInt("imageHeight"),
                fx = j.getDouble("fx").toFloat(),
                fy = j.getDouble("fy").toFloat(),
                cx = j.getDouble("cx").toFloat(),
                cy = j.getDouble("cy").toFloat(),
                distortionCoeffs = j.getJSONArray("distortionCoeffs").toFloatArray(),
                reprojectionErrorPx = j.getDouble("reprojectionErrorPx").toFloat(),
                uncertaintyNote = j.getString("uncertaintyNote")
            )
        }.getOrElse { null }
    }

    private fun loadMounting(): MountingConfig {
        if (!mountFile.exists()) return MountingConfig.IDENTITY
        return runCatching {
            val j = JSONObject(mountFile.readText())
            MountingConfig(
                name = j.getString("name"),
                rotation = j.getJSONArray("rotation").toFloatArray(),
                cameraToDevice = j.getJSONArray("cameraToDevice").toFloatArray(),
                description = j.getString("description")
            )
        }.getOrElse { MountingConfig.IDENTITY }
    }

    private fun JSONArray.toFloatArray(): FloatArray =
        FloatArray(length()) { i -> this.getDouble(i).toFloat() }

    private fun imuToJson(c: ImuCalibration) = JSONObject().apply {
        put("createdAtMs", c.createdAtMs)
        put("provenance", c.provenance.name)
        put("method", c.method)
        put("durationNs", c.durationNs)
        put("orientationCount", c.orientationCount)
        put("accelerometerBiasMs2", JSONArray().from(c.accelerometerBiasMs2))
        put("accelerometerNoiseStdMs2", JSONArray().from(c.accelerometerNoiseStdMs2))
        put("gyroscopeBiasRadS", JSONArray().from(c.gyroscopeBiasRadS))
        put("gyroscopeNoiseStdRadS", JSONArray().from(c.gyroscopeNoiseStdRadS))
        put("magnetometerBiasUt", JSONArray().from(c.magnetometerBiasUt))
        put("gravityMagnitudeMs2", c.gravityMagnitudeMs2)
        put("gravityMagnitudeStdMs2", c.gravityMagnitudeStdMs2)
        put("driftNote", c.driftNote)
        put("uncertaintyNote", c.uncertaintyNote)
    }

    private fun cameraToJson(c: CameraIntrinsics) = JSONObject().apply {
        put("createdAtMs", c.createdAtMs)
        put("provenance", c.provenance.name)
        put("method", c.method)
        put("imageWidth", c.imageWidth)
        put("imageHeight", c.imageHeight)
        put("fx", c.fx); put("fy", c.fy); put("cx", c.cx); put("cy", c.cy)
        put("distortionCoeffs", JSONArray().from(c.distortionCoeffs))
        put("reprojectionErrorPx", c.reprojectionErrorPx)
        put("uncertaintyNote", c.uncertaintyNote)
    }

    private fun mountingToJson(m: MountingConfig) = JSONObject().apply {
        put("name", m.name)
        put("rotation", JSONArray().from(m.rotation))
        put("cameraToDevice", JSONArray().from(m.cameraToDevice))
        put("description", m.description)
    }

    private fun JSONArray.from(a: FloatArray): JSONArray {
        a.forEach { put(it.toDouble()) }; return this
    }
}
