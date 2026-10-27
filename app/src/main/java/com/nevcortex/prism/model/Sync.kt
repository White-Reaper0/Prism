package com.nevcortex.prism.model

/**
 * Synchronization diagnostics for one session. Every claim here is derived from measured
 * timestamps; nothing is assumed. When the field `synchronizationVerified` is false, downstream
 * consumers must NOT treat camera and IMU as synchronized.
 */
data class SyncStats(
    val cameraFrameCount: Long,
    val imuSampleCount: Long,
    val cameraFirstNs: Long,
    val cameraLastNs: Long,
    val imuFirstNs: Long,
    val imuLastNs: Long,
    /** Estimated offset between camera clock and IMU clock in ns (camera - imu). May be unstable. */
    val cameraImuOffsetNs: Long,
    val cameraImuOffsetJitterNs: Long,
    val cameraIntervalMedianNs: Long,
    val cameraIntervalStdNs: Long,
    val cameraAchievedFps: Float,
    val droppedFrames: Int,
    val imuRateByKind: Map<SensorKind, Float>,
    val maxImuGapNs: Long,
    /** True only if a controlled test confirmed both clocks share an origin. Defaults false. */
    val synchronizationVerified: Boolean,
    val verificationNote: String
)
