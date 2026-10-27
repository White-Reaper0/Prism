# Repository Audit & Environment

## Repository state before this work
The workspace `f:\Creations\pending\prism` was **empty**. There was **no prior implementation** in
this workspace — no existing YOLO detector, Depth Anything V2-Small, SegFormer-B0, P.R.I.S.M.
traversability/uncertainty models, Doubt Engine, Belief Ledger, Scar Tissue Memory, or VIO
pipeline to "retain." Those were therefore implemented as explicit interfaces/scaffolds with
honest "not bundled / not integrated" status rather than claimed as existing functionality.

## Toolchain on the development machine
- **JDK:** Eclipse Adoptium **JDK 25** is the only full JDK installed. **AGP 8.7.3 requires JDK
  17 or 21 to run the Gradle build**, so the build cannot run on this machine's default JDK.
- **Android SDK / build-tools / platform-tools:** **not installed.**
- **Android Studio:** not installed.
- **Gradle (global):** not installed (the project ships the 8.11.1 wrapper instead).
- **adb:** not on PATH.

## Consequence — what was NOT done
- The **debug APK was not built** and no `adb install` was performed.
- **No physical-device sensor/camera synchronization, VIO accuracy, or sensor behavior was
  verified**, because no device or build ran here. Per the project's own honesty rules, those are
  reported as **not verified**, not silently assumed.

## What WAS done
- A complete, correctly-wired Gradle Android project (AGP 8.7.3, Kotlin 2.0.21, Compose BOM
  2024.12.01, CameraX 1.4.1, compileSdk 36 / minSdk 30 / targetSdk 36).
- The Gradle wrapper (`gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, `gradle-wrapper.properties`
  for 8.11.1) is included so the project builds once a compatible JDK + SDK are present.
- Full source for Priorities 1–5 (IMU acquisition + diagnostics, synchronized camera+IMU
  recording, calibration, dataset export, playback) and honest scaffolds for 4 and 6.
- Documentation of the data format, timestamp conventions, calibration workflow, and VIO status.

## To actually build and verify
Follow [INSTALL.md](INSTALL.md). Build with JDK 17 or 21 and an Android SDK containing
`platforms;android-36`. Then run on the OnePlus Nord 4 and perform the focused smoke checks
described there; only after those pass should specific behaviors be reported as verified.
