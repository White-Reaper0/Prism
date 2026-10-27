package com.nevcortex.prism.ui.screens.recording

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.service.RecordingPhase
import com.nevcortex.prism.service.RecordingService
import com.nevcortex.prism.service.RecordingState
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.ReadinessRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.permission.rememberCameraPermission
import com.nevcortex.prism.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecordingScreen(container: AppContainer, onOpenRecordings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording by container.recordingState.collectAsState()
    val camera = rememberCameraPermission()
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val sensors = remember {
        container.sensorRepository.enumerate(
            listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE)
        )
    }
    val accel = sensors.firstOrNull { it.identity.kind == SensorKind.ACCELEROMETER }?.available == true
    val gyro = sensors.firstOrNull { it.identity.kind == SensorKind.GYROSCOPE }?.available == true

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val recordingActive = recording.phase == RecordingPhase.STARTING ||
        recording.phase == RecordingPhase.RECORDING ||
        recording.phase == RecordingPhase.STOPPING

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        container.liveStream.stop()
        container.link.disconnect()
        val config = container.settingsStore.newSessionConfig("Recording").copy(
            captureCamera = true,
            captureImu = true,
            captureMode = CameraCaptureMode.VIDEO
        )
        RecordingService.start(context, config)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            stringResource(R.string.record_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (recording.phase) {
            RecordingPhase.IDLE -> StatusBanner(
                level = if (camera.granted) StatusLevel.INFO else StatusLevel.WARNING,
                title = stringResource(R.string.record_ready),
                message = if (camera.granted) {
                    stringResource(R.string.record_ready_body)
                } else {
                    stringResource(R.string.record_camera_missing)
                }
            )
            RecordingPhase.STARTING -> StatusBanner(
                level = StatusLevel.INFO,
                title = stringResource(R.string.record_state_starting),
                message = stringResource(R.string.studio_recording_starting_body)
            )
            RecordingPhase.RECORDING -> StatusBanner(
                level = StatusLevel.GOOD,
                title = stringResource(R.string.record_state_recording),
                message = stringResource(R.string.record_live_body)
            )
            RecordingPhase.STOPPING -> StatusBanner(
                level = StatusLevel.INFO,
                title = stringResource(R.string.record_state_stopping),
                message = stringResource(R.string.record_saving_body)
            )
            RecordingPhase.SAVED -> StatusBanner(
                level = StatusLevel.GOOD,
                title = stringResource(R.string.record_state_saved),
                message = stringResource(R.string.record_saved_body)
            )
            RecordingPhase.FAILED -> StatusBanner(
                level = StatusLevel.ERROR,
                title = stringResource(R.string.record_state_failed),
                message = recording.detail ?: stringResource(R.string.failure_camera)
            )
        }

        Section(stringResource(R.string.record_readiness_title)) {
            ReadinessRow(
                title = stringResource(R.string.home_camera),
                detail = if (camera.granted) stringResource(R.string.home_camera_ready)
                else stringResource(R.string.home_camera_needed),
                level = if (camera.granted) StatusLevel.GOOD else StatusLevel.WARNING,
                actionLabel = if (camera.granted) null else stringResource(R.string.home_allow_camera),
                onAction = if (camera.granted) null else camera.request
            )
            ReadinessRow(
                title = stringResource(R.string.home_sensors),
                detail = when {
                    accel && gyro -> stringResource(R.string.home_sensors_ready)
                    accel || gyro -> stringResource(R.string.home_sensors_partial)
                    else -> stringResource(R.string.home_sensors_missing)
                },
                level = when {
                    accel && gyro -> StatusLevel.GOOD
                    accel || gyro -> StatusLevel.WARNING
                    else -> StatusLevel.ERROR
                }
            )
            if (!accel || !gyro) {
                Spacer(Modifier.height(Spacing.xs))
                InlineNotice(
                    stringResource(R.string.record_sensors_missing),
                    StatusLevel.WARNING
                )
            }
        }

        when (recording.phase) {
            RecordingPhase.IDLE, RecordingPhase.SAVED, RecordingPhase.FAILED -> {
                Button(
                    onClick = { start() },
                    enabled = camera.granted && !recordingActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.record_start))
                }
                if (recording.phase == RecordingPhase.SAVED) {
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { container.recordingState.value = RecordingState() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
            RecordingPhase.RECORDING -> {
                Button(
                    onClick = { RecordingService.stop(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.record_stop))
                }
            }
            else -> Unit
        }

        recording.summary?.let { summary ->
            Section(stringResource(R.string.record_result_title)) {
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
                    stringResource(R.string.record_dropped),
                    summary.sync.droppedFrames.toString()
                )
                MetricRow(
                    stringResource(R.string.record_sync_verified),
                    if (summary.sync.synchronizationVerified) {
                        stringResource(R.string.record_yes)
                    } else {
                        stringResource(R.string.record_not_verified)
                    },
                    mono = false
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val out = File(summary.sessionDir, "export/portable")
                                        container.datasetExporter.exportPortable(summary, out)
                                    }
                                }
                                busy = false
                                result.onSuccess { message = "Exported: ${it.absolutePath}" }
                                    .onFailure { message = "Export failed: ${it.message}" }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.recordings_export_zip))
                    }
                    Button(onClick = onOpenRecordings, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.record_open_recordings))
                    }
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    InlineNotice(message, StatusLevel.INFO)
                }
            }
        }

        Section(stringResource(R.string.record_settings_title)) {
            val settings = container.settingsStore
            MetricRow(
                stringResource(R.string.settings_imu_rate_label),
                "${settings.imuRateHz} Hz"
            )
            MetricRow(
                stringResource(R.string.settings_analysis_fps_label),
                "%.0f fps".format(settings.targetAnalysisFps)
            )
            MetricRow(
                stringResource(R.string.settings_lens_label),
                if (settings.lensFacing == 0) stringResource(R.string.settings_lens_back)
                else stringResource(R.string.settings_lens_front),
                mono = false
            )
            MetricRow(
                stringResource(R.string.settings_stabilization_label),
                settings.stabilization.name,
                mono = false
            )
            MetricRow(
                stringResource(R.string.settings_gps_label),
                if (settings.enableGps) stringResource(R.string.record_on)
                else stringResource(R.string.record_off),
                mono = false
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.record_settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ExpandableSection(stringResource(R.string.record_pipeline_title), initiallyExpanded = true) {
            Text(
                stringResource(R.string.record_pipeline_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ExpandableSection(stringResource(R.string.record_calibration_title)) {
            val cal = container.calibrationStore
            MetricRow(
                stringResource(R.string.settings_imu_calibration),
                cal.imu?.method ?: stringResource(R.string.diag_none),
                mono = false
            )
            MetricRow(
                stringResource(R.string.settings_camera_intrinsics),
                cal.camera?.method ?: stringResource(R.string.diag_none),
                mono = false
            )
            if (cal.camera == null) {
                Spacer(Modifier.height(Spacing.xs))
                InlineNotice(
                    stringResource(R.string.record_calibration_none),
                    StatusLevel.WARNING
                )
            }
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}
