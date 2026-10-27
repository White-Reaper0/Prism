package com.nevcortex.prism.vio

/**
 * The documented bridge between P.R.I.S.M. Mobile sessions and the PC-side Nevcortex VIO pipeline.
 *
 * This object does NOT execute anything. It builds the instructions/commands the user runs on the
 * PC against an exported EuRoC-style dataset, and records which assumptions must hold. Nothing
 * here hides the fact that on-device VIO is not integrated.
 */
object PcBridge {

    /** Tools the bridge documents how to invoke. Names are placeholders for the user's pipeline. */
    enum class Tool(val label: String) {
        NEVCORTEX_VIO("Nevcortex PC-side VIO pipeline (project-internal)"),
        OKVIS("OKVIS (keyframe-based VIO)"),
        VINS_MONO("VINS-Mono / VINS-Fusion"),
        ORB_SLAM3("ORB-SLAM3 (visual + visual-inertial)"),
        KIMERA("Kimera-VIO")
    }

    /**
     * Build the instructions to run an exported EuRoC-style dataset through [tool]. The returned
     * text is shown in the VIO screen and exported into VIO_STATUS.md.
     */
    fun instructions(tool: Tool, exportPath: String): String = buildString {
        appendLine("PC-side VIO bridge — ${tool.label}")
        appendLine("Export path: $exportPath")
        appendLine()
        appendLine("Before running:")
        appendLine("  1. Confirm camera/IMU timestamps: synchronization is NOT verified on-device.")
        appendLine("     If your tool assumes one clock, apply the measured offset from session.json.")
        appendLine("  2. Provide real camera intrinsics in mav0/cam0/sensor.yaml. P.R.I.S.M. writes")
        appendLine("     zeros + a note when no calibration was entered; replace with calibrated values.")
        appendLine("  3. Provide characterized IMU noise in mav0/imu0/sensor.yaml (zeros are placeholders).")
        appendLine("  4. Set T_BS to the real camera-to-IMU extrinsic if known; otherwise leave identity")
        appendLine("     and treat the resulting trajectory as approximate.")
        appendLine()
        when (tool) {
            Tool.NEVCORTEX_VIO -> appendLine("  $ nevcortex_vio --dataset <export>/mav0 --config <config.yaml>")
            Tool.OKVIS -> appendLine("  $ okvis_node_okvis <config.yaml> <export>/mav0")
            Tool.VINS_MONO -> appendLine("  $ rosrun vins vins_estimator <vins_config.yaml>  # bag the mav0")
            Tool.ORB_SLAM3 -> appendLine("  $ Examples/Monocular-Inertial/mono_inertial_euroc <vocab> <settings> <export>/mav0")
            Tool.KIMERA -> appendLine("  $ kimera_vio_ros_runner --dataset_path <export>/mav0 --params <params>")
        }
        appendLine()
        appendLine("Evaluation:")
        appendLine("  - Compare visual-only vs visual-inertial on the SAME exported sequence.")
        appendLine("  - Report drift only against a defined reference (ground truth or a known loop).")
        appendLine("    If no reference exists, report the metric as UNAVAILABLE and use qualitative")
        appendLine("    diagnostics (tracking loss, sensor gaps, relocalization) instead.")
    }
}
