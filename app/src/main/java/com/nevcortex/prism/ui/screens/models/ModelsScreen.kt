package com.nevcortex.prism.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.perception.PerceptionPipeline
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusChip

@Composable
fun ModelsScreen(container: AppContainer) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Models", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(PerceptionPipeline.status(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Section("Perception model slots") {
            PerceptionPipeline.models.forEach { m ->
                MetricRow(m.name, if (m.available) "available" else "NOT bundled")
                Text("  ${m.note}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("  consumes: " + listOfNotNull(
                    if (m.consumesFrameTimestamps) "frame timestamps" else null,
                    if (m.consumesImu) "IMU" else null
                ).joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                StatusChip(if (m.available) "ok" else "missing", ok = m.available)
            }
        }
        Section("Adding models") {
            Text(
                "Place ONNX/TFLite weights in app-local storage and wire a runtime delegate. Models " +
                    "must expose a defined interface (frame timestamps in, detections/depth/masks out) " +
                    "so the evidence and mapping modules consume results without hidden coupling. IMU " +
                    "values are NOT fed into vision models without a defined interface.",
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
