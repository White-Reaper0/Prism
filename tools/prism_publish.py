#!/usr/bin/env python3
"""Publish a PRISM Android build to the local update service (PRISM Update v1).

Workflow implemented here:
    1. Assemble the debug APK (Gradle), or reuse one with --skip-build.
    2. Read the APK's real package name, versionCode and versionName with aapt2,
       so the manifest can never claim something the file is not.
    3. Compute the APK's real SHA-256 digest and byte size.
    4. Copy the APK into the publication folder under a unique, version-bearing
       name (a release is never silently overwritten).
    5. Write manifest.json atomically next to it.

Guards:
  * Refuses to publish when the new versionCode is not greater than the code
    already in manifest.json (monotonic releases), unless --force.
  * Refuses to overwrite an existing APK of the same name, unless --force.
  * The publication folder is separate from the streaming receiver's session
    output (tools/prism_receiver.py) and must stay that way.

Usage:
    python tools/prism_publish.py [--notes "string"] [--skip-build]
    python tools/prism_publish.py --min-code 2 --notes-file release.txt

Typical PC paths (auto-detected, overridable with flags):
    gradle:  C:\\Gradle\\gradle-8.11.1\\bin\\gradle.bat   (or the wrapper)
    aapt2:   C:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk\\build-tools\\36.0.0
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_ROOT = PROJECT_DIR / "build" / "publish"
APK_OUTPUT = PROJECT_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
SDK_LOCAL_PROPERTIES = PROJECT_DIR / "local.properties"
FORMAT_NAME = "prism-update"
FORMAT_VERSION = 1
MAX_NOTES_CHARS = 4000


def fail(message: str) -> None:
    print(f"[prism-publish] ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def run_gradle(project: Path) -> None:
    gradle = shutil.which("gradle.bat") or shutil.which("gradle")
    candidates = [Path("C:/Gradle/gradle-8.11.1/bin/gradle.bat")] if sys.platform == "win32" else []
    if not gradle:
        for candidate in candidates:
            if candidate.is_file():
                gradle = str(candidate)
                break
    if not gradle:
        fail("gradle was not found; install it or add it to PATH")
    print(f"[prism-publish] gradle: {gradle}")
    command = [gradle, ":app:assembleDebug", "--console=plain"]
    completed = subprocess.run(command, cwd=project)
    if completed.returncode != 0:
        fail("Gradle build failed; fix the project and retry")


def unescape_properties(value: str) -> str:
    """Java .properties escaping: \\: \\\\ and \\= become plain characters."""
    out: list[str] = []
    iterator = iter(range(len(value)))
    i = 0
    while i < len(value):
        char = value[i]
        if char == "\\" and i + 1 < len(value):
            nxt = value[i + 1]
            if nxt in (":", "=", "\\"):
                out.append(nxt)
                i += 2
                continue
        out.append(char)
        i += 1
    return "".join(out)


def sdk_from_local_properties(project: Path) -> Path | None:
    properties = project / "local.properties"
    if not properties.is_file():
        return None
    for line in properties.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("sdk.dir="):
            return Path(unescape_properties(line[len("sdk.dir="):]))
    return None


def find_aapt2(project: Path, explicit: str | None) -> Path:
    if explicit:
        candidate = Path(explicit)
        if not candidate.is_file():
            fail(f"--aapt2 does not exist: {candidate}")
        return candidate
    sdk = sdk_from_local_properties(project)
    if sdk is None or not sdk.is_dir():
        fail("could not locate the Android SDK (missing local.properties sdk.dir); pass --aapt2")
    build_tools = sdk / "build-tools"
    versions = sorted(
        (p for p in build_tools.iterdir() if p.is_dir() and re.match(r"^\d+\.\d+\.\d+$", p.name)),
        key=lambda p: [int(x) for x in p.name.split(".")],
        reverse=True,
    )
    for candidate in versions:
        aapt2 = candidate / ("aapt2.exe" if sys.platform == "win32" else "aapt2")
        if aapt2.is_file():
            return aapt2
    fail(f"no aapt2 found under {build_tools}; pass --aapt2")


def read_apk_badging(aapt2: Path, apk: Path) -> tuple[str, int, str]:
    """aapt2 dump badging reports the real identity compiled into the APK."""
    completed = subprocess.run(
        [str(aapt2), "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        fail(f"aapt2 could not read {apk}: {completed.stderr.strip()[:300]}")
    package_line = next(
        (line for line in completed.stdout.splitlines() if line.startswith("package:")),
        None,
    )
    if not package_line:
        fail("aapt2 badging output has no package line")
    name_match = re.search(r"name='([^']+)'", package_line)
    code_match = re.search(r"versionCode='([^']+)'", package_line)
    version_match = re.search(r"versionName='([^']*)'", package_line)
    if not (name_match and code_match and version_match):
        fail(f"could not parse the aapt2 package line: {package_line}")
    application_id = name_match.group(1)
    try:
        version_code = int(code_match.group(1).split()[0])
    except ValueError:
        fail(f"non-numeric versionCode in the APK: {code_match.group(1)!r}")
    version_name = version_match.group(1)
    return application_id, version_code, version_name


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_existing_manifest(root: Path) -> dict | None:
    path = root / "manifest.json"
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish the debug APK for local PRISM updates.")
    parser.add_argument("--root", default=str(DEFAULT_ROOT), help="publication folder")
    parser.add_argument("--notes", default="", help="release notes shown on the phone")
    parser.add_argument("--notes-file", default=None, help="release notes read from a file")
    parser.add_argument(
        "--min-code",
        type=int,
        default=0,
        help="minimum versionCode allowed to jump to this release (minSupportedVersionCode)",
    )
    parser.add_argument("--skip-build", action="store_true", help="reuse the existing app-debug.apk")
    parser.add_argument(
        "--force",
        action="store_true",
        help="allow overwriting an APK of the same name or going back in versionCode",
    )
    parser.add_argument("--aapt2", default=None, help="explicit path to aapt2")
    args = parser.parse_args()

    notes = args.notes
    if args.notes_file:
        notes = Path(args.notes_file).read_text(encoding="utf-8").strip()
    notes = notes[:MAX_NOTES_CHARS]

    if not args.skip_build:
        run_gradle(PROJECT_DIR)
    if not APK_OUTPUT.is_file():
        fail(f"no APK at {APK_OUTPUT}. Build first (drop --skip-build) or fix the path.")

    aapt2 = find_aapt2(PROJECT_DIR, args.aapt2)
    application_id, version_code, version_name = read_apk_badging(aapt2, APK_OUTPUT)
    print(f"[prism-publish] aapt2:   {aapt2}")
    print(f"[prism-publish] package: {application_id}")
    print(f"[prism-publish] release: {version_name} (versionCode {version_code})")

    if version_code <= 0:
        fail("the APK has a non-positive versionCode; fix app/build.gradle.kts first")

    root = Path(args.root).resolve()
    root.mkdir(parents=True, exist_ok=True)

    existing = load_existing_manifest(root)
    if existing:
        try:
            previous_code = int(existing.get("versionCode", 0))
        except (TypeError, ValueError):
            previous_code = 0
        if version_code <= previous_code and not args.force:
            fail(
                f"published manifest is versionCode {previous_code}; the new APK is "
                f"{version_code}. Increment versionCode in app/build.gradle.kts "
                "(or --force to override)."
            )
        if existing.get("applicationId") != application_id:
            fail(
                "applicationId changed between releases "
                f"({existing.get('applicationId')} -> {application_id}). Do not change the "
                "app identity for an in-place update."
            )

    apk_name = f"prism-{version_name}-vc{version_code}.apk".replace(" ", "_")
    destination = root / apk_name
    if destination.exists() and not args.force:
        fail(
            f"{destination} already exists. Builds are never silently overwritten; "
            "publish a new versionCode or pass --force."
        )
    if destination.exists():
        print(f"[prism-publish] overwriting {destination} (--force)")
    shutil.copyfile(APK_OUTPUT, destination)

    digest = sha256_of(destination)
    size = destination.stat().st_size
    manifest = {
        "format": FORMAT_NAME,
        "formatVersion": FORMAT_VERSION,
        "applicationId": application_id,
        "versionName": version_name,
        "versionCode": version_code,
        "apk": apk_name,
        "sha256": digest,
        "sizeBytes": size,
        "notes": notes,
        "minSupportedVersionCode": max(0, int(args.min_code)),
    }

    # Atomic write (temp file + replace) so a phone can never read half a manifest.
    manifest_path = root / "manifest.json"
    temp_path = manifest_path.with_suffix(".json.tmp")
    temp_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    temp_path.replace(manifest_path)

    print(f"[prism-publish] apk:     {apk_name} ({size:,} bytes)")
    print(f"[prism-publish] sha256:  {digest}")
    print(f"[prism-publish] manifest written: {manifest_path}")
    print("[prism-publish] start or restart the update service:")
    print("    python tools/prism_updater.py")
    print("[prism-publish] the running updater keeps serving its startup snapshot, so a")
    print("[prism-publish] phone mid-download is never disturbed by this publication.")


if __name__ == "__main__":
    main()
