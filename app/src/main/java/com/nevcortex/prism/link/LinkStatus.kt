package com.nevcortex.prism.link

/**
 * Explicit network states. A completed handshake is [CONNECTED]; [STREAMING] additionally
 * requires recent camera frames. Nothing collapses these into one green "connected" light.
 */
enum class LinkPhase {
    NOT_CONFIGURED,
    READY,
    CONNECTING,
    CONNECTED,
    STREAMING,
    INTERRUPTED,
    RECONNECTING,
    DISCONNECTED,
    UNSUPPORTED,
    STALE
}

/** Machine-readable failure reasons so the UI can show plain language and keep details separate. */
enum class LinkFailure {
    NONE,
    NO_HOST,
    BAD_ADDRESS,
    CONNECTION_REFUSED,
    TIMEOUT,
    HOST_UNREACHABLE,
    HANDSHAKE_REJECTED,
    PROTOCOL_MISMATCH,
    SERVER_CLOSED,
    IO_ERROR,
    CAMERA_UNAVAILABLE,
    SENSOR_UNAVAILABLE,
    UNKNOWN
}

data class LinkStatus(
    val phase: LinkPhase = LinkPhase.NOT_CONFIGURED,
    val host: String = "",
    val port: Int = LinkProtocol.DEFAULT_PORT,
    val failure: LinkFailure = LinkFailure.NONE,
    /** Detailed, user-safe explanation. Raw exception text is kept in [technicalDetail]. */
    val failureDetail: String? = null,
    val technicalDetail: String? = null,
    val protocol: String? = null,
    val receiverName: String? = null,
    val connectedAtElapsedMs: Long = 0L,
    val lastFrameAtElapsedMs: Long = 0L,
    val lastImuAtElapsedMs: Long = 0L,
    val lastPongAtElapsedMs: Long = 0L,
    val roundTripMs: Long = -1L,
    val framesSent: Long = 0L,
    val imuSamplesSent: Long = 0L,
    val bytesSent: Long = 0L,
    val droppedFrames: Long = 0L,
    val measuredFps: Float = 0f,
    val reconnectAttempts: Int = 0
) {
    val hasHost: Boolean get() = host.isNotBlank()
    val isLive: Boolean
        get() = phase == LinkPhase.CONNECTED ||
            phase == LinkPhase.STREAMING ||
            phase == LinkPhase.STALE
}
