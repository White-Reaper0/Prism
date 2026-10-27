package com.nevcortex.prism.ui.screens.mission

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.service.RecordingService
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The reproducible-experiment workflow. Honest at every step: nothing is "verified" unless it was. */
@Composable
fun MissionScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<com.nevcortex.prism.model.SessionSummary?>(null) }
    var message by remember { mutableStateOf("") }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val denied = r.filter { !it.value }.keys
        if (denied.any()) message = "Permissions needed: ${denied.joinToString()}"
    }

    LaunchedEffect(Unit) {
        val need = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (container.settingsStore.enableGps) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        perms.launch(need.toTypedArray())
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) {
            repeat(20) { delay(250); if (container.lastSessionSummary != null) summary = container.lastSessionSummary }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Mission", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "A mission is a reproducible experiment: configure, verify, record, validate, replay, export.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )

        Section("1 · Configuration") {
            val s = container.settingsStore
            MetricRow("capture mode", s.captureMode.name)
            MetricRow("requested IMU rate", "${s.imuRateHz}", "Hz")
            MetricRow("target analysis fps", "%.0f".format(s.targetAnalysisFps))
            MetricRow("lens", if (s.lensFacing == 0) "back" else "front")
            MetricRow("stabilization", s.stabilization.name)
            MetricRow("mounting", s.mountingName)
            MetricRow("GPS ground truth", if (s.enableGps) "ON" else "off")
        }

        Section("2 · Calibration in use") {
            val cal = container.calibrationStore
            MetricRow("IMU calibration", cal.imu?.method ?: "none", mono = false)
            MetricRow("  provenance", cal.imu?.provenance?.name ?: "—")
            MetricRow("camera intrinsics", cal.camera?.method ?: "none", mono = false)
            MetricRow("  provenance", cal.camera?.provenance?.name ?: "—")
            if (cal.camera == null || cal.camera!!.fx.isNaN())
                Text("No camera intrinsics. Do not treat any visual estimate as metric.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Section("3 · Sensor & timestamp health") {
            val avail = remember { container.sensorRepository.enumerate() }
            val imuKinds = avail.filter { it.identity.kind.isImu }
            MetricRow("IMU-class sensors present", "${imuKinds.count { it.available }}/${imuKinds.size}")
            imuKinds.filter { it.available }.forEach {
                MetricRow("  ${it.identity.kind.name}", it.identity.name, mono = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(
                    if (imuKinds.count { it.available } >= 2) "ok" else "WARN: need accel+gyro",
                    ok = imuKinds.count { it.available } >= 2
                )
            }
            Text(
                "Timestamp health is checked during recording (offset stability, gaps, monotonicity). " +
                    "Camera/IMU synchronization is reported as NOT verified unless a controlled test ran.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        }

        Section("4 · Record camera + IMU") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isRecording) {
                    Button(onClick = {
                        val cfg = container.settingsStore.newSessionConfig("Mission").copy(
                            captureCamera = true, captureImu = true, captureMode = CameraCaptureMode.VIDEO
                        )
                        RecordingService.start(context, cfg)
                        isRecording = true
                        message = "Recording (foreground service). Screen may turn off; capture continues."
                    }) { Text("Start recording") }
                } else {
                    Button(onClick = {
                        RecordingService.stop(context); isRecording = false; message = "Stopping…"
                    }) { Text("Stop recording") }
                }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        summary?.let { sum ->
            Section("5 · Validate") {
                MetricRow("frames", "${sum.sync.cameraFrameCount}")
                MetricRow("IMU samples", "${sum.sync.imuSampleCount}")
                MetricRow("camera fps", "%.1f".format(sum.sync.cameraAchievedFps))
                MetricRow("dropped frames", "${sum.sync.droppedFrames}")
                MetricRow("cam/IMU offset est.", "${sum.sync.cameraImuOffsetNs}", "ns")
                MetricRow("max IMU gap", "${sum.sync.maxImuGapNs}", "ns")
                MetricRow("sync verified", "${sum.sync.synchronizationVerified}")
                sum.validationNotes.forEach { MetricRow("note", it, mono = false) }
            }
            Section("6 · Replay / pipeline") {
                Text("No on-device VIO is integrated. Replay by exporting and running the PC pipeline:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    com.nevcortex.prism.vio.PcBridge.instructions(
                        com.nevcortex.prism.vio.PcBridge.Tool.NEVCORTEX_VIO,
                        "<sessionDir>/export/euroc/mav0"
                    ),
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall
                )
            }
            Section("7 · Export run, calibration & metrics") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching {
                                val out = java.io.File(sum.sessionDir, "export/portable")
                                container.datasetExporter.exportPortable(sum, out)
                                message = "Exported: ${out.parentFile}"
                            }.onFailure { message = "Export failed: ${it.message}" }
                        }
                    }) { Text("Export ZIP") }
                    OutlinedButton(onClick = {
                        runCatching {
                            val out = java.io.File(sum.sessionDir, "export/euroc")
                            com.nevcortex.prism.dataset.FormatConverters.toEuRoCLike(
                                java.io.File(sum.sessionDir), out, container.calibrationStore.current()
                            )
                            message = "EuRoC-style: ${out.absolutePath}"
                        }.onFailure { message = "Convert failed: ${it.message}" }
                    }) { Text("Export EuRoC-style") }
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
