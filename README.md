# Nevcortex P.R.I.S.M. Mobile

**P**erception · **R**easoning · **I**nertial · **S**ensor · **M**apping

An Android **live camera + motion streaming and recording** app for the Nevcortex GPS-denied
autonomy project. The phone shows a camera preview and motion status, streams a low-rate JPEG
preview plus raw accelerometer/gyroscope/magnetometer samples to a PC on the same network, and can
still record synchronized datasets on the device.

The app is built to be honest about what is and is not working: connection, camera, sensors, and
tracking each report their own state in plain language. Nothing is labelled "streaming" until
frames are actually arriving, and no pose, localization, GPS, or end-to-end latency is claimed.

> **Build status (0.2.1-live):** the debug APK builds with the local toolchain
> (`C:\Program Files\Android\openjdk\jdk-21.0.8`, Gradle 8.11.1, Android SDK 36). JVM unit tests
> pass for the streaming protocol, the update manifest, and the front/rear camera-lens rules
> (25/25). The Python receiver passes its own unit tests (13/13) and completed a simulated
> handshake, IMU, heartbeat, and JPEG-frame session. The physical OnePlus Nord 4 was **not
> available**, and the installed Android emulator (`netsimd.exe` / QEMU) crashes on this host
> before boot, so camera, sensor, permission, ARCore, OxygenOS, on-device camera switching, and
> on-emulator UI behaviour remain **unverified**.

---

## What it does

| Area | What is implemented | What is not claimed |
|---|---|---|
| **Live Studio** | CameraX preview, measured camera frame timing, front/rear lens choice (availability-aware), live accelerometer/gyroscope/magnetometer readout, optional JPEG streaming to a named PC | No glass-to-glass latency, no hardware camera/IMU synchronization |
| **PC link** | Plain TCP `PRISM Link v1` on port `47777` with handshake, heartbeat, JPEG frames, IMU JSON lines, and a reference Python receiver that can show a live preview window of the phone's frames (optional, `--no-preview` to disable) | No discovery, pairing, TLS, authentication, or encryption |
| **Recording** | Foreground synchronized camera + IMU session, IMU-only motion session, replay, ZIP export, EuRoC-style export | Recording and streaming cannot run together because both need exclusive camera ownership |
| **Diagnostics** | Connection status and counters, camera timing, sensor plots, ARCore tracking test, pipeline notes, recordings, settings/calibration | ARCore pose is never sent to the PC or presented as localization |
| **Calibration** | Stationary + guided IMU bias/noise estimation, manual camera intrinsics entry, phone-to-body mounting transform, provenance labels | No on-device VIO, SLAM, metric scale, or map |
| **App updates** | Settings card + PC publisher + HTTP updater on port `47778`: check, download, verify SHA-256, hand to Android's installer | No silent install, no TLS or signatures — trusted LAN only; on-device install not yet validated |

The reference receiver saves frames and IMU lines to disk, and can also show the newest phone
frame in a live preview window (the PC has no camera of its own; the preview only mirrors what the
phone sends). It does not fuse anything. See
[docs/STREAMING_PROTOCOL.md](docs/STREAMING_PROTOCOL.md).

---

## App navigation

The bottom bar has three destinations. Technical detail is nested under Diagnostics rather than
competing with the main workflow.

| Screen | Purpose |
|---|---|
| **Home** | Readiness, PC address, one **Connect** action, and shortcuts to recordings and settings |
| **Live Studio** | Preview, connection summary, motion status, tracking status, measured stats, and optional on-device recording |
| **Diagnostics** | Hub for connection, motion waveforms, camera timing, tracking, synchronized recording, recordings/export, pipeline notes, and settings |
| **Diagnostics → Connection** | Receiver name/protocol, bytes and frame counters, measured frame rate, heartbeat RTT, last frame/IMU time, raw failure detail |
| **Diagnostics → Motion** | Live sensor plots and measured interval/rate/gap/clipping diagnostics |
| **Diagnostics → Camera** | Per-frame timing and measured camera rate |
| **Diagnostics → Tracking** | ARCore availability, install/update state, tracking state, and loss explanation |
| **Diagnostics → Recording** | Foreground synchronized camera + IMU recording |
| **Diagnostics → Recordings** | List, inspect, replay, and export saved sessions |
| **Diagnostics → Settings** | PC address/port, stream quality, IMU rate, capture controls, calibration, mounting |

