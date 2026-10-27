#!/usr/bin/env python3
"""Reference PC receiver for PRISM Link v1.

This is intentionally small and dependency-free so it can be read end to end. It accepts one
Android client, completes the JSON handshake, saves JPEG frames and IMU lines, and answers the
heartbeat. Alongside saving, it can show the newest received frame in a live PC preview window —
the frames still come from the phone over the link; this receiver has no camera of its own.
See docs/STREAMING_PROTOCOL.md for the wire format.

Usage (Windows PowerShell):
    python tools/prism_receiver.py --out C:\\Users\\you\\prism-live
    python tools/prism_receiver.py --no-preview     # record to disk only

Live preview is optional. It needs a JPEG decoder, which the Python standard library does not
ship. The receiver tries backends and only degrades the *display*; recording never depends on it:
    1. Pillow + Tk (recommended):  pip install pillow
    2. OpenCV:                     pip install opencv-python
If neither is installed (or --no-preview), the receiver reports why and keeps receiving, exactly
as before.

The phone must use the PC's LAN address and TCP port 47777. On the Android emulator the host is
reachable at 10.0.2.2, so use 10.0.2.2:47777 inside the emulator.

The link is plain TCP with no authentication or encryption. Run it only on a trusted local
network, and do not expose the port to the internet.

Nothing here measures glass-to-glass latency. The app sends a heartbeat every 2 s and this
receiver echoes it, which measures TCP round-trip delay only.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import threading
import time
from pathlib import Path

PROTOCOL_NAME = "prism-link"
PROTOCOL_VERSION = 1
DEFAULT_PORT = 47777
RECEIVER_NAME = "prism-receiver-py"
MAX_LINE_BYTES = 64 * 1024          # a JSON control line should never exceed this
MAX_FRAME_BYTES = 16 * 1024 * 1024  # sanity bound for one JPEG


def log(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def read_line(stream, limit: int = MAX_LINE_BYTES) -> bytes | None:
    """Read one newline-terminated line, or None at EOF. Rejects absurd control lines."""
    line = stream.readline(limit)
    if not line:
        return None
    if not line.endswith(b"\n"):
        raise ValueError("control line exceeded the size limit or was not newline-terminated")
    return line


def send_json(conn: socket.socket, payload: dict) -> None:
    conn.sendall(json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n")


def handshake(conn: socket.socket, stream) -> dict:
    line = read_line(stream)
    if line is None:
        raise ConnectionError("client closed before sending hello")
    hello = json.loads(line.decode("utf-8"))
    if hello.get("type") != "hello":
        raise ConnectionError(f"expected hello, got {hello.get('type')!r}")
    if hello.get("protocol") != PROTOCOL_NAME:
        raise ConnectionError(f"wrong protocol {hello.get('protocol')!r}")
    if hello.get("version") != PROTOCOL_VERSION:
        raise ConnectionError(f"unsupported protocol version {hello.get('version')!r}")

    send_json(conn, {
        "type": "welcome",
        "protocol": PROTOCOL_NAME,
        "version": PROTOCOL_VERSION,
        "receiver": RECEIVER_NAME,
    })
    return hello


class LatestFrameSlot:
    """Single-slot storage for the newest JPEG. `push` from the network thread, `take` from the
    preview thread. Always overwrite the previous frame so no stale frames pile up; `take`
    returns None when there is nothing new."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._payload: bytes | None = None
        self._has_new = False
        self.closed = False

    def push(self, jpeg: bytes) -> None:
        with self._lock:
            if self.closed:
                return
            self._payload = jpeg
            self._has_new = True

    def take(self) -> bytes | None:
        with self._lock:
            payload, self._payload = self._payload, None
            had_new, self._has_new = self._has_new, False
            return payload if had_new else None

    def close(self) -> None:
        with self._lock:
            self.closed = True
            self._payload = None


class Preview:
    """Live preview of frames *sent by the phone*. Shows the newest frame only: `take()` is
    latest-wins and anything untouched is dropped, so a slow window never builds a backlog.

    The network thread only copies a byte-slice reference into the slot; decoding and drawing
    happen on the preview side. If a decode or draw raises, `on_error` records it once and the
    window keeps working — the receive loop never sees preview failures."""

    def __init__(self) -> None:
        self.slot = LatestFrameSlot()
        self.decode_errors = 0

    def push(self, jpeg: bytes) -> None:
        # Never let a preview problem reach the network loop.
        try:
            self.slot.push(jpeg)
        except Exception as exc:  # noqa: BLE001 - the receive loop must survive anything here
            self.on_error(exc)

    def on_error(self, exc: Exception) -> None:
        self.decode_errors += 1
        if self.decode_errors == 1:
            log(f"preview error (reception continues): {exc}")

    def run(self) -> None:
        raise NotImplementedError

    def stop(self) -> None:
        self.slot.close()


