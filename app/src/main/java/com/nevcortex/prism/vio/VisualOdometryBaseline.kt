package com.nevcortex.prism.vio

/**
 * Contract for a visual-only odometry baseline (for comparison against a full visual-inertial
 * pipeline). The bundled implementation is [NoOpVisualOdometry], which is honest: it reports that
 * no on-device VO is integrated and produces no trajectory.
 *
 * Shipping a real VO here requires a vision library (feature detection, matching, essential
 * matrix, PnP) — typically OpenCV. That integration is intentionally NOT faked. The real path is:
 * export the EuRoC-style dataset (see [com.nevcortex.prism.dataset.FormatConverters]) and run it
 * through the PC-side pipeline (see [PcBridge]).
 */
interface VisualOdometryBaseline {
    val name: String
    fun status(): VioStatus
    /** @return [TrajectoryEstimate.NONE] if no pipeline ran; never a fabricated trajectory. */
    fun process(sessionDir: String): TrajectoryEstimate
}

/** Honest no-op baseline. Reports NOT_INTEGRATED and returns no trajectory. */
class NoOpVisualOdometry : VisualOdometryBaseline {
    override val name: String = "No on-device VO (export to PC pipeline)"
    override fun status(): VioStatus = VioStatus.NOT_INTEGRATED
    override fun process(sessionDir: String): TrajectoryEstimate = TrajectoryEstimate.NONE
}
