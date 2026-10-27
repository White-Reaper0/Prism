# Calibration Workflow

Calibration results are always tagged with **provenance** (`ESTIMATED`, `FACTORY`,
`USER_PROVIDED`, `UNKNOWN`). An estimate is never silently promoted to a verified calibration.

## IMU calibration

### Stationary session (Settings/Calibration → "Stationary (4s)")
- **Gyroscope bias** = mean of the gyroscope while still. Estimable from one window if the device
  was truly still.
- **Gravity magnitude** `|g|` = norm of the accelerometer mean.
- **Noise** = per-axis std during stillness.
- **Accelerometer bias is NOT estimated** from a single orientation: the measured mean equals
  `bias + gravity-in-device-frame`, which are inseparable. The result records accel bias as `NaN`
  with that note.

### Guided multi-orientation ("Add orientation" then "Finish multi-orient", ≥2 windows)
- Hold the device still in several distinct orientations, adding a 3 s window each.
- **Accelerometer bias** = average of per-window means. This is valid **only for symmetric
  orientation coverage**; the method string and uncertainty note record that assumption and the
  window count. Non-symmetric coverage biases the result.
- Gyro bias = mean of still-window means; noise is pooled across windows.
- Magnetometer bias is `NaN` (full hard/soft-iron calibration needs more data).

### What is NOT characterized (honest limits)
Scale factors, cross-axis sensitivity, nonlinearity, temperature dependence, and long-term drift
are **not modeled**. The uncertainty note says so explicitly. A short stationary sample does not
constitute a "complete" calibration.

## Camera intrinsic calibration
On-device nonlinear intrinsic estimation needs a chessboard/ChArUco detector + bundle adjustment
(OpenCV/Kalibr). Rather than ship a fragile hand-rolled solver, P.R.I.S.M. supports:
1. **Target capture session** — creates a target directory for offline estimation (still-frame
   capture via `ImageCapture` is **not yet wired**; record a VIDEO session and extract frames
   offline, or wire FRAMES mode in a future iteration).
2. **Manual entry** — enter `fx, fy, cx, cy` and image size from an offline calibration; saved with
   provenance `USER_PROVIDED`. Unknown values stay `NaN` and are flagged in the UI.

Until real intrinsics are entered, the EuRoC-style export writes **zeros + a note** so downstream
tools fail loudly rather than silently using wrong numbers.

## Coordinate frames & mounting
- **ANDROID_DEVICE** — raw sensor/camera axes (portrait: X right, Y up, Z out of screen).
- **CAMERA** — camera optical frame (Z forward, X right, Y down).
- **BODY** — user-configurable experiment/vehicle frame, via the `MountingConfig` 3×3 rotation in
  Settings/Calibration.
- **WORLD** — local navigation frame defined by the selected VIO implementation, not assumed here.

`cameraToDevice` and `MountingConfig.rotation` are **defaults/guesses** until verified for your
rig. The UI warns that rolling shutter, optical stabilization, and loose mounting affect results,
and that handheld phone motion is not drone motion.
