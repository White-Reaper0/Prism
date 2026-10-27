package com.nevcortex.prism.vio

/** State of the on-device visual-inertial localization pipeline. */
enum class VioStatus {
    /** No on-device VIO library is bundled. Sessions are exported and run on the PC pipeline. */
    NOT_INTEGRATED,
    /** A visual-only baseline exists but produced no metric scale (no depth/stereo/scale source). */
    VISUAL_ONLY_NO_SCALE,
    /** A full visual-inertial pipeline is wired and running. */
    INTEGRATED,
    /** The pipeline lost tracking; output should not be trusted. */
    LOST
}

/**
 * Honest result descriptor. A trajectory is only reported when [valid] is true, and only labeled
 * metrically accurate when [hasScale] is true AND a scale source/ground truth justified it. We
 * never populate this with fabricated data.
 */
data class TrajectoryEstimate(
    val valid: Boolean,
    val hasScale: Boolean,
    val scaleNote: String,
    val status: VioStatus,
    val trackingQuality: Float,   // [0..1], tool-reported; -1 if unavailable
    val pointCount: Int,
    val uncertaintyNote: String
) {
    companion object {
        val NONE = TrajectoryEstimate(
            valid = false,
            hasScale = false,
            scaleNote = "No trajectory produced.",
            status = VioStatus.NOT_INTEGRATED,
            trackingQuality = -1f,
            pointCount = 0,
            uncertaintyNote = "No on-device VIO pipeline is integrated. Export the session and " +
                "run it through the PC-side pipeline (see VIO_STATUS.md)."
        )
    }
}
