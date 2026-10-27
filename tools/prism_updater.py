#!/usr/bin/env python3
"""PRISM local update service (HTTP, default port 47778).

Serves the "PRISM Update v1" manifest and its one APK file to the PRISM Android
app over the local network, so builds you compile on the PC can be installed on
the phone without copying files by hand.

Design rules (documented in docs/APP_UPDATES.md):
  * The manifest is validated at startup and the validated snapshot is held in
    memory. The server never re-reads a manifest mid-run, so a rebuild on the
    PC cannot swap the bytes underneath a phone that is currently downloading.
    Publish a new release and restart this service to advertise it.
  * Only the exact APK file named by the manifest is served. No listing, no
    other paths, no traversal.
  * The APK's real SHA-256 and size are recomputed at startup and must match
    the manifest, so the service cannot advertise a broken or tampered build
    by mistake.

This tool does not touch tools/prism_receiver.py or its session output. The
receiver is a raw TCP JSON-lines service on port 47777; this one is HTTP on
port 47778 and must remain on a separate port.

Usage:
    python tools/prism_updater.py [--root build/publish] [--port 47778] [--bind 0.0.0.0]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MANIFEST_NAME = "manifest.json"
FORMAT_NAME = "prism-update"
FORMAT_VERSION = 1
MAX_MANIFEST_BYTES = 64 * 1024


def fail(message: str) -> None:
    print(f"[prism-updater] ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def load_manifest(path: Path) -> dict:
    """Parse manifest.json with the same strictness the phone applies."""
    if not path.is_file():
        fail(f"manifest not found: {path} (run tools/prism_publish.py first)")
    raw = path.read_bytes()
    if len(raw) == 0 or len(raw) >= MAX_MANIFEST_BYTES:
        fail(f"manifest {path} is empty or suspiciously large ({len(raw)} bytes)")
    try:
        # utf-8-sig tolerates a BOM that Windows editors sometimes leave behind.
        manifest = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        fail(f"manifest is not valid UTF-8 JSON: {exc}")
    if not isinstance(manifest, dict):
        fail("manifest must be a JSON object")

    required_strings = (
        "applicationId",
        "versionName",
        "apk",
        "sha256",
    )
    for key in required_strings:
        value = manifest.get(key)
        if not isinstance(value, str) or not value.strip():
            fail(f"manifest field {key!r} must be a non-empty string")

    if manifest.get("format") != FORMAT_NAME:
        fail(f"manifest field 'format' must be {FORMAT_NAME!r}")
    if manifest.get("formatVersion") != FORMAT_VERSION:
        fail(f"manifest field 'formatVersion' must be {FORMAT_VERSION}")

    apk_name = manifest["apk"]
    if len(apk_name) > 128:
        fail("manifest field 'apk' is longer than 128 characters")
    if "/" in apk_name or "\\" in apk_name or ".." in apk_name or apk_name != apk_name.strip():
        fail(f"manifest field 'apk' must be a plain file name (got {apk_name!r})")
    if not apk_name.lower().endswith(".apk"):
        fail("manifest field 'apk' must point to an .apk file")

    try:
        version_code = int(manifest["versionCode"])
        size_bytes = int(manifest["sizeBytes"])
    except (KeyError, TypeError, ValueError):
        fail("manifest fields 'versionCode' and 'sizeBytes' must be integers")
    if version_code <= 0:
        fail("manifest field 'versionCode' must be positive")
    if size_bytes <= 0:
        fail("manifest field 'sizeBytes' must be positive")
    if len(manifest.get("notes", "")) > 4000:
        fail("manifest field 'notes' must be at most 4000 characters")

    sha256 = manifest["sha256"].lower()
    if len(sha256) != 64 or any(c not in "0123456789abcdef" for c in sha256):
        fail("manifest field 'sha256' must be a lowercase 64-hex digest")
    manifest["sha256"] = sha256

    return manifest


def verify_apk(root: Path, manifest: dict) -> tuple[Path, os.stat_result, int]:
    """The served file must exist and really match its declared digest and size."""
    apk = root / manifest["apk"]
    if not apk.is_file():
        fail(f"manifest points to {apk}, which does not exist")
    declared_sha = manifest["sha256"]
    digest = hashlib.sha256()
    size = 0
    with apk.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    if size != manifest["sizeBytes"]:
        fail(
            f"APK size mismatch: file is {size} bytes, manifest declares "
            f"{manifest['sizeBytes']}"
        )
    actual = digest.hexdigest()
    if actual != declared_sha:
        fail(
            f"APK digest mismatch: file is {actual}, manifest declares {declared_sha}"
        )
    return apk, apk.stat(), size


def lan_address_hint(port: int) -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.168.255.255", 9))
            return probe.getsockname()[0]
    except OSError:
        return "<this-pc-address>"


class UpdateHandler(BaseHTTPRequestHandler):
    server_version = "PrismUpdater/1"
    protocol_version = "HTTP/1.1"

    # Set on the server object after startup validation.
    manifest_text: bytes = b""
    manifest_dict: dict = {}

    def log_message(self, fmt: str, *args) -> None:
        print(f"[prism-updater] {self.address_string()} - {fmt % args}")

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self) -> None:  # noqa: N802 - http.server API
        route = self.path.split("?", 1)[0].split("#", 1)[0]
        if route == "/manifest.json":
            self._send(200, UpdateHandler.manifest_text, "application/json; charset=utf-8")
            return
        if route.startswith("/apk/"):
            self._serve_apk(route[len("/apk/"):])
            return
        if route == "/" or route == "/health":
            served = UpdateHandler.manifest_dict
            body = json.dumps(
                {
                    "service": "prism-updater",
                    "applicationId": served.get("applicationId"),
                    "versionName": served.get("versionName"),
                    "versionCode": served.get("versionCode"),
                }
            ).encode("utf-8")
            self._send(200, body, "application/json; charset=utf-8")
            return
        self._send(404, b"not found\n", "text/plain; charset=utf-8")

    def _serve_apk(self, name: str) -> None:
        expected = UpdateHandler.manifest_dict.get("apk")
        apk = getattr(self.server, "apk_file", None)
        stat = getattr(self.server, "apk_stat", None)
        if name != expected or apk is None or stat is None:
            # Every other path is refused, including different APK names, so the
            # server can never be talked into serving an unrelated file.
            self._send(404, b"not found\n", "text/plain; charset=utf-8")
            return
        current = apk.stat()
        if current.st_size != stat.st_size or current.st_mtime_ns != stat.st_mtime_ns:
            # The build directory changed under us; a phone mid-download would get
            # a digest mismatch, so stop serving until the service is restarted.
            self._send(410, "APK changed on the PC; reconnect after restart\n".encode("utf-8"),
                       "text/plain; charset=utf-8")
            return
        with apk.open("rb") as handle:
            payload = handle.read()
        self._send(200, payload, "application/vnd.android.package-archive")

    def do_HEAD(self) -> None:  # noqa: N802 - http.server API
        self._send(405, b"", "text/plain; charset=utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Serve the PRISM local app update.")
    parser.add_argument(
        "--root",
        default="build/publish",
        help="publication folder containing manifest.json and the APK (default: build/publish)",
    )
    parser.add_argument("--port", type=int, default=47778, help="HTTP port (default 47778)")
    parser.add_argument("--bind", default="0.0.0.0", help="interface to bind (default: all)")
    args = parser.parse_args()

    if not (1 <= args.port <= 65535):
        fail("port must be in 1..65535")

    root = Path(args.root).resolve()
    if not root.is_dir():
        fail(f"publication root {root} does not exist")

    manifest = load_manifest(root / MANIFEST_NAME)
    manifest_text = (root / MANIFEST_NAME).read_bytes()
    apk, stat, size = verify_apk(root, manifest)

    print(f"[prism-updater] root:        {root}")
    print(f"[prism-updater] application: {manifest['applicationId']}")
    print(f"[prism-updater] release:     {manifest['versionName']} (code {manifest['versionCode']})")
    print(f"[prism-updater] apk:         {manifest['apk']} ({size:,} bytes)")
    print(f"[prism-updater] sha256:      {manifest['sha256'][:16]}... verified against file")
    ip = lan_address_hint(args.port)
    print(f"[prism-updater] listening:   http://{args.bind}:{args.port}  (LAN address: {ip})")
    print(
        "[prism-updater] on the phone: PRISM Settings -> App updates -> PC address "
        f"{ip}, port {args.port}, then 'Check for updates'."
    )
    print("[prism-updater] keep this window open. Ctrl+C to stop.")

    UpdateHandler.manifest_text = manifest_text
    UpdateHandler.manifest_dict = manifest

    server = ThreadingHTTPServer((args.bind, args.port), UpdateHandler)
    server.apk_file = apk  # type: ignore[attr-defined]
    server.apk_stat = stat  # type: ignore[attr-defined]
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[prism-updater] stopped.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
