package com.nevcortex.prism.ui.screens.motion

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.ImuStreamStats
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SessionSummary
import com.nevcortex.prism.service.RecordingPhase
import com.nevcortex.prism.ui.components.AxisLegend
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatTile
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusChip
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.components.TriAxisPlot
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MotionRecording { IDLE, RECORDING, PAUSED, SAVING }

/**
 * Plain-language motion diagnostics. Plots are live measurements, not a position estimate. Timing
 * details are available but collapsed by default.
 */
@Composable
fun MotionScreen(container: AppContainer, onOpenRecordings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sensorRepo = container.sensorRepository
    val statsMap by sensorRepo.stats.collectAsState()
    val serviceRecording by container.recordingState.collectAsState()
    val statuses = remember { sensorRepo.enumerate() }

    val serviceRecordingActive = serviceRecording.phase == RecordingPhase.STARTING ||
        serviceRecording.phase == RecordingPhase.RECORDING ||
        serviceRecording.phase == RecordingPhase.STOPPING

    val activeKinds = remember {
        listOf(
            SensorKind.ACCELEROMETER,
            SensorKind.GYROSCOPE,
            SensorKind.MAGNETOMETER,
            SensorKind.GRAVITY,
            SensorKind.LINEAR_ACCELERATION,
            SensorKind.ROTATION_VECTOR
        ).filter { kind ->
            sensorRepo.enumerate(listOf(kind)).firstOrNull()?.available == true
        }
    }
    val ownsSensors = remember { !serviceRecordingActive }
    var recState by remember { mutableStateOf(MotionRecording.IDLE) }
    var lastSummary by remember { mutableStateOf<SessionSummary?>(null) }
    var message by remember { mutableStateOf("") }

    var ax by remember { mutableStateOf<List<Float>>(emptyList()) }
    var ay by remember { mutableStateOf<List<Float>>(emptyList()) }
    var az by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gx by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gy by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gz by remember { mutableStateOf<List<Float>>(emptyList()) }
    var mx by remember { mutableStateOf<List<Float>>(emptyList()) }
    var my by remember { mutableStateOf<List<Float>>(emptyList()) }
    var mz by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (ownsSensors) {
            sensorRepo.start(activeKinds, container.settingsStore.imuRateHz.coerceIn(50, 400))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recState != MotionRecording.IDLE && recState != MotionRecording.SAVING) {
                // The screen is gone; save in the background, then release the sensors.
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { container.recorder.stop() }
                    if (ownsSensors) sensorRepo.stop()
                }
            } else if (ownsSensors) {
                sensorRepo.stop()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(80L)
            val accel = sensorRepo.plotBuffer(SensorKind.ACCELEROMETER)?.snapshot().orEmpty()
            val gyro = sensorRepo.plotBuffer(SensorKind.GYROSCOPE)?.snapshot().orEmpty()
            val mag = sensorRepo.plotBuffer(SensorKind.MAGNETOMETER)?.snapshot().orEmpty()
            ax = accel.map { it.values.getOrElse(0) { 0f } }
            ay = accel.map { it.values.getOrElse(1) { 0f } }
            az = accel.map { it.values.getOrElse(2) { 0f } }
            gx = gyro.map { it.values.getOrElse(0) { 0f } }
            gy = gyro.map { it.values.getOrElse(1) { 0f } }
            gz = gyro.map { it.values.getOrElse(2) { 0f } }
            mx = mag.map { it.values.getOrElse(0) { 0f } }
            my = mag.map { it.values.getOrElse(1) { 0f } }
            mz = mag.map { it.values.getOrElse(2) { 0f } }
        }
    }

    fun startMotionRecording() {
        scope.launch {
            val cfg = container.settingsStore.newSessionConfig("Motion recording").copy(
                captureCamera = false,
                captureImu = true
            )
            runCatching { container.recorder.start(cfg, null) }
                .onSuccess {
                    recState = MotionRecording.RECORDING
                    message = ""
                }
                .onFailure {
                    recState = MotionRecording.IDLE
                    message = it.message ?: it.javaClass.simpleName
                }
        }
    }

    fun stopMotionRecording() {
        scope.launch {
            recState = MotionRecording.SAVING
            runCatching { container.recorder.stop() }
                .onSuccess {
                    lastSummary = it
                    recState = MotionRecording.IDLE
                    message = ""
                }
                .onFailure {
                    recState = MotionRecording.IDLE
                    message = it.message ?: it.javaClass.simpleName
                }
        }
    }

    val accelStats = statsMap[SensorKind.ACCELEROMETER]
    val gyroStats = statsMap[SensorKind.GYROSCOPE]
    val hasMotion = activeKinds.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            stringResource(R.string.motion_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (serviceRecordingActive) {
            InlineNotice(
                stringResource(R.string.motion_service_recording_note),
                StatusLevel.INFO
            )
        }

        when {
            !hasMotion -> StatusBanner(
                level = StatusLevel.ERROR,
                title = stringResource(R.string.studio_sensors_unavailable),
                message = stringResource(R.string.failure_sensors)
            )
            (accelStats?.sampleCount ?: 0L) > 0L -> StatusBanner(
                level = StatusLevel.GOOD,
                title = stringResource(R.string.motion_working),
                message = stringResource(R.string.motion_live_body)
            )
            else -> StatusBanner(
                level = StatusLevel.INFO,
                title = stringResource(R.string.motion_none),
                message = stringResource(R.string.studio_sensors_starting)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile(
                label = stringResource(R.string.motion_samples),
                value = (accelStats?.sampleCount ?: 0L).toString(),
                caption = stringResource(R.string.motion_accel_short),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.motion_rate),
                value = Formats.hertz(accelStats?.achievedRateHz ?: 0f),
                caption = stringResource(R.string.motion_measured_caption),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.motion_rate),
                value = Formats.hertz(gyroStats?.achievedRateHz ?: 0f),
                caption = stringResource(R.string.motion_gyro_short),
                modifier = Modifier.weight(1f)
            )
        }

        Section(stringResource(R.string.motion_plots_title)) {
            Text(
                stringResource(R.string.motion_accel_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            TriAxisPlot(ax, ay, az)
            AxisLegend()
            Spacer(Modifier.height(Spacing.md))
            Text(
                stringResource(R.string.motion_gyro_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            TriAxisPlot(gx, gy, gz)
            AxisLegend()
            if (mx.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(R.string.motion_mag_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.xs))
                TriAxisPlot(mx, my, mz)
                AxisLegend()
            } else {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(R.string.motion_no_magnetometer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Section(stringResource(R.string.motion_sensors_detected_title)) {
            statuses.filter { it.identity.kind.isImu }.forEach { st ->
                MetricRow(
                    st.identity.kind.label,
                    if (st.available) {
                        stringResource(R.string.motion_present, st.identity.name)
                    } else {
                        stringResource(R.string.motion_absent)
                    },
                    mono = false
                )
            }
        }

        Section(stringResource(R.string.motion_record_title)) {
            Text(
                stringResource(R.string.motion_record_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            when (recState) {
                MotionRecording.IDLE -> Button(
                    onClick = { startMotionRecording() },
                    enabled = hasMotion && !serviceRecordingActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.motion_record_start))
                }
                MotionRecording.RECORDING -> {
                    StatusBanner(
                        level = StatusLevel.GOOD,
                        title = stringResource(R.string.record_state_recording),
                        message = stringResource(R.string.motion_record_live_body)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(
                            onClick = {
                                container.recorder.pause()
                                recState = MotionRecording.PAUSED
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.motion_record_pause))
                        }
                        Button(
                            onClick = { stopMotionRecording() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.motion_record_stop))
                        }
                    }
                }
                MotionRecording.PAUSED -> {
                    StatusBanner(
                        level = StatusLevel.WARNING,
                        title = stringResource(R.string.motion_record_paused_title),
                        message = stringResource(R.string.motion_record_paused_body)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(
                            onClick = {
                                container.recorder.resume()
                                recState = MotionRecording.RECORDING
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.motion_record_resume))
                        }
                        Button(
                            onClick = { stopMotionRecording() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.motion_record_stop))
                        }
                    }
                }
                MotionRecording.SAVING -> StatusBanner(
                    level = StatusLevel.INFO,
                    title = stringResource(R.string.record_state_stopping),
                    message = stringResource(R.string.motion_saving_body)
                )
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(R.string.motion_recording_error, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            lastSummary?.let { summary ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(
                        R.string.motion_record_saved_detail,
                        Formats.duration(summary.durationNs),
                        summary.sync.imuSampleCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val out = java.io.File(summary.sessionDir, "export/portable")
                                    container.datasetExporter.exportPortable(summary, out)
                                }.onFailure { message = it.message ?: "Export failed" }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.recordings_export_zip))
                    }
                    OutlinedButton(onClick = onOpenRecordings, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.motion_open_recordings))
                    }
                }
            }
        }

        ExpandableSection(stringResource(R.string.motion_show_technical)) {
            statuses.filter { it.identity.kind.isImu && it.available }.forEach { st ->
                val s = statsMap[st.identity.kind] ?: return@forEach
                StreamStatsBlock(s)
                Spacer(Modifier.height(Spacing.sm))
            }
            Text(
                stringResource(R.string.motion_clock_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun StreamStatsBlock(s: ImuStreamStats) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                s.kind.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            StatusChip(
                text = when {
                    s.discontinuity -> "non-monotonic"
                    s.clipping -> "clipping"
                    s.gapsDetected > 0 -> "${s.gapsDetected} gaps"
                    s.sampleCount == 0L -> "idle"
                    else -> "ok"
                },
                ok = s.sampleCount > 0 && !s.discontinuity && !s.clipping && s.gapsDetected == 0
            )
            if (s.kind.derived) StatusChip("Android fusion", ok = false)
        }
        MetricRow(stringResource(R.string.motion_samples), s.sampleCount.toString())
        MetricRow(stringResource(R.string.motion_rate), "%.1f Hz".format(s.achievedRateHz))
        MetricRow(stringResource(R.string.motion_expected), "%.1f Hz".format(s.expectedRateHz))
        MetricRow(
            stringResource(R.string.motion_interval),
            "${s.medianIntervalNs} ns"
        )
        MetricRow(stringResource(R.string.motion_accuracy), s.lastAccuracy.toString())
        MetricRow(stringResource(R.string.motion_gaps), s.gapsDetected.toString())
        MetricRow(stringResource(R.string.motion_clock_offset), "${s.offsetMedianNs} ns")
        MetricRow(stringResource(R.string.motion_clock_jitter), "${s.offsetJitterNs} ns")
    }
}
