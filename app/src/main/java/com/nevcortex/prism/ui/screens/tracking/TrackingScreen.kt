package com.nevcortex.prism.ui.screens.tracking

import android.app.Activity
import android.view.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.tracking.TrackingAvailability
import com.nevcortex.prism.tracking.TrackingPhase
import com.nevcortex.prism.ui.components.ExpandableSection
import com.nevcortex.prism.ui.components.InlineNotice
import com.nevcortex.prism.ui.components.MetricRow
import com.nevcortex.prism.ui.components.Section
import com.nevcortex.prism.ui.components.StatusBanner
import com.nevcortex.prism.ui.components.StatusLevel
import com.nevcortex.prism.ui.format.Formats
import com.nevcortex.prism.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
fun TrackingScreen(container: AppContainer) {
    val context = LocalContext.current
    val snapshot by container.trackingRepository.state.collectAsState()
    var availability by remember { mutableStateOf(container.arCoreTracking.availability()) }

    LaunchedEffect(Unit) {
        // ARCore availability can be transient immediately after install; re-check a few times.
        repeat(3) {
            if (availability == TrackingAvailability.CHECKING) {
                delay(700L)
                availability = container.arCoreTracking.availability()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { container.arCoreTracking.stop() }
    }

    fun start() {
        val display = context.display
        val rotationDegrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val metrics = context.resources.displayMetrics
        container.arCoreTracking.start(
            rotationDegrees,
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )
    }

    val running = snapshot.running
    val phase = snapshot.phase

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            stringResource(R.string.tracking_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (availability) {
            TrackingAvailability.SUPPORTED -> {
                if (!running && phase == TrackingPhase.NOT_STARTED) {
                    StatusBanner(
                        level = StatusLevel.INFO,
                        title = stringResource(R.string.tracking_supported),
                        message = stringResource(R.string.tracking_intro)
                    )
                }
            }
            TrackingAvailability.SUPPORTED_NOT_INSTALLED -> {
                StatusBanner(
                    level = StatusLevel.WARNING,
                    title = stringResource(R.string.tracking_not_installed),
                    message = stringResource(R.string.tracking_install_body),
                    actionLabel = stringResource(R.string.tracking_install),
                    onAction = {
                        val activity = context as? Activity
                        if (activity != null) {
                            container.arCoreTracking.let {
                                com.nevcortex.prism.tracking.ArCoreTrackingEngine.requestInstall(activity)
                            }
                        }
                        availability = container.arCoreTracking.availability()
                    }
                )
            }
            TrackingAvailability.UNSUPPORTED -> {
                StatusBanner(
                    level = StatusLevel.ERROR,
                    title = stringResource(R.string.tracking_unsupported),
                    message = stringResource(R.string.tracking_unsupported_body)
                )
            }
            TrackingAvailability.ERROR -> {
                StatusBanner(
                    level = StatusLevel.ERROR,
                    title = stringResource(R.string.tracking_error),
                    message = stringResource(R.string.tracking_unsupported_body),
                    actionLabel = stringResource(R.string.tracking_retry_check),
                    onAction = { availability = container.arCoreTracking.availability() }
                )
            }
            else -> {
                StatusBanner(
                    level = StatusLevel.INFO,
                    title = stringResource(R.string.tracking_checking),
                    message = stringResource(R.string.tracking_intro)
                )
            }
        }

        // Live phase. Only one banner is shown so the current state stays unambiguous.
        when (phase) {
            TrackingPhase.TRACKING -> StatusBanner(
                level = StatusLevel.GOOD,
                title = stringResource(R.string.tracking_phase_tracking),
                message = stringResource(R.string.tracking_next_tracking)
            )
            TrackingPhase.INITIALIZING, TrackingPhase.CHECKING -> StatusBanner(
                level = StatusLevel.INFO,
                title = stringResource(R.string.tracking_phase_initializing),
                message = stringResource(R.string.tracking_next_initializing)
            )
            TrackingPhase.LOST, TrackingPhase.PAUSED -> StatusBanner(
                level = StatusLevel.WARNING,
                title = stringResource(R.string.tracking_phase_lost),
                message = snapshot.failureDetail ?: stringResource(R.string.tracking_next_lost)
            )
            TrackingPhase.ERROR -> StatusBanner(
                level = StatusLevel.ERROR,
                title = stringResource(R.string.tracking_phase_error),
                message = snapshot.failureDetail ?: stringResource(R.string.tracking_no_pose)
            )
            TrackingPhase.UNSUPPORTED -> StatusBanner(
                level = StatusLevel.ERROR,
                title = stringResource(R.string.tracking_phase_unsupported),
                message = snapshot.failureDetail ?: stringResource(R.string.tracking_unsupported_body)
            )
            TrackingPhase.STOPPED -> StatusBanner(
                level = StatusLevel.NEUTRAL,
                title = stringResource(R.string.tracking_phase_stopped),
                message = stringResource(R.string.tracking_next_stopped)
            )
            TrackingPhase.NOT_STARTED -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (!running) {
                Button(
                    onClick = { start() },
                    enabled = availability == TrackingAvailability.SUPPORTED,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.tracking_start))
                }
            } else {
                Button(
                    onClick = { container.arCoreTracking.stop() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.tracking_stop))
                }
            }
            if (availability == TrackingAvailability.SUPPORTED_NOT_INSTALLED ||
                availability == TrackingAvailability.ERROR
            ) {
                OutlinedButton(
                    onClick = { availability = container.arCoreTracking.availability() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.tracking_retry_check))
                }
            }
        }

        if (running) {
            InlineNotice(stringResource(R.string.tracking_camera_note), StatusLevel.INFO)
        }

        val pose = snapshot.pose
        if (pose != null && phase == TrackingPhase.TRACKING) {
            Section(stringResource(R.string.tracking_pose_title)) {
                Text(
                    stringResource(R.string.tracking_pose_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                MetricRow(
                    stringResource(R.string.tracking_position),
                    stringResource(
                        R.string.tracking_position_value,
                        pose.x,
                        pose.y,
                        pose.z
                    ),
                    mono = false
                )
                MetricRow(
                    stringResource(R.string.tracking_orientation),
                    stringResource(
                        R.string.tracking_orientation_value,
                        pose.yawDeg,
                        pose.pitchDeg,
                        pose.rollDeg
                    ),
                    mono = false
                )
                Spacer(Modifier.height(Spacing.sm))
                InlineNotice(stringResource(R.string.tracking_accuracy_note), StatusLevel.WARNING)
            }
        } else if (running) {
            Section(stringResource(R.string.tracking_pose_title)) {
                Text(
                    stringResource(R.string.tracking_no_pose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ExpandableSection(stringResource(R.string.tracking_technical_title)) {
            MetricRow(
                stringResource(R.string.tracking_provider),
                snapshot.provider,
                mono = false
            )
            MetricRow(
                stringResource(R.string.tracking_timestamp),
                stringResource(R.string.tracking_timestamp_value, snapshot.poseTimestampNs)
            )
            MetricRow(
                stringResource(R.string.tracking_continuity),
                stringResource(
                    R.string.tracking_continuity_value,
                    snapshot.timestampGapCount,
                    snapshot.lastTimestampGapMs
                )
            )
            pose?.let {
                MetricRow(
                    stringResource(R.string.tracking_quaternion),
                    "%.3f, %.3f, %.3f, %.3f".format(it.qx, it.qy, it.qz, it.qw)
                )
            }
            MetricRow(
                stringResource(R.string.tracking_pose_age),
                if (snapshot.updatedAtElapsedMs > 0L) {
                    Formats.ago(snapshot.updatedAtElapsedMs)
                } else stringResource(R.string.value_none),
                mono = false
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.tracking_coordinate_frame_value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}
