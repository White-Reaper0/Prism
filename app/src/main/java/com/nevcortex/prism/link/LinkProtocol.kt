package com.nevcortex.prism.link

import com.nevcortex.prism.model.ImuSample
import org.json.JSONObject
import java.util.Locale

/**
 * PRISM Link v1 — the wire contract between this Android app and the PC receiver shipped in
 * `tools/prism_receiver.py`.
 *
 * Transport: plain TCP. One UTF-8 JSON object per line for control/metadata. A camera frame is a
 * JSON header line followed by exactly `bytes` JPEG bytes. There is no authentication or
 * encryption: use it only on a trusted local network. Nothing here claims that a discovery
 * service exists — the user enters the PC address explicitly.
 */
object LinkProtocol {

    const val NAME = "prism-link"
    const val VERSION = 1
    const val DEFAULT_PORT = 47777

    const val TYPE_HELLO = "hello"
    const val TYPE_WELCOME = "welcome"
    const val TYPE_IMU = "imu"
    const val TYPE_FRAME = "frame"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_ERROR = "error"

    fun hello(
        deviceManufacturer: String,
        deviceModel: String,
        appVersion: String,
        streams: List<String> = listOf("jpeg", "imu")
    ): String = JSONObject()
        .put("type", TYPE_HELLO)
        .put("protocol", NAME)
        .put("version", VERSION)
        .put("app", "PRISM Android")
        .put("appVersion", appVersion)
        .put("device", "$deviceManufacturer $deviceModel".trim())
        .put("streams", org.json.JSONArray(streams))
        .toString()

    /** One line per IMU sample. Units are the Android sensor units stated in the docs. */
    fun imuLine(sample: ImuSample): String = JSONObject()
        .put("type", TYPE_IMU)
        .put("kind", sample.kind.name.lowercase(Locale.ROOT))
        .put("t", sample.sensorTimestampNs)
        .put("a", sample.arrivalElapsedNs)
        .put("seq", sample.sequence)
        .put("accuracy", sample.accuracy)
        .put("v", org.json.JSONArray(sample.values.map { it.toDouble() }))
        .toString()

    /**
     * JPEG frame header. `t` is the camera-HAL timestamp, `a` the elapsedRealtime arrival stamp,
     * and [rotationDegrees] tells the receiver how the JPEG should be rotated for display.
     */
    fun frameHeader(
        index: Long,
        cameraTimestampNs: Long,
        arrivalElapsedNs: Long,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        bytes: Int
    ): String = JSONObject()
        .put("type", TYPE_FRAME)
        .put("n", index)
        .put("t", cameraTimestampNs)
        .put("a", arrivalElapsedNs)
        .put("w", width)
        .put("h", height)
        .put("rotation", rotationDegrees)
        .put("format", "jpeg")
        .put("bytes", bytes)
        .toString()

    fun pingLine(id: Long, sentAtNs: Long): String = JSONObject()
        .put("type", TYPE_PING)
        .put("id", id)
        .put("t", sentAtNs)
        .toString()

    fun parse(line: String): ServerMessage? = runCatching {
        val o = JSONObject(line)
        ServerMessage(
            type = o.optString("type"),
            protocol = o.optString("protocol").ifBlank { null },
            version = o.optInt("version", -1),
            receiver = o.optString("receiver").ifBlank { null },
            id = if (o.has("id")) o.optLong("id") else null,
            message = o.optString("message").ifBlank { null }
        )
    }.getOrNull()
}

data class ServerMessage(
    val type: String,
    val protocol: String? = null,
    val version: Int = -1,
    val receiver: String? = null,
    val id: Long? = null,
    val message: String? = null
)
