package com.nevcortex.prism.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.link.LinkFailure
import com.nevcortex.prism.service.RecordingPhase
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.NavigationCard
import com.nevcortex.prism.ui.components.ScreenHeader
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.nav.Routes
import com.nevcortex.prism.ui.status.failureMessage
import com.nevcortex.prism.ui.status.linkUiMessage
import com.nevcortex.prism.ui.theme.Spacing

/** Hub for technical screens. Ordinary copy stays plain; raw numbers live inside expandable rows. */
@Composable
fun DiagnosticsScreen(container: AppContainer, onNavigate: (String) -> Unit) {
    val linkStatus by container.link.status.collectAsState()
    val recording by container.recordingState.collectAsState()
    val message = linkUiMessage(linkStatus)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ScreenHeader(
            stringResource(R.string.diagnostics_title),
            stringResource(R.string.diagnostics_subtitle)
        )

        StatusBanner(
            level = message.level,
            title = stringResource(message.title),
            message = if (message.bodyArgs.isEmpty()) {
                stringResource(message.body)
            } else {
                stringResource(message.body, *message.bodyArgs.toTypedArray())
            },
            actionLabel = stringResource(R.string.diag_view_connection),
            onAction = { onNavigate(Routes.CONNECTION) }
        )

        if (linkStatus.failure != LinkFailure.NONE) {
            Section(stringResource(R.string.diag_last_error)) {
                Text(
                    stringResource(failureMessage(linkStatus.failure)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                linkStatus.technicalDetail?.let { detail ->
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.diag_technical_detail),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Section(stringResource(R.string.diag_snapshot_title)) {
            MetricRow(
                stringResource(R.string.diag_protocol),
                linkStatus.protocol ?: stringResource(R.string.diag_none),
                mono = false
            )
            MetricRow(
                stringResource(R.string.diag_endpoint),
                if (linkStatus.hasHost) "${linkStatus.host}:${linkStatus.port}"
                else stringResource(R.string.diag_none),
                mono = false
            )
            MetricRow(
                stringResource(R.string.diag_frames_sent),
                linkStatus.framesSent.toString()
            )
            MetricRow(
                stringResource(R.string.diag_imu_sent),
                linkStatus.imuSamplesSent.toString()
            )
            if (recording.phase != RecordingPhase.IDLE) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(R.string.diag_recording_state, recordingStateText(recording.phase)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            ExpandableSection(stringResource(R.string.diag_technical_detail)) {
                MetricRow(
                    stringResource(R.string.diag_receiver),
                    linkStatus.receiverName ?: stringResource(R.string.diag_none),
                    mono = false
                )
                MetricRow(
                    stringResource(R.string.diag_connected_since),
                    if (linkStatus.connectedAtElapsedMs > 0L) {
                        Formats.ago(linkStatus.connectedAtElapsedMs)
                    } else stringResource(R.string.diag_none),
                    mono = false
                )
                MetricRow(
                    stringResource(R.string.diag_rtt),
                    if (linkStatus.roundTripMs >= 0L) "${linkStatus.roundTripMs} ms"
                    else stringResource(R.string.diag_none)
                )
                MetricRow(
                    stringResource(R.string.diag_bytes),
                    Formats.bytes(linkStatus.bytesSent)
                )
                MetricRow(
                    stringResource(R.string.diag_dropped),
                    linkStatus.droppedFrames.toString()
                )
                MetricRow(
                    stringResource(R.string.diag_reconnect_attempt),
                    linkStatus.reconnectAttempts.toString()
                )
            }
        }

        Text(
            stringResource(R.string.diag_tools_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        NavigationCard(
            title = stringResource(R.string.diag_connection_title),
            body = stringResource(R.string.diag_connection_body),
            icon = Icons.Filled.Wifi,
            onClick = { onNavigate(Routes.CONNECTION) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_motion_title),
            body = stringResource(R.string.diag_motion_body),
            icon = Icons.Filled.Sensors,
            onClick = { onNavigate(Routes.MOTION) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_camera_title),
            body = stringResource(R.string.diag_camera_body),
            icon = Icons.Filled.CameraAlt,
            onClick = { onNavigate(Routes.CAMERA) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_tracking_title),
            body = stringResource(R.string.diag_tracking_body),
            icon = Icons.Filled.Route,
            onClick = { onNavigate(Routes.TRACKING) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_recording_title),
            body = stringResource(R.string.record_intro),
            icon = Icons.Filled.VideocamOff,
            onClick = { onNavigate(Routes.RECORDING) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_recordings_title),
            body = stringResource(R.string.diag_recordings_body),
            icon = Icons.Filled.Folder,
            onClick = { onNavigate(Routes.RECORDINGS) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_pipeline_title),
            body = stringResource(R.string.diag_pipeline_body),
            icon = Icons.Filled.Build,
            onClick = { onNavigate(Routes.PIPELINE) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_settings_title),
            body = stringResource(R.string.diag_settings_body),
            icon = Icons.Filled.Settings,
            onClick = { onNavigate(Routes.SETTINGS) }
        )

        Text(
            stringResource(R.string.diag_advanced_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        NavigationCard(
            title = stringResource(R.string.diag_mapping_title),
            body = stringResource(R.string.diag_mapping_body),
            icon = Icons.Filled.Map,
            onClick = { onNavigate(Routes.MAPPING) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_models_title),
            body = stringResource(R.string.diag_models_body),
            icon = Icons.Filled.Psychology,
            onClick = { onNavigate(Routes.MODELS) }
        )
        NavigationCard(
            title = stringResource(R.string.diag_evidence_title),
            body = stringResource(R.string.diag_evidence_body),
            icon = Icons.Filled.HelpOutline,
            onClick = { onNavigate(Routes.EVIDENCE) }
        )

        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.diagnostics_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun recordingStateText(phase: RecordingPhase): String = when (phase) {
    RecordingPhase.IDLE -> stringResource(R.string.state_idle)
    RecordingPhase.STARTING -> stringResource(R.string.record_state_starting)
    RecordingPhase.RECORDING -> stringResource(R.string.record_state_recording)
    RecordingPhase.STOPPING -> stringResource(R.string.record_state_stopping)
    RecordingPhase.SAVED -> stringResource(R.string.record_state_saved)
    RecordingPhase.FAILED -> stringResource(R.string.record_state_failed)
}