Streaming runs only while Live Studio is on screen. Leaving Live Studio stops the camera and sensor
listeners so the app does not hold the camera or drain the battery in the background.

---

## Quick start: phone to PC

1. Start the receiver on the PC from the repository root:

   ```powershell
   python tools\prism_receiver.py --out C:\Users\you\prism-live
   ```

   Python 3.9+ is enough for recording. An optional live preview window of the incoming
   phone frames needs a JPEG decoder — install it once with `pip install pillow` (Tk ships
   with Python on Windows). Prefer disk-only recording? Use `--no-preview`. Startup prints
   one line saying whether preview is enabled, disabled, or unavailable; reception is
   identical either way.

2. Put the phone and PC on the same Wi-Fi/LAN. Find the PC's IPv4 address with `ipconfig`
   (Windows) or `ip addr` (Linux).
3. Install and open the app. On **Home**, enter that address and port `47777`, then tap
   **Connect**.
4. Once the handshake completes, the app opens **Live Studio**. The status line moves from
   **Connected** to **Streaming** only after frames are actually sent. The **Camera lens**
   chips under the preview switch between the phone's rear and front camera; lenses the
   phone does not report are disabled there and the same choice is persisted in
   **Diagnostics → Settings**.
5. Watch the receiver print frame and IMU counts every 5 s. Frames land in
   `prism-live\frames\` and motion samples in `prism-live\imu.jsonl`.

For the **Android emulator**, use `10.0.2.2` instead of the PC LAN address:
`10.0.2.2:47777`.

If Windows Firewall prompts, allow Python on **Private** networks. If the app says **Connection
refused**, the receiver is not running or the port does not match. If it says the host is
unreachable or timed out, check the address, the network, and the firewall.

The link is unencrypted. Do not expose port `47777` to the internet.

---

## Updating the app from your PC

Once PRISM is installed on the phone, a new build can be applied over Wi-Fi without copying an APK
by hand. The PC publishes the build and serves it; the phone pulls and applies it from
**Settings → App updates**.

```powershell
# 1. Bump versionCode in app\build.gradle.kts, then build + hash + publish:
python tools\prism_publish.py --notes "What changed."
# 2. Serve it (keep open; restart after publishing a new release):
python tools\prism_updater.py
# 3. On the phone: Settings -> App updates -> PC address -> Check for updates
#    -> Download update -> Install update (Android shows its own confirmation).
```

The phone checks the application identifier and version code, verifies size and SHA-256 against
the manifest, and only then offers installation through Android's own installer — never silently,
and never while a recording is running. The manifest is validated on the PC against the real APK
bytes before the service starts, so broken builds are not advertised. Full protocol, manifest
schema, signing notes, and threat model: [docs/APP_UPDATES.md](docs/APP_UPDATES.md).

The updater is HTTP on port `47778` because streaming (TCP 47777 JSON-lines) is not
HTTP-compatible. The same trust rule applies: trusted local network only.

---

## Build

### Prerequisites

- **JDK 17 or 21** — AGP 8.7.3 rejects JDK 25. Working install:
  `C:\Program Files\Android\openjdk\jdk-21.0.8`.
- **Gradle 8.11.1** at `C:\Gradle\gradle-8.11.1`.
- **Android SDK** with `platforms;android-36` and build tools. The SDK path is in
  `local.properties`.

### Exact build command (verified)

```powershell
Set-Location "F:\Creations\pending\prism"
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "C:\Gradle\gradle-8.11.1\bin\gradle.bat" :app:assembleDebug
```

Output APK: `app\build\outputs\apk\debug\app-debug.apk`
Debug application ID: `com.nevcortex.prism.debug`

### Tests

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
& "C:\Gradle\gradle-8.11.1\bin\gradle.bat" :app:testDebugUnitTest
```

`LinkProtocolTest` pins the handshake, IMU, frame, and ping wire fields used by the Python
receiver. `CameraLensTest` pins the front/rear lens rules (availability reporting, safe fallback
for a stored lens the phone does not have). `UpdateManifestTest` pins the update-manifest
validation. They run on the JVM and do not require a device.

