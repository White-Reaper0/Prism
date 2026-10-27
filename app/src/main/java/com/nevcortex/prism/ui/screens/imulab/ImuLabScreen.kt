package com.nevcortex.prism.ui.screens.imulab

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.model.ImuStreamStats
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SessionSummary
import com.nevcortex.prism.ui.components.AxisLegend
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusChip
import com.nevcortex.prism.ui.components.TriAxisPlot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RecState { IDLE, RECORDING, PAUSED }

@Composable
fun ImuLabScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val sensorRepo = container.sensorRepository

    // Live stats from the collector (measured, not requested).
    val statsMap by sensorRepo.stats.collectAsState()
    val statuses = remember { sensorRepo.enumerate() }

    // Plot series, refreshed on a bounded ticker so rendering never blocks capture.
    var ax by remember { mutableStateOf<List<Float>>(emptyList()) }
    var ay by remember { mutableStateOf<List<Float>>(emptyList()) }
    var az by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gx by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gy by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gz by remember { mutableStateOf<List<Float>>(emptyList()) }
    var mx by remember { mutableStateOf<List<Float>>(emptyList()) }
    var my by remember { mutableStateOf<List<Float>>(emptyList()) }
    var mz by remember { mutableStateOf<List<Float>>(emptyList()) }

    var recState by remember { mutableStateOf(RecState.IDLE) }
    var lastSummary by remember { mutableStateOf<SessionSummary?>(null) }
    var message by remember { mutableStateOf("") }

    val activeKinds = listOf(
        SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE, SensorKind.MAGNETOMETER,
        SensorKind.GRAVITY, SensorKind.LINEAR_ACCELERATION, SensorKind.ROTATION_VECTOR
    ).filter { sensorRepo.maxRateHz(it) > 0 || sensorRepo.enumerate(listOf(it)).firstOrNull()?.available == true }

    // Start sensors on enter, stop on leave (IMU Lab is in-activity; cam+IMU sessions use the
    // foreground service from Mission/Live Camera).
    LaunchedEffect(Unit) {
        sensorRepo.start(activeKinds, container.settingsStore.imuRateHz)
    }
    DisposableEffect(Unit) {
        onDispose { sensorRepo.stop() }
    }

    // Bounded plot refresh.
    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("IMU Lab", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Live diagnostics. Achieved rates are MEASURED. Raw and Android-fusion streams are labeled separately.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )

        // Recording controls (in-activity IMU-only sessions).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (recState) {
                RecState.IDLE -> Button(onClick = {
                    scope.launch {
                        val cfg = container.settingsStore.newSessionConfig("IMU Lab").copy(
                            captureCamera = false, captureImu = true
                        )
                        runCatching { container.recorder.start(cfg, null) }
                            .onSuccess { recState = RecState.RECORDING; message = "Recording IMU: ${cfg.id}" }
                            .onFailure { message = "Start failed: ${it.message}" }
                    }
                }) { Text("Start") }
                RecState.RECORDING -> {
                    OutlinedButton(onClick = { container.recorder.pause(); recState = RecState.PAUSED }) { Text("Pause") }
                    Button(onClick = {
                        scope.launch {
                            runCatching { container.recorder.stop() }
                                .onSuccess { lastSummary = it; recState = RecState.IDLE; message = "Saved: ${it.id}" }
                                .onFailure { message = "Stop failed: ${it.message}" }
                        }
                    }) { Text("Stop") }
                }
                RecState.PAUSED -> {
                    OutlinedButton(onClick = { container.recorder.resume(); recState = RecState.RECORDING }) { Text("Resume") }
                    Button(onClick = {
                        scope.launch {
                            runCatching { container.recorder.stop() }
                                .onSuccess { lastSummary = it; recState = RecState.IDLE; message = "Saved: ${it.id}" }
                                .onFailure { message = "Stop failed: ${it.message}" }
                        }
                    }) { Text("Stop") }
                }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        // Sensor availability
        Section("Sensors detected") {
            statuses.filter { it.identity.kind.isImu }.forEach { st ->
                MetricRow(
                    st.identity.kind.label,
                    if (st.available) "present · ${st.identity.name}" else "ABSENT",
                    unit = "",
                    mono = false
                )
                if (st.available && st.identity.minDelayUs > 0) {
                    MetricRow("  max rate", "${1_000_000 / st.identity.minDelayUs}", "Hz")
                }
            }
        }

        // Per-stream stats (measured).
        Section("Measured sample-rate & timing") {
            SensorKind.values().filter { it.isImu }.forEach { kind ->
                val s = statsMap[kind] ?: return@forEach
                StreamStatsBlock(kind, s)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Clock offsets are (arrival elapsedRealtimeNs − source timestamp). Small, stable " +
                    "offset ⇒ same clock domain as the alignment clock. Large/jittery ⇒ different " +
                    "domain; synchronization must NOT be assumed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        }

        // Plots
        Section("Accelerometer  (m/s², raw)") {
            TriAxisPlot(ax, ay, az); AxisLegend()
        }
        Section("Gyroscope  (rad/s, raw)") {
            TriAxisPlot(gx, gy, gz); AxisLegend()
        }
        if (mx.isNotEmpty()) {
            Section("Magnetometer  (µT, raw)") {
                TriAxisPlot(mx, my, mz); AxisLegend()
            }
        } else {
            Section("Magnetometer") {
                Text("No magnetometer samples in the current window.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Post-recording export
        lastSummary?.let { sum ->
            Section("Last session: ${sum.id.take(8)}") {
                MetricRow("frames", "${sum.sync.cameraFrameCount}")
                MetricRow("IMU samples", "${sum.sync.imuSampleCount}")
                MetricRow("duration", "${"%.2f".format(sum.durationNs / 1e9)}", "s")
                sum.validationNotes.forEach { MetricRow("note", it, mono = false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching {
                                val out = java.io.File(sum.sessionDir, "export/portable")
                                val zip = container.datasetExporter.exportPortable(sum, out)
                                message = "Exported: ${zip.absolutePath}"
                            }.onFailure { message = "Export failed: ${it.message}" }
                        }
                    }) { Text("Export ZIP") }
                    OutlinedButton(onClick = {
                        runCatching {
                            val out = java.io.File(sum.sessionDir, "export/euroc")
                            com.nevcortex.prism.dataset.FormatConverters.toEuRoCLike(
                                java.io.File(sum.sessionDir), out, container.calibrationStore.current()
                            )
                            message = "EuRoC-style export: ${out.absolutePath}"
                        }.onFailure { message = "Conversion failed: ${it.message}" }
                    }) { Text("Export EuRoC-style") }
                }
            }
        }
    }
}

@Composable
private fun StreamStatsBlock(kind: SensorKind, s: ImuStreamStats) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kind.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            StatusChip(
                text = when {
                    s.discontinuity -> "WARN: non-monotonic ts"
                    s.clipping -> "WARN: clipping"
                    s.gapsDetected > 0 -> "WARN: ${s.gapsDetected} gaps"
                    s.sampleCount == 0L -> "idle"
                    else -> "ok"
                },
                ok = s.sampleCount > 0 && !s.discontinuity && !s.clipping && s.gapsDetected == 0
            )
            if (kind.derived) StatusChip("Android fusion", ok = false)
        }
        MetricRow("  achieved rate", "%.1f".format(s.achievedRateHz), "Hz")
        MetricRow("  expected rate", "%.1f".format(s.expectedRateHz), "Hz")
        MetricRow("  interval med/min/max", "${s.medianIntervalNs}/${s.minIntervalNs}/${s.maxIntervalNs}", "ns")
        MetricRow("  accuracy", "${s.lastAccuracy} (0=unreliable..3=high)")
        MetricRow("  clock offset med", "${s.offsetMedianNs}", "ns")
        MetricRow("  clock offset jitter", "${s.offsetJitterNs}", "ns")
        if (kind == SensorKind.ACCELEROMETER) MetricRow("  state", if (s.moving) "MOVING" else "stationary")
        Spacer(Modifier.height(2.dp))
    }
}
