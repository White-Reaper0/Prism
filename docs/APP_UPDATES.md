# PRISM Link App Updates (local Wi-Fi APK updates)

PRISM can update itself from an APK you compile on the PC, over your own Wi-Fi. No cloud, no app
store, no manual APK copying: the PC serves a versioned manifest and one APK; the phone checks,
downloads, verifies, and hands the file to Android's installer.

This system is **separate** from PRISM Link v1 (cameras/IMU streaming, TCP JSON-lines on port
47777). Streaming is not HTTP and cannot carry APK downloads, so the updater is a plain HTTP
service on its own port. `tools/prism_receiver.py` is untouched.

## Participants

| Component | Path | Role |
|---|---|---|
| Android: model + parser | `app/src/main/java/com/nevcortex/prism/update/UpdateModels.kt` | Manifest parsing/validation, phases, failures |
| Android: orchestration | `app/src/main/java/com/nevcortex/prism/update/UpdateManager.kt` | Check → download → verify → hand to installer |
| Android: UI | `app/src/main/java/com/nevcortex/prism/ui/screens/settings/AppUpdatesSection.kt` | Settings card "App updates" |
| Android: message mapping | `app/src/main/java/com/nevcortex/prism/ui/status/UpdatePresenter.kt` | Plain-language messages per state |
| PC: publisher | `tools/prism_publish.py` | Build → real identity via aapt2 → hash → unique name → atomic manifest |
| PC: update service | `tools/prism_updater.py` | HTTP server for the verified manifest + APK (port 47778) |
| Tests | `app/src/test/java/com/nevcortex/prism/update/UpdateManifestTest.kt` | 9 JVM tests on parser/validator rules |

## Port: 47778

Streaming lives on TCP 47777 and speaks its own line protocol; reusing that port for HTTP was not
compatible, so:

- **47777** — PRISM Link v1 streaming (phone → PC, TCP JSON-lines, never changed).
- **47778** — updater HTTP (phone pulls from PC). Configurable per build via `--port"/settings.

## Update protocol (PRISM Update v1)

The phone does exactly two HTTP GETs against `http://<pc>:<port>`:

1. `GET /manifest.json` → the manifest document (≤ 64 KB, UTF-8 JSON).
2. `GET /apk/<.apk file named by the manifest>` → the raw APK bytes.

Anything else returns `404`. If the APK file changed on the PC after startup (size/mtime), the
server answers `410` instead of serving mixed bytes mid-download.

### Manifest schema

```json
{
  "format": "prism-update",
  "formatVersion": 1,
  "applicationId": "com.nevcortex.prism.debug",
  "versionName": "0.2.1-live",
  "versionCode": 3,
  "apk": "prism-0.2.1-live-vc3.apk",
  "sha256": "adbd54c8baedd86577da61c7cd8e983866d38cfab68226cece427e984b490526",
  "sizeBytes": 66210165,
  "notes": "Live studio polish and the first updateable build.",
  "minSupportedVersionCode": 0
}
```

Validation applied on the PC at startup (same rules as the phone):

