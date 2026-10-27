#!/usr/bin/env python3
"""Focused tests for tools/prism_receiver.py (stdlib unittest).

They drive the receiver over real loopback sockets in the same shape as run_session callers:
hello -> welcome, ping -> pong echo, imu lines appended, frame header + exact payload read.
They verify that disk recording is unaffected by preview availability, that a preview fault
does not stop the receive loop, and that the latest-frame slot is newest-wins (no stale queue).

Run from the repo root:
    python -m unittest discover -s tools -p "test_*.py" -v
"""

from __future__ import annotations

import io
import json
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import prism_receiver as rx  # noqa: E402


HELLO = json.dumps({
    "type": "hello",
    "protocol": "prism-link",
    "version": 1,
    "device": "test phone",
    "appVersion": "test",
}).encode() + b"\n"


try:
    import PIL  # noqa: F401
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False


def _tiny_jpeg() -> bytes:
    """A real 2x2 white JPEG (produced when Pillow is installed)."""
    from PIL import Image

    buf = io.BytesIO()
    Image.new("RGB", (2, 2), (255, 255, 255)).save(buf, format="JPEG")
    return buf.getvalue()


class RecordingSlot:
    """Slot stub that records every push so tests can assert exact payloads."""

    def __init__(self) -> None:
        self.frames: list[bytes] = []
        self.closed = False

    def push(self, jpeg: bytes) -> None:
        if not self.closed:
            self.frames.append(jpeg)

    def take(self) -> None:
        return None

    def close(self) -> None:
        self.closed = True


class ExplodeSlot:
    """Slot stub whose push always raises, to prove the receive loop survives preview faults."""

    def __init__(self) -> None:
        self.errors = 0
        self.closed = False

    def push(self, jpeg: bytes) -> None:
        self.errors += 1
        raise RuntimeError("preview blew up on purpose")

    def take(self) -> None:
        return None

    def close(self) -> None:
        self.closed = True


class ReceiverHarness:
    """Binds run_session to a connected socketpair, structured exactly like serve() calls it."""

    def __init__(self, preview=None):
        with tempfile.TemporaryDirectory() as _:
            pass
        self.out = Path(tempfile.mkdtemp(prefix="prism-receiver-test-"))
        self.preview = preview
        self.received = []
        pair = socket.socketpair()
        self.client, self.conn = pair[0], pair[1]
        self.error: BaseException | None = None
        self.done = threading.Event()
        self.thread = threading.Thread(target=self._run_thread, daemon=True)

    def _run_thread(self):
        try:
            with self.conn:
                self.conn.settimeout(5.0)
                with self.conn.makefile("rb") as stream:
                    hello = rx.handshake(self.conn, stream)
                    # After the handshake the real serve() clears the timeout; mirror it.
                    self.conn.settimeout(None)
                    rx.run_session(self.conn, stream, hello, self.out, self.preview)
        except BaseException as exc:
            self.error = exc
        finally:
            self.done.set()

    def start(self) -> "ReceiverHarness":
        self.thread.start()
        return self

    def send_line(self, payload: dict) -> None:
        self.client.sendall(json.dumps(payload).encode() + b"\n")

    def send_frame(self, payload: bytes, index: int | None = None) -> None:
        msg = {"type": "frame", "bytes": len(payload)}
        if index is not None:
            msg["n"] = index
        self.send_line(msg)
        self.client.sendall(payload)

    def wait(self, seconds: float = 5.0) -> bool:
        return self.done.wait(seconds)


class TestHandshake(unittest.TestCase):

    def test_valid_hello_gets_welcome(self):
        harness = ReceiverHarness().start()
        harness.client.sendall(HELLO)
        reply = json.loads(harness.client.recv(1024))
        self.assertEqual(reply["type"], "welcome")
        self.assertEqual(reply["protocol"], "prism-link")
        self.assertEqual(reply["version"], 1)
        harness.client.shutdown(socket.SHUT_WR)
        self.assertTrue(harness.wait(5.0))
        self.assertIsNone(harness.error)

    def test_wrong_protocol_is_rejected(self):
        harness = ReceiverHarness().start()
        bad = json.dumps({"type": "hello", "protocol": "other", "version": 1}).encode() + b"\n"
        harness.client.sendall(bad)
        self.assertTrue(harness.wait(5.0))
        self.assertIsInstance(harness.error, ConnectionError)

    def test_wrong_version_is_rejected(self):
        harness = ReceiverHarness().start()
        bad = json.dumps({"type": "hello", "protocol": "prism-link", "version": 2}).encode() + b"\n"
        harness.client.sendall(bad)
        self.assertTrue(harness.wait(5.0))
        self.assertIsInstance(harness.error, ConnectionError)


