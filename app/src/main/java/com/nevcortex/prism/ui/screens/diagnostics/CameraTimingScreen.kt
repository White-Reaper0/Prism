package com.nevcortex.prism.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatTile
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.theme.Spacing

@Composable
fun CameraTimingScreen(container: AppContainer, onOpenStudio: () -> Unit) {
    val stats by container.frameCollector.stats.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        if (stats.frameCount == 0L) {
            StatusBanner(
                level = StatusLevel.INFO,
                title = stringResource(R.string.camera_no_frames_title),
                message = stringResource(R.string.camera_idle),
                actionLabel = stringResource(R.string.camera_open_studio),
                onAction = onOpenStudio
            )
        } else {
            StatusBanner(
                level = if (stats.droppedFrames > 0) StatusLevel.WARNING else StatusLevel.GOOD,
                title = stringResource(R.string.camera_measuring_title),
                message = if (stats.droppedFrames > 0) {
                    stringResource(R.string.camera_drops_body, stats.droppedFrames)
                } else {
                    stringResource(R.string.camera_measuring_body)
                }
            )
        }

        Text(
            stringResource(R.string.camera_diag_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Section(stringResource(R.string.camera_diag_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile(
                    label = stringResource(R.string.camera_frames),
                    value = stats.frameCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.camera_fps),
                    value = Formats.fps(stats.achievedFps),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile(
                    label = stringResource(R.string.camera_interval),
                    value = Formats.milliseconds(stats.intervalMedianNs),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.camera_dropped),
                    value = stats.droppedFrames.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        ExpandableSection(stringResource(R.string.diag_technical_detail)) {
            MetricRow(
                stringResource(R.string.camera_interval),
                "${stats.intervalMedianNs} ns"
            )
            MetricRow(
                stringResource(R.string.camera_interval_std),
                "${stats.intervalStdNs} ns"
            )
            MetricRow(
                stringResource(R.string.camera_clock_offset),
                "${stats.offsetMedianNs} ns"
            )
            MetricRow(
                stringResource(R.string.camera_offset_jitter),
                "${stats.offsetJitterNs} ns"
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.camera_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}
