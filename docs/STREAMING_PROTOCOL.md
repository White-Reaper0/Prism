# PRISM Link v1 — live streaming protocol

PRISM Link v1 is the contract between the Android app (`com.nevcortex.prism`) and the reference
receiver in [`tools/prism_receiver.py`](../tools/prism_receiver.py). It carries a low-rate JPEG
preview and the raw motion samples that Live Studio is already reading. It does not carry pose,
localization, maps, or GPS.

This document describes what the code actually does. If the app and this document disagree, the
app is authoritative and this document is a bug.

---

## 1. Transport and scope

| Property | Value |
|---|---|
| Transport | Plain TCP, one client per receiver |
| Default port | `47777` |
| Encoding | UTF-8 JSON, one object per line, `\n` terminated |
| Camera payload | A JSON header line followed by exactly `bytes` JPEG bytes (no newline after the JPEG) |
| Discovery | **None.** The user types the PC address. No mDNS, SSDP, or broadcast is sent. |
| Security | **None.** No TLS, no authentication, no integrity check. Use a trusted LAN only. |
| Direction | Phone → PC for data; PC → phone only for `welcome`, `pong`, and `error` |

Video is a *preview*, not a recording. JPEG quality, scale, and frame rate come from the saved
quality preset (`Data saver` 640 px / 8 fps, `Balanced` 960 px / 12 fps, `Sharp` 1280 px / 15 fps).
Those are targets; the achieved frame rate is measured and shown in the app.

---

## 2. Connection sequence

1. The phone opens the TCP connection.
2. The phone sends one `hello` line.
3. The receiver must answer with one `welcome` line within 5 s.
4. The phone validates `type`, `protocol`, and `version`.
   - A wrong protocol/version is reported as **Unsupported** and is **not** retried.
   - A missing, malformed, or non-PRISM reply is reported as a rejected handshake.
5. After a valid `welcome`, the phone sends `ping`, `imu`, and `frame` messages.

The phone retries an ordinary connection failure at most **3** times, waiting 1 s, 2 s, then 3 s.
Every failure is shown in the app with a plain-language reason; raw exception text stays in
Diagnostics.

### `hello` (phone → receiver)

```json
{"type":"hello","protocol":"prism-link","version":1,"app":"PRISM Android","appVersion":"0.2.0-live","device":"OnePlus Nord 4","streams":["jpeg","imu"]}
```

`device` is the manufacturer and model reported by Android. It is informational only.

### `welcome` (receiver → phone)

```json
{"type":"welcome","protocol":"prism-link","version":1,"receiver":"prism-receiver-py"}
```

`receiver` is an optional display name. The phone shows it in Diagnostics.

### `error` (receiver → phone)

```json
{"type":"error","message":"unsupported frame size"}
```

An `error` line closes the local link and is surfaced as an interrupted connection. The message is
used as technical detail, not as normal UI copy.

---

## 3. Heartbeat

The phone sends a `ping` every **2 s**:

```json
{"type":"ping","id":17,"t":123456789012345}
```

`id` is a monotonically increasing integer. `t` is the phone's `elapsedRealtimeNanos` at send time.
The receiver must echo both fields:

```json
{"type":"pong","id":17,"t":123456789012345}
```

The phone computes `roundTripMs` from its own clock. This is **TCP round-trip delay**, not
glass-to-glass latency and not a synchronization measurement. If no `pong` arrives for **6 s**,
the phone closes the socket and reports an interruption.

---

## 4. IMU messages

One line per sample, sent on the sensor callback thread and queued for the writer:

```json
{"type":"imu","kind":"accelerometer","t":123456789012345,"a":123456799012345,"seq":412,"accuracy":3,"v":[0.12,-9.79,0.33]}
```

| Field | Meaning |
|---|---|
| `kind` | `accelerometer`, `gyroscope`, or `magnetometer` (lowercase enum names) |
| `t` | `SensorEvent.timestamp` in nanoseconds, from the sensor's own clock |
| `a` | `SystemClock.elapsedRealtimeNanos()` when the app received the event |
| `seq` | Per-stream sequence number from the collector; gaps indicate missed samples |
| `accuracy` | Android `SensorEvent.accuracy` (0–3), or `-1` if unknown |
| `v` | Raw `SensorEvent.values`, unfiltered |

Units are Android sensor units, unchanged:

- accelerometer: m/s² (includes gravity)
- gyroscope: rad/s
- magnetometer: µT

Live Studio subscribes to accelerometer, gyroscope, and magnetometer when the phone reports them,
at the saved IMU rate clamped to 50–200 Hz. Derived Android fusion streams are not sent by Live
Studio. Timestamps are not rewritten or aligned: the receiver gets both clock domains so it can
study the offset itself.

### Queue behaviour (intentional)

- The IMU queue holds 2048 lines and drops the **oldest** when full. Drops are not counted
  separately today; `seq` exposes them.
- Camera frames are not queued deeply. Only the newest pending frame is kept; replacing an older
  one increments the app's dropped-frame counter.