class CvPreview(Preview):
    """OpenCV backend: `imshow` window, decode each poll in the main thread."""

    def __init__(self, title: str) -> None:
        super().__init__()
        import cv2  # local import; only required when this backend is chosen

        self._cv2 = cv2
        self._window = title
        self._stopped = False

    def run(self) -> None:
        cv2 = self._cv2
        cv2.namedWindow(self._window, cv2.WINDOW_AUTOSIZE)
        try:
            import numpy  # cv2.imdecode needs ndarray; numpy ships with opencv-python

            while not self._stopped:
                jpeg = self.slot.take()
                if jpeg is not None:
                    try:
                        img = cv2.imdecode(numpy.frombuffer(jpeg, dtype=numpy.uint8),
                                           cv2.IMREAD_COLOR)
                        if img is not None:
                            cv2.imshow(self._window, img)
                    except Exception as exc:  # noqa: BLE001 - keep the window alive
                        self.on_error(exc)
                if cv2.waitKey(33) & 0xFF == ord("q"):  # ~30 Hz poll, 'q' closes the window
                    break
        finally:
            cv2.destroyAllWindows()

    def stop(self) -> None:
        self._stopped = True
        super().stop()


class TkPreview(Preview):
    """Pillow + Tk backend: Tk is in the standard library; Pillow decodes the JPEG."""

    def __init__(self, title: str, waiting_message: str) -> None:
        super().__init__()
        import tkinter as tk
        from PIL import Image, ImageTk

        self._tk = tk
        self._ImageTk = ImageTk
        self._Image = Image
        self._stopped = False
        self._photo: object | None = None

        self._root = tk.Tk()
        self._root.title(title)
        self._root.geometry("640x480")
        self._root.protocol("WM_DELETE_WINDOW", self._on_close)
        self._label = tk.Label(
            self._root,
            text=waiting_message,
            font=("TkDefaultFont", 11),
            wraplength=560,
            justify="center",
        )
        self._label.pack(expand=True, fill="both")

    def _on_close(self) -> None:
        self._stopped = True
        self.stop()
        try:
            self._root.destroy()
        except self._tk.TclError:
            pass

    def run(self) -> None:
        if self._stopped:
            return
        # Tk wants every widget touch on its own (main) thread, which is where run() is called.
        self._poll()
        try:
            self._root.mainloop()
        except Exception as exc:  # noqa: BLE001 - a dying window must not process-limit us
            self.on_error(exc)
        finally:
            self._stopped = True
            self.slot.close()

    def _poll(self) -> None:
        if self._stopped:
            return
        jpeg = self.slot.take()
        if jpeg is not None:
            try:
                image = self._Image.open(__import__("io").BytesIO(jpeg))
                # master=self._root pins the Tk photo to this window's interpreter: with
                # several Tk roots in one process, the default master would be the first.
                self._photo = self._ImageTk.PhotoImage(image, master=self._root)
                self._label.configure(image=self._photo, text="")
            except Exception as exc:  # noqa: BLE001 - keep the window alive
                self.on_error(exc)
        try:
            self._root.after(40, self._poll)
        except self._tk.TclError:
            self._stopped = True

    def stop(self) -> None:
        super().stop()
        root = getattr(self, "_root", None)
        if root is None:
            return
        try:
            if root.winfo_exists():
                root.destroy()
        except Exception:  # noqa: BLE001 - already destroyed or tkinter gone
            pass


def pick_backend(no_preview: bool) -> tuple[str | None, Preview | None]:
    """Choose the best installed preview backend, or explain why there is none."""
    if no_preview:
        print("live preview: disabled by --no-preview; frames are still saved to disk")
        return None, None
    try:
        import tkinter  # noqa: F401

        import PIL  # noqa: F401
    except ImportError as exc:
        print(
            "live preview: unavailable "
            f"({exc.__class__.__name__}: {exc}) — install with `pip install pillow` "
            "(Tk is included with this Python). Recording continues to work normally."
        )
        return None, None
    backend = TkPreview("PRISM live", "Waiting for the phone to connect and send frames…")
    print("live preview: enabled (Pillow + Tk); close the window or press Ctrl+C to stop")
    return "pillow+tk", backend


