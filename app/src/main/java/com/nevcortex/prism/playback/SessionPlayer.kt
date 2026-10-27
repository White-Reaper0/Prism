package com.nevcortex.prism.playback

import com.nevcortex.prism.model.FrameSample
import com.nevcortex.prism.model.FrameSource
import com.nevcortex.prism.model.ImuSample
import com.nevcortex.prism.model.SensorKind
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Loads a recorded session and replays its IMU + frame timelines. Playback preserves source
 * timestamps; timing is reconstructed from the recorded sensor/camera clocks. This is the
 * foundation for running the same sequence through visual-only and visual-inertial pipelines
 * offline (the pipelines themselves are not bundled on-device — see VIO_STATUS.md).
 */
class SessionPlayer {

    data class LoadedSession(
        val imu: Map<SensorKind, List<ImuSample>>,
        val frames: List<FrameSample>,
        val sessionDir: File
    ) {
        val hasImu: Boolean get() = imu.values.any { it.isNotEmpty() }
        val hasFrames: Boolean get() = frames.isNotEmpty()
    }

    fun load(sessionDir: File): LoadedSession {
        val imu = HashMap<SensorKind, List<ImuSample>>()
        val imuDir = File(sessionDir, "imu")
        if (imuDir.isDirectory) {
            imuDir.listFiles { f -> f.name.endsWith(".csv") }?.forEach { f ->
                val kind = runCatching {
                    SensorKind.valueOf(f.nameWithoutExtension.uppercase())
                }.getOrNull() ?: return@forEach
                imu[kind] = readImu(kind, f)
            }
        }
        val frames = readFrames(File(sessionDir, "frames.csv"))
        return LoadedSession(imu, frames, sessionDir)
    }

    /**
     * Replay by relative source time. [speed] > 1 plays faster. Callbacks fire on the caller's
     * coroutine; no UI thread assumption. Cancellation stops playback.
     */
    suspend fun replay(
        loaded: LoadedSession,
        speed: Float = 1f,
        onImu: (ImuSample) -> Unit = {},
        onFrame: (FrameSample) -> Unit = {}
    ) {
        // Merge IMU + frame events ordered by source timestamp (each on its own clock; we replay
        // them by their own relative t0 — see docs on why this is NOT synchronization).
        val imuT0 = loaded.imu.values.filter { it.isNotEmpty() }.minOfOrNull { it.first().sensorTimestampNs } ?: Long.MAX_VALUE
        val camT0 = loaded.frames.firstOrNull()?.cameraTimestampNs ?: Long.MAX_VALUE
        // We can only replay by wall progress; use the earliest IMU t0 as the session t0 and
        // offset camera onto it using the measured clock offset when available.
        val t0 = minOf(imuT0, camT0)
        val baseWall = System.nanoTime()
        // Build a merged event list
        data class Ev(val relNs: Long, val isFrame: Boolean, val imu: ImuSample?, val frame: FrameSample?)
        val events = ArrayList<Ev>()
        loaded.imu.values.flatten().forEach { events.add(Ev(it.sensorTimestampNs - t0, false, it, null)) }
        loaded.frames.forEach { events.add(Ev(it.cameraTimestampNs - t0, true, null, it)) }
        events.sortBy { it.relNs }
        for (e in events) {
            val target = baseWall + (e.relNs / speed).toLong()
            val now = System.nanoTime()
            val wait = target - now
            if (wait > 0) delay(wait / 1_000_000L)
            if (e.isFrame) e.frame?.let(onFrame) else e.imu?.let(onImu)
        }
    }

    private fun readImu(kind: SensorKind, file: File): List<ImuSample> {
        val out = ArrayList<ImuSample>()
        BufferedReader(InputStreamReader(file.inputStream())).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (!line.startsWith("#") && line.isNotBlank() && !line.startsWith("sequence")) {
                    val p = line.split(",")
                    if (p.size >= 7) {
                        val seq = p[0].toLong()
                        val ts = p[1].toLong()
                        val arr = p[2].toLong()
                        val acc = p[3].toInt()
                        val vals = FloatArray(p.size - 4) { p[4 + it].toFloat() }
                        out.add(ImuSample(kind, vals, acc, ts, arr, sequence = seq))
                    }
                }
                line = r.readLine()
            }
        }
        return out
    }

    private fun readFrames(file: File): List<FrameSample> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<FrameSample>()
        BufferedReader(InputStreamReader(file.inputStream())).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (!line.startsWith("#") && line.isNotBlank() && !line.startsWith("frame_index")) {
                    val p = line.split(",")
                    if (p.size >= 8) {
                        out.add(
                            FrameSample(
                                frameIndex = p[0].toLong(),
                                cameraTimestampNs = p[1].toLong(),
                                arrivalElapsedNs = p[2].toLong(),
                                width = p[3].toInt(),
                                height = p[4].toInt(),
                                format = 0,
                                source = runCatching { FrameSource.valueOf(p[6]) }.getOrDefault(FrameSource.ANALYSIS),
                                rotationDegrees = p[5].toInt(),
                                iso = 0,
                                exposureNs = 0,
                                aperture = 0f
                            )
                        )
                    }
                }
                line = r.readLine()
            }
        }
        return out
    }

    /** Read session.json from a session dir (best-effort). */
    fun readSummary(sessionDir: File): JSONObject? {
        val f = File(sessionDir, "session.json")
        return if (f.exists()) JSONObject(f.readText()) else null
    }
}
