package com.nevcortex.prism.ui.screens.studio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nevcortex.prism.R
import com.nevcortex.prism.camera.CameraLens
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.link.LinkPhase
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.service.RecordingPhase
import com.nevcortex.prism.service.RecordingService
import com.nevcortex.prism.service.RecordingState
import com.nevcortex.prism.stream.CameraStreamState
import com.nevcortex.prism.stream.SensorStreamState
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.ScreenHeader
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatTile
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.permission.rememberCameraPermission
import com.nevcortex.prism.ui.status.failureMessage
import com.nevcortex.prism.ui.status.linkUiMessage
import com.nevcortex.prism.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live preview and the controls for sending it to the PC. Recording is deliberately separate and
 * uses the camera exclusively, so starting a recording stops the live stream.
 */
@Composable
fun LiveStudioScreen(
    container: AppContainer,
    onOpenTracking: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenConnectionSetup: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val linkStatus by container.link.status.collectAsState()
    val stream by container.liveStream.state.collectAsState()
    val frameStats by container.frameCollector.stats.collectAsState()
    val sensorStats by container.sensorRepository.stats.collectAsState()
    val recording by container.recordingState.collectAsState()
    val tracking by container.trackingRepository.state.collectAsState()
    val camera = rememberCameraPermission()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val recordingActive = recording.phase == RecordingPhase.STARTING ||
        recording.phase == RecordingPhase.RECORDING ||
        recording.phase == RecordingPhase.STOPPING

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(recordingActive, camera.granted) {
        if (recordingActive) {
            container.liveStream.stop()
            if (linkStatus.isLive) container.link.disconnect()
        } else      if (!container.liveStream.state.value.active) {
            container.liveStream.start(lifecycleOwner, previewView)
        } else if (camera.granted && container.liveStream.state.value.camera == CameraStreamState.PERMISSION_DENIED) {
            container.liveStream.stop()
            container.liveStream.start(lifecycleOwner, previewView)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            container.liveStream.stop()
            container.link.disconnect()
        }
    }

    fun connect() {
        val host = container.settingsStore.pcHost
        if (host.isBlank()) {
            onOpenConnectionSetup()
        } else {
            container.link.connect(host, container.settingsStore.pcPort)
        }
    }

    fun startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        container.liveStream.stop()
        container.link.disconnect()
        val config = container.settingsStore.newSessionConfig("Live Studio recording").copy(
            captureCamera = true,
            captureImu = true,
            captureMode = CameraCaptureMode.VIDEO
        )
        RecordingService.start(context, config)
    }

    val message = linkUiMessage(linkStatus)
    val actionLabel: String? = when (linkStatus.phase) {
        LinkPhase.NOT_CONFIGURED -> stringResource(R.string.studio_setup_pc)
        LinkPhase.READY, LinkPhase.DISCONNECTED -> stringResource(R.string.studio_connect_to_pc)
        LinkPhase.CONNECTING, LinkPhase.RECONNECTING, LinkPhase.INTERRUPTED ->
            stringResource(R.string.status_interrupted_action)
        LinkPhase.CONNECTED, LinkPhase.STREAMING, LinkPhase.STALE ->
            stringResource(R.string.studio_stop_stream)
        LinkPhase.UNSUPPORTED -> null
    }
    val action: (() -> Unit)? = when (linkStatus.phase) {
        LinkPhase.NOT_CONFIGURED -> onOpenConnectionSetup
        LinkPhase.READY, LinkPhase.DISCONNECTED -> ({ connect() })
        LinkPhase.CONNECTING, LinkPhase.RECONNECTING, LinkPhase.INTERRUPTED ->
            ({ container.link.disconnect() })
        LinkPhase.CONNECTED, LinkPhase.STREAMING, LinkPhase.STALE ->
            ({ container.link.disconnect() })
        LinkPhase.UNSUPPORTED -> null
    }

    val accelRate = sensorStats[SensorKind.ACCELEROMETER]?.achievedRateHz ?: 0f

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ScreenHeader(
            stringResource(R.string.studio_title),
            stringResource(R.string.studio_subtitle)
        )

        StatusBanner(
            level = message.level,
            title = stringResource(message.title),
            message = if (message.bodyArgs.isEmpty()) {
                stringResource(message.body)
            } else {
                stringResource(message.body, *message.bodyArgs.toTypedArray())
            },
            actionLabel = actionLabel,
            onAction = action
        )

        if (linkStatus.failure != com.nevcortex.prism.link.LinkFailure.NONE &&
            linkStatus.phase == LinkPhase.DISCONNECTED
        ) {
            InlineNotice(stringResource(failureMessage(linkStatus.failure)), StatusLevel.ERROR)
        }

        // Preview
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                stream.camera == CameraStreamState.PREVIEW_READY && !recordingActive ->
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                recordingActive -> Text(
                    stringResource(R.string.studio_preview_recording),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg)
                )
                stream.camera == CameraStreamState.STARTING -> Text(
                    stringResource(R.string.studio_camera_starting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stream.camera == CameraStreamState.PERMISSION_DENIED -> Text(
                    stringResource(R.string.studio_camera_permission),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg)
                )
                stream.camera == CameraStreamState.ERROR -> Text(
                    stringResource(R.string.studio_camera_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(Spacing.lg)
                )
                else -> Text(
                    stringResource(R.string.studio_preview_idle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg)
                )
            }
        }

        // Front/rear choice straight where camera work happens. Availability comes from the
        // camera provider; missing lenses are disabled and explained instead of hard-coded.
        if (stream.availableLenses.isNotEmpty()) {
            Text(
                stringResource(R.string.studio_lens_label),
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(
                    selected = stream.lensFacing == CameraLens.BACK,
                    enabled = CameraLens.selectable(CameraLens.BACK, stream.availableLenses),
                    onClick = { container.liveStream.switchLens(CameraLens.BACK, lifecycleOwner, previewView) },
                    label = { Text(stringResource(R.string.settings_lens_back)) }
                )
                FilterChip(
                    selected = stream.lensFacing == CameraLens.FRONT,
                    enabled = CameraLens.selectable(CameraLens.FRONT, stream.availableLenses),
                    onClick = { container.liveStream.switchLens(CameraLens.FRONT, lifecycleOwner, previewView) },
                    label = { Text(stringResource(R.string.settings_lens_front)) }
                )
            }
            if (stream.availableLenses.size == 1) {
                InlineNotice(
                    stringResource(
                        R.string.studio_lens_single_note,
                        if (stream.availableLenses.contains(CameraLens.FRONT)) {
                            stringResource(R.string.settings_lens_front)
                        } else {
                            stringResource(R.string.settings_lens_back)
                        }
                    ),
                    StatusLevel.INFO
                )
            }
            if (stream.cameraDetail != null && stream.camera == CameraStreamState.PREVIEW_READY) {
                InlineNotice(stream.cameraDetail!!, StatusLevel.WARNING)
            }
        }

        if (!camera.granted) {
            StatusBanner(
                level = StatusLevel.WARNING,
                title = stringResource(R.string.studio_camera_permission),
                message = stringResource(R.string.home_camera_denied),
                actionLabel = stringResource(R.string.studio_grant_camera),
                onAction = camera.request
            )
        } else if (stream.camera == CameraStreamState.ERROR) {
            StatusBanner(
                level = StatusLevel.ERROR,
                title = stringResource(R.string.studio_camera_error),
                message = stream.cameraDetail ?: stringResource(R.string.studio_camera_hint),
                actionLabel = stringResource(R.string.studio_retry_camera),
                onAction = {
                    container.liveStream.stop()
                    container.liveStream.start(lifecycleOwner, previewView)
                }
            )
        }

        when (stream.sensors) {
            SensorStreamState.UNAVAILABLE -> StatusBanner(
                level = StatusLevel.ERROR,
                title = stringResource(R.string.studio_sensors_unavailable),
                message = stringResource(R.string.failure_sensors)
            )
            SensorStreamState.PARTIAL -> InlineNotice(
                stringResource(R.string.studio_sensors_partial),
                StatusLevel.WARNING
            )
            SensorStreamState.WORKING -> InlineNotice(
                stringResource(R.string.studio_sensors_working),
                StatusLevel.GOOD
            )
            SensorStreamState.STARTING -> InlineNotice(
                stringResource(R.string.studio_sensors_starting),
                StatusLevel.INFO
            )
            SensorStreamState.NOT_STARTED -> InlineNotice(
                stringResource(R.string.studio_sensors_starting),
                StatusLevel.INFO
            )
        }

        Section(stringResource(R.string.studio_live_stats_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile(
                    label = stringResource(R.string.studio_stat_fps),
                    value = if (linkStatus.isLive) Formats.fps(linkStatus.measuredFps) else "—",
                    caption = if (linkStatus.isLive) null else stringResource(R.string.studio_not_sending),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.studio_stat_delay),
                    value = if (linkStatus.roundTripMs >= 0L) {
                        stringResource(R.string.studio_ms_value, linkStatus.roundTripMs)
                    } else "—",
                    caption = stringResource(R.string.studio_measured_caption),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile(
                    label = stringResource(R.string.studio_stat_frames),
                    value = linkStatus.framesSent.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.studio_stat_motion),
                    value = Formats.hertz(accelRate),
                    caption = stringResource(R.string.studio_measured_caption),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            InlineNotice(
                stringResource(R.string.studio_stats_note),
                StatusLevel.INFO
            )
        }

        Section(stringResource(R.string.studio_recording_title)) {
            Text(
                stringResource(R.string.studio_recording_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            when (recording.phase) {
                RecordingPhase.IDLE -> {
                    if (linkStatus.isLive) {
                        InlineNotice(
                            stringResource(R.string.studio_recording_will_stop_stream),
                            StatusLevel.WARNING
                        )
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    Button(
                        onClick = { startRecording() },
                        enabled = !recordingActive,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.studio_record_button))
                    }
                }
                RecordingPhase.STARTING -> {
                    StatusBanner(
                        level = StatusLevel.INFO,
                        title = stringResource(R.string.record_state_starting),
                        message = stringResource(R.string.studio_recording_starting_body)
                    )
                }
                RecordingPhase.RECORDING -> {
                    StatusBanner(
                        level = StatusLevel.GOOD,
                        title = stringResource(R.string.studio_recording_active),
                        message = stringResource(R.string.record_state_recording)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = { RecordingService.stop(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.studio_stop_recording))
                    }
                }
                RecordingPhase.STOPPING -> {
                    StatusBanner(
                        level = StatusLevel.INFO,
                        title = stringResource(R.string.record_state_stopping),
                        message = recording.detail ?: stringResource(R.string.record_saved_body)
                    )
                }
                RecordingPhase.SAVED -> {
                    val summary = recording.summary
                    StatusBanner(
                        level = StatusLevel.GOOD,
                        title = stringResource(R.string.studio_recording_saved),
                        message = buildString {
                            append(stringResource(R.string.record_saved_body))
                            if (summary != null) {
                                append(" ")
                                append(
                                    stringResource(
                                        R.string.studio_recording_saved_detail,
                                        Formats.duration(summary.durationNs),
                                        summary.sync.cameraFrameCount,
                                        summary.sync.imuSampleCount
                                    )
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = onOpenRecordings, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.studio_open_recordings))
                        }
                        OutlinedButton(
                            onClick = { container.recordingState.value = RecordingState() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
                RecordingPhase.FAILED -> {
                    StatusBanner(
                        level = StatusLevel.ERROR,
                        title = stringResource(R.string.studio_recording_failed),
                        message = recording.detail ?: stringResource(R.string.failure_camera),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = {
                            container.recordingState.value = RecordingState()
                            startRecording()
                        }
                    )
                }
            }
        }

        Section(stringResource(R.string.studio_tracking_title)) {
            val hasCheck = tracking.updatedAtElapsedMs > 0L
            Text(
                text = if (hasCheck) {
                    stringResource(
                        R.string.studio_tracking_last,
                        trackingPhaseLabel(tracking.phase),
                        ((android.os.SystemClock.elapsedRealtime() - tracking.updatedAtElapsedMs) / 1000L)
                            .coerceAtLeast(0L)
                    )
                } else {
                    stringResource(R.string.studio_tracking_not_tested)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(R.string.studio_tracking_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(onClick = onOpenTracking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.studio_tracking_open))
            }
        }

        ExpandableSection(stringResource(R.string.studio_receiver_help_title)) {
            Text(
                stringResource(R.string.studio_receiver_help_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        InlineNotice(stringResource(R.string.studio_leave_note), StatusLevel.NEUTRAL)
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun trackingPhaseLabel(phase: com.nevcortex.prism.tracking.TrackingPhase): String =
    when (phase) {
        com.nevcortex.prism.tracking.TrackingPhase.TRACKING ->
            stringResource(R.string.tracking_phase_tracking)
        com.nevcortex.prism.tracking.TrackingPhase.INITIALIZING ->
            stringResource(R.string.tracking_phase_initializing)
        com.nevcortex.prism.tracking.TrackingPhase.LOST ->
            stringResource(R.string.tracking_phase_lost)
        com.nevcortex.prism.tracking.TrackingPhase.PAUSED ->
            stringResource(R.string.tracking_phase_paused)
        com.nevcortex.prism.tracking.TrackingPhase.STOPPED ->
            stringResource(R.string.tracking_phase_stopped)
        com.nevcortex.prism.tracking.TrackingPhase.UNSUPPORTED ->
            stringResource(R.string.tracking_phase_unsupported)
        com.nevcortex.prism.tracking.TrackingPhase.ERROR ->
            stringResource(R.string.tracking_phase_error)
        else -> stringResource(R.string.state_idle)
    }
