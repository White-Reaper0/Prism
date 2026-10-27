# Installation & Build

## Prerequisites
- **JDK 17 or 21** (Temurin/Adoptium recommended). AGP 8.7.3 does **not** support JDK 25 as the
  build JVM. Set `JAVA_HOME` to the 17/21 install.
- **Android SDK** with:
  - `platform-tools`
  - `build-tools` (any recent, e.g. 35.0.0 or 36.0.0)
  - `platforms;android-36` (compileSdk is 36)
  - Accept licenses (`sdkmanager --licenses`).
- A connected device or emulator running **API 30+**. The first test device is a OnePlus Nord 4
  (OxygenOS 16). For >200 Hz IMU rates the app requests `HIGH_SAMPLING_RATE_SENSORS`.

## Option A — Android Studio
1. Open the repo folder.
2. Let Gradle sync. If it asks for an SDK location, point it at your Android SDK.
3. Select a device/emulator and press Run, or `Build → Build APK(s)`.

## Option B — Command line (Windows PowerShell)
```pwsh
# 1) Install a JDK 17 or 21 (Adoptium Temurin). Example using winget:
#    winget install EclipseAdoptium.Temurin.21.JDK

# 2) Install Android command-line tools, then:
$env:ANDROID_HOME = "C:\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" `
    "platform-tools" "platforms;android-36" "build-tools;35.0.0"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses   # accept each (y)

# 3) Build the debug APK
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot"  # your 17/21 path
cd f:\Creations\pending\prism
.\gradlew.bat :app:assembleDebug

# 4) Install on the connected device (USB debugging on)
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r `
    "app\build\outputs\apk\debug\app-debug.apk"
```

The installed app id is `com.nevcortex.prism.debug` (debug build suffix).

## First-run permissions on the device
- **Camera** — requested on the Live Camera / Mission tabs.
- **Notifications** — required so the foreground recording service can post its notification.
- **High sensor rate** — the requested IMU rate is configurable; if >200 Hz the permission is
  needed, otherwise lower the rate in Settings.
- **Location (optional)** — only if you enable GPS as optional ground truth. GPS is **off by
  default**; the GPS-denied evaluation remains possible without it.

## Focused smoke checks (run after a real build, then report honestly)
1. Open **IMU Lab**: confirm accelerometer/gyroscope populate, the achieved rate is shown (not the
   requested rate), and plots scroll without retaining unbounded history.
2. In **Settings/Calibration**, run a **Stationary** IMU calibration and confirm gyro bias + |g|
   are written and labeled `ESTIMATED`.
3. In **Live Camera**, start a recording, stop it, and confirm `session.json`, `imu/*.csv`, and
   `frames.csv` exist and `synchronizationVerified` is `false` until a controlled test is run.
4. Export a session (EuRoC-style) and confirm `mav0/imu0/data.csv` and `mav0/cam0/timestamps.txt`
   are populated with the recorded timestamps.

Only after these checks pass on a real device should those behaviors be reported as verified.
