<div align="center">

# P · R · I · S · M

### **Perception · Reasoning · Inertial · Sensor · Mapping**

*A mobile visual-inertial research platform for GPS-denied autonomy — by [Nevcortex](https://github.com/nevcortex)*

[![Platform](https://img.shields.io/badge/platform-Android%2011%2B-brightgreen?style=flat-square&logo=android)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-30%2B%20%28minSdk%29-blue?style=flat-square)](https://developer.android.com/about/versions/11)
[![compileSdk](https://img.shields.io/badge/compileSdk-36-blue?style=flat-square)](https://developer.android.com/about/versions/16)
[![Build](https://img.shields.io/badge/build-passing-brightgreen?style=flat-square)](#build)
[![Tests](https://img.shields.io/badge/tests-49%20JVM%20%7C%2013%20Python-brightgreen?style=flat-square)](#tests)
[![License](https://img.shields.io/badge/license-PolyForm%20Noncommercial-orange?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/version-3.0.1-informational?style=flat-square)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12-purple?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)

</div>

---

> **PRISM turns an Android phone into a precision sensor collection and streaming instrument for visual-inertial navigation research.** It streams live JPEG frames and raw IMU samples to a PC over Wi-Fi, records synchronized datasets in EuRoC format, and gives you honest, measured diagnostics — not estimated ones.

```
phone  ──(Wi-Fi TCP 47777)──►  PC receiver  ──►  VIO / SLAM pipeline
         JPEG + IMU JSONL               imu.jsonl + frames/
```

---

## Why PRISM?

Most phone-based IMU tools either fabricate synchronization claims or hide the clock math. PRISM does neither:

- Every IMU sample carries **two independent timestamps** — the hardware sensor clock (`SensorEvent.timestamp`) and the arrival wall-clock — so you can measure the offset yourself instead of trusting it.
- Calibration results are tagged with **provenance** (`ESTIMATED` / `USER_PROVIDED` / `UNKNOWN`). Nothing gets promoted silently to "verified."
- The protocol is **plain TCP JSON lines** on port 47777. No magic, no proprietary format. A 100-line Python script is all you need to receive it.
- Nothing is labelled "streaming" until frames are actually arriving. Nothing is labelled "localized" because no VIO runs on-device yet — just the honest measurement pipeline that makes VIO possible.

---

## What it does

| Feature | Detail |
|---|---|
| 📡 **Live streaming** | JPEG preview frames + raw accel / gyro / mag over a verified TCP handshake |
| 📐 **IMU diagnostics** | Measured sample rate, interval std, gap detection, clock-domain offset, clipping — all from real `SensorEvent.timestamp` values |
| 🎥 **Synchronized recording** | Foreground camera + IMU sessions with source timestamps preserved in nanoseconds; EuRoC-style export |
| 🔬 **Camera calibration** | Chessboard target capture → offline OpenCV → manual intrinsics entry (fx, fy, cx, cy, distortion) |
| 📊 **IMU calibration** | Stationary gyro bias + noise estimation; guided multi-orientation accelerometer; provenance labelled |
| 🔄 **OTA updates** | PC publishes APK → phone pulls, verifies SHA-256, installs via Android's own installer |
| 🧩 **Extensible** | Clean module boundaries: stream / link / sensors / camera / recording / calibration / vio / perception |

> **What it does not do (yet):** on-device VIO, SLAM, metric localization, or camera-to-IMU extrinsic calibration. Those come next. The architecture is ready for them.

---

## First run in under 5 minutes

**1 — Start the receiver on your PC**

```bash
python tools/prism_receiver.py --out ~/prism-live
```

Optional live preview window needs `pip install pillow`. Disk-only? Add `--no-preview`.

**2 — Connect the phone**

Install the APK, open **Home**, type your PC's IP address, port `47777`, tap **Connect**.

**3 — Data arrives immediately**

```
prism-live/
  imu.jsonl          ← one JSON line per sample, both timestamps per line
  frames/
    frame_00000001.jpg
    frame_00000002.jpg
    ...
```

**4 — Record to the device instead**

**Diagnostics → Recording → Start** — a foreground service keeps capture alive with the screen off. Export as ZIP or EuRoC-style from the Recordings screen.

> **Emulator address:** use `10.0.2.2:47777` instead of a LAN IP.

---

## IMU wire format

Every line in `imu.jsonl` looks like this:

```json
{
  "type": "imu",
  "kind": "gyroscope",
  "t": 670170684235345,
  "a": 670170688330968,
  "seq": 0,
  "accuracy": 3,
  "v": [-0.2362, -0.0503, 0.0509]
}
```

| Field | Meaning |
|---|---|
| `t` | `SensorEvent.timestamp` — hardware sensor clock, nanoseconds (same timebase as `SystemClock.elapsedRealtimeNanos`) |
| `a` | Arrival clock captured at callback entry (`SystemClock.elapsedRealtimeNanos`) |
| `seq` | Monotonic per-stream counter — gaps in this sequence mean dropped samples |
| `accuracy` | Android sensor accuracy 0 (unreliable) → 3 (high) |
| `v` | Raw `SensorEvent.values` — **never filtered, never bias-corrected** at this stage |

The offset `a − t` is the clock-domain diagnostic: a stable ~5 ms offset means the sensor and alignment clocks share a timebase. A drifting offset means they don't, and synchronization must not be assumed.

---

## Measured performance (OnePlus Nord 4 · OxygenOS 16)

From a 35-second live session (`C:\Users\Administrator\prism-live\imu.jsonl`, 17,334 samples):

```
Gyroscope       196.9 Hz  │  interval mean 5080 µs  │  std 174 µs  │  gaps 1
Accelerometer   196.6 Hz  │  interval mean 5087 µs
Magnetometer     100 Hz
Clock offset    mean 4.9 ms  │  std 1.1 ms  ← sensor and arrival clock share domain
Accuracy        3 / 3 (highest) on all 17,334 samples
Sequence gaps   0 missing samples in gyro stream (seq 0 → 6916 complete)
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App                               │
│                                                                  │
│  SensorEventListener (HandlerThread)                             │
│       │  event.timestamp + elapsedRealtime captured together     │
│       ▼                                                          │
│  ImuCollector  ──► plotBuffers (bounded)   ──► IMU Lab UI       │
│       │         └► recordChannel (4096)    ──► SessionRecorder  │
│       │         └► streamListener          ──► LinkClient        │
│                                                    │             │
│  CameraX (Analysis + VideoCapture)                 │             │
│       │  camera HAL timestamp + arrival            │             │
│       ▼                                            │             │
│  FrameCollector ──────────────────────────────────► LinkClient  │
│                                                    │             │
└────────────────────────────────────────────────────┼────────────┘
                                                     │ TCP 47777
                                               PRISM Link v1
                                             (JSON lines + JPEG)
                                                     │
                                                     ▼
                                          tools/prism_receiver.py
                                         imu.jsonl  +  frames/*.jpg
```

**Module breakdown:**

```
app/src/main/java/com/nevcortex/prism/
├── link/           TCP client, protocol wire format, reconnection, status machine
├── stream/         JPEG encoding, frame delivery, stream lifecycle
├── sensors/        ImuCollector, SensorRepository, gap/interval/offset stats
├── camera/         CameraRepository, FrameCollector (dual timestamps)
├── recording/      SessionRecorder, SessionStore, CSV writers
├── calibration/    ImuCalibration, CameraCalibration, CalibrationStore
├── sync/           Clock domain reasoning, time alignment
├── dataset/        EuRoC converter, ZIP exporter
├── playback/       Session player (replay + offline pipeline feed)
├── vio/            VIO status, PC-bridge instructions, NoOp baseline
├── model/          ImuSample, FrameSample, ImuStreamStats, CalibrationBundle
└── ui/             Compose screens: Home, LiveStudio, Diagnostics (9 sub-screens)
```

---

## Build

> **Prerequisites:** JDK 17 or 21 (AGP 8.7 rejects JDK 25), Android SDK with `platforms;android-36`.

```powershell
Set-Location "path\to\prism"
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"  # or your JDK 17/21
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

# Build debug APK (~61 MB)
.\gradlew.bat :app:assembleDebug

# Install on connected phone
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Output: `app/build/outputs/apk/debug/app-debug.apk`  
Debug application ID: `com.nevcortex.prism.debug`

### Tests

```powershell
# JVM unit tests (49 tests — no device required)
.\gradlew.bat :app:testDebugUnitTest

# Python receiver tests (13 tests — loopback sockets, no phone required)
python -m unittest discover -s tools -p "test_*.py" -v
```

The JVM suite pins the wire protocol fields (so the Python receiver never silently breaks), the camera-lens availability rules, and the update-manifest validation. The Python suite drives real TCP loopback: handshake accept/reject, frame exactness on disk, IMU append, and `--no-preview` behaviour.

---

## Cloning and repo structure

```
prism/
├── app/                    Android app module
│   ├── build.gradle.kts
│   ├── prism_calibration/  Camera calibration images + Python script + output
│   └── src/
├── tools/
│   ├── prism_receiver.py   PC receiver — run this to capture frames and IMU
│   ├── prism_publisher.py  APK publisher for OTA updates
│   ├── prism_updater.py    HTTP update server (port 47778)
│   └── test_prism_receiver.py
├── docs/
│   ├── STREAMING_PROTOCOL.md
│   ├── APP_UPDATES.md
│   ├── CALIBRATION.md
│   ├── DATASET_FORMAT.md
│   ├── TIMESTAMP_CONVENTIONS.md
│   └── VIO_STATUS.md
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

**Files not committed (create them after cloning):**

| File | Why missing | Fix |
|---|---|---|
| `local.properties` | Your SDK path | `sdk.dir=C\:\\Android\\Sdk` or let Android Studio create it |
| `keystore.properties` | Signing passwords | Copy `keystore.properties.example` → fill in real values |
| `keystores/prism-dev.keystore` | Private key | Restore from your backup — see [docs/APP_UPDATES.md](docs/APP_UPDATES.md) |

Without the keystore the debug APK still builds, but OTA updates won't work over it.

---

## OTA updates over Wi-Fi

Once PRISM is on the phone, push new builds without a cable:

```powershell
# 1. Build + publish (bumps versionCode, hashes APK, writes manifest)
python tools/prism_publish.py --notes "What changed"

# 2. Serve it (keep open)
python tools/prism_updater.py

# 3. On the phone → Diagnostics → Settings → App updates → Check
```

The phone verifies SHA-256 before touching anything and only offers installation through Android's own confirmation dialog — nothing silent. Full threat model in [docs/APP_UPDATES.md](docs/APP_UPDATES.md).

---

## Camera calibration

Camera intrinsics for the Nord 4 have been calibrated using 69 chessboard images (landscape 1600×715):

```
fx = 1206.01 px   fy = 1206.66 px
cx =  809.68 px   cy =  350.52 px
k1 = 0.434   k2 = -3.364   p1 = -0.00255   p2 = 0.00144   k3 = 9.234
RMS reprojection error: 0.246 px
```

Results live in `app/prism_calibration/output/`. Enter them in **Diagnostics → Settings → Camera intrinsics**.

The calibration script is `app/prism_calibration/calibration.py`. Re-run it on your own images (the script handles filenames with spaces and two-resolution datasets). Physical scale is unverified (target was on a monitor screen) — see [docs/CALIBRATION.md](docs/CALIBRATION.md).

---

## Honest status

| Claim | Verified? |
|---|---|
| Debug APK builds (`app-debug.apk`, ~61 MB) | ✅ JDK 21 + Gradle 8.11.1 |
| JVM unit tests pass (49/49) | ✅ |
| Python receiver tests pass (13/13) | ✅ |
| Live streaming works on Nord 4 | ✅ measured 196 Hz gyro, 35 s session |
| Camera calibration RMS < 0.3 px | ✅ 0.246 px on 69 images |
| Camera/IMU hardware synchronization | ❌ offsets measured, not verified |
| On-device VIO / SLAM / localization | ❌ not integrated — PC-side pipeline only |
| IMU bias correction applied to streamed data | ❌ raw values only — see [docs/CALIBRATION.md](docs/CALIBRATION.md) |
| Camera-to-IMU extrinsic calibration | ❌ not yet done |

Nothing is labelled complete that isn't.

---

## Roadmap

The IMU calibration audit (in the repository wiki) identified these as the next concrete steps:

- [ ] Apply stored gyro/accel bias to streamed and recorded values (currently raw)
- [ ] Six-face accelerometer calibration with per-axis gravity cancellation
- [ ] Capture timestamps during calibration for Allan Variance (noise model for VIO filter)
- [ ] Camera-to-IMU extrinsic calibration (target: Kalibr or equivalent offline tool)
- [ ] Wire an on-device VIO backend (OpenCV + MSCKF or similar) to the existing sensor + frame pipeline
- [ ] Portrait-mode camera calibration (32 images already collected)

---

## Documentation

| Doc | Contents |
|---|---|
| [STREAMING_PROTOCOL.md](docs/STREAMING_PROTOCOL.md) | Wire format, connection states, receiver usage |
| [APP_UPDATES.md](docs/APP_UPDATES.md) | OTA update flow, manifest schema, threat model |
| [CALIBRATION.md](docs/CALIBRATION.md) | IMU + camera calibration workflow and honest limitations |
| [DATASET_FORMAT.md](docs/DATASET_FORMAT.md) | On-disk session layout, CSV columns, units |
| [TIMESTAMP_CONVENTIONS.md](docs/TIMESTAMP_CONVENTIONS.md) | Clock domains, offset measurement, sync verification |
| [VIO_STATUS.md](docs/VIO_STATUS.md) | VIO integration status and PC-pipeline bridge instructions |
| [AUDIT.md](docs/AUDIT.md) | Repository and environment audit |
| [INSTALL.md](docs/INSTALL.md) | Full SDK + JDK setup steps |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `SDK location not found` | Add `sdk.dir=C\:\\path\\to\\Sdk` to `local.properties` |
| Build fails — JDK version | Point `JAVA_HOME` at JDK 17 or 21; AGP 8.7 rejects JDK 25 |
| **Connection refused** | `prism_receiver.py` is not running, or port mismatch |
| **Host unreachable / timed out** | Wrong IP, different subnet, or Windows Firewall blocking port 47777 |
| **Unsupported receiver** | PC is not running a PRISM Link v1 receiver — no retry happens by design |
| **Stale** (frames stopped) | Camera or receiver stopped; socket still open |
| No motion data | Phone may lack a gyroscope — Live Studio names the missing sensor |
| `synchronizationVerified=false` in export | Expected — requires a controlled external sync test |
| App crashes on start | `adb logcat -s PrismApp:*` |
| Recording stops when screen turns off | Disable battery optimisation for P.R.I.S.M. in Android Settings → Apps |

---

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free for research, education, and personal use.  
Commercial use requires explicit permission from Nevcortex.

---

<div align="center">

*Built for the Nevcortex GPS-denied autonomy project.*  
*First experimental device: OnePlus Nord 4 · OxygenOS 16 · Android 16.*

</div>
