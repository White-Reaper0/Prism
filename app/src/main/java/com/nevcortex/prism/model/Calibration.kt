package com.nevcortex.prism.model

/**
 * Calibration results. We distinguish ESTIMATED (computed from collected data in this app) from
 * FACTORY (vendor-reported specs). Nothing here is trusted blindly.
 */
enum class CalibrationProvenance { ESTIMATED, FACTORY, USER_PROVIDED, UNKNOWN }

data class ImuCalibration(
    val createdAtMs: Long,
    val provenance: CalibrationProvenance,
    val method: String,
    val durationNs: Long,
    val orientationCount: Int,               // guided multi-orientation coverage
    val accelerometerBiasMs2: FloatArray,    // m/s^2, or NaN if not estimable
    val accelerometerNoiseStdMs2: FloatArray,// per-axis std during stillness
    val gyroscopeBiasRadS: FloatArray,       // rad/s, or NaN
    val gyroscopeNoiseStdRadS: FloatArray,
    val magnetometerBiasUt: FloatArray,      // µT, or NaN
    val gravityMagnitudeMs2: Float,          // observed |g| during stillness
    val gravityMagnitudeStdMs2: Float,
    val driftNote: String,                   // qualitative drift observation
    val uncertaintyNote: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuCalibration) return false
        return createdAtMs == other.createdAtMs && provenance == other.provenance &&
            accelerometerBiasMs2.contentEquals(other.accelerometerBiasMs2) &&
            gyroscopeBiasRadS.contentEquals(other.gyroscopeBiasRadS) &&
            magnetometerBiasUt.contentEquals(other.magnetometerBiasUt)
    }

    override fun hashCode(): Int {
        var h = createdAtMs.hashCode()
        h = 31 * h + provenance.hashCode()
        h = 31 * h + accelerometerBiasMs2.contentHashCode()
        h = 31 * h + gyroscopeBiasRadS.contentHashCode()
        h = 31 * h + magnetometerBiasUt.contentHashCode()
        return h
    }
}

data class CameraIntrinsics(
    val createdAtMs: Long,
    val provenance: CalibrationProvenance,
    val method: String,
    val imageWidth: Int,
    val imageHeight: Int,
    /** fx, fy, cx, cy in pixels. NaN if not yet calibrated. */
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val distortionCoeffs: FloatArray,        // k1,k2,p1,p2,k3... NaN if unknown
    val reprojectionErrorPx: Float,         // -1 if not computed
    val uncertaintyNote: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraIntrinsics) return false
        return imageWidth == other.imageWidth && imageHeight == other.imageHeight &&
            fx == other.fx && fy == other.fy && cx == other.cx && cy == other.cy &&
            distortionCoeffs.contentEquals(other.distortionCoeffs)
    }

    override fun hashCode(): Int {
        var h = imageWidth.hashCode()
        h = 31 * h + imageHeight.hashCode()
        h = 31 * h + fx.hashCode()
        h = 31 * h + fy.hashCode()
        h = 31 * h + cx.hashCode()
        h = 31 * h + cy.hashCode()
        h = 31 * h + distortionCoeffs.contentHashCode()
        return h
    }
}

/** Full calibration bundle attached to a session. */
data class CalibrationBundle(
    val imu: ImuCalibration?,
    val camera: CameraIntrinsics?,
    val mountingNote: String
)
