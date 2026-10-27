package com.nevcortex.prism.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.status.updateUiMessage
import com.nevcortex.prism.ui.theme.Spacing
import com.nevcortex.prism.update.UpdatePhase
import com.nevcortex.prism.update.UpdateStatus

/**
 * The Settings "App Updates" card: installed version, updater endpoint, check / download /
 * install actions, and every state the manager can enter, worded for a nontechnical user.
 * The card never shows "update available" unless the manager verified a newer manifest itself.
 */
@Composable
fun AppUpdatesSection(container: AppContainer) {
    val updater = container.updateManager
    val context = LocalContext.current
    val status by updater.status.collectAsState()

    // Re-evaluate every resume: returning from Android's installer or the "install unknown
    // apps" permission screen lands here, and both change what the card should say.
    var canInstallNow by remember { mutableStateOf(updater.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        updater.onScreenOpened()
        canInstallNow = updater.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    var host by rememberSaveable { mutableStateOf(container.settingsStore.updateHost) }
    var portText by rememberSaveable { mutableStateOf(container.settingsStore.updatePort.toString()) }
    var serverSaved by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf(false) }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // The installer hands control back when it closes. A confirmed install usually
        // restarts PRISM before this can run, so this only reports what Android said.
        updater.onInstallScreenClosed(result.resultCode == Activity.RESULT_OK)
    }

    var installStartFailed by remember { mutableStateOf(false) }

    Section(stringResource(R.string.update_section_title)) {
        Text(
            stringResource(R.string.update_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))

        MetricRow(
            "Installed version",
            "${status.installedVersionName} (code ${status.installedVersionCode})",
            mono = false
        )
        MetricRow(
            "Last check",
            if (status.lastCheckAtMs > 0L) {
                Formats.timestamp(status.lastCheckAtMs)
            } else {
                stringResource(R.string.update_last_check_never)
            },
            mono = false
        )

        Spacer(Modifier.height(Spacing.sm))

        // ---- Current state banner ------------------------------------------------------------
        val ui = updateUiMessage(status)
        StatusBanner(
            level = ui.level,
            title = stringResource(ui.title),
            message = stringResource(ui.body, *ui.bodyArgs.toTypedArray())
        )
        status.technicalDetail?.let {
            Text(
                stringResource(R.string.update_technical_details) + ": $it",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (status.phase == UpdatePhase.DOWNLOADING) {
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { status.downloadPercent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${Formats.bytes(status.downloadedBytes)} · ${status.downloadPercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // ---- Actions -------------------------------------------------------------------------
        val busy = status.phase == UpdatePhase.CHECKING ||
            status.phase == UpdatePhase.DOWNLOADING ||
            status.phase == UpdatePhase.VERIFYING

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedButton(
                onClick = { updater.checkForUpdates() },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.update_check_now))
            }
            Button(
                onClick = { updater.downloadPendingUpdate() },
                enabled = !busy && status.phase == UpdatePhase.AVAILABLE,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.update_download_action))
            }
        }
        if (status.phase == UpdatePhase.DOWNLOADING) {
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = { updater.cancelDownload() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.update_cancel_download_action))
            }
        }

        val installable = status.phase == UpdatePhase.INSTALL_READY && !busy
        if (installable && canInstallNow) {
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = {
                    installStartFailed = false
                    val intent = updater.installIntent()
                    if (intent != null) {
                        runCatching { installLauncher.launch(intent) }
                            .onFailure { installStartFailed = true }
                    } else {
                        installStartFailed = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.update_install_action))
            }
        } else if (installable) {
            // Permission asked only when needed, and the app explains why first.
            Spacer(Modifier.height(Spacing.sm))
            InlineNotice(
                stringResource(R.string.update_permission_title) + " " +
                    stringResource(R.string.update_permission_body),
                StatusLevel.WARNING
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    runCatching { context.startActivity(updater.installPermissionIntent()) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.update_permission_action))
            }
        }
        if (installStartFailed) {
            Spacer(Modifier.height(Spacing.xs))
            InlineNotice(
                stringResource(R.string.update_fail_install_start),
                StatusLevel.ERROR
            )
        }

        if (status.phase == UpdatePhase.INSTALL_READY ||
            status.phase == UpdatePhase.INSTALL_PROMPTED
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    installStartFailed = false
                    updater.discardDownloadedFile()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.update_discard_action))
            }
            Text(
                stringResource(R.string.update_hash_verified_note) + "\n" +
                    stringResource(R.string.update_install_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- Offered release -----------------------------------------------------------------
        status.latest?.let { latest ->
            Spacer(Modifier.height(Spacing.sm))
            MetricRow(
                "Offered build",
                "${latest.versionName} (code ${latest.versionCode})",
                mono = false
            )
            if (latest.releaseNotes.isNotBlank()) {
                Text(
                    stringResource(R.string.update_notes_title),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    latest.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Endpoint configuration ----------------------------------------------------------
        Spacer(Modifier.height(Spacing.md))
        Text(
            stringResource(R.string.update_server_title),
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            stringResource(R.string.update_server_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it; serverError = false; serverSaved = false },
            label = { Text(stringResource(R.string.update_server_host_label)) },
            placeholder = { Text(stringResource(R.string.update_server_host_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5); serverError = false; serverSaved = false },
            label = { Text(stringResource(R.string.update_server_port_label)) },
            placeholder = { Text(stringResource(R.string.update_server_port_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        if (serverError) {
            Text(
                stringResource(R.string.home_address_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (serverSaved) {
            Text(
                stringResource(R.string.update_server_saved),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Button(
            onClick = {
                val port = portText.toIntOrNull()
                if (host.isBlank() || port == null || port !in 1..65535) {
                    serverError = true
                } else {
                    container.settingsStore.updateHost = host
                    container.settingsStore.updatePort = port
                    updater.configure(host.trim(), port)
                    serverSaved = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.update_server_save))
        }
        InlineNotice(stringResource(R.string.update_server_suggestion), StatusLevel.NEUTRAL)
        InlineNotice(stringResource(R.string.update_persist_note), StatusLevel.WARNING)
    }
}
