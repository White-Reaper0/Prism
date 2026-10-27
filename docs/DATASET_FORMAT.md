# Dataset Format

Sessions live under `sessions/<id>/` in the app's private files (`/data/data/com.nevcortex.prism.debug/files/sessions/`).
Raw data is **immutable**; exports are derived copies under `export/`.

## Layout
```
sessions/<id>/
  session.json            metadata: config, sync diagnostics, validation notes, file list
  README.txt              human-readable summary
  imu/
    accelerometer.csv     raw accelerometer
    gyroscope.csv         raw gyroscope
    magnetometer.csv     raw magnetometer (if present)
    gravity.csv           Android fusion (gravity)
    linear_acceleration.csv  Android fusion (linear acceleration)
    rotation_vector.csv   Android fusion (rotation vector)
  frames.csv              frame-timestamp manifest (camera + arrival clocks)
  video.mp4               encoded video (VIDEO mode only)
  frames/                 still frames (FRAMES mode only — not yet wired)
  export/
    portable/             portable export (imu.csv, frames.csv, session.json, README.txt)
    portable.zip         zipped copy of the above
    euroc/mav0/           EuRoC-style dataset for VIO/SLAM tools
  calibration_ref.json     reference to the active calibration at record time
```

## IMU CSV (per kind)
Two comment header lines document units/axes/frame, then a data header:
```
sequence,sensor_timestamp_ns,arrival_elapsed_ns,accuracy,x,y,z[,w]
```
- `sensor_timestamp_ns`: the source sensor clock (`SensorEvent.timestamp`, ns).
- `arrival_elapsed_ns`: `SystemClock.elapsedRealtimeNanos()` when the event arrived. The
  difference `arrival - sensor` is the clock-domain diagnostic (see TIMESTAMP_CONVENTIONS.md).
- `accuracy`: `SensorEvent.accuracy` (0 unreliable … 3 high) or -1.
- `x,y,z[,w]`: **raw, never filtered**. Units: accelerometer `m/s²`, gyroscope `rad/s`,
  magnetometer `µT`, gravity/linear-accel `m/s²`, rotation-vector unitless quaternion.
- The `w` column appears only for rotation-vector kinds.

Derived/filtered values (aligned timestamps, bias-corrected streams) are always written as
separate fields or files, never overwriting these raw columns.

## frames.csv
```
frame_index,camera_timestamp_ns,arrival_elapsed_ns,width,height,rotation_deg,source,clock_offset_ns
```
- `camera_timestamp_ns`: the **camera HAL clock** (`ImageProxy.imageInfo.timestamp`, ns). It is
  **not** assumed to share an origin with the IMU clock.
- `arrival_elapsed_ns`: alignment clock at the analysis-callback entry.
- `clock_offset_ns = arrival - camera`.
- `source`: `ANALYSIS` (analysis-callback arrival; a faithful proxy for frame timing, **not** the
  per-encoded-frame timestamp). For exact per-frame timestamps in VIDEO mode, extract them from
  the MP4 offline.

## session.json
Contains `schema`, `id`, `label`, `createdAtMs`, `durationNs`, `config` (capture mode, requested
rates, lens, stabilization, mounting, record flags), `sync` (frame/IMU counts, camera fps, camera/IMU
offset estimate + jitter, dropped frames, `synchronizationVerified` = **false** until a controlled
test confirms it, `verificationNote`), and file lists.

## EuRoC-style export (`export/euroc/mav0/`)
```
cam0/timestamps.txt     one camera frame timestamp (ns) per line
imu0/data.csv           ns,gx,gy,gz,ax,ay,az   (rad/s, m/s²)
cam0/sensor.yaml        intrinsics (zeros + note if not calibrated)
imu0/sensor.yaml        IMU noise = 0 + "UNKNOWN" note
CONVERSION_NOTES.txt    structural-only caveats
```
This is a **structural** converter: timestamps and values are copied faithfully; it does **not**
verify synchronization or invent calibration. Replace zeros with real calibration/noise before
running metric VIO.

## Raw data immutability
The recorder only appends to the raw CSVs and writes `session.json` once at stop. Exports never
modify the raw files. Processing provenance is attached to derived results.
