# VIO Status

## On-device status
**Not integrated.** No on-device visual-inertial odometry pipeline runs. A genuine VIO pipeline
needs feature tracking, IMU preintegration, and joint optimization — typically built on OpenCV —
which is not bundled. The bundled `NoOpVisualOdometry` reports `NOT_INTEGRATED` and produces
**no trajectory**; nothing is fabricated.

This follows the project's explicit rule: *"If the complete VIO pipeline cannot initially run on
Android, first deliver reliable synchronized recording, calibration, and export; then connect the
collected sessions to the PC-side pipeline through an optional, documented bridge."*

## The PC-side bridge
Export a session to the EuRoC-style format (`Sessions` or `Mission` tab → "Export EuRoC-style"),
then run `mav0` through the Nevcortex PC pipeline or a supported open tool. Per-tool instructions
are generated in-app (`VIO / Trajectory` tab) and below.

### Before running (these must hold)
1. Camera/IMU synchronization is **not** verified on-device. If your tool assumes one clock, apply
   the measured offset from `session.json` (`cameraImuOffsetNs`).
2. Provide real camera intrinsics in `mav0/cam0/sensor.yaml` (P.R.I.S.M. writes zeros + a note when
   none were entered).
3. Provide characterized IMU noise in `mav0/imu0/sensor.yaml` (zeros are placeholders).
4. Set `T_BS` to the real camera→IMU extrinsic if known; otherwise leave identity and treat the
   trajectory as approximate.

### Tools
- **Nevcortex PC-side VIO pipeline** — `nevcortex_vio --dataset <export>/mav0 --config <config.yaml>`
- **OKVIS** — `okvis_node_okvis <config.yaml> <export>/mav0`
- **VINS-Mono / VINS-Fusion** — bag the `mav0` and run the estimator.
- **ORB-SLAM3** — `Examples/Monocular-Inertial/mono_inertial_euroc <vocab> <settings> <export>/mav0`
- **Kimera-VIO** — `kimera_vio_ros_runner --dataset_path <export>/mav0 --params <params>`

## Visual-only vs visual-inertial comparison
Run **both** modes on the **same** exported sequence (e.g. ORB-SLAM3 monocular vs
monocular-inertial). Report drift **only** against a defined reference (ground truth or a known
loop). With no reference, report the metric as **UNAVAILABLE** and provide qualitative
diagnostics (tracking loss, sensor gaps, relocalization) instead. Raw double-integrated
accelerometer readings are **not** valid position; do not present them as such.

## Enabling on-device VIO later
The `VisualOdometryBaseline` interface and `VioStatus`/`TrajectoryEstimate` types are the seam.
Integrate an OpenCV-based VO/VIO library behind `VisualOdometryBaseline`, feed it the same
timestamped frames + IMU streams the recorder already produces, and surface results via
`TrajectoryEstimate` (with `hasScale` only true when a scale source justifies it). Perception,
mapping, and evidence modules then consume poses/results through their defined interfaces.
