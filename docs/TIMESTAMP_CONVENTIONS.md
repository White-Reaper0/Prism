# Timestamp Conventions

P.R.I.S.M. never assumes camera and IMU timestamps share a clock. Every measurement carries its
**source clock** value and an **alignment-clock arrival time**, so the offset between them is
*measured*, not *assumed*.

## Clocks in play
| Clock | Source | Domain |
|---|---|---|
| `sensor_timestamp_ns` | `SensorEvent.timestamp` | Android boot-since monotonic (excludes deep sleep), same timebase as `SystemClock.elapsedRealtimeNanos` on API 19+ |
| `camera_timestamp_ns` | `ImageProxy.imageInfo.timestamp` | Camera HAL clock — **not guaranteed** to share an origin with the sensor clock |
| `arrival_elapsed_ns` | `SystemClock.elapsedRealtimeNanos()` | Alignment clock (boot-since, monotonic) |
| wall clock | `System.currentTimeMillis()` | Metadata only, never used for alignment |

## The diagnostic
For each sample we store `clock_offset = arrival_elapsed_ns - source_timestamp_ns`.
- If the source clock **is** the alignment clock, the offset is small and stable.
- A **large or drifting** offset means the source clock has a different origin — synchronization
  must **not** be claimed for it.
- `SyncStats.cameraImuOffsetNs` is the estimated camera−IMU offset from the per-sample offsets.
  It is an **estimate**; `cameraImuOffsetJitterNs` shows its stability.

## What "synchronization verified" means
`synchronizationVerified` defaults to **false**. It becomes true only after a **controlled
external test** confirms the camera and IMU clocks share an origin (e.g., a synchronized visual +
inertial event, or a hardware trigger). The app measures offsets but does **not** perform that
test automatically — claiming it without the test would violate the project's honesty rules.

## Alignment (non-destructive)
`TimeAlignment.toAligned(sourceNs, offsetNs) = sourceNs + offset` expresses a source timestamp in
the alignment clock using a measured offset. This produces a **derived** value; the raw source
timestamp is always kept. Downstream tools that require a single clock should apply the measured
offset and label the result as aligned/estimated.

## Do-not list (enforced in code)
- Never timestamp a buffered sample only when it is processed — source timestamps are captured at
  the sensor/callback boundary.
- Never present raw double-integrated accelerometer readings as accurate position.
- Never label a trajectory as metrically accurate without a valid scale source + evaluation.
- Never use GPS to silently rescue localization. GPS may be recorded as optional ground truth when
  explicitly enabled; the GPS-denied evaluation must remain possible.