The Python tools have their own tests:

```powershell
python -m unittest discover -s tools -p "test_*.py" -v
```

The receiver tests drive the real protocol over loopback sockets: handshake accept/reject, frame
exactness on disk, IMU append, preview-fault survival, and `--no-preview` behaviour.

### Using the Gradle wrapper

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleDebug
```

---

## Repository notes (cloning this project)

These files are deliberately **not** committed, and cloning a fresh copy means a few steps:

| Missing file | Why | What to do |
|---|---|---|
| `local.properties` | Developer-machine SDK path | Created automatically by Android Studio, or add `sdk.dir=C:\\...\\Sdk` yourself |
| `keystore.properties` | Signing passwords (secret) | Copy `keystore.properties.example` to `keystore.properties` and fill in real passwords |
| `keystores/prism-dev.keystore` | The pinned development signing key (secret) | Restore from a **private** backup; it is never in the repo |

Without the keystore files the build still succeeds, but signs with Gradle's normal **debug
key**. That is fine for a fresh install, but such a build will not install as an in-place
update over an existing PRISM install that was signed with the pinned key (Android refuses
signature changes). The pinned key is what makes `tools\prism_updater.py` over-the-air updates
work across builds — see [docs/APP_UPDATES.md](docs/APP_UPDATES.md) for the full story, and
keep a private backup of the keystore: losing it permanently locks future installs out of
in-place updates.

Build outputs (`build/`, `*.apk`, `.gradle/`, `.kotlin/`), Python caches (`__pycache__/`),
and receiver output (`prism-live/`) are all gitignored as well.

## Install and first run

### Physical phone

```powershell
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

Grant when prompted:

| Permission | Why |
|---|---|
| **Camera** | Preview, streaming, and camera recording |
| **Notifications** | Foreground recording notification (Android 13+) |
| **High sensor rate** | Only if the IMU rate is set above 200 Hz |

Location is optional and off by default; it is only for GPS ground-truth experiments.

### Emulator

```powershell
$emulator = "C:\Users\Administrator\AppData\Local\Android\Sdk\emulator\emulator.exe"
& $emulator -list-avds
& $emulator -avd <avd-name>
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

The emulator's synthetic camera and sensors would be useful for UI and link smoke tests, but they
are **not** evidence of real camera timing, sensor quality, ARCore tracking, or OxygenOS behaviour.
On this build host the installed API 37 emulator exits before boot (`netsimd.exe` illegal
instruction), so no emulator UI run is claimed here.

---

## Recording and export

Recording is separate from streaming and works without a PC connection.

1. Open **Diagnostics → Recording** for a synchronized camera + IMU session, or
   **Diagnostics → Motion** for an IMU-only session.
2. Tap **Start**, then **Stop** when done. A foreground service keeps a synchronized recording
   alive if the screen turns off.
3. Find the session in **Diagnostics → Recordings**. Replay it as a timing sanity check, or use
   **Export ZIP** / **Export EuRoC-style** through the Android share sheet.

On-device layout:

```
/data/data/com.nevcortex.prism.debug/files/sessions/<id>/
  session.json        metadata + sync diagnostics + validation notes
  imu/accelerometer.csv
  imu/gyroscope.csv
  imu/magnetometer.csv
  ...
  frames.csv          frame-timestamp manifest (camera clock + arrival clock)
  video.mp4           encoded video (VIDEO mode)
```

Recording and live streaming cannot run at the same time: CameraX camera ownership is exclusive.
The app disables Connect while a recording is active and says why.

### Pulling a session with adb

```powershell
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.nevcortex.prism.debug"

& $adb shell run-as $pkg ls files/sessions
& $adb shell run-as $pkg cat "files/sessions/<id>/session.json"
& $adb exec-out run-as $pkg tar -C files/sessions -cf - "<id>" | `
    tar -xf - -C "C:\Users\Administrator\Desktop\prism-sessions"
```

The in-app ZIP export is simpler for quick pulls.

---

## Honest limitations

