package com.nevcortex.prism.ui.screens.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section

@Composable
fun EvidenceScreen(container: AppContainer) {
    var refreshKey by remember { mutableStateOf(0) }
    val entries = remember(refreshKey) { container.beliefLedger.entries() }
    val scars = remember(refreshKey) { container.scarTissueMemory.scars() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Evidence & Doubt", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Section("Doubt / uncertainty") {
            Text(
                "No confidence is produced until a real producer (VIO tracking quality, sensor " +
                    "health, perception) feeds it. A confidence value here is never fabricated.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
            MetricRow("belief-ledger entries", "${entries.size}")
            MetricRow("scar-tissue events", "${scars.size}")
            OutlinedButton(onClick = { refreshKey++ }) { Text("Refresh") }
        }
        Section("Belief Ledger (recent)") {
            if (entries.isEmpty()) Text("Empty. Entries are recorded when a real claim is made.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            entries.takeLast(20).forEach { e ->
                MetricRow("${e.source} @ ${e.timestampMs}", e.claim, mono = false)
                Text("  conf=${"%.2f".format(e.confidence)}  ${e.evidence}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        Section("Scar Tissue Memory") {
            if (scars.isEmpty()) Text("No failure events recorded yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            scars.takeLast(20).forEach { Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
