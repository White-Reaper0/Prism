package com.nevcortex.prism.ui.screens.diagnostics

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.link.LinkFailure
import com.nevcortex.prism.link.LinkPhase
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.status.failureMessage
import com.nevcortex.prism.ui.status.linkUiMessage
import com.nevcortex.prism.ui.theme.Spacing

@Composable
fun ConnectionScreen(container: AppContainer, onOpenStudio: () -> Unit) {
    val status by container.link.status.collectAsState()
    var host by rememberSaveable(container.settingsStore.pcHost) {
        mutableStateOf(container.settingsStore.pcHost)
    }
    var portText by rememberSaveable(container.settingsStore.pcPort) {
        mutableStateOf(container.settingsStore.pcPort.toString())
    }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    val live = status.isLive
    val busy = status.phase == LinkPhase.CONNECTING ||
        status.phase == LinkPhase.RECONNECTING
    val message = linkUiMessage(status)

    fun connect() {
        val cleanHost = host.trim()
        val port = portText.trim().toIntOrNull()
        when {
            cleanHost.isBlank() -> errorRes = R.string.home_address_error
            port == null || port !in 1..65535 -> errorRes = R.string.home_port_error
            else -> {
                errorRes = null
                container.settingsStore.pcHost = cleanHost
                container.settingsStore.pcPort = port
                container.link.connect(cleanHost, port)
            }
        }
    }

    val actionLabel: String? = when (status.phase) {
        LinkPhase.NOT_CONFIGURED -> stringResource(R.string.status_not_set_action)
        LinkPhase.READY, LinkPhase.DISCONNECTED -> stringResource(R.string.status_ready_action)
        LinkPhase.CONNECTING, LinkPhase.RECONNECTING, LinkPhase.INTERRUPTED ->
            stringResource(R.string.status_interrupted_action)
        LinkPhase.CONNECTED, LinkPhase.STREAMING -> stringResource(R.string.studio_stop_stream)
        LinkPhase.STALE -> stringResource(R.string.status_stale_action)
        LinkPhase.UNSUPPORTED -> null
    }
    val action: (() -> Unit)? = when (status.phase) {
        LinkPhase.CONNECTED, LinkPhase.STREAMING, LinkPhase.STALE ->
            ({ container.link.disconnect() })
        LinkPhase.CONNECTING, LinkPhase.RECONNECTING, LinkPhase.INTERRUPTED ->
            ({ container.link.disconnect() })
        LinkPhase.UNSUPPORTED -> null
        else -> ({ connect() })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        StatusBanner(
            level = message.level,
            title = stringResource(message.title),
            message = if (message.bodyArgs.isEmpty()) {
                stringResource(message.body)
            } else {
                stringResource(message.body, *message.bodyArgs.toTypedArray())
            },
            actionLabel = actionLabel,
            onAction = action
        )

        if (status.failure != LinkFailure.NONE) {
            InlineNotice(
                stringResource(failureMessage(status.failure)),
                StatusLevel.ERROR
            )
        }

        Section(stringResource(R.string.home_pc)) {
            Text(
                stringResource(R.string.home_pc_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; errorRes = null },
                label = { Text(stringResource(R.string.home_pc_address_label)) },
                placeholder = { Text(stringResource(R.string.home_pc_address_hint)) },
                singleLine = true,
                enabled = !live,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5); errorRes = null },
                label = { Text(stringResource(R.string.home_port_label)) },
                singleLine = true,
                enabled = !live,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            errorRes?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (live) {
                    Button(onClick = onOpenStudio, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_open_studio))
                    }
                    OutlinedButton(
                        onClick = { container.link.disconnect() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.home_disconnect))
                    }
                } else {
                    Button(
                        onClick = { connect() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.home_connect))
                    }
                    if (busy) {
                        OutlinedButton(
                            onClick = { container.link.disconnect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.diag_no_discovery_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section(stringResource(R.string.diag_connection_title)) {
            MetricRow(
                stringResource(R.string.diag_endpoint),
                if (status.hasHost) "${status.host}:${status.port}"
                else stringResource(R.string.diag_none),
                mono = false
            )
            MetricRow(
                stringResource(R.string.diag_protocol),
                status.protocol ?: stringResource(R.string.diag_none),
                mono = false
            )
            MetricRow(
                stringResource(R.string.diag_receiver),
                status.receiverName ?: stringResource(R.string.diag_none),
                mono = false
            )
            MetricRow(
                stringResource(R.string.diag_connected_since),
                if (status.connectedAtElapsedMs > 0L) {
                    Formats.ago(status.connectedAtElapsedMs)
                } else stringResource(R.string.diag_none),
                mono = false
            )
            MetricRow(
                stringResource(R.string.diag_rtt),
                if (status.roundTripMs >= 0L) "${status.roundTripMs} ms"
                else stringResource(R.string.diag_none)
            )
            MetricRow(
                stringResource(R.string.diag_frames_sent),
                status.framesSent.toString()
            )
            MetricRow(
                stringResource(R.string.diag_imu_sent),
                status.imuSamplesSent.toString()
            )
            MetricRow(
                stringResource(R.string.diag_dropped),
                status.droppedFrames.toString()
            )
            MetricRow(
                stringResource(R.string.diag_bytes),
                Formats.bytes(status.bytesSent)
            )
            MetricRow(
                stringResource(R.string.diag_reconnect_attempt),
                status.reconnectAttempts.toString()
            )
            Spacer(Modifier.height(Spacing.sm))
            InlineNotice(
                stringResource(R.string.diag_latency_note),
                StatusLevel.INFO
            )
        }

        ExpandableSection(stringResource(R.string.diag_technical_detail)) {
            MetricRow(
                stringResource(R.string.diag_last_error),
                status.failure.name,
                mono = false
            )
            Text(
                status.technicalDetail ?: stringResource(R.string.diag_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.studio_receiver_help_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}