- **No device discovery.** The PC address is typed by the user.
- **No encryption or authentication.** Use a trusted local network only.
- **No end-to-end latency metric.** Diagnostics reports TCP heartbeat RTT, not glass-to-glass delay.
- **No proven camera/IMU hardware synchronization.** Clock offsets are measured, not assumed, and
  exports carry a sync-not-verified note.
- **No on-device VIO, SLAM, GPS, or metric localization.** The pipeline screen explains what would
  be required; no result is fabricated.
- **ARCore is optional and diagnostic.** If ARCore is missing or the device is unsupported, the app
  still installs, previews, streams, and records. Tracking loss is reported as a state, never as a
  pose.
- **Physical-device verification is incomplete.** The Nord 4 was unavailable, so the following are
  unvalidated on real hardware: permission flows, CameraX camera selection on OxygenOS, real sensor
  rates, ARCore availability, thermal/battery behaviour, and sustained streaming over Wi-Fi.
- **Emulator results are smoke tests only.** They cannot establish camera or IMU timing quality.

---

## Documentation

- [docs/STREAMING_PROTOCOL.md](docs/STREAMING_PROTOCOL.md) — wire format, states, receiver usage
- [docs/APP_UPDATES.md](docs/APP_UPDATES.md) — local Wi-Fi APK updates: protocol, manifest, publishing, threat model
- [docs/AUDIT.md](docs/AUDIT.md) — repository audit and environment
- [docs/INSTALL.md](docs/INSTALL.md) — SDK + JDK setup and build steps
- [docs/DATASET_FORMAT.md](docs/DATASET_FORMAT.md) — on-disk layout, CSV columns, units
- [docs/TIMESTAMP_CONVENTIONS.md](docs/TIMESTAMP_CONVENTIONS.md) — clock domains, offsets, sync verification
- [docs/CALIBRATION.md](docs/CALIBRATION.md) — IMU + camera calibration workflow and honest limits
- [docs/VIO_STATUS.md](docs/VIO_STATUS.md) — on-device VIO status and PC pipeline bridge

---

## Troubleshooting

| Symptom | What to check |
|---|---|
| `SDK location not found` | `local.properties` must contain the SDK path |
| Build fails on JDK version | Point `JAVA_HOME` at `jdk-21.0.8`; AGP 8.7 rejects JDK 25 |
| **Connection refused** | Start `tools/prism_receiver.py`; confirm port `47777` |
| **Host unreachable / timed out** | Wrong address, different network, or PC firewall |
| **Unsupported receiver** | The PC is not running a PRISM Link v1 receiver; no retry happens |
| **Stale** | The socket is open but frames stopped for more than 3 s; check the camera and receiver |
| Camera shows **permission needed** | Grant Camera permission on Home or in Android Settings |
| No motion data | The phone may lack a gyroscope or accelerometer; Live Studio names the missing sensor |
| ARCore says **not installed / unsupported** | Install or update Google Play Services for AR, or use a supported device; streaming is unaffected |
| No frames in `frames.csv` | Grant Camera permission before starting a camera recording |
| `synchronizationVerified=false` | Expected; this requires a controlled external sync test |
| App crashes on start | `adb logcat -s PrismApp:* PrismApp` |
| Recording stops in the background | Disable battery optimisation for P.R.I.S.M. in Android Settings → Apps |

---

## Module (package) map

| Module | Package |
|---|---|
| Live streaming and JPEG encoding | `stream` |
| PC link protocol and client | `link` |
| Sensor acquisition | `sensors` |
| Camera acquisition | `camera` |
| Timestamp alignment and sync diagnostics | `sync` |
| Calibration | `calibration` |
| Recording, sessions, replay, export | `recording`, `playback`, `dataset` |
| Optional ARCore tracking diagnostics | `tracking` |
| VIO status and PC bridge notes | `vio` |
| Perception inference | `perception` |
| Mapping | `mapping` |
| Evidence and uncertainty | `evidence` |
| UI, navigation, persistence, services | `ui`, `persistence`, `di`, `service` |

The separate Nevcortex decision engine is not part of this project and is not modified here.

---

## License

Copyright (c) 2026 Nevcortex. All rights reserved. See [LICENSE](LICENSE): this is **not**
open-source — you must ask Nevcortex for and receive permission before using, modifying, or
distributing any part of it.