- `format` == `prism-update`, `formatVersion` == 1.
- `applicationId`, `versionName`, `apk`, `sha256` are non-empty strings.
- `versionCode` > 0, `sizeBytes` > 0.
- `sha256` is exactly 64 lowercase hex characters.
- `apk` is a plain basename (< 129 chars, no `/`, no `\`, no `..`, ends in `.apk`).
- `notes` ≤ 4000 chars.
- The named APK must exist in the root folder and its **real recomputed digest and size** must
  match the manifest. A mismatch refuses to start the service.

Validation applied on the phone before anything is offered or installed:

- `applicationId` must equal `BuildConfig.APPLICATION_ID` of the running build
  (`com.nevcortex.prism.debug` for debug builds).
- `versionCode` must be **greater** than the installed one, otherwise the UI says you are already
  on the latest version.
- `minSupportedVersionCode` > 0 must not exceed the installed code; otherwise the release demands
  an intermediate step-up build.

## PC: publish a build

```powershell
# 1. Bump versionCode / versionName in app\build.gradle.kts.
# 2. Build, hash, and publish (uses aapt2 to read the APK's true identity):
python tools\prism_publish.py --notes "What changed in this build."
#    or reuse an already-built APK:
python tools\prism_publish.py --skip-build
# 3. Start/restart the update service:
python tools\prism_updater.py
```

What the publisher guarantees:

- Reads the real `package`, `versionCode`, `versionName` from the APK via `aapt2 dump badging`
  (SDK build-tools, newest first; override with `--aapt2 "...\aapt2.exe"`).
- Copies the APK to `build/publish/prism-<versionName>-vc<versionCode>.apk` — a unique name per
  release. It refuses to overwrite an existing release without `--force`, so a phone that is
  downloading cannot have its bytes swapped.
- Refuses to publish a `versionCode` that is not greater than the currently published one without
  `--force` (monotonic releases), and refuses an `applicationId` change outright.
- Writes `manifest.json` atomically (temp file + replace), so phones never read a half-written
  manifest.
- Publications go to `build/publish/`, deliberately separate from the streaming receiver's
  session output.

## PC: run the update service

```powershell
python tools\prism_updater.py                    # serves build/publish on port 47778
python tools\prism_updater.py --port 50000       # custom port
```

The service validates the manifest and APK at startup and then serves that snapshot from memory.
Publishing a new release does not disturb a running download — restart the service to advertise
the new release. Keep the console window open; Ctrl+C stops it.

Instructions for the phone (address and port) are printed on start.

## Phone: check, download, install

1. **Set the endpoint (once).** In PRISM: *Settings → App updates*, enter the PC's LAN address
   (e.g. `192.168.1.42`) and port `47778`, press *Save update server*.
2. **Check for updates.** The banner then honestly reports one of:
   - *"You're using the latest version."* (server answered with a real PRISM manifest; nothing newer)
   - *"PRISM 0.2.1-live is available."* (newer code, size shown, plus release notes)
   - A plain error: PC unreadable, manifest not PRISM, wrong app, hash mismatch, and so on — always
     alongside *"Your current version is unchanged."*
3. **Download update.** Live progress in percent + bytes. *Cancel download* aborts; nothing partial
   is kept and nothing is installed.
4. **Verify.** The download is only "ready" after size == `sizeBytes` and SHA-256 == `sha256`.
   On a mismatch the file is deleted and a retry is offered.
5. **Install update.** Android's first-ever install from this app requires granting
   *Install unknown apps* to PRISM; the card explains why and offers a shortcut. Once allowed,
   Android's own installer screen shows the package and requires a confirmation. This is never a
   silent install.
   The downloaded file stays in PRISM's private storage (`filesDir/updates`) until installed or
   discarded. After Android finishes, PRISM restarts as the new version; the Settings card shows
   the installed version, which is the truth.

Update checks are refused while a synchronized recording is running
(*"A recording is running."*), so the updater cannot disturb a capture session.

## Security model and limitations (honest)

The updater is designed for a **trusted development LAN**. Specifically:

- Plain HTTP. No TLS, no client/server authentication, no signatures on the manifest. Anyone on
  the same Wi-Fi can serve a manifest to the phone if you point it at them.
- The SHA-256 in the manifest protects against **corrupt or truncated transfers** and against a
  server advertising **broken builds** (the PC verifies each APK against its manifest at startup),
  but it does not make a hostile server trustworthy: a malicious actor would just recompute the
  hash of whatever they serve.
- The last hard line is Android itself: an update installs only if the pack is signed with a key
  accepted by Android for the installed application ID. PRISM debug builds are pinned to
  `keystores/prism-dev.keystore` (`keystore.properties`), so every PC build signs identically. If
  the signature does not match, Android blocks the update and restored-from-backup keys cannot
  bypass it — nothing in this updater attempts to get around signature checks, and a mismatch is
  reported instead.
- Nothing is installed without the user's explicit confirmation on Android's own installer screen,
  and PRISM needs one-time *Install unknown apps* permission before it is even allowed to ask.

Practical hardening for this environment:

- Only run the updater on your home/hotspot Wi-Fi; **do not expose port 47778 to the internet**.
- Do not run the updater on guest networks or shared hotspots.
- Publishing is deliberate (`prism_publish.py`) and each release has a unique filename, so a
  stray attacker cannot publish by touching the folder — but they could still try to MITM the
  phone on an untrusted network.

## First-launch signing note

If your phone currently has a PRISM installed from **Android's default debug key** (before
`keystore.properties`/\`prism-dev.keystore` was introduced), the pinned-key build will be refused
by Android as a *different signature*. That one-time migration requires uninstalling the old copy
(delete its recordings/exports that are on app-private storage first; session folders may need a
manual copy-out), then installing the pinned-key build manually. Every update after that has the
same pinned key and is a normal in-place update — data preservation, no uninstall, no clear-all.

## Testing (as actually run)

- JVM: 18 unit tests green (`LinkProtocolTest` 9, `UpdateManifestTest` 9) covering manifest parse
  rules, traversal-safety of the `apk` name, wrong app id, not-newer, and `minSupportedVersionCode`.
- PC: live end-to-end over HTTP on this machine — publish (`--skip-build`, aapt2 read real values
  `com.nevcortex.prism.debug` / `0.2.1-live` / code 3), exact APK served with digest matching,
  `/manifest.json`, health, 404s for wrong names/traversal, monotonous republish refused, and the
  updater refusing a bad-digest and a traversal-named manifest at startup.
- Device: **not yet validated in this workspace** (`adb devices` sees no Nord 4; emulator cannot
  boot here). The full check → download → verify → install loop on real hardware remains to be
  confirmed by running both ends as described above — treat the updater as ready-for-testing, not
  as device-validated.
