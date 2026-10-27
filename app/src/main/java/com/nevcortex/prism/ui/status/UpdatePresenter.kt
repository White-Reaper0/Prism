package com.nevcortex.prism.ui.status

import androidx.annotation.StringRes
import com.nevcortex.prism.R
import com.nevcortex.prism.update.UpdateFailure
import com.nevcortex.prism.update.UpdatePhase
import com.nevcortex.prism.update.UpdateStatus
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats

/** One plain-language description of the update state, plus the action that fits it. */
data class UpdateUiMessage(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val bodyArgs: List<Any> = emptyList(),
    @StringRes val action: Int? = null,
    val level: StatusLevel
)

fun updateUiMessage(status: UpdateStatus): UpdateUiMessage {
    val failureTitle: Int = failureRes(status.failure)
    return when (status.phase) {
        UpdatePhase.NOT_CONFIGURED -> UpdateUiMessage(
            R.string.update_not_set_title,
            R.string.update_not_set_body,
            action = R.string.update_configure_action,
            level = StatusLevel.NEUTRAL
        )
        UpdatePhase.RECORDING_ACTIVE -> UpdateUiMessage(
            R.string.update_recording_title,
            R.string.update_recording_body,
            action = null,
            level = StatusLevel.WARNING
        )
        UpdatePhase.READY -> UpdateUiMessage(
            R.string.update_ready_title,
            R.string.update_ready_body,
            action = R.string.update_check_now,
            level = StatusLevel.INFO
        )
        UpdatePhase.CHECKING -> UpdateUiMessage(
            R.string.update_checking_title,
            R.string.update_checking_body,
            listOf(status.host.ifBlank { "…" }, status.port),
            level = StatusLevel.INFO
        )
        UpdatePhase.LATEST -> UpdateUiMessage(
            R.string.update_latest_title,
            R.string.update_latest_body,
            listOf(status.installedVersionName),
            level = StatusLevel.GOOD
        )
        UpdatePhase.AVAILABLE -> UpdateUiMessage(
            R.string.update_available_title,
            R.string.update_available_body,
            listOf(
                status.latest?.versionName.orEmpty(),
                status.installedVersionName,
                Formats.bytes(status.latest?.sizeBytes ?: 0L)
            ),
            action = R.string.update_download_action,
            level = StatusLevel.INFO
        )
        UpdatePhase.DOWNLOADING -> UpdateUiMessage(
            R.string.update_downloading_title,
            R.string.update_downloading_body,
            listOf(status.downloadPercent),
            level = StatusLevel.INFO
        )
        UpdatePhase.VERIFYING -> UpdateUiMessage(
            R.string.update_verifying_title,
            R.string.update_verifying_body,
            level = StatusLevel.INFO
        )
        UpdatePhase.INSTALL_READY -> UpdateUiMessage(
            R.string.update_ready_install_title,
            R.string.update_ready_install_body,
            action = R.string.update_install_action,
            level = StatusLevel.GOOD
        )
        UpdatePhase.INSTALL_PROMPTED -> UpdateUiMessage(
            R.string.update_prompted_title,
            R.string.update_prompted_body,
            level = StatusLevel.INFO
        )
        UpdatePhase.NEED_INSTALL_PERMISSION -> UpdateUiMessage(
            R.string.update_permission_title,
            R.string.update_permission_body,
            action = R.string.update_permission_action,
            level = StatusLevel.WARNING
        )
        UpdatePhase.CANCELLED -> UpdateUiMessage(
            R.string.update_cancelled_title,
            R.string.update_cancelled_body,
            level = StatusLevel.NEUTRAL
        )
        UpdatePhase.FAILED -> UpdateUiMessage(
            failureTitle,
            R.string.update_failed_generic_body,
            action = R.string.update_retry_action,
            level = StatusLevel.ERROR
        )
    }
}

@StringRes
private fun failureRes(failure: UpdateFailure): Int = when (failure) {
    UpdateFailure.NONE -> R.string.update_failed_generic_title
    UpdateFailure.BAD_ADDRESS -> R.string.update_fail_bad_address
    UpdateFailure.UNREACHABLE -> R.string.update_fail_unreachable
    UpdateFailure.TIMEOUT -> R.string.update_fail_timeout
    UpdateFailure.MALFORMED -> R.string.update_fail_malformed
    UpdateFailure.WRONG_APP -> R.string.update_fail_wrong_app
    UpdateFailure.NOT_NEWER -> R.string.update_fail_not_newer
    UpdateFailure.UNSUPPORTED_VERSION -> R.string.update_fail_unsupported
    UpdateFailure.SIZE_MISMATCH -> R.string.update_fail_size
    UpdateFailure.HASH_MISMATCH -> R.string.update_fail_hash
    UpdateFailure.INCOMPLETE -> R.string.update_fail_incomplete
    UpdateFailure.STORAGE -> R.string.update_fail_storage
    UpdateFailure.INSTALL_PERMISSION -> R.string.update_fail_permission
    UpdateFailure.INSTALL_START -> R.string.update_fail_install_start
    UpdateFailure.RECORDING_ACTIVE -> R.string.update_recording_body
    UpdateFailure.UNKNOWN -> R.string.update_fail_unknown
}
