package com.nevcortex.prism.calibration

import android.content.Context
import com.nevcortex.prism.core.coords.MountingConfig
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.model.CalibrationBundle
import com.nevcortex.prism.model.CalibrationProvenance
import com.nevcortex.prism.model.CameraIntrinsics
import com.nevcortex.prism.model.GyroCalibration
import com.nevcortex.prism.model.ImuCalibration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistent store for the currently-active calibration and the phone mounting configuration.
 * Calibration results are saved as JSON in [root]; the app loads the most recent on startup.
 *
 * Everything stored here is clearly tagged with its provenance (ESTIMATED vs FACTORY vs
 * USER_PROVIDED); the app never silently promotes an estimate to a verified calibration.
 *
 * Write protocol: serialization may produce JSON nulls for non-finite floats (JSON has no NaN),
 * every file is written to a temp sibling first and atomically moved into place, and the
 * in-memory value is updated ONLY after the move succeeds. A failed save therefore never
 * destroys the previous file nor the previous in-memory calibration.
 */
class CalibrationStore(context: Context) {

    private val files = CalibrationFiles(File(context.filesDir, "calibration").also { it.mkdirs() })

    private var _imu: ImuCalibration? = null
    private var _camera: CameraIntrinsics? = null
    private var _mounting: MountingConfig = MountingConfig.IDENTITY

    val imu: ImuCalibration? get() = _imu
    val camera: CameraIntrinsics? get() = _camera
    val mounting: MountingConfig get() = _mounting

    init {
        _imu = files.loadImu()
        _camera = files.loadCamera()
        _mounting = files.loadMounting() ?: MountingConfig.IDENTITY
    }

    fun saveImu(cal: ImuCalibration) {
        // An accelerometer (re)calibration must not wipe a dedicated gyro calibration.
        val merged = CalibrationMerge.preserveDedicatedGyro(_imu, cal)
        files.saveImu(merged)     // persist first
        _imu = merged             // commit only after a successful write
    }

    fun saveCamera(cam: CameraIntrinsics) {
        files.saveCamera(cam)
        _camera = cam
    }

    fun saveMounting(m: MountingConfig) {
        files.saveMounting(m)
        _mounting = m
    }

    /**
     * Persist the dedicated stationary gyroscope calibration, preserving whatever accelerometer /
     * magnetometer / gravity calibration already exists. The dedicated gyro record supersedes the
     * legacy gyro fields for the same reason it exists: to be explicit about that particular
     * measurement instead of overwriting it silently.
     */
    fun saveDedicatedGyro(gyro: GyroCalibration) {
        val merged = CalibrationMerge.withDedicatedGyro(_imu, gyro)
        files.saveImu(merged)     // persist first
        _imu = merged             // commit only after a successful write
    }

    fun current(): CalibrationBundle =
        CalibrationBundle(_imu, _camera, "mounting=${_mounting.name}")
}

/**
 * File-level read/write of the calibration JSONs. Kept free of android.* imports so the logic is
 * unit-testable on the JVM with a temporary directory.
 */
internal class CalibrationFiles(private val dir: File) {

    val imuFile = File(dir, "imu.json")
    val camFile = File(dir, "camera.json")
    val mountFile = File(dir, "mounting.json")

    // --- public API: each save serializes, atomically writes, and returns on success ---

    fun saveImu(cal: ImuCalibration) = atomicWrite(imuFile, imuToJson(cal).toString(2))

    fun saveCamera(cam: CameraIntrinsics) = atomicWrite(camFile, cameraToJson(cam).toString(2))

    fun saveMounting(m: MountingConfig) = atomicWrite(mountFile, mountingToJson(m).toString(2))

    fun loadImu(): ImuCalibration? {
        if (!imuFile.exists()) return null
        return runCatching { imuFromJson(JSONObject(imuFile.readText())) }.getOrNull()
    }

    fun loadCamera(): CameraIntrinsics? {
        if (!camFile.exists()) return null
        return runCatching { cameraFromJson(JSONObject(camFile.readText())) }.getOrNull()
    }

    fun loadMounting(): MountingConfig? {
        if (!mountFile.exists()) return null
        return runCatching { mountingFromJson(JSONObject(mountFile.readText())) }.getOrNull()
    }

    // --- serialization: finite -> number, NaN/±Infinity -> JSON null, restore null -> NaN ---

