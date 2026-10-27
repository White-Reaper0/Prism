package com.nevcortex.prism.recording

import android.content.Context
import com.nevcortex.prism.core.coords.MountingConfig
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.CameraStabilization
import com.nevcortex.prism.model.FrameSample
import com.nevcortex.prism.model.ImuSample
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SessionConfig
import com.nevcortex.prism.model.SessionSummary
import com.nevcortex.prism.model.SyncStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * On-disk layout for a recording session. Everything is written under [sessionsRoot]/id/.
 *
 *   sessions/<id>/session.json           — config, calibration ref, sync stats, validation
 *   sessions/<id>/imu/<kind>.csv         — raw per-sample IMU stream (units in header)
 *   sessions/<id>/frames.csv             — frame-timestamp manifest (camera + arrival)
 *   sessions/<id>/video.mp4              — encoded video (VIDEO mode only; per-frame exact
 *                                          timestamps require offline MP4 extraction — see docs)
 *   sessions/<id>/frames/<n>.jpg         — still frames (FRAMES mode only)
 *   sessions/<id>/export/                — generated exports (CSV/JSON/JSONL/EuRoC-style)
 *   sessions/<id>/README.txt             — human-readable dataset description
 *
 * Raw data is immutable. Derived values (aligned timestamps, calibration-corrected streams) are
 * always written as separate files/fields, never overwriting raw samples.
 */
class SessionStore(context: Context) {

    val sessionsRoot: File = File(context.filesDir, "sessions").also { it.mkdirs() }
    val exportsRoot: File = File(context.filesDir, "exports").also { it.mkdirs() }

    fun sessionDir(id: String): File = File(sessionsRoot, id).also { it.mkdirs() }

    fun imuDir(id: String): File = File(sessionDir(id), "imu").also { it.mkdirs() }
    fun frameDir(id: String): File = File(sessionDir(id), "frames").also { it.mkdirs() }
    fun exportDir(id: String): File = File(sessionDir(id), "export").also { it.mkdirs() }

    fun videoFile(id: String): File = File(sessionDir(id), "video.mp4")

    fun imuFile(id: String, kind: SensorKind): File = File(imuDir(id), "${kind.name.lowercase()}.csv")
    fun framesFile(id: String): File = File(sessionDir(id), "frames.csv")
    fun sessionFile(id: String): File = File(sessionDir(id), "session.json")
    fun readmeFile(id: String): File = File(sessionDir(id), "README.txt")

