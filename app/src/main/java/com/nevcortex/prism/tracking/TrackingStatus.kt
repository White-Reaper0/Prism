package com.nevcortex.prism.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TrackingAvailability {
    UNKNOWN,
    CHECKING,
    SUPPORTED,
    SUPPORTED_NOT_INSTALLED,
    UNSUPPORTED,
    ERROR
}

enum class TrackingPhase {
    NOT_STARTED,
    CHECKING,
    INITIALIZING,
    TRACKING,
    PAUSED,
    LOST,
    STOPPED,
    UNSUPPORTED,
    ERROR
}

enum class TrackingFailure {
    BAD_STATE,
    INSUFFICIENT_LIGHT,
    EXCESSIVE_MOTION,
    INSUFFICIENT_FEATURES,
    CAMERA_UNAVAILABLE,
    UNKNOWN
}

/** Pose reported by a provider. Translation is in metres in that provider's local frame. */
data class TrackingPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float
)

/**
 * Honest tracking state. ARCore supplies a *local, relative* pose: metric units within its own
 * session frame, but no global reference, no surveyed origin, and no GPS. This object never
 * converts that into a geographic position or claims a validated accuracy.
 */
data class TrackingSnapshot(
    val phase: TrackingPhase = TrackingPhase.NOT_STARTED,
    val availability: TrackingAvailability = TrackingAvailability.UNKNOWN,
    val running: Boolean = false,
    val provider: String = "ARCore",
    val failure: TrackingFailure? = null,
    val failureDetail: String? = null,
    val pose: TrackingPose? = null,
    val poseTimestampNs: Long = 0L,
    val timestampGapCount: Long = 0L,
    val lastTimestampGapMs: Long = 0L,
    val updatedAtElapsedMs: Long = 0L,
    val sessionStartedAtElapsedMs: Long = 0L,
    val note: String? = null
)

class TrackingRepository {
    private val _state = MutableStateFlow(TrackingSnapshot())
    val state: StateFlow<TrackingSnapshot> = _state.asStateFlow()

    fun set(snapshot: TrackingSnapshot) {
        _state.value = snapshot
    }

    fun update(transform: (TrackingSnapshot) -> TrackingSnapshot) {
        _state.update(transform)
    }
}