    private fun putFloat(j: JSONObject, key: String, v: Float) {
        if (v.isFinite()) j.put(key, v.toDouble()) else j.put(key, JSONObject.NULL)
    }

    private fun getFloat(j: JSONObject, key: String): Float =
        if (!j.has(key) || j.isNull(key)) Float.NaN else j.getDouble(key).toFloat()

    private fun JSONArray.fromFinite(a: FloatArray): JSONArray =
        JSONArray().apply {
            for (v in a) if (v.isFinite()) put(v.toDouble()) else put(JSONObject.NULL)
        }

    private fun JSONArray.toFloatArray(): FloatArray =
        FloatArray(length()) { i -> if (isNull(i)) Float.NaN else getDouble(i).toFloat() }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            // Best-effort cleanup of OUR artifact only — never delete a pre-existing directory.
            runCatching { if (tmp.isFile) tmp.delete() }
            Logger.e(TAG, "atomic write failed for ${file.name}; existing file left intact", t)
            throw t
        }
    }

    private fun imuToJson(c: ImuCalibration) = JSONObject().apply {
        put("createdAtMs", c.createdAtMs)
        put("provenance", c.provenance.name)
        put("method", c.method)
        put("durationNs", c.durationNs)
        put("orientationCount", c.orientationCount)
        put("accelerometerBiasMs2", JSONArray().fromFinite(c.accelerometerBiasMs2))
        put("accelerometerNoiseStdMs2", JSONArray().fromFinite(c.accelerometerNoiseStdMs2))
        put("gyroscopeBiasRadS", JSONArray().fromFinite(c.gyroscopeBiasRadS))
        put("gyroscopeNoiseStdRadS", JSONArray().fromFinite(c.gyroscopeNoiseStdRadS))
        put("magnetometerBiasUt", JSONArray().fromFinite(c.magnetometerBiasUt))
        putFloat(this, "gravityMagnitudeMs2", c.gravityMagnitudeMs2)
        putFloat(this, "gravityMagnitudeStdMs2", c.gravityMagnitudeStdMs2)
        put("driftNote", c.driftNote)
        put("uncertaintyNote", c.uncertaintyNote)
        c.dedicatedGyro?.let { put("dedicatedGyroCalibration", gyroToJson(it)) }
    }

    private fun gyroToJson(g: GyroCalibration) = JSONObject().apply {
        put("createdAtMs", g.createdAtMs)
        put("provenance", g.provenance.name)
        put("method", g.method)
        put("durationNs", g.durationNs)
        put("sampleCount", g.sampleCount)
        put("biasRadS", JSONArray().fromFinite(g.biasRadS))
        put("noiseStdRadS", JSONArray().fromFinite(g.noiseStdRadS))
        put("stationary", g.stationary)
        put("note", g.note)
    }

    private fun imuFromJson(j: JSONObject) = ImuCalibration(
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
        gravityMagnitudeMs2 = getFloat(j, "gravityMagnitudeMs2"),
        gravityMagnitudeStdMs2 = getFloat(j, "gravityMagnitudeStdMs2"),
        driftNote = j.getString("driftNote"),
        uncertaintyNote = j.getString("uncertaintyNote"),
        // Optional across versions: imu.json files written before the dedicated gyro feature
        // simply have no such object; unknown keys are ignored, missing ones default to null.
        dedicatedGyro = j.optJSONObject("dedicatedGyroCalibration")?.let { gyroFromJson(it) }
    )

    private fun gyroFromJson(j: JSONObject) = GyroCalibration(
        createdAtMs = j.getLong("createdAtMs"),
        provenance = CalibrationProvenance.valueOf(j.getString("provenance")),
        method = j.getString("method"),
        durationNs = j.getLong("durationNs"),
        sampleCount = j.getInt("sampleCount"),
        biasRadS = j.getJSONArray("biasRadS").toFloatArray(),
        noiseStdRadS = j.getJSONArray("noiseStdRadS").toFloatArray(),
        stationary = j.getBoolean("stationary"),
        note = j.getString("note")
    )

    private fun cameraToJson(c: CameraIntrinsics) = JSONObject().apply {
        put("createdAtMs", c.createdAtMs)
        put("provenance", c.provenance.name)
        put("method", c.method)
        put("imageWidth", c.imageWidth)
        put("imageHeight", c.imageHeight)
        putFloat(this, "fx", c.fx); putFloat(this, "fy", c.fy)
        putFloat(this, "cx", c.cx); putFloat(this, "cy", c.cy)
        put("distortionCoeffs", JSONArray().fromFinite(c.distortionCoeffs))
        putFloat(this, "reprojectionErrorPx", c.reprojectionErrorPx)
        put("uncertaintyNote", c.uncertaintyNote)
    }

    private fun cameraFromJson(j: JSONObject) = CameraIntrinsics(
        createdAtMs = j.getLong("createdAtMs"),
        provenance = CalibrationProvenance.valueOf(j.getString("provenance")),
        method = j.getString("method"),
        imageWidth = j.getInt("imageWidth"),
        imageHeight = j.getInt("imageHeight"),
        fx = getFloat(j, "fx"),
        fy = getFloat(j, "fy"),
        cx = getFloat(j, "cx"),
        cy = getFloat(j, "cy"),
        distortionCoeffs = j.getJSONArray("distortionCoeffs").toFloatArray(),
        reprojectionErrorPx = getFloat(j, "reprojectionErrorPx"),
        uncertaintyNote = j.getString("uncertaintyNote")
    )

    private fun mountingToJson(m: MountingConfig) = JSONObject().apply {
        put("name", m.name)
        put("rotation", JSONArray().fromFinite(m.rotation))
        put("cameraToDevice", JSONArray().fromFinite(m.cameraToDevice))
        put("description", m.description)
    }

    private fun mountingFromJson(j: JSONObject) = MountingConfig(
        name = j.getString("name"),
        rotation = j.getJSONArray("rotation").toFloatArray(),
        cameraToDevice = j.getJSONArray("cameraToDevice").toFloatArray(),
        description = j.getString("description")
    )

    companion object { private const val TAG = "CalibrationStore" }
}

