package com.nevcortex.prism.ui.screens.recordings

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SessionSummary
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecordingsScreen(container: AppContainer, onOpenStudio: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(container.sessionStore.listSessions()) }
    var selectedDir by remember { mutableStateOf<File?>(null) }
    var replayJob by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf(false) }
    var lastAccel by remember { mutableStateOf<FloatArray?>(null) }
    var lastFrame by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { sessions = container.sessionStore.listSessions() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            stringResource(R.string.recordings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (sessions.isEmpty()) {
            Section(stringResource(R.string.recordings_empty)) {
                Text(
                    stringResource(R.string.recordings_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Button(onClick = onOpenStudio, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.camera_open_studio))
                }
            }
        }

        sessions.forEach { dir ->
            val summary = remember(dir.path, sessions.size) { container.sessionStore.readSummary(dir) }
            SessionCard(
                dir = dir,
                summary = summary,
                busy = busy,
                onInspect = {
                    selectedDir = if (selectedDir == dir) null else dir
                    playing = false
                    replayJob?.cancel()
                },
                onExportZip = {
                    if (summary == null) {
                        message = "This recording has no session summary, so the portable export cannot be built."
                    } else {
                        scope.launch {
                            busy = true
                            message = ""
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val out = File(summary.sessionDir, "export/portable")
                                    container.datasetExporter.exportPortable(summary, out)
                                }
                            }
                            busy = false
                            result.onSuccess { zip ->
                                message = "Exported: ${zip.absolutePath}"
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            container.datasetExporter.shareIntent(zip),
                                            "Share recording"
                                        )
                                    )
                                }
                            }.onFailure {
                                message = "Export failed: ${it.message}"
                            }
                        }
                    }
                },
                onExportPc = {
                    scope.launch {
                        busy = true
                        message = ""
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val out = File(dir, "export/euroc")
                                com.nevcortex.prism.dataset.FormatConverters.toEuRoCLike(
                                    dir,
                                    out,
                                    container.calibrationStore.current()
                                )
                                out
                            }
                        }
                        busy = false
                        result.onSuccess { out ->
                            message = "PC-ready export: ${out.absolutePath}"
                        }.onFailure {
                            message = "Export failed: ${it.message}"
                        }
                    }
                }
            )

            if (selectedDir == dir) {
                val loaded = remember(dir.path) { container.sessionPlayer.load(dir) }
                Section(stringResource(R.string.recordings_inspect_title)) {
                    MetricRow(
                        stringResource(R.string.recordings_imu),
                        loaded.imu.values.sumOf { it.size }.toString()
                    )
                    MetricRow(
                        stringResource(R.string.recordings_frames),
                        loaded.frames.size.toString()
                    )
                    MetricRow(
                        stringResource(R.string.recordings_streams),
                        loaded.imu.size.toString()
                    )
                    if (!loaded.hasImu && !loaded.hasFrames) {
                        InlineNotice(
                            stringResource(R.string.recordings_empty_data),
                            StatusLevel.WARNING
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!playing) {
                            Button(
                                onClick = {
                                    playing = true
                                    lastAccel = null
                                    lastFrame = 0L
                                    replayJob = scope.launch {
                                        runCatching {
                                            container.sessionPlayer.replay(
                                                loaded,
                                                speed = 5f,
                                                onImu = {
                                                    if (it.kind == SensorKind.ACCELEROMETER) {
                                                        lastAccel = it.values
                                                    }
                                                },
                                                onFrame = { lastFrame = it.frameIndex }
                                            )
                                        }.onFailure {
                                            message = "Replay failed: ${it.message}"
                                        }
                                        playing = false
                                    }
                                },
                                enabled = loaded.frames.isNotEmpty() || loaded.hasImu,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.recordings_replay))
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    replayJob?.cancel()
                                    playing = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.recordings_stop_replay))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                selectedDir = null
                                playing = false
                                replayJob?.cancel()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                    if (playing) {
                        MetricRow(
                            stringResource(R.string.recordings_last_accel),
                            lastAccel?.joinToString(", ") { "%.2f".format(it) } ?: "—",
                            mono = false
                        )
                        MetricRow(
                            stringResource(R.string.recordings_last_frame),
                            lastFrame.toString()
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.recordings_replay_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (message.isNotBlank()) {
            InlineNotice(message, StatusLevel.INFO)
        }

        if (sessions.isNotEmpty()) {
            InlineNotice(
                stringResource(R.string.recordings_sync_note),
                StatusLevel.WARNING
            )
            OutlinedButton(
                onClick = { sessions = container.sessionStore.listSessions() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_refresh))
            }
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun SessionCard(
    dir: File,
    summary: SessionSummary?,
    busy: Boolean,
    onInspect: () -> Unit,
    onExportZip: () -> Unit,
    onExportPc: () -> Unit
) {
    val title = summary?.label?.ifBlank { dir.name } ?: dir.name
    Section(title) {
        if (summary == null) {
            StatusBanner(
                level = StatusLevel.WARNING,
                title = stringResource(R.string.recordings_incomplete),
                message = stringResource(R.string.recordings_incomplete_body)
            )
        } else {
            MetricRow(
                stringResource(R.string.recordings_saved_at),
                Formats.timestamp(summary.createdAtMs),
                mono = false
            )
            MetricRow(
                stringResource(R.string.recordings_duration),
                Formats.duration(summary.durationNs),
                mono = false
            )
            MetricRow(
                stringResource(R.string.recordings_frames),
                summary.sync.cameraFrameCount.toString()
            )
            MetricRow(
                stringResource(R.string.recordings_imu),
                summary.sync.imuSampleCount.toString()
            )
            MetricRow(
                stringResource(R.string.recordings_file),
                dir.name,
                mono = false
            )
            if (summary.validationNotes.any { it.startsWith("FAIL") }) {
                Spacer(Modifier.height(Spacing.xs))
                InlineNotice(
                    stringResource(R.string.recordings_incomplete_body),
                    StatusLevel.WARNING
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = onInspect,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.recordings_inspect))
            }
            OutlinedButton(
                onClick = onExportPc,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.recordings_export_euroc))
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(
            onClick = onExportZip,
            enabled = !busy && summary != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.recordings_export_zip))
        }
    }
}