class ReceiverRun:
    """Owns the accepted sockets so a preview window or Ctrl+C can close them cleanly."""

    def __init__(self, host: str, port: int, out_dir: Path, preview: Preview | None) -> None:
        self.host = host
        self.port = port
        self.out_dir = out_dir
        self.preview = preview
        self._stopped = threading.Event()

    def stop(self) -> None:
        self._stopped.set()
        if self.preview is not None:
            self.preview.stop()

    def serve(self) -> None:
        out_dir = self.out_dir
        out_dir.mkdir(parents=True, exist_ok=True)
        self._stopped.clear()
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
                server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server.bind((self.host, self.port))
                server.listen(1)
                log(f"listening on {self.host}:{self.port}; output {out_dir.resolve()}")
                log("enter this address on the phone (emulator: 10.0.2.2:47777)")
                while not self._stopped.is_set():
                    try:
                        conn, addr = server.accept()
                    except OSError:
                        break
                    with conn:
                        conn.settimeout(30.0)
                        log(f"connection from {addr[0]}:{addr[1]}")
                        with conn.makefile("rb") as stream:
                            try:
                                hello = handshake(conn, stream)
                                conn.settimeout(None)
                                run_session(conn, stream, hello, out_dir, self.preview)
                            except (ConnectionError, ValueError, json.JSONDecodeError) as exc:
                                log(f"session rejected: {exc}")
                                try:
                                    send_json(conn, {"type": "error", "message": str(exc)})
                                except OSError:
                                    pass
                    if self._stopped.is_set():
                        break
                    log("waiting for another phone to connect (Ctrl+C to stop)")
        except OSError as exc:
            if not self._stopped.is_set():
                log(f"socket error: {exc}")


def run_session(
    conn: socket.socket,
    stream,
    hello: dict,
    out_dir: Path,
    preview: Preview | None = None,
) -> None:
    frames_dir = out_dir / "frames"
    frames_dir.mkdir(parents=True, exist_ok=True)
    imu_path = out_dir / "imu.jsonl"

    frames = 0
    imu_lines = 0
    bytes_in = 0
    last_report = time.monotonic()
    started = time.monotonic()
    client = hello.get("device") or "unknown device"
    log(f"client: {client} (app {hello.get('appVersion', '?')})")

    with imu_path.open("w", encoding="utf-8") as imu_file:
        while True:
            line = read_line(stream)
            if line is None:
                log("client closed the connection")
                break
            bytes_in += len(line)
            try:
                msg = json.loads(line.decode("utf-8"))
            except json.JSONDecodeError:
                log("ignoring a control line that was not valid JSON")
                continue

            kind = msg.get("type")

            if kind == "ping":
                # Echo the id and the sender's timestamp unchanged; the phone computes its own RTT.
                send_json(conn, {"type": "pong", "id": msg.get("id"), "t": msg.get("t")})

            elif kind == "imu":
                imu_file.write(line.decode("utf-8"))
                imu_lines += 1

            elif kind == "frame":
                size = msg.get("bytes")
                if not isinstance(size, int) or size <= 0 or size > MAX_FRAME_BYTES:
                    raise ValueError(f"invalid frame byte count: {size!r}")
                payload = stream.read(size)
                if payload is None or len(payload) != size:
                    raise ConnectionError("connection ended in the middle of a frame")
                bytes_in += size
                index = msg.get("n", frames + 1)
                frame_path = frames_dir / f"frame_{index:08d}.jpg"
                frame_path.write_bytes(payload)
                frames += 1
                # Preview is best-effort: it must never affect framing, timing, or saving.
                if preview is not None:
                    preview.push(payload)

            else:
                # Unknown message types are ignored on purpose so a newer sender stays usable.
                log(f"ignoring unknown message type {kind!r}")

            now = time.monotonic()
            if now - last_report >= 5.0:
                elapsed = max(now - started, 1e-6)
                log(
                    f"frames={frames} ({frames / elapsed:.1f}/s) "
                    f"imu={imu_lines} received={bytes_in / 1_048_576:.1f} MiB"
                )
                last_report = now

    elapsed = max(time.monotonic() - started, 1e-6)
    log(
        f"session ended: {frames} frames, {imu_lines} IMU lines, "
        f"{bytes_in / 1_048_576:.1f} MiB in {elapsed:.1f} s"
    )
    log(f"frames: {frames_dir}")
    log(f"imu:    {imu_path}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Reference PRISM Link v1 receiver")
    parser.add_argument("--host", default="0.0.0.0", help="interface to bind (default: all)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"TCP port (default: {DEFAULT_PORT})")
    parser.add_argument("--out", default="prism-live", help="output directory (default: ./prism-live)")
    parser.add_argument(
        "--no-preview",
        action="store_true",
        help="record to disk without a live preview window",
    )
    args = parser.parse_args(argv)

    backend_name, preview = pick_backend(args.no_preview)

    run = ReceiverRun(args.host, args.port, Path(args.out), preview)
    receiver_thread = threading.Thread(target=run.serve, name="prism-receive", daemon=True)
    receiver_thread.start()

    try:
        if preview is not None:
            preview.run()  # blocks until the window is closed; receiver thread does the rest
        else:
            while receiver_thread.is_alive():
                time.sleep(0.2)
    except KeyboardInterrupt:
        log("stopped (Ctrl+C)")
    finally:
        run.stop()
        if preview is not None:
            preview.stop()
        receiver_thread.join(timeout=2.0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
