package com.nevcortex.prism.link

import android.os.SystemClock
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.core.util.Time
import com.nevcortex.prism.model.ImuSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android side of PRISM Link v1: a TCP client to a compatible local receiver.
 *
 * Reliability properties:
 *  - The app never assumes a receiver is present. No discovery is attempted.
 *  - A completed HELLO/WELCOME exchange is reported as CONNECTED; STREAMING requires recent
 *    camera frames, and STALE is shown when frames stop while the socket stays open.
 *  - Camera frames are bounded to the newest pending frame (no stale-frame backlog).
 *  - Reconnects are attempted a finite number of times and every failure is surfaced.
 *  - End-to-end delay is NOT claimed: the only latency metric is TCP ping round-trip time.
 */
class LinkClient(
    private val deviceManufacturer: String,
    private val deviceModel: String,
    private val appVersion: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val counters = Counters()
    private val imuQueue = Channel<ByteArray>(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val frameSignal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pendingFrame = AtomicReference<FramePacket?>(null)
    private val pendingPings = ConcurrentHashMap<Long, Long>()

    @Volatile private var socket: Socket? = null
    @Volatile private var connected = false
    @Volatile private var manualClose = false
    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var lastImuAtMs = 0L
    @Volatile private var lastPongAtMs = 0L
    @Volatile private var nextPingId = 1L
    private val nextFrameIndex = AtomicLong(0)

    private var connectionJob: Job? = null
    private var tickerJob: Job? = null

    /** Seed the UI with a persisted endpoint without connecting. */
    fun configure(host: String, port: Int) {
        if (connected || connectionJob?.isActive == true) return
        val cleanHost = host.trim()
        _status.update {
            it.copy(
                host = cleanHost,
                port = port,
                phase = if (cleanHost.isBlank()) LinkPhase.NOT_CONFIGURED else LinkPhase.READY,
                failure = LinkFailure.NONE,
                failureDetail = null,
                technicalDetail = null
            )
        }
    }

    fun connect(host: String, port: Int) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            _status.update {
                it.copy(
                    host = "",
                    port = port,
                    phase = LinkPhase.DISCONNECTED,
                    failure = LinkFailure.NO_HOST,
                    failureDetail = null,
                    technicalDetail = null
                )
            }
            return
        }

        manualClose = false
        // Unblock the previous reader before waiting for its job to finish. The old connection
        // owns its own Socket instance, so closing it here cannot close the new one.
        val previous = connectionJob
        previous?.cancel()
        tickerJob?.cancel()
        tickerJob = null
        closeSocket()
        connected = false
        resetMediaState()

        _status.update {
            it.copy(
                host = cleanHost,
                port = port,
                phase = LinkPhase.CONNECTING,
                failure = LinkFailure.NONE,
                failureDetail = null,
                technicalDetail = null,
                protocol = null,
                receiverName = null,
                connectedAtElapsedMs = 0L,
                lastFrameAtElapsedMs = 0L,
                lastImuAtElapsedMs = 0L,
                lastPongAtElapsedMs = 0L,
                roundTripMs = -1L,
                framesSent = 0L,
                imuSamplesSent = 0L,
                bytesSent = 0L,
                droppedFrames = 0L,
                measuredFps = 0f,
                reconnectAttempts = 0
            )
        }
        // Open the new socket only after the previous job has finished. That prevents an old
        // connection's cleanup from marking the new one disconnected.
        connectionJob = scope.launch {
            previous?.cancelAndJoin()
            runConnection(cleanHost, port)
        }
    }

    /** User-initiated stop. Reports DISCONNECTED rather than pretending the link is still "ready". */
    fun disconnect() {
        manualClose = true
        tickerJob?.cancel()
        connectionJob?.cancel()
        connectionJob = null
        connected = false
        closeSocket()
        _status.update {
            it.copy(
                phase = if (it.host.isBlank()) LinkPhase.NOT_CONFIGURED else LinkPhase.DISCONNECTED,
                failure = LinkFailure.NONE,
                failureDetail = null,
                connectedAtElapsedMs = 0L,
                lastFrameAtElapsedMs = 0L,
                lastImuAtElapsedMs = 0L,
                measuredFps = 0f
            )
        }
    }

    /** Queue a JPEG frame. Only the newest frame is kept; an older pending frame is counted as dropped. */
    fun offerFrame(
        jpeg: ByteArray,
        cameraTimestampNs: Long,
        arrivalElapsedNs: Long,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Boolean {
        if (!connected) return false
        val index = nextFrameIndex.incrementAndGet()
        val header = LinkProtocol.frameHeader(
            index = index,
            cameraTimestampNs = cameraTimestampNs,
            arrivalElapsedNs = arrivalElapsedNs,
            width = width,
            height = height,
            rotationDegrees = rotationDegrees,
            bytes = jpeg.size
        ).toByteArray(Charsets.UTF_8)
        val packet = FramePacket(header, jpeg)
        val previous = pendingFrame.getAndSet(packet)
        if (previous != null) counters.droppedFrames.incrementAndGet()
        frameSignal.trySend(Unit)
        return true
    }

    fun offerImu(sample: ImuSample): Boolean {
        if (!connected) return false
        val line = LinkProtocol.imuLine(sample).toByteArray(Charsets.UTF_8)
        return imuQueue.trySend(line).isSuccess
    }

    private suspend fun runConnection(host: String, port: Int) {
        var attempt = 0
        while (currentCoroutineContext().isActive && !manualClose) {
            if (attempt > 0) {
                _status.update {
                    it.copy(
                        phase = LinkPhase.RECONNECTING,
                        reconnectAttempts = attempt
                    )
                }
                delay(minOf(5_000L, 1_000L * attempt))
            }
            try {
                openAndRun(host, port)
                if (manualClose || !currentCoroutineContext().isActive) return
                throw ServerClosedException()
            } catch (c: CancellationException) {
                throw c
            } catch (u: UnsupportedReceiverException) {
                _status.update {
                    it.copy(
                        phase = LinkPhase.UNSUPPORTED,
                        failure = u.failure,
                        failureDetail = u.message,
                        technicalDetail = u.technical
                    )
                }
                return
            } catch (t: Throwable) {
                if (manualClose || !currentCoroutineContext().isActive) return
                val failure = classify(t)
                if (attempt >= MAX_RECONNECTS) {
                    _status.update {
                        it.copy(
                            phase = LinkPhase.DISCONNECTED,
                            failure = failure,
                            failureDetail = null,
                            technicalDetail = t.toString(),
                            connectedAtElapsedMs = 0L,
                            measuredFps = 0f
                        )
                    }
                    Logger.w(TAG, "link failed permanently: $failure", t)
                    return
                }
                _status.update {
                    it.copy(
                        phase = LinkPhase.INTERRUPTED,
                        failure = failure,
                        failureDetail = null,
                        technicalDetail = t.toString(),
                        connectedAtElapsedMs = 0L,
                        measuredFps = 0f
                    )
                }
                Logger.w(TAG, "link interrupted; will retry", t)
                attempt++
            }
        }
    }

    private suspend fun openAndRun(host: String, port: Int) {
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.soTimeout = HANDSHAKE_TIMEOUT_MS

            val out = BufferedOutputStream(s.getOutputStream())
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

            val hello = LinkProtocol.hello(deviceManufacturer, deviceModel, appVersion)
            writeLine(out, hello.toByteArray(Charsets.UTF_8))
            out.flush()

            val welcomeLine = reader.readLine()
                ?: throw UnsupportedReceiverException(
                    LinkFailure.HANDSHAKE_REJECTED,
                    "The PC closed the connection before completing the PRISM handshake.",
                    "EOF during handshake"
                )
            val welcome = LinkProtocol.parse(welcomeLine)
                ?: throw UnsupportedReceiverException(
                    LinkFailure.HANDSHAKE_REJECTED,
                    "The PC answered, but not with a PRISM handshake.",
                    welcomeLine.take(240)
                )
            if (welcome.type != LinkProtocol.TYPE_WELCOME) {
                throw UnsupportedReceiverException(
                    LinkFailure.HANDSHAKE_REJECTED,
                    "The PC answered, but not with a PRISM handshake.",
                    welcomeLine.take(240)
                )
            }
            if (welcome.protocol != LinkProtocol.NAME) {
                throw UnsupportedReceiverException(
                    LinkFailure.PROTOCOL_MISMATCH,
                    "The PC is running a different receiver protocol.",
                    "protocol=${welcome.protocol}"
                )
            }
            if (welcome.version != LinkProtocol.VERSION) {
                throw UnsupportedReceiverException(
                    LinkFailure.PROTOCOL_MISMATCH,
                    "The PC receiver speaks protocol version ${welcome.version}; this app needs version ${LinkProtocol.VERSION}.",
                    "version=${welcome.version}"
                )
            }

            s.soTimeout = 0
            connected = true
            lastPongAtMs = SystemClock.elapsedRealtime()
            counters.reset()
            pendingPings.clear()
            nextPingId = 1L
            nextFrameIndex.set(0L)

            val now = SystemClock.elapsedRealtime()
            _status.update {
                it.copy(
                    phase = LinkPhase.CONNECTED,
                    failure = LinkFailure.NONE,
                    failureDetail = null,
                    technicalDetail = null,
                    protocol = "${welcome.protocol} v${welcome.version}",
                    receiverName = welcome.receiver,
                    connectedAtElapsedMs = now,
                    lastPongAtElapsedMs = now,
                    roundTripMs = -1L,
                    reconnectAttempts = 0
                )
            }
            Logger.i(TAG, "link connected to $host:$port (${welcome.receiver ?: "unnamed receiver"})")

            tickerJob?.cancel()
            tickerJob = scope.launch { statusTicker() }

            kotlinx.coroutines.coroutineScope {
                val writerJob = launch(Dispatchers.IO) { writeLoop(out) }
                val readerJob = launch(Dispatchers.IO) { readLoop(reader) }
                select<Unit> {
                    writerJob.onJoin { }
                    readerJob.onJoin { }
                }
                writerJob.cancel()
                readerJob.cancel()
                writerJob.join()
                readerJob.join()
            }
        } finally {
            connected = false
            tickerJob?.cancel()
            tickerJob = null
            // Only clear the shared reference if it still points at this connection's socket.
            if (socket === s) socket = null
            runCatching { s.close() }
        }
    }

    private suspend fun writeLoop(out: OutputStream) {
        var lastPingAt = 0L
        while (currentCoroutineContext().isActive) {
            val now = Time.elapsedNanos()
            if (now - lastPingAt >= PING_INTERVAL_NS) {
                val id = nextPingId++
                pendingPings[id] = now
                writeLine(out, LinkProtocol.pingLine(id, now).toByteArray(Charsets.UTF_8))
                out.flush()
                lastPingAt = now
                // Do not let an unresponsive receiver grow the pending map without bound.
                if (pendingPings.size > 64) {
                    pendingPings.keys.take(pendingPings.size - 64).forEach { pendingPings.remove(it) }
                }
            }

            var firstImu: ByteArray? = null
            val signal = withTimeoutOrNull(IDLE_SELECT_MS) {
                select<Int> {
                    frameSignal.onReceive { 0 }
                    imuQueue.onReceive { line ->
                        firstImu = line
                        1
                    }
                }
            }
            if (signal == null) continue

            firstImu?.let { line ->
                writeLine(out, line)
                counters.imuSent.incrementAndGet()
                counters.bytesAdded(line.size + 1L)
                lastImuAtMs = SystemClock.elapsedRealtime()
            }

            // Newest camera frame wins. A frame queued behind it is intentionally discarded.
            pendingFrame.getAndSet(null)?.let { frame ->
                writeLine(out, frame.header)
                out.write(frame.jpeg)
                counters.framesSent.incrementAndGet()
                counters.bytesAdded(frame.header.size + 1L + frame.jpeg.size)
                lastFrameAtMs = SystemClock.elapsedRealtime()
            }

            // Drain a bounded IMU backlog so a camera frame is never stuck behind seconds of IMU.
            repeat(64) {
                val line = imuQueue.tryReceive().getOrNull() ?: return@repeat
                writeLine(out, line)
                counters.imuSent.incrementAndGet()
                counters.bytesAdded(line.size + 1L)
                lastImuAtMs = SystemClock.elapsedRealtime()
            }
            out.flush()
        }
    }

    private fun readLoop(reader: BufferedReader) {
        while (connected) {
            val line = reader.readLine() ?: return
            val msg = LinkProtocol.parse(line) ?: continue
            when (msg.type) {
                LinkProtocol.TYPE_PONG -> {
                    lastPongAtMs = SystemClock.elapsedRealtime()
                    msg.id?.let { id ->
                        val sentAt = pendingPings.remove(id)
                        if (sentAt != null) {
                            val rttMs = ((Time.elapsedNanos() - sentAt) / 1_000_000L).coerceAtLeast(0L)
                            _status.update { it.copy(roundTripMs = rttMs, lastPongAtElapsedMs = lastPongAtMs) }
                        }
                    }
                }
                LinkProtocol.TYPE_ERROR -> {
                    throw IOException(msg.message ?: "PC receiver reported an error")
                }
                else -> Unit
            }
        }
    }

    private suspend fun statusTicker() {
        var lastStatsAtMs = SystemClock.elapsedRealtime()
        var lastFramesSent = 0L
        while (currentCoroutineContext().isActive) {
            delay(500L)
            if (!connected) continue
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastPongAtMs > HEARTBEAT_TIMEOUT_MS) {
                _status.update {
                    it.copy(
                        phase = LinkPhase.INTERRUPTED,
                        failure = LinkFailure.SERVER_CLOSED,
                        technicalDetail = "No heartbeat response for ${nowMs - lastPongAtMs} ms"
                    )
                }
                closeSocket()
                return
            }

            val frames = counters.framesSent.get()
            val dt = (nowMs - lastStatsAtMs).coerceAtLeast(1L)
            val measuredFps = ((frames - lastFramesSent) * 1000f / dt)
            lastStatsAtMs = nowMs
            lastFramesSent = frames

            val stale = frames > 0L && lastFrameAtMs > 0L && nowMs - lastFrameAtMs > DATA_STALE_MS
            val phase = when {
                stale -> LinkPhase.STALE
                frames > 0L -> LinkPhase.STREAMING
                else -> LinkPhase.CONNECTED
            }
            _status.update {
                it.copy(
                    phase = phase,
                    framesSent = frames,
                    imuSamplesSent = counters.imuSent.get(),
                    bytesSent = counters.bytesSent.get(),
                    droppedFrames = counters.droppedFrames.get(),
                    measuredFps = if (stale) 0f else measuredFps,
                    lastFrameAtElapsedMs = lastFrameAtMs,
                    lastImuAtElapsedMs = lastImuAtMs,
                    reconnectAttempts = 0
                )
            }
        }
    }

    private fun writeLine(out: OutputStream, line: ByteArray) {
        out.write(line)
        out.write('\n'.code)
    }

    private fun resetMediaState() {
        counters.reset()
        pendingFrame.set(null)
        pendingPings.clear()
        while (frameSignal.tryReceive().isSuccess) Unit
        while (imuQueue.tryReceive().isSuccess) Unit
        lastFrameAtMs = 0L
        lastImuAtMs = 0L
        lastPongAtMs = 0L
        nextFrameIndex.set(0L)
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun classify(t: Throwable): LinkFailure = when (t) {
        is UnsupportedReceiverException -> t.failure
        is ConnectException -> LinkFailure.CONNECTION_REFUSED
        is SocketTimeoutException -> LinkFailure.TIMEOUT
        is NoRouteToHostException -> LinkFailure.HOST_UNREACHABLE
        is UnknownHostException -> LinkFailure.BAD_ADDRESS
        is ServerClosedException -> LinkFailure.SERVER_CLOSED
        is SocketException -> LinkFailure.IO_ERROR
        is IOException -> LinkFailure.IO_ERROR
        else -> LinkFailure.UNKNOWN
    }

    private class FramePacket(val header: ByteArray, val jpeg: ByteArray)

    private class ServerClosedException : IOException("The PC receiver closed the connection")

    private class UnsupportedReceiverException(
        val failure: LinkFailure,
        message: String,
        val technical: String?
    ) : IOException(message)

    private class Counters {
        val framesSent = AtomicLong(0)
        val imuSent = AtomicLong(0)
        val bytesSent = AtomicLong(0)
        val droppedFrames = AtomicLong(0)

        fun bytesAdded(n: Long) {
            bytesSent.addAndGet(n)
        }

        fun reset() {
            framesSent.set(0)
            imuSent.set(0)
            bytesSent.set(0)
            droppedFrames.set(0)
        }
    }

    companion object {
        private const val TAG = "PrismLink"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val PING_INTERVAL_NS = 2_000_000_000L
        private const val HEARTBEAT_TIMEOUT_MS = 6_000L
        private const val DATA_STALE_MS = 3_000L
        private const val IDLE_SELECT_MS = 250L
        private const val MAX_RECONNECTS = 3
    }
}