/** Merge rules between the legacy IMU calibration and the optional dedicated gyro block. */
internal object CalibrationMerge {

    /**
     * When [incoming] carries no dedicated gyro block, keep the one from [existing] instead of
     * dropping it. An explicit dedicated-gyro save is the only operation that replaces it.
     */
    fun preserveDedicatedGyro(existing: ImuCalibration?, incoming: ImuCalibration): ImuCalibration {
        val keep = existing?.dedicatedGyro ?: return incoming
        return if (incoming.dedicatedGyro != null) incoming else incoming.copy(dedicatedGyro = keep)
    }

    /**
     * Build the merged calibration for a dedicated gyro save: accelerometer/magnetometer/gravity
     * fields come from [base] when present (never fabricated), gyro fields from [gyro]. With no
     * base, everything unmeasured is stored as NaN — never as zero.
     */
    fun withDedicatedGyro(base: ImuCalibration?, gyro: GyroCalibration): ImuCalibration {
        if (base != null) {
            return base.copy(
                durationNs = base.durationNs + gyro.durationNs,
                gyroscopeBiasRadS = gyro.biasRadS.copyOf(),
                gyroscopeNoiseStdRadS = gyro.noiseStdRadS.copyOf(),
                dedicatedGyro = gyro
            )
        }
        return ImuCalibration(
            createdAtMs = gyro.createdAtMs,
            provenance = CalibrationProvenance.ESTIMATED,
            method = "dedicated stationary gyroscope calibration",
            durationNs = gyro.durationNs,
            orientationCount = 0,
            accelerometerBiasMs2 = FloatArray(3) { Float.NaN },
            accelerometerNoiseStdMs2 = FloatArray(3) { Float.NaN },
            gyroscopeBiasRadS = gyro.biasRadS.copyOf(),
            gyroscopeNoiseStdRadS = gyro.noiseStdRadS.copyOf(),
            magnetometerBiasUt = FloatArray(3) { Float.NaN },
            gravityMagnitudeMs2 = Float.NaN,
            gravityMagnitudeStdMs2 = Float.NaN,
            driftNote = "Dedicated 60 s stationary collection; accel/mag not estimated.",
            uncertaintyNote = "Gyroscope bias valid only while the device was truly still. " +
                "Scale, cross-axis, and temperature dependence not modeled. Raw samples unchanged.",
            dedicatedGyro = gyro
        )
    }
}
