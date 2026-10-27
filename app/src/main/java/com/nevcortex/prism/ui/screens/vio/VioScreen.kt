package com.nevcortex.prism.ui.screens.vio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.vio.PcBridge

@Composable
fun VioScreen(container: AppContainer) {
    var tool by remember { mutableStateOf(PcBridge.Tool.NEVCORTEX_VIO) }
    val vo = container.visualOdometry
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("VIO / Trajectory", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "On-device status: ${vo.status()} — ${if (vo.process("").valid) "trajectory available" else "no trajectory produced"}",
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            vo.name + ". No trajectory is fabricated. Export a session and run the PC pipeline.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )

        Section("On-device pipeline") {
            Text(
                "A genuine visual-inertial odometry pipeline (feature tracking + IMU preintegration " +
                    "+ joint optimization) is not integrated on-device. Integrating one requires a vision " +
                    "library (OpenCV) and a VIO backend. Until then, P.R.I.S.M. delivers reliable " +
                    "synchronized recording, calibration, and export, and connects to the PC pipeline.",
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall
            )
        }

        Section("PC-side pipeline bridge") {
            Text("Tool", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PcBridge.Tool.values().forEach { t ->
                    FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t.label.take(12)) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                PcBridge.instructions(tool, "<sessionDir>/export/euroc/mav0"),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall
            )
        }

        Section("Visual-only vs visual-inertorial") {
            Text(
                "To compare camera-only against camera-plus-IMU, run BOTH modes on the SAME exported " +
                    "sequence (e.g. ORB-SLAM3 monocular vs monocular-inertial). Report drift only against " +
                    "a defined reference; without one, report the metric as UNAVAILABLE.",
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall
            )
        }

        container.lastSessionSummary?.let { sum ->
            Section("Last session: ${sum.id.take(8)}") {
                OutlinedButton(onClick = {
                    runCatching {
                        val out = java.io.File(sum.sessionDir, "export/euroc")
                        com.nevcortex.prism.dataset.FormatConverters.toEuRoCLike(
                            java.io.File(sum.sessionDir), out, container.calibrationStore.current()
                        )
                    }
                }) { Text("Export EuRoC-style for ${tool.label.take(14)}") }
            }
        }
    }
}
