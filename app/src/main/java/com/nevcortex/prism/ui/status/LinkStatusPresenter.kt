package com.nevcortex.prism.ui.status

import androidx.annotation.StringRes
import com.nevcortex.prism.R
import com.nevcortex.prism.link.LinkFailure
import com.nevcortex.prism.link.LinkPhase
import com.nevcortex.prism.link.LinkStatus
import com.nevcortex.prism.ui.components.StatusLevel

/** A complete, plain-language description of the current link state. */
data class LinkUiMessage(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val bodyArgs: List<Any> = emptyList(),
    @StringRes val action: Int? = null,
    val level: StatusLevel
)

fun linkUiMessage(status: LinkStatus): LinkUiMessage = when (status.phase) {
    LinkPhase.NOT_CONFIGURED -> LinkUiMessage(
        R.string.status_not_set_title,
        R.string.status_not_set_body,
        action = R.string.status_not_set_action,
        level = StatusLevel.NEUTRAL
    )
    LinkPhase.READY -> LinkUiMessage(
        R.string.status_ready_title,
        R.string.status_ready_body,
        action = R.string.status_ready_action,
        level = StatusLevel.INFO
    )
    LinkPhase.CONNECTING -> LinkUiMessage(
        R.string.status_connecting_title,
        R.string.status_connecting_body,
        listOf(status.host, status.port),
        action = R.string.status_connecting_action,
        level = StatusLevel.INFO
    )
    LinkPhase.CONNECTED -> LinkUiMessage(
        R.string.status_connected_title,
        R.string.status_connected_body,
        action = R.string.status_connected_action,
        level = StatusLevel.GOOD
    )
    LinkPhase.STREAMING -> LinkUiMessage(
        R.string.status_streaming_title,
        R.string.status_streaming_body,
        action = R.string.status_streaming_action,
        level = StatusLevel.GOOD
    )
    LinkPhase.STALE -> LinkUiMessage(
        R.string.status_stale_title,
        R.string.status_stale_body,
        action = R.string.status_stale_action,
        level = StatusLevel.WARNING
    )
    LinkPhase.INTERRUPTED -> LinkUiMessage(
        R.string.status_interrupted_title,
        R.string.status_interrupted_body,
        action = R.string.status_interrupted_action,
        level = StatusLevel.WARNING
    )
    LinkPhase.RECONNECTING -> LinkUiMessage(
        R.string.status_reconnecting_title,
        R.string.status_reconnecting_body,
        listOf(status.reconnectAttempts.coerceAtLeast(1)),
        action = R.string.status_reconnecting_action,
        level = StatusLevel.INFO
    )
    LinkPhase.DISCONNECTED -> LinkUiMessage(
        R.string.status_disconnected_title,
        R.string.status_disconnected_body,
        action = R.string.status_disconnected_action,
        level = StatusLevel.NEUTRAL
    )
    LinkPhase.UNSUPPORTED -> LinkUiMessage(
        R.string.status_unsupported_title,
        R.string.status_unsupported_body,
        action = R.string.status_unsupported_action,
        level = StatusLevel.ERROR
    )
}

@StringRes
fun failureMessage(failure: LinkFailure): Int = when (failure) {
    LinkFailure.NONE -> R.string.diag_none
    LinkFailure.NO_HOST -> R.string.failure_no_host
    LinkFailure.BAD_ADDRESS -> R.string.failure_bad_address
    LinkFailure.CONNECTION_REFUSED -> R.string.failure_refused
    LinkFailure.TIMEOUT -> R.string.failure_timeout
    LinkFailure.HOST_UNREACHABLE -> R.string.failure_unreachable
    LinkFailure.HANDSHAKE_REJECTED -> R.string.failure_handshake
    LinkFailure.PROTOCOL_MISMATCH -> R.string.failure_protocol
    LinkFailure.SERVER_CLOSED -> R.string.failure_closed
    LinkFailure.IO_ERROR -> R.string.failure_io
    LinkFailure.CAMERA_UNAVAILABLE -> R.string.failure_camera
    LinkFailure.SENSOR_UNAVAILABLE -> R.string.failure_sensors
    LinkFailure.UNKNOWN -> R.string.failure_unknown
}

/** True when the user action for this phase should start a connection. */
fun LinkPhase.actionStartsConnection(): Boolean =
    this == LinkPhase.NOT_CONFIGURED || this == LinkPhase.READY || this == LinkPhase.DISCONNECTED
