package com.nevcortex.prism.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.link.LinkPhase
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.service.RecordingPhase
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.NavigationCard
import com.nevcortex.prism.ui.components.ReadinessRow
import com.nevcortex.prism.ui.components.ScreenHeader
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.permission.rememberCameraPermission
import com.nevcortex.prism.ui.status.linkUiMessage
import com.nevcortex.prism.ui.theme.Spacing

/**
 * The one-screen starting point: readiness, PC address, and a single Connect action. Technical
 * details live in Diagnostics.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenStudio: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val linkStatus by container.link.status.collectAsState()
    val recording by container.recordingState.collectAsState()
    val camera = rememberCameraPermission()

    var host by rememberSaveable(container.settingsStore.pcHost) {
        mutableStateOf(container.settingsStore.pcHost)
    }
    var portText by rememberSaveable(container.settingsStore.pcPort) {
        mutableStateOf(container.settingsStore.pcPort.toString())
    }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var openAfterConnect by remember { mutableStateOf(false) }

    val recordingActive = recording.phase == RecordingPhase.STARTING ||
        recording.phase == RecordingPhase.RECORDING ||
        recording.phase == RecordingPhase.STOPPING
    val live = linkStatus.isLive
    val busy = linkStatus.phase == LinkPhase.CONNECTING ||
        linkStatus.phase == LinkPhase.RECONNECTING

    val sensorStatuses = remember {
        container.sensorRepository.enumerate(
            listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE)
        )
    }
    val accel = sensorStatuses.firstOrNull { it.identity.kind == SensorKind.ACCELEROMETER }
    val gyro = sensorStatuses.firstOrNull { it.identity.kind == SensorKind.GYROSCOPE }
    val accelOk = accel?.available == true
    val gyroOk = gyro?.available == true

    fun connect() {
        val cleanHost = host.trim()
        val port = portText.trim().toIntOrNull()
        when {
            cleanHost.isBlank() -> errorRes = R.string.home_address_error
            port == null || port !in 1..65535 -> errorRes = R.string.home_port_error
            else -> {
                errorRes = null
                container.settingsStore.pcHost = cleanHost
                container.settingsStore.pcPort = port
                openAfterConnect = true
                container.link.connect(cleanHost, port)
            }
        }
    }

    LaunchedEffect(linkStatus.phase) {
        if (openAfterConnect && live) {
            openAfterConnect = false
            onOpenStudio()
        }
        if (openAfterConnect && linkStatus.phase == LinkPhase.DISCONNECTED &&
            linkStatus.failure != com.nevcortex.prism.link.LinkFailure.NONE
        ) {
            openAfterConnect = false
        }
    }

    val message = linkUiMessage(linkStatus)
    val showBannerAction = linkStatus.phase == LinkPhase.CONNECTED ||
        linkStatus.phase == LinkPhase.STREAMING ||
        linkStatus.phase == LinkPhase.STALE ||
        linkStatus.phase == LinkPhase.INTERRUPTED ||
        linkStatus.phase == LinkPhase.RECONNECTING ||
        linkStatus.phase == LinkPhase.UNSUPPORTED

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ScreenHeader(
            stringResource(R.string.app_name),
            stringResource(R.string.app_tagline)
        )

        StatusBanner(
            level = message.level,
            title = stringResource(message.title),
            message = if (message.bodyArgs.isEmpty()) {
                stringResource(message.body)
            } else {
                stringResource(message.body, *message.bodyArgs.toTypedArray())
            },
            actionLabel = if (showBannerAction) {
                message.action?.let { stringResource(it) }
            } else {
                null
            },
            onAction = if (showBannerAction) {
                {
                    when (linkStatus.phase) {
                        LinkPhase.CONNECTED, LinkPhase.STREAMING, LinkPhase.STALE -> onOpenStudio()
                        LinkPhase.INTERRUPTED, LinkPhase.RECONNECTING -> container.link.disconnect()
                        LinkPhase.UNSUPPORTED -> Unit
                        else -> connect()
                    }
                }
            } else {
                null
            }
        )

        if (recordingActive) {
            StatusBanner(
                level = StatusLevel.INFO,
                title = stringResource(R.string.studio_recording_active),
                message = stringResource(R.string.home_recording_active_body),
                actionLabel = stringResource(R.string.home_open_studio),
                onAction = onOpenStudio
            )
        }

        Section(stringResource(R.string.home_pc)) {
            Text(
                stringResource(R.string.home_pc_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; errorRes = null },
                label = { Text(stringResource(R.string.home_pc_address_label)) },
                placeholder = { Text(stringResource(R.string.home_pc_address_hint)) },
                singleLine = true,
                enabled = !live,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5); errorRes = null },
                label = { Text(stringResource(R.string.home_port_label)) },
                singleLine = true,
                enabled = !live,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            errorRes?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(Spacing.md))
            if (live) {
                Button(onClick = onOpenStudio, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_open_studio))
                }
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = { container.link.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.home_disconnect))
                }
            } else {
                Button(
                    onClick = { connect() },
                    enabled = !busy && !recordingActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (busy) stringResource(R.string.home_connecting)
                        else stringResource(R.string.home_connect)
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { container.link.disconnect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
            if (recordingActive) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(R.string.home_recording_blocks_connect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Section(stringResource(R.string.home_readiness_title)) {
            val cameraDetail = when {
                camera.granted -> stringResource(R.string.home_camera_ready)
                else -> stringResource(R.string.home_camera_needed)
            }
            ReadinessRow(
                title = stringResource(R.string.home_camera),
                detail = cameraDetail,
                level = if (camera.granted) StatusLevel.GOOD else StatusLevel.WARNING,
                actionLabel = if (camera.granted) null else stringResource(R.string.home_allow_camera),
                onAction = if (camera.granted) null else camera.request
            )
            val sensorDetail = when {
                accelOk && gyroOk -> stringResource(R.string.home_sensors_ready)
                accelOk || gyroOk -> stringResource(R.string.home_sensors_partial)
                else -> stringResource(R.string.home_sensors_missing)
            }
            ReadinessRow(
                title = stringResource(R.string.home_sensors),
                detail = sensorDetail,
                level = when {
                    accelOk && gyroOk -> StatusLevel.GOOD
                    accelOk || gyroOk -> StatusLevel.WARNING
                    else -> StatusLevel.ERROR
                }
            )
            ReadinessRow(
                title = stringResource(R.string.home_pc),
                detail = when {
                    live -> stringResource(R.string.home_pc_streaming)
                    linkStatus.phase == LinkPhase.INTERRUPTED ||
                        linkStatus.phase == LinkPhase.RECONNECTING ->
                        stringResource(R.string.home_pc_interrupted)
                    linkStatus.hasHost -> stringResource(R.string.home_pc_ready)
                    else -> stringResource(R.string.home_pc_not_set)
                },
                level = when {
                    live -> StatusLevel.GOOD
                    linkStatus.phase == LinkPhase.INTERRUPTED ||
                        linkStatus.phase == LinkPhase.RECONNECTING -> StatusLevel.WARNING
                    linkStatus.hasHost -> StatusLevel.INFO
                    else -> StatusLevel.WARNING
                }
            )
        }

        Section(stringResource(R.string.home_how_title)) {
            HowStep(
                stringResource(R.string.home_step_1_title),
                stringResource(R.string.home_step_1_body)
            )
            HowStep(
                stringResource(R.string.home_step_2_title),
                stringResource(R.string.home_step_2_body)
            )
            HowStep(
                stringResource(R.string.home_step_3_title),
                stringResource(R.string.home_step_3_body)
            )
        }

        NavigationCard(
            title = stringResource(R.string.home_recordings),
            body = stringResource(R.string.diag_recordings_body),
            icon = Icons.Filled.Folder,
            onClick = onOpenRecordings
        )
        NavigationCard(
            title = stringResource(R.string.home_settings),
            body = stringResource(R.string.diag_settings_body),
            icon = Icons.Filled.Settings,
            onClick = onOpenSettings
        )
        NavigationCard(
            title = stringResource(R.string.nav_live_studio),
            body = stringResource(R.string.studio_subtitle),
            icon = Icons.Filled.Videocam,
            onClick = onOpenStudio
        )

        InlineNotice(stringResource(R.string.home_network_note), StatusLevel.INFO)
        Text(
            stringResource(R.string.home_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun HowStep(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}
