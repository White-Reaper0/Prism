package com.nevcortex.prism.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nevcortex.prism.ui.theme.LocalPrismStatusColors
import com.nevcortex.prism.ui.theme.PrismBlue
import com.nevcortex.prism.ui.theme.PrismGreen
import com.nevcortex.prism.ui.theme.PrismRed
import com.nevcortex.prism.ui.theme.Spacing

enum class StatusLevel { NEUTRAL, INFO, GOOD, WARNING, ERROR }

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(Spacing.md))
            content()
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, unit: String = "", mono: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            "$value${if (unit.isNotBlank()) " $unit" else ""}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

@Composable
fun StatusChip(text: String, ok: Boolean) {
    val status = LocalPrismStatusColors.current
    val color = when {
        ok -> status.success
        text.contains("WARN", true) || text.contains("FAIL", true) ||
            text.contains("ABSENT", true) || text.contains("error", true) -> MaterialTheme.colorScheme.error
        else -> status.warning
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = Spacing.sm, vertical = 3.dp)
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * A complete state message: what is happening, what it means, and the next action. Colour is
 * never the only signal — there is always an icon and text.
 */
@Composable
fun StatusBanner(
    level: StatusLevel,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val status = LocalPrismStatusColors.current
    val accent = when (level) {
        StatusLevel.GOOD -> status.success
        StatusLevel.WARNING -> status.warning
        StatusLevel.ERROR -> MaterialTheme.colorScheme.error
        StatusLevel.INFO -> status.info
        StatusLevel.NEUTRAL -> status.muted
    }
    val container = when (level) {
        StatusLevel.GOOD -> status.successContainer
        StatusLevel.WARNING -> status.warningContainer
        StatusLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusLevel.INFO -> status.infoContainer
        StatusLevel.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val icon = when (level) {
        StatusLevel.GOOD -> Icons.Filled.CheckCircle
        StatusLevel.WARNING -> Icons.Filled.WarningAmber
        StatusLevel.ERROR -> Icons.Filled.ErrorOutline
        StatusLevel.INFO -> Icons.Filled.Info
        StatusLevel.NEUTRAL -> Icons.Filled.Circle
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(Modifier.padding(Spacing.lg)) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(actionLabel, color = accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ReadinessRow(
    title: String,
    detail: String,
    level: StatusLevel,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val status = LocalPrismStatusColors.current
    val accent = when (level) {
        StatusLevel.GOOD -> status.success
        StatusLevel.WARNING -> status.warning
        StatusLevel.ERROR -> MaterialTheme.colorScheme.error
        StatusLevel.INFO -> status.info
        StatusLevel.NEUTRAL -> status.muted
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(9.dp)
                .background(accent, CircleShape)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.sm)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A tappable secondary destination row. Uses a full-row touch target and an explicit chevron. */
@Composable
fun NavigationCard(
    title: String,
    body: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InlineNotice(text: String, level: StatusLevel = StatusLevel.INFO, modifier: Modifier = Modifier) {
    val status = LocalPrismStatusColors.current
    val accent = when (level) {
        StatusLevel.GOOD -> status.success
        StatusLevel.WARNING -> status.warning
        StatusLevel.ERROR -> MaterialTheme.colorScheme.error
        StatusLevel.INFO -> status.info
        StatusLevel.NEUTRAL -> status.muted
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.08f), MaterialTheme.shapes.small)
            .padding(Spacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 6.dp).size(6.dp).background(accent, CircleShape))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.md))
                content()
            }
        }
    }
}

/** Single-axis scrolling line plot over the most recent values (bounded by the caller). */
@Composable
fun ScrollingPlot(values: List<Float>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(90.dp)) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val n = values.size
        val min = values.min()
        val max = values.max()
        val range = (max - min).coerceAtLeast(1e-6f)
        val w = size.width
        val h = size.height
        val step = w / (n - 1)
        val path = Path()
        for (i in 0 until n) {
            val x = i * step
            val y = h - (values[i] - min) / range * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
}

/** Three overlaid series (X/Y/Z) sharing one time axis. */
@Composable
fun TriAxisPlot(
    x: List<Float>,
    y: List<Float>,
    z: List<Float>,
    modifier: Modifier = Modifier.fillMaxWidth().height(110.dp)
) {
    Canvas(modifier) {
        val series = listOf(x to PrismRed, y to PrismGreen, z to PrismBlue)
        var gmin = Float.POSITIVE_INFINITY
        var gmax = Float.NEGATIVE_INFINITY
        for (s in listOf(x, y, z)) {
            if (s.isNotEmpty()) {
                gmin = minOf(gmin, s.min())
                gmax = maxOf(gmax, s.max())
            }
        }
        if (gmin.isInfinite() || gmax.isInfinite()) return@Canvas
        val range = (gmax - gmin).coerceAtLeast(1e-6f)
        val w = size.width
        val h = size.height
        for ((vals, col) in series) {
            if (vals.size < 2) continue
            val n = vals.size
            val step = w / (n - 1)
            val path = Path()
            for (i in 0 until n) {
                val px = i * step
                val py = h - (vals[i] - gmin) / range * h
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, col, style = Stroke(width = 1.6f))
        }
    }
}

@Composable
fun AxisLegend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        LegendDot(PrismRed, "X")
        Spacer(Modifier.size(Spacing.sm))
        LegendDot(PrismGreen, "Y")
        Spacer(Modifier.size(Spacing.sm))
        LegendDot(PrismBlue, "Z")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.size(Spacing.xs))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
