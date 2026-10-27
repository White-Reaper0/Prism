package com.nevcortex.prism.model

import com.nevcortex.prism.core.coords.MountingConfig

/** What gets captured during a session. */
data class SessionConfig(
    val id: String,                         // UUID
    val label: String,
    val createdAtMs: Long,
    val captureImu: Boolean = true,
    val captureCamera: Boolean = true,
    val captureMode: CameraCaptureMode = CameraCaptureMode.VIDEO,
    /** Target analysis fps for the frame-timestamp manifest (best-effort; not guaranteed). */
    val targetAnalysisFps: Float = 20f,
    val recordAccelerometer: Boolean = true,
    val recordGyroscope: Boolean = true,
    val recordMagnetometer: Boolean = true,
    val recordRotationVector: Boolean = true,
    val recordGameRotationVector: Boolean = false,
    val recordGravity: Boolean = true,
    val recordLinearAcceleration: Boolean = true,
    /** Requested IMU rate in Hz; the achieved rate is always measured. */
    val imuRateHz: Int = 400,
    val cameraLensFacing: Int = 0,          // CameraSelector.LENS_FACING_BACK
    val enableGpsGroundTruth: Boolean = false,
    val mounting: MountingConfig = MountingConfig.IDENTITY,
    val stabilization: CameraStabilization = CameraStabilization.OFF
)

enum class CameraCaptureMode { VIDEO, FRAMES }

enum class CameraStabilization(val label: String) {
    OFF("Off — raw sensor capture (preferred for research)"),
    PREVIEW("Preview stabilization only (does not affect recording)"),
    VIDEO("Video stabilization (changes pixel correspondence — documented)")
}

/** Persisted summary of a saved session, written next to the raw data. */
data class SessionSummary(
    val id: String,
    val label: String,
    val createdAtMs: Long,
    val durationNs: Long,
    val config: SessionConfig,
    val sync: SyncStats,
    val sessionDir: String,                 // absolute path
    val videoFile: String?,                 // relative to session dir
    val frameManifestFile: String?,          // frames.csv path relative to session dir
    val imuFiles: List<String>,              // per-kind csv paths relative to session dir
    val metadataFile: String,                // session.json
    val validated: Boolean,
    val validationNotes: List<String>
)
