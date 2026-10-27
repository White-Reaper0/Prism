package com.nevcortex.prism.recording

import androidx.lifecycle.LifecycleOwner
import com.nevcortex.prism.camera.CameraRepository
import com.nevcortex.prism.camera.FrameCollector
import com.nevcortex.prism.core.util.BoundedRingBuffer
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.core.util.Time
import com.nevcortex.prism.model.CalibrationBundle
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.FrameSample
import com.nevcortex.prism.model.ImuSample
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SessionConfig
import com.nevcortex.prism.model.SessionSummary
import com.nevcortex.prism.model.SyncStats
import com.nevcortex.prism.sensors.SensorRepository
import com.nevcortex.prism.sync.SyncDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter

/**
 * Orchestrates a synchronized recording session:
 *  - opens raw IMU CSVs and the frame-timestamp manifest
 *  - enables the IMU + frame recording sinks (channels with drop protection)
 *  - binds the camera headlessly on [lifecycleOwner] (the foreground service) so capture survives
 *    screen-off, and starts MP4 recording for VIDEO mode
 *  - drains channels to disk on IO coroutines without blocking the sensor/camera paths
 *  - on stop, closes everything, computes MEASURED [SyncStats], validates, and writes session.json
 *
 * Raw measurements are never modified. Derived/aligned values are written as separate fields.
 */
