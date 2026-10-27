package com.nevcortex.prism.ui.screens.pipeline

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.theme.Spacing
import com.nevcortex.prism.vio.PcBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PipelineScreen(
    container: AppContainer,
    onOpenRecordings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(PcBridge.Tool.NEVCORTEX_VIO) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            stringResource(R.string.pipeline_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusBanner(
            level = StatusLevel.WARNING,
            title = stringResource(R.string.pipeline_status),
            message = stringResource(R.string.pipeline_status_body)
        )

        Section(stringResource(R.string.pipeline_how_title)) {
            Text(
                stringResource(R.string.pipeline_how_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(onClick = onOpenRecordings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pipeline_open_recordings))
            }
        }

        Section(stringResource(R.string.pipeline_tool)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PcBridge.Tool.entries.forEach { candidate ->
                    FilterChip(
                        selected = tool == candidate,
                        onClick = { tool = candidate },
                        label = { Text(candidate.label.take(14)) }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                PcBridge.instructions(tool, "<sessionDir>/export/euroc/mav0"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            InlineNotice(stringResource(R.string.pipeline_note), StatusLevel.WARNING)
        }

        Section(stringResource(R.string.pipeline_export_title)) {
            val summary = container.lastSessionSummary
            if (summary == null) {
                Text(
                    stringResource(R.string.pipeline_no_recording),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MetricRow(
                    stringResource(R.string.pipeline_last_recording),
                    summary.label,
                    mono = false
                )
                MetricRow(
                    stringResource(R.string.recordings_duration),
                    com.nevcortex.prism.ui.format.Formats.duration(summary.durationNs),
                    mono = false
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            message = ""
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val out = File(summary.sessionDir, "export/euroc")
                                    com.nevcortex.prism.dataset.FormatConverters.toEuRoCLike(
                                        File(summary.sessionDir),
                                        out,
                                        container.calibrationStore.current()
                                    )
                                    out
                                }
                            }
                            busy = false
                            result.onSuccess {
                                message = "Exported for the PC pipeline: ${it.absolutePath}"
                            }.onFailure {
                                message = "Export failed: ${it.message}"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.recordings_export_euroc))
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    InlineNotice(message, StatusLevel.INFO)
                }
            }
        }

        ExpandableSection(stringResource(R.string.pipeline_calibration_title)) {
            val cal = container.calibrationStore
            MetricRow(
                stringResource(R.string.settings_camera_intrinsics),
                cal.camera?.method ?: stringResource(R.string.diag_none),
                mono = false
            )
            if (cal.camera == null) {
                Spacer(Modifier.height(Spacing.xs))
                InlineNotice(stringResource(R.string.pipeline_no_intrinsics), StatusLevel.WARNING)
            }
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diag_settings_title))
            }
        }

        InlineNotice(stringResource(R.string.pipeline_not_metric), StatusLevel.INFO)
        Spacer(Modifier.height(Spacing.xl))
    }
}