    fun listSessions(): List<File> =
        sessionsRoot.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    /**
     * Rebuild a [SessionSummary] from a saved `session.json`. Returns null when the file is absent
     * or unreadable, so the recordings screen can show a clear "incomplete recording" state.
     */
    fun readSummary(dir: File): SessionSummary? {
        val file = File(dir, "session.json")
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val cfg = o.optJSONObject("config") ?: JSONObject()
            val rec = cfg.optJSONObject("record") ?: JSONObject()
            val sync = o.optJSONObject("sync") ?: JSONObject()
            val ratesObject = sync.optJSONObject("imuRateByKind") ?: JSONObject()
            val rates = buildMap {
                ratesObject.keys().forEach { key ->
                    runCatching { SensorKind.valueOf(key) }.getOrNull()?.let { kind ->
                        put(kind, ratesObject.optDouble(key, 0.0).toFloat())
                    }
                }
            }
            val id = o.optString("id", dir.name)
            val label = o.optString("label", dir.name)
            val created = o.optLong("createdAtMs", 0L)
            val config = SessionConfig(
                id = id,
                label = label,
                createdAtMs = created,
                captureImu = cfg.optBoolean("captureImu", true),
                captureCamera = cfg.optBoolean("captureCamera", true),
                captureMode = runCatching {
                    CameraCaptureMode.valueOf(cfg.optString("captureMode", CameraCaptureMode.VIDEO.name))
                }.getOrDefault(CameraCaptureMode.VIDEO),
                targetAnalysisFps = cfg.optDouble("targetAnalysisFps", 20.0).toFloat(),
                recordAccelerometer = rec.optBoolean("accelerometer", true),
                recordGyroscope = rec.optBoolean("gyroscope", true),
                recordMagnetometer = rec.optBoolean("magnetometer", true),
                recordRotationVector = rec.optBoolean("rotationVector", true),
                recordGameRotationVector = rec.optBoolean("gameRotationVector", false),
                recordGravity = rec.optBoolean("gravity", true),
                recordLinearAcceleration = rec.optBoolean("linearAcceleration", true),
                imuRateHz = cfg.optInt("imuRateHz", 400),
                cameraLensFacing = cfg.optInt("cameraLensFacing", 0),
                enableGpsGroundTruth = cfg.optBoolean("enableGpsGroundTruth", false),
                mounting = when (cfg.optString("mounting", MountingConfig.IDENTITY.name)) {
                    MountingConfig.FLAT_SCREEN_UP.name -> MountingConfig.FLAT_SCREEN_UP
                    else -> MountingConfig.IDENTITY
                },
                stabilization = runCatching {
                    CameraStabilization.valueOf(cfg.optString("stabilization", CameraStabilization.OFF.name))
                }.getOrDefault(CameraStabilization.OFF)
            )
            val notes = ArrayList<String>()
            o.optJSONArray("validationNotes")?.let { array ->
                for (i in 0 until array.length()) notes.add(array.optString(i))
            }
            val imuFiles = ArrayList<String>()
            o.optJSONArray("imuFiles")?.let { array ->
                for (i in 0 until array.length()) imuFiles.add(array.optString(i))
            }
            SessionSummary(
                id = id,
                label = label,
                createdAtMs = created,
                durationNs = o.optLong("durationNs", 0L),
                config = config,
                sync = SyncStats(
                    cameraFrameCount = sync.optLong("cameraFrameCount", 0L),
                    imuSampleCount = sync.optLong("imuSampleCount", 0L),
                    cameraFirstNs = sync.optLong("cameraFirstNs", 0L),
                    cameraLastNs = sync.optLong("cameraLastNs", 0L),
                    imuFirstNs = sync.optLong("imuFirstNs", 0L),
                    imuLastNs = sync.optLong("imuLastNs", 0L),
                    cameraImuOffsetNs = sync.optLong("cameraImuOffsetNs", 0L),
                    cameraImuOffsetJitterNs = sync.optLong("cameraImuOffsetJitterNs", 0L),
                    cameraIntervalMedianNs = sync.optLong("cameraIntervalMedianNs", 0L),
                    cameraIntervalStdNs = sync.optLong("cameraIntervalStdNs", 0L),
                    cameraAchievedFps = sync.optDouble("cameraAchievedFps", 0.0).toFloat(),
                    droppedFrames = sync.optInt("droppedFrames", 0),
                    imuRateByKind = rates,
                    maxImuGapNs = sync.optLong("maxImuGapNs", 0L),
                    synchronizationVerified = sync.optBoolean("synchronizationVerified", false),
                    verificationNote = sync.optString("verificationNote", "")
                ),
                sessionDir = o.optString("sessionDir", dir.absolutePath),
                videoFile = o.optString("videoFile", "").takeIf { it.isNotBlank() && it != "null" },
                frameManifestFile = o.optString("frameManifestFile", "").takeIf { it.isNotBlank() && it != "null" },
                imuFiles = imuFiles,
                metadataFile = o.optString("metadataFile", "session.json"),
                validated = o.optBoolean("validated", false),
                validationNotes = notes
            )
        }.getOrNull()
    }

    /** Open a CSV writer for an IMU kind. Caller must close. */
    fun openImuWriter(id: String, kind: SensorKind): BufferedWriter {
        val w = BufferedWriter(FileWriter(imuFile(id, kind)))
        // Header documents units, axes, frame, and which fields are raw vs measured.
        val dims = if (kind.derived && kind.name.contains("ROTATION_VECTOR")) 4 else 3
        w.write("# kind=${kind.name} derived=${kind.derived} unit=see-below frame=ANDROID_DEVICE\n")
        w.write("# units: accelerometer=m/s^2 gyroscope=rad/s magnetometer=uT gravity=m/s^2 linear_acceleration=m/s^2 rotation_vector=unitless quaternion\n")
        w.write("# sequence,sensor_timestamp_ns,arrival_elapsed_ns,accuracy,x,y,z" +
            (if (dims == 4) ",w" else "") + "\n")
        return w
    }

    fun openFramesWriter(id: String): BufferedWriter {
        val w = BufferedWriter(FileWriter(framesFile(id)))
        w.write("# frame manifest. camera_timestamp_ns is the camera HAL clock (NOT assumed aligned to IMU).\n")
        w.write("# arrival_elapsed_ns is the alignment clock at callback entry. clock_offset_ns = arrival - camera.\n")
        w.write("frame_index,camera_timestamp_ns,arrival_elapsed_ns,width,height,rotation_deg,source,clock_offset_ns\n")
        return w
    }

    fun appendImu(w: BufferedWriter, s: ImuSample) {
        val sb = StringBuilder(64)
        sb.append(s.sequence).append(',')
            .append(s.sensorTimestampNs).append(',')
            .append(s.arrivalElapsedNs).append(',')
            .append(s.accuracy).append(',')
        for (i in s.values.indices) {
            sb.append(formatFloat(s.values[i]))
            if (i < s.values.size - 1) sb.append(',')
        }
        sb.append('\n')
        w.write(sb.toString())
    }

    fun appendFrame(w: BufferedWriter, f: FrameSample) {
        val off = f.arrivalElapsedNs - f.cameraTimestampNs
        w.write("${f.frameIndex},${f.cameraTimestampNs},${f.arrivalElapsedNs}," +
            "${f.width},${f.height},${f.rotationDegrees},${f.source},${off}\n")
    }

    /** Write the full session.json describing the saved dataset. */
    fun writeSessionJson(summary: SessionSummary) {
        val json = JSONObject()
        json.put("schema", "prism.session.v1")
        json.put("id", summary.id)
        json.put("label", summary.label)
        json.put("createdAtMs", summary.createdAtMs)
        json.put("durationNs", summary.durationNs)
        json.put("sessionDir", summary.sessionDir)
        json.put("videoFile", summary.videoFile ?: JSONObject.NULL)
        json.put("frameManifestFile", summary.frameManifestFile ?: JSONObject.NULL)
        json.put("metadataFile", summary.metadataFile)
        json.put("validated", summary.validated)
        json.put("validationNotes", JSONArray(summary.validationNotes))

        val config = JSONObject()
        val c = summary.config
        config.put("captureImu", c.captureImu)
        config.put("captureCamera", c.captureCamera)
        config.put("captureMode", c.captureMode.name)
        config.put("targetAnalysisFps", c.targetAnalysisFps)
        config.put("imuRateHz", c.imuRateHz)
        config.put("cameraLensFacing", c.cameraLensFacing)
        config.put("enableGpsGroundTruth", c.enableGpsGroundTruth)
        config.put("stabilization", c.stabilization.name)
        config.put("mounting", c.mounting.name)
        val rec = JSONObject()
        rec.put("accelerometer", c.recordAccelerometer)
        rec.put("gyroscope", c.recordGyroscope)
        rec.put("magnetometer", c.recordMagnetometer)
        rec.put("rotationVector", c.recordRotationVector)
        rec.put("gameRotationVector", c.recordGameRotationVector)
        rec.put("gravity", c.recordGravity)
        rec.put("linearAcceleration", c.recordLinearAcceleration)
        config.put("record", rec)
        json.put("config", config)

        val sync = JSONObject()
        val s = summary.sync
        sync.put("cameraFrameCount", s.cameraFrameCount)
        sync.put("imuSampleCount", s.imuSampleCount)
        sync.put("cameraFirstNs", s.cameraFirstNs)
        sync.put("cameraLastNs", s.cameraLastNs)
        sync.put("cameraImuOffsetNs", s.cameraImuOffsetNs)
        sync.put("cameraImuOffsetJitterNs", s.cameraImuOffsetJitterNs)
        sync.put("cameraIntervalMedianNs", s.cameraIntervalMedianNs)
        sync.put("cameraIntervalStdNs", s.cameraIntervalStdNs)
        sync.put("cameraAchievedFps", s.cameraAchievedFps)
        sync.put("droppedFrames", s.droppedFrames)
        sync.put("maxImuGapNs", s.maxImuGapNs)
        sync.put("synchronizationVerified", s.synchronizationVerified)
        sync.put("verificationNote", s.verificationNote)
        val rates = JSONObject()
        s.imuRateByKind.forEach { (k, v) -> rates.put(k.name, v) }
        sync.put("imuRateByKind", rates)
        json.put("sync", sync)

        val imuFiles = JSONArray()
        summary.imuFiles.forEach { imuFiles.put(it) }
        json.put("imuFiles", imuFiles)

        sessionFile(summary.id).writeText(json.toString(2))
    }

    fun writeReadme(id: String, summary: SessionSummary) {
        readmeFile(id).writeText(buildString {
            appendLine("Nevcortex P.R.I.S.M. Mobile — session ${summary.id}")
            appendLine("Label: ${summary.label}")
            appendLine("Created: ${summary.createdAtMs} (epoch ms)")
            appendLine("Duration: ${summary.durationNs} ns")
            appendLine()
            appendLine("IMPORTANT: Camera and IMU timestamps are NOT assumed synchronized.")
            appendLine("synchronizationVerified=${summary.sync.synchronizationVerified}")
            appendLine(summary.sync.verificationNote)
            appendLine()
            appendLine("Contents:")
            appendLine("  session.json   — full metadata, config, sync diagnostics")
            appendLine("  imu/*.csv       — raw IMU samples (raw values, never filtered here)")
            appendLine("  frames.csv      — frame-timestamp manifest (camera + arrival clocks)")
            if (summary.videoFile != null) appendLine("  ${summary.videoFile}   — encoded video (per-frame exact ts needs offline MP4 extraction)")
            appendLine("  export/        — generated dataset exports")
            appendLine()
            appendLine("See docs/DATASET_FORMAT.md and docs/TIMESTAMP_CONVENTIONS.md for details.")
        })
    }

    private fun formatFloat(f: Float): String =
        if (f.isNaN()) "nan" else "%.9f".format(f)
}
