package com.nevcortex.prism.ui.screens.mapping

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
import com.nevcortex.prism.ui.components.Section

@Composable
fun MappingScreen(container: AppContainer) {
    val map = container.localMap
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Mapping", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Section("Local map") {
            Text("Available: ${map.available}", color = MaterialTheme.colorScheme.onSurface)
            Text(map.note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Section("How mapping gets enabled") {
            Text(
                "A local map needs (a) camera poses from a VIO/VO trajectory and (b) depth or " +
                    "obstacle observations from perception. Neither is produced on-device yet. Once an " +
                    "on-device or PC-fed trajectory + perception outputs exist, mapping, evidence, and " +
                    "the P.R.I.S.M. traversability/uncertainty models connect into one system.",
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
