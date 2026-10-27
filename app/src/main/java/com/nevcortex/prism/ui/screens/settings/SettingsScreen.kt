package com.nevcortex.prism.ui.screens.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.R
import com.nevcortex.prism.core.coords.MountingConfig
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.CameraStabilization
import com.nevcortex.prism.stream.StreamQuality
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusChip
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val s = container.settingsStore

    var imuRate by remember { mutableStateOf(s.imuRateHz.toFloat()) }
    var fps by remember { mutableStateOf(s.targetAnalysisFps) }
    var mode by remember { mutableStateOf(s.captureMode) }
    var lens by remember { mutableStateOf(s.lensFacing) }
    var stab by remember { mutableStateOf(s.stabilization) }
    var gps by remember { mutableStateOf(s.enableGps) }
    var mount by remember { mutableStateOf(s.mountingName) }
    var quality by remember { mutableStateOf(s.streamQuality) }

    var host by remember { mutableStateOf(s.pcHost) }
    var portText by remember { mutableStateOf(s.pcPort.toString()) }
    var connectionError by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("") }

    val calWindows = remember { mutableStateListOf<com.nevcortex.prism.calibration.ImuCalibration.Window>() }

    var fx by remember { mutableStateOf("") }
    var fy by remember { mutableStateOf("") }
    var cx by remember { mutableStateOf("") }
    var cy by remember { mutableStateOf("") }
    var iw by remember { mutableStateOf("") }
    var ih by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Section(stringResource(R.string.settings_pc_title)) {
            Text(
                stringResource(R.string.settings_pc_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; connectionError = null },
                label = { Text(stringResource(R.string.settings_pc_host)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5); connectionError = null },
                label = { Text(stringResource(R.string.settings_pc_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            connectionError?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                    when {
                        host.isBlank() -> connectionError = R.string.home_address_error
                        port == null || port !in 1..65535 -> connectionError = R.string.home_port_error
                        else -> {
                            s.pcHost = host
                            s.pcPort = port
                            container.link.configure(host.trim(), port)
                            status = "PC connection settings saved."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_save_connection))
            }
        }

        AppUpdatesSection(container)

        Section(stringResource(R.string.settings_quality_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                QualityOption(
                    quality = StreamQuality.DATA_SAVER,
                    selected = quality == StreamQuality.DATA_SAVER,
                    label = stringResource(R.string.settings_quality_data_saver),
                    onSelect = { quality = it; s.streamQuality = it }
                )
                QualityOption(
                    quality = StreamQuality.BALANCED,
                    selected = quality == StreamQuality.BALANCED,
                    label = stringResource(R.string.settings_quality_balanced),
                    onSelect = { quality = it; s.streamQuality = it }
                )
                QualityOption(
                    quality = StreamQuality.SHARP,
                    selected = quality == StreamQuality.SHARP,
                    label = stringResource(R.string.settings_quality_sharp),
                    onSelect = { quality = it; s.streamQuality = it }
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_quality_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section(stringResource(R.string.settings_recording_title)) {
            Text(
                stringResource(R.string.settings_imu_rate_label) + ": ${imuRate.toInt()} Hz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Slider(
                value = imuRate,
                onValueChange = { imuRate = it },
                valueRange = 50f..800f,
                onValueChangeFinished = { s.imuRateHz = imuRate.toInt() }
            )
            Text(
                stringResource(R.string.settings_analysis_fps_label) + ": ${fps.toInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Slider(
                value = fps,
                onValueChange = { fps = it },
                valueRange = 1f..60f,
                onValueChangeFinished = { s.targetAnalysisFps = fps }
            )
            Text(
                stringResource(R.string.settings_capture_mode_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CameraCaptureMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m; s.captureMode = m },
                        label = { Text(m.name) }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_lens_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(
                    selected = lens == 0,
                    onClick = { lens = 0; s.lensFacing = 0 },
                    label = { Text(stringResource(R.string.settings_lens_back)) }
                )
                FilterChip(
                    selected = lens == 1,
                    onClick = { lens = 1; s.lensFacing = 1 },
                    label = { Text(stringResource(R.string.settings_lens_front)) }
                )
            }
            Text(
                stringResource(R.string.settings_lens_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_stabilization_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column {
                CameraStabilization.entries.forEach { st ->
                    FilterChip(
                        selected = stab == st,
                        onClick = { stab = st; s.stabilization = st },
                        label = { Text(st.label) }
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = gps,
                    onCheckedChange = { gps = it; s.enableGps = it }
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    stringResource(R.string.settings_gps_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        ExpandableSection(stringResource(R.string.settings_mounting_title)) {
            listOf(MountingConfig.IDENTITY, MountingConfig.FLAT_SCREEN_UP).forEach { m ->
                FilterChip(
                    selected = mount == m.name,
                    onClick = {
                        mount = m.name
                        s.mountingName = m.name
                        container.calibrationStore.saveMounting(m)
                    },
                    label = { Text(m.name) }
                )
                Text(
                    m.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
            }
            Text(
                stringResource(R.string.settings_mounting_note),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        ExpandableSection(stringResource(R.string.settings_imu_calibration)) {
            val current = container.calibrationStore.imu
            MetricRow(
                stringResource(R.string.settings_method),
                current?.method ?: stringResource(R.string.diag_none),
                mono = false
            )
            if (current != null) {
                MetricRow(
                    stringResource(R.string.settings_gyro_bias),
                    current.gyroscopeBiasRadS.joinToString(", ") { "%.5f".format(it) },
                    mono = false
                )
                MetricRow(
                    stringResource(R.string.settings_gravity),
                    "%.3f m/s²".format(current.gravityMagnitudeMs2),
                    mono = false
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        status = "Keep the phone still for 4 seconds…"
                        val window = container.imuCalibration.collectWindow(4000)
                        val calibration = container.imuCalibration.stationaryCalibration(window)
                        container.calibrationStore.saveImu(calibration)
                        status = "Stationary calibration saved. Still: ${window.stationary}. ${window.note}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_stationary_calibration))
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_multi_orientation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            status = "Collecting orientation window ${calWindows.size + 1}…"
                            val window = container.imuCalibration.collectWindow(3000)
                            calWindows.add(window)
                            status = "Window ${calWindows.size} added. Still: ${window.stationary}"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_add_orientation))
                }
                Button(
                    onClick = {
                        if (calWindows.size >= 2) {
                            runCatching {
                                container.imuCalibration.multiOrientationCalibration(calWindows.toList())
                            }.onSuccess {
                                container.calibrationStore.saveImu(it)
                                status = "Multi-orientation calibration saved."
                                calWindows.clear()
                            }.onFailure {
                                status = "Calibration failed: ${it.message}"
                            }
                        } else {
                            status = "Add at least two orientation windows."
                        }
                    },
                    enabled = calWindows.size >= 2,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_finish_calibration))
                }
            }
            MetricRow(stringResource(R.string.settings_windows_staged), calWindows.size.toString())
        }

        ExpandableSection(stringResource(R.string.settings_camera_intrinsics)) {
            val current = container.calibrationStore.camera
            MetricRow(
                stringResource(R.string.settings_method),
                current?.method ?: stringResource(R.string.diag_none),
                mono = false
            )
            Text(
                stringResource(R.string.settings_intrinsics_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    fx,
                    onValueChange = { fx = it },
                    label = { Text("fx") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    fy,
                    onValueChange = { fy = it },
                    label = { Text("fy") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    cx,
                    onValueChange = { cx = it },
                    label = { Text("cx") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    cy,
                    onValueChange = { cy = it },
                    label = { Text("cy") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    iw,
                    onValueChange = { iw = it },
                    label = { Text("width") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    ih,
                    onValueChange = { ih = it },
                    label = { Text("height") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = {
                    runCatching {
                        val camera = container.cameraCalibration.manualIntrinsics(
                            w = iw.toInt(),
                            h = ih.toInt(),
                            fx = fx.toFloat(),
                            fy = fy.toFloat(),
                            cx = cx.toFloat(),
                            cy = cy.toFloat(),
                            distortion = FloatArray(0),
                            reproErrPx = -1f,
                            note = ""
                        )
                        container.calibrationStore.saveCamera(camera)
                        status = "Camera intrinsics saved."
                    }.onFailure {
                        status = "Enter valid numbers: ${it.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_save_intrinsics))
            }
        }

        ExpandableSection(stringResource(R.string.settings_permissions_title)) {
            MetricRow(
                "HIGH_SAMPLING_RATE_SENSORS",
                stringResource(R.string.settings_declared),
                mono = false
            )
            MetricRow(
                stringResource(R.string.settings_foreground_service),
                stringResource(R.string.settings_foreground_service_value),
                mono = false
            )
            StatusChip(stringResource(R.string.settings_sync_manual), ok = false)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_sync_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (status.isNotBlank()) {
            InlineNotice(status, StatusLevel.INFO)
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun QualityOption(
    quality: StreamQuality,
    selected: Boolean,
    label: String,
    onSelect: (StreamQuality) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        FilterChip(
            selected = selected,
            onClick = { onSelect(quality) },
            label = { Text(label) }
        )
        Text(
            "${quality.targetWidth} px · JPEG ${quality.jpegQuality}% · max ${quality.maxFps} fps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