- A camera frame is written before draining at most 64 queued IMU lines, so video cannot be stuck
  behind a long sensor backlog. Interleaving is therefore normal and the receiver must use the
  message type, never arrival order, to tell data apart.

---

## 5. Camera frames

A frame is **one header line plus the exact number of JPEG bytes**:

```
{"type":"frame","n":231,"t":123456789012345,"a":123456799012345,"w":960,"h":720,"rotation":90,"format":"jpeg","bytes":48213}\n
<48213 raw JPEG bytes><next JSON line…>
```

There is no delimiter or padding after the JPEG. A receiver must read `bytes` from the header and
then read exactly that many bytes before parsing the next line.

| Field | Meaning |
|---|---|
| `n` | Frame index since this connection started, starting at 1 |
| `t` | CameraX/Camera2 `ImageProxy` timestamp in nanoseconds (camera/HAL clock) |
| `a` | `elapsedRealtimeNanos` when the app received the frame |
| `w`, `h` | Pixel dimensions of the encoded JPEG |
| `rotation` | Clockwise rotation in degrees (0/90/180/270) needed to display the JPEG upright |
| `format` | Always `jpeg` in v1 |
| `bytes` | Exact payload length; bounded by the app's frame size policy |

The JPEG is already scaled and compressed by the app, but it is **not** rotated: apply `rotation`
for display. Because the frame is delivered through a single TCP stream, a slow receiver throttles
the sender (backpressure) rather than silently dropping every frame; the app additionally keeps
only the newest pending frame.

Only frames encoded while the link is live are sent. Preview and motion readout continue to work
with no PC connected.

---

## 6. State shown by the app

The app deliberately separates "socket open" from "data arriving":

| State | Meaning |
|---|---|
| **Not configured** | No PC address saved yet |
| **Ready** | Address saved, not connected |
| **Connecting** | TCP connect or handshake in progress |
| **Connected** | Handshake succeeded; no recent camera frame yet |
| **Streaming** | Handshake succeeded **and** at least one frame was sent recently |
| **Stale** | Frames were sent, then stopped for more than 3 s while the socket stays open |
| **Interrupted** | Heartbeat or socket failed; bounded reconnect may follow |
| **Reconnecting** | A retry is scheduled or running |
| **Unsupported** | The receiver is not PRISM Link v1; no retry |
| **Disconnected** | Stopped by the user, or retries exhausted |

`measuredFps` is computed from frames actually written over a 500 ms window. `roundTripMs` is the
last heartbeat RTT. The app never labels either one as end-to-end latency.

---

## 7. Running the reference receiver

Requirements: Python 3.9+ (standard library only). No packages to install.

```powershell
# Windows PowerShell, from the repository root
python tools\prism_receiver.py --out C:\Users\you\prism-live
```

```bash
# macOS / Linux
python3 tools/prism_receiver.py --out ~/prism-live
```

Options:

| Flag | Default | Meaning |
|---|---|---|
| `--host` | `0.0.0.0` | Interface to bind. Binding all interfaces is required for a phone on the LAN. |
| `--port` | `47777` | TCP port; must match the app's setting |
| `--out` | `prism-live` | Output directory |

Output:

```
prism-live/
  frames/frame_00000001.jpg
  frames/frame_00000002.jpg
  imu.jsonl                 # one JSON object per IMU line, exactly as received
```

The receiver prints a line every 5 s with frames, IMU lines, and bytes received. It accepts one
connection at a time; restart it for a new session. It does not decode, display, or synchronize
the streams.

### Finding the right address

- **Android emulator:** use `10.0.2.2` (the host loopback) and port `47777`.
- **Physical phone:** use the PC's LAN IPv4 address, for example `192.168.1.42`. Check with
  `ipconfig` on Windows or `ip addr` on Linux.
- Both devices must be on the same network, and the PC firewall must allow inbound TCP `47777`
  for Python. On Windows, allow it on **Private** networks only.
- A phone on mobile data cannot reach a PC on the home LAN without a tunnel; that setup is out of
  scope and untested.

If the app shows **Connection refused**, the receiver is not running or the port is wrong.
**Host unreachable / timed out** usually means the address is wrong, the devices are on different
networks, or the firewall is blocking the port.

---

## 8. What this protocol does not do

- No device discovery, pairing, or authentication.
- No TLS or encryption. Anyone on the LAN who can reach the port can read the stream.
- No retransmission log, recording, or file format. The app records separately through its own
  foreground recording service; streaming and synchronized recording cannot run at the same time
  because both need exclusive camera ownership.
- No claim of end-to-end latency, camera/IMU hardware synchronization, metric scale, localization,
  SLAM, or GPS.
- No on-device VIO or pose output. ARCore tracking diagnostics stay in the app and are never sent
  as pose data by this protocol.
- No backpressure policy beyond TCP and the newest-frame rule; a receiver that stops reading will
  stall the writer and eventually trip the heartbeat timeout.