class SessionRecorder(
    private val sensorRepo: SensorRepository,
    private val cameraRepo: CameraRepository,
    private val frameCollector: FrameCollector,
    private val store: SessionStore,
    private val currentCalibration: () -> CalibrationBundle?
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var config: SessionConfig? = null
    private var imuWriters: MutableMap<SensorKind, BufferedWriter> = mutableMapOf()
    private var framesWriter: BufferedWriter? = null
    private var drainJobs: MutableList<Job> = mutableListOf()
    private var startedElapsedNs: Long = 0L

    private val imuOffsets: MutableMap<SensorKind, BoundedRingBuffer<Long>> = mutableMapOf()
    private val frameIntervals = BoundedRingBuffer<Long>(1024)
    private val frameOffsets = BoundedRingBuffer<Long>(512)
    private var frameFirstNs = 0L
    private var frameLastNs = 0L
    private var frameCount = 0L
    private var lastFrameTs = 0L

    var recording: Boolean = false
        private set

    /** Pause/resume writing without closing the capture channels (drain stays alive). */
    @Volatile
    var paused: Boolean = false
        private set

    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Begin recording. Camera binds to [cameraLifecycle] (the service); null disables camera. */
    suspend fun start(config: SessionConfig, cameraLifecycle: LifecycleOwner?): Boolean {
        if (recording) return false
        this.config = config
        recording = true
        startedElapsedNs = Time.elapsedNanos()

        // open writers
        config.let { c ->
            if (c.recordAccelerometer) openImuWriter(SensorKind.ACCELEROMETER, c.id)
            if (c.recordGyroscope) openImuWriter(SensorKind.GYROSCOPE, c.id)
            if (c.recordMagnetometer) openImuWriter(SensorKind.MAGNETOMETER, c.id)
            if (c.recordRotationVector) openImuWriter(SensorKind.ROTATION_VECTOR, c.id)
            if (c.recordGameRotationVector) openImuWriter(SensorKind.GAME_ROTATION_VECTOR, c.id)
            if (c.recordGravity) openImuWriter(SensorKind.GRAVITY, c.id)
            if (c.recordLinearAcceleration) openImuWriter(SensorKind.LINEAR_ACCELERATION, c.id)
        }
        if (config.captureCamera) {
            framesWriter = store.openFramesWriter(config.id)
        }

        // enable sinks (channels with drop protection)
        sensorRepo.setRecording(true)
        if (config.captureCamera) {
            frameCollector.setRecording(true, config.targetAnalysisFps)
        }

        // bind camera headlessly on the service lifecycle so it survives screen-off
        if (config.captureCamera && cameraLifecycle != null) {
            runCatching { cameraRepo.bindRecording(cameraLifecycle, config, frameCollector) }
                .onFailure { Logger.e(TAG, "bindRecording failed", it) }
            if (config.captureMode == CameraCaptureMode.VIDEO) {
                runCatching { cameraRepo.startRecording(store.videoFile(config.id)) }
                    .onFailure { Logger.e(TAG, "startRecording failed", it) }
            }
        }

        // drain coroutines
        drainJobs += scope.launch { drainImu() }
        if (config.captureCamera) drainJobs += scope.launch { drainFrames() }

        Logger.i(TAG, "session ${config.id} started")
        return true
    }

    private fun openImuWriter(kind: SensorKind, id: String) {
        imuWriters[kind] = store.openImuWriter(id, kind)
        imuOffsets[kind] = BoundedRingBuffer(8192)
    }

    private suspend fun drainImu() {
        val ch = sensorRepo.collector().recordChannel ?: return
        try {
            for (sample in ch) {
                val w = imuWriters[sample.kind] ?: continue
                if (!paused) store.appendImu(w, sample)
                imuOffsets[sample.kind]?.add(sample.clockOffsetNs)
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "drainImu error", t)
        }
    }

    private suspend fun drainFrames() {
        val ch = frameCollector.recordChannel ?: return
        try {
            for (f in ch) {
                if (!paused) framesWriter?.let { store.appendFrame(it, f) }
                if (frameFirstNs == 0L) frameFirstNs = f.cameraTimestampNs
                frameLastNs = f.cameraTimestampNs
                frameCount++
                if (lastFrameTs != 0L) {
                    val d = f.cameraTimestampNs - lastFrameTs
                    if (d > 0) frameIntervals.add(d)
                }
                frameOffsets.add(f.arrivalElapsedNs - f.cameraTimestampNs)
                lastFrameTs = f.cameraTimestampNs
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "drainFrames error", t)
        }
    }

    suspend fun stop(): SessionSummary {
        if (!recording) error("not recording")
        recording = false
        val c = config ?: error("no config")

        // stop feeding the channels (closes them); drains then finish
        if (c.captureCamera) frameCollector.setRecording(false, 0f)
        sensorRepo.setRecording(false)
        // let drains flush remaining samples
        withTimeoutOrNull(5_000) { drainJobs.joinAll() }
        drainJobs.clear()

        // stop video + unbind camera
        if (c.captureCamera && c.captureMode == CameraCaptureMode.VIDEO) {
            cameraRepo.stopRecording()
        }
        cameraRepo.unbindAll()

        // close writers
        imuWriters.values.forEach { runCatching { it.flush(); it.close() } }
        imuWriters.clear()
        framesWriter?.let { runCatching { it.flush(); it.close() } }
        framesWriter = null

        val now = Time.elapsedNanos()
        val durationNs = now - startedElapsedNs

        // compute measured sync stats
        val rates = sensorRepo.stats.value
            .filterKeys { imuOffsets.containsKey(it) }
            .mapValues { it.value.achievedRateHz }
        val allImuOffsets = imuOffsets.values.flatMap { it.snapshot() }
        val (camImuOff, camImuJit) = SyncDiagnostics.cameraImuOffset(
            frameOffsets.snapshot(), allImuOffsets
        )
        val frameData = buildFrameProxyList()
        val sync = SyncDiagnostics.compute(
            imuOffsets = imuOffsets.mapValues { it.value.snapshot() },
            frameData = frameData,
            imuRates = rates,
            cameraImuOffsetNs = camImuOff,
            cameraImuOffsetJitterNs = camImuJit
        )

        val notes = validate(c, sync)
        val summary = SessionSummary(
            id = c.id,
            label = c.label,
            createdAtMs = c.createdAtMs,
            durationNs = durationNs,
            config = c,
            sync = sync,
            sessionDir = store.sessionDir(c.id).absolutePath,
            videoFile = if (c.captureCamera && c.captureMode == CameraCaptureMode.VIDEO) "video.mp4" else null,
            frameManifestFile = if (c.captureCamera) "frames.csv" else null,
            imuFiles = imuOffsets.keys.map { "imu/${it.name.lowercase()}.csv" },
            metadataFile = "session.json",
            validated = notes.none { it.startsWith("FAIL") },
            validationNotes = notes
        )
        store.writeSessionJson(summary)
        store.writeReadme(c.id, summary)

        // attach calibration reference (does not copy calibration into the session; it's referenced)
        currentCalibration()?.let { writeCalibrationRef(c.id, it) }

        Logger.i(TAG, "session ${c.id} stopped; frames=${sync.cameraFrameCount} imu=${sync.imuSampleCount}")
        return summary
    }

    /** Minimal in-memory proxy of frames for SyncStats (count/first/last/intervals/offsets only). */
    private fun buildFrameProxyList(): List<FrameSample> {
        // We don't keep all frames; reconstruct a minimal list from stats for the diagnostics API.
        if (frameCount == 0L) return emptyList()
        // Two synthetic endpoints carrying first/last timestamps are enough for SyncStats' span,
        // but intervals are already captured; feed a representative pair so the API computes span.
        return listOf(
            FrameSample(0, frameFirstNs, 0, 0, 0, 0,
                com.nevcortex.prism.model.FrameSource.ANALYSIS, 0, 0, 0, 0f),
            FrameSample(frameCount - 1, frameLastNs, 0, 0, 0, 0,
                com.nevcortex.prism.model.FrameSource.ANALYSIS, 0, 0, 0, 0f)
        )
    }

    private fun validate(c: SessionConfig, sync: SyncStats): List<String> {
        val n = mutableListOf<String>()
        if (c.captureImu && sync.imuSampleCount == 0L) n += "FAIL: no IMU samples recorded"
        if (c.captureCamera && sync.cameraFrameCount == 0L) n += "FAIL: no camera frames recorded"
        if (c.captureCamera && c.captureMode == CameraCaptureMode.VIDEO) {
            val vf = store.videoFile(c.id)
            if (!vf.exists() || vf.length() == 0L) n += "WARN: video file missing or empty"
        }
        if (c.captureCamera && !sync.synchronizationVerified)
            n += "INFO: camera/IMU synchronization NOT verified (see verificationNote)"
        if (sync.maxImuGapNs > 0L && c.imuRateHz > 0) {
            val expected = (1e9f / c.imuRateHz).toLong()
            if (sync.maxImuGapNs > expected * 5) n += "WARN: large IMU gap detected (${sync.maxImuGapNs} ns)"
        }
        if (sync.droppedFrames > 0) n += "WARN: ${sync.droppedFrames} dropped/missed frames (analysis-level heuristic)"
        return n
    }

    private fun writeCalibrationRef(id: String, cal: CalibrationBundle) {
        val json = org.json.JSONObject()
        json.put("mountingNote", cal.mountingNote)
        cal.imu?.let {
            json.put("imu", org.json.JSONObject().apply {
                put("provenance", it.provenance.name)
                put("method", it.method)
                put("accelerometerBiasMs2", it.accelerometerBiasMs2.toList())
                put("gyroscopeBiasRadS", it.gyroscopeBiasRadS.toList())
                put("uncertaintyNote", it.uncertaintyNote)
            })
        }
        cal.camera?.let {
            json.put("camera", org.json.JSONObject().apply {
                put("provenance", it.provenance.name)
                put("fx", it.fx); put("fy", it.fy); put("cx", it.cx); put("cy", it.cy)
                put("method", it.method)
            })
        }
        java.io.File(store.sessionDir(id), "calibration_ref.json").writeText(json.toString(2))
    }

    fun shutdown() {
        runCatching { runBlocking { if (recording) stop() } }
        scope.cancel()
    }

    companion object { private const val TAG = "SessionRecorder" }
}
