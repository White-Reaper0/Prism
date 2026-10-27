package com.nevcortex.prism.ui.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(container.sessionStore.listSessions()) }
    var selectedDir by remember { mutableStateOf<File?>(null) }
    var selectedSummary by remember { mutableStateOf<JSONObject?>(null) }
    var replayJob by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf(false) }
    var liveAccel by remember { mutableStateOf<FloatArray?>(null) }
    var liveFrame by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { sessions = container.sessionStore.listSessions() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Data Sessions", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Recorded sessions are stored locally and immutable. Export copies live under export/. " +
                "Replay here is a sanity check; run VIO on the exported dataset via the PC pipeline.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )

        if (sessions.isEmpty()) {
            Section("No sessions yet") {
                Text("Record a mission on the Mission or Live Camera tab.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        sessions.forEach { dir ->
            val sum = remember(dir) { container.sessionPlayer.readSummary(dir) }
            Section(
                title = sum?.optString("label")?.ifBlank { dir.name } ?: dir.name
            ) {
                val created = sum?.optLong("createdAtMs") ?: 0L
                if (created > 0) MetricRow("created", fmt(created), mono = false)
                MetricRow("dir", dir.name, mono = false)
                val sync = sum?.optJSONObject("sync")
                MetricRow("frames", "${sync?.optLong("cameraFrameCount") ?: 0}")
                MetricRow("IMU samples", "${sync?.optLong("imuSampleCount") ?: 0}")
                MetricRow("sync verified", "${sync?.optBoolean("synchronizationVerified") ?: false}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        selectedDir = dir; selectedSummary = sum
                    }) { Text("Inspect / replay") }
                    OutlinedButton(onClick = {
                        // quick EuRoC export
                        runCatching {
                            val out = File(dir, "export/euroc")
                            com.nevcortex.prism.dataset.FormatConverters.toEuRoCLike(
                                dir, out, container.calibrationStore.current()
                            )
                            message = "EuRoC-style: ${out.absolutePath}"
                        }.onFailure { message = "Export failed: ${it.message}" }
                    }) { Text("Export EuRoC") }
                }
            }
        }

        selectedDir?.let { dir ->
            Section("Inspect: ${dir.name}") {
                val loaded = remember(dir) { container.sessionPlayer.load(dir) }
                MetricRow("IMU streams", "${loaded.imu.size}")
                MetricRow("IMU samples", "${loaded.imu.values.sumOf { it.size }}")
                MetricRow("frames", "${loaded.frames.size}")
                MetricRow("has IMU", "${loaded.hasImu}")
                MetricRow("has frames", "${loaded.hasFrames}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!playing) {
                        Button(onClick = {
                            playing = true
                            replayJob = scope.launch {
                                runCatching {
                                    container.sessionPlayer.replay(
                                        loaded, speed = 5f,
                                        onImu = { if (it.kind == SensorKind.ACCELEROMETER) liveAccel = it.values },
                                        onFrame = { liveFrame = it.frameIndex }
                                    )
                                }.onFailure { message = "Replay failed: ${it.message}" }
                                playing = false
                            }
                        }) { Text("Replay (5× sanity)") }
                    } else {
                        Button(onClick = { replayJob?.cancel(); playing = false }) { Text("Stop replay") }
                    }
                }
                if (playing) {
                    MetricRow("last accel", liveAccel?.joinToString(",") { "%.2f".format(it) } ?: "—", "m/s²")
                    MetricRow("last frame index", "$liveFrame")
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Note: replay re-emits IMU + frame timestamps by their own clocks. This is a timing " +
                        "sanity check, NOT a synchronized playback — the offset between camera and IMU " +
                        "clocks is preserved, not removed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun fmt(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