class TestSession(unittest.TestCase):

    def _session(self, preview=None) -> ReceiverHarness:
        harness = ReceiverHarness(preview=preview).start()
        harness.client.sendall(HELLO)
        reply = json.loads(harness.client.recv(1024))
        self.assertEqual(reply["type"], "welcome")
        return harness

    def test_ping_is_echoed(self):
        harness = self._session()
        harness.send_line({"type": "ping", "id": 7, "t": 123456})
        pong = json.loads(harness.client.recv(1024))
        self.assertEqual(pong, {"type": "pong", "id": 7, "t": 123456})
        harness.client.shutdown(socket.SHUT_WR)
        self.assertTrue(harness.wait(5.0))
        self.assertIsNone(harness.error)

    def test_unknown_type_is_ignored_not_fatal(self):
        harness = self._session()
        harness.send_line({"type": "future-thing", "x": 1})
        harness.send_line({"type": "ping", "id": 9, "t": 9})
        pong = json.loads(harness.client.recv(1024))
        self.assertEqual(pong["id"], 9)
        harness.client.shutdown(socket.SHUT_WR)
        self.assertTrue(harness.wait(5.0))
        self.assertIsNone(harness.error)

    def test_frames_and_imu_are_saved_with_no_preview(self):
        harness = self._session(preview=None)
        payload = b"\xd8\xff" + b"opaque-frame-bytes" * 5
        harness.send_frame(payload, index=2)
        harness.send_line({"type": "imu", "t": 1, "gyroZ": 0.01})
        harness.client.shutdown(socket.SHUT_WR)
        self.assertTrue(harness.wait(5.0))
        self.assertIsNone(harness.error)
        frame = harness.out / "frames" / "frame_00000002.jpg"
        self.assertTrue(frame.is_file())
        self.assertEqual(frame.read_bytes(), payload)
        imu_text = (harness.out / "imu.jsonl").read_text(encoding="utf-8")
        self.assertIn('"t": 1', imu_text)
        self.assertIn("gyroZ", imu_text)
        self.assertTrue((harness.out / "frames" / "frame_00000002.jpg").is_file())

    def test_preview_receives_exact_frame_payload(self):
        preview = rx.Preview()
        preview.slot = RecordingSlot()
        harness = ReceiverHarness(preview=preview).start()
        harness.client.sendall(HELLO)
        harness.client.recv(1024)
        payload = b"FAKEJPEG" + b"x" * 32
        harness.send_frame(payload)
        harness.client.shutdown(socket.SHUT_WR)
        self.assertTrue(harness.wait(5.0))
        self.assertEqual(preview.slot.frames, [payload])
        self.assertIsNone(harness.error)

    def test_preview_exception_does_not_stop_the_receive_loop(self):
        preview = rx.Preview()
        slot = ExplodeSlot()
        preview.slot = slot
        harness = ReceiverHarness(preview=preview).start()
        harness.client.sendall(HELLO)
        harness.client.recv(1024)
        harness.send_frame(b"frame-one")
        harness.send_line({"type": "imu", "t": 2})
        harness.send_frame(b"frame-two")
        harness.client.shutdown(socket.SHUT_WR)
        self.assertTrue(harness.wait(5.0))
        # The wrapper keeps the loop alive: the session never fails, both frames reached
        # the preview push (which raised), and the IMU line was still appended.
        self.assertIsNone(harness.error)
        self.assertEqual(slot.errors, 2)
        self.assertEqual(preview.decode_errors, 2)
        imu_text = (harness.out / "imu.jsonl").read_text(encoding="utf-8")
        self.assertIn('"t": 2', imu_text)


class TestLatestFrameSlot(unittest.TestCase):

    def test_latest_wins_and_take_drains(self):
        slot = rx.LatestFrameSlot()
        slot.push(b"one")
        slot.push(b"two")
        slot.push(b"three")
        self.assertEqual(slot.take(), b"three")   # only the newest is kept
        self.assertIsNone(slot.take())            # take() marks it consumed
        slot.push(b"four")
        self.assertEqual(slot.take(), b"four")

    def test_push_after_close_is_ignored(self):
        slot = rx.LatestFrameSlot()
        slot.close()
        slot.push(b"late")
        self.assertIsNone(slot.take())


class TestPreviewBackends(unittest.TestCase):

    @unittest.skipUnless(PIL_AVAILABLE, "Pillow is not installed; preview tests need it")
    def test_tk_backend_decodes_a_real_jpeg(self):
        backend = rx.TkPreview("PRISM test", "waiting")
        try:
            backend.slot.push(_tiny_jpeg())
            backend._poll()  # one manual poll step; the mainloop is not entered
            self.assertEqual(backend.decode_errors, 0)
            self.assertIsNotNone(backend._photo)
            self.assertTrue(
                str(backend._label.cget("image")).startswith("pyimage"),
                "the decoded frame was not attached to the label",
            )
        finally:
            backend._on_close()

    def test_pick_backend_reports_disabled(self):
        name, preview = rx.pick_backend(no_preview=True)
        self.assertIsNone(name)
        self.assertIsNone(preview)

    def test_pick_backend_works_with_pillow_installed(self):
        if not PIL_AVAILABLE:
            self.skipTest("Pillow is not installed")
        backend_name, preview = rx.pick_backend(no_preview=False)
        self.assertEqual(backend_name, "pillow+tk")
        self.assertIsNotNone(preview)
        preview.stop()  # construct-and-stop; no window interaction claimed


if __name__ == "__main__":
    unittest.main()
