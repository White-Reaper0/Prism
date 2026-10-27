package com.nevcortex.prism.ui.screens.livecamera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.service.RecordingService
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LiveCameraScreen(container: AppContainer) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val frameStats by container.frameCollector.stats.collectAsState()
    var hasCamera by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<com.nevcortex.prism.model.SessionSummary?>(null) }

    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        if (!granted) message = "Camera permission denied — recording unavailable."
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        hasCamera = granted
        if (!granted) camPermission.launch(Manifest.permission.CAMERA)
        // Notifications are needed for the foreground recording service on Android 13+.
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Preview (live view). While recording the service owns the camera, so we unbind here.
    val previewView = remember { PreviewView(context) }
    LaunchedEffect(hasCamera, isRecording) {
        if (hasCamera && !isRecording) {
            runCatching { container.cameraRepository.init() }.onSuccess {
                val cfg = container.settingsStore.newSessionConfig("Live Camera")
                container.cameraRepository.bindPreview(lifecycleOwner, previewView, cfg, container.frameCollector)
            }.onFailure { message = "Camera init failed: ${it.message}" }
        }
    }

    // Poll for the post-stop summary set by the service.
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            repeat(20) { delay(250); if (container.lastSessionSummary != null) summary = container.lastSessionSummary }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Live Camera", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Camera frame timestamps come from the camera HAL clock, NOT assumed aligned with the " +
                "IMU clock. The offset is measured, not assumed.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )

        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f), contentAlignment = Alignment.Center) {
            if (hasCamera && !isRecording) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    if (isRecording) "Recording — camera bound to foreground service (headless capture)"
                    else "Camera permission not granted",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isRecording) {
                Button(onClick = {
                    val cfg = container.settingsStore.newSessionConfig("Live Camera").copy(
                        captureCamera = true, captureImu = true, captureMode = CameraCaptureMode.VIDEO
                    )
                    RecordingService.start(context, cfg)
                    isRecording = true
                    message = "Recording started (service foreground)"
                }, enabled = hasCamera) { Text("Record (camera + IMU)") }
            } else {
                Button(onClick = {
                    RecordingService.stop(context)
                    isRecording = false
                    message = "Stopping…"
                }) { Text("Stop") }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        Section("Frame timing (measured)") {
            MetricRow("frames", "${frameStats.frameCount}")
            MetricRow("achieved fps", "%.1f".format(frameStats.achievedFps))
            MetricRow("interval median", "${frameStats.intervalMedianNs}", "ns")
            MetricRow("interval std", "${frameStats.intervalStdNs}", "ns")
            MetricRow("clock offset median", "${frameStats.offsetMedianNs}", "ns")
            MetricRow("clock offset jitter", "${frameStats.offsetJitterNs}", "ns")
            MetricRow("dropped/missed frames", "${frameStats.droppedFrames}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(
                    if (frameStats.frameCount == 0L) "idle" else if (frameStats.droppedFrames > 0) "WARN: drops" else "ok",
                    ok = frameStats.frameCount > 0 && frameStats.droppedFrames == 0
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Per-frame EXACT timestamps for VIDEO mode require offline MP4 extraction. " +
                    "frames.csv records analysis-callback arrivals (a faithful proxy, not the encoder).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        }

        summary?.let { sum ->
            Section("Session ${sum.id.take(8)}") {
                MetricRow("frames", "${sum.sync.cameraFrameCount}")
                MetricRow("IMU samples", "${sum.sync.imuSampleCount}")
                MetricRow("camera fps", "%.1f".format(sum.sync.cameraAchievedFps))
                MetricRow("cam/IMU offset est.", "${sum.sync.cameraImuOffsetNs}", "ns")
                MetricRow("sync verified", "${sum.sync.synchronizationVerified}")
                sum.validationNotes.forEach { MetricRow("note", it, mono = false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching {
                                val out = java.io.File(sum.sessionDir, "export/portable")
                                container.datasetExporter.exportPortable(sum, out)
                                message = "Exported to ${out.parentFile}"
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
            }
        }
    }
}
