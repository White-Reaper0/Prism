package com.nevcortex.prism.link

import com.nevcortex.prism.model.ImuSample
import com.nevcortex.prism.model.SensorKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for PRISM Link v1. These run on the JVM and pin the exact field names that
 * `tools/prism_receiver.py` reads, so a rename cannot silently break the receiver.
 */
class LinkProtocolTest {

    @Test
    fun `hello names the protocol and version`() {
        val hello = JSONObject(
            LinkProtocol.hello(
                deviceManufacturer = "OnePlus",
                deviceModel = "Nord 4",
                appVersion = "0.2.0-live"
            )
        )

        assertEquals("hello", hello.getString("type"))
        assertEquals("prism-link", hello.getString("protocol"))
        assertEquals(1, hello.getInt("version"))
        assertEquals("OnePlus Nord 4", hello.getString("device"))
        assertEquals("0.2.0-live", hello.getString("appVersion"))
        assertEquals("jpeg", hello.getJSONArray("streams").getString(0))
        assertEquals("imu", hello.getJSONArray("streams").getString(1))
    }

    @Test
    fun `hello omits trailing space when manufacturer is blank`() {
        val device = JSONObject(
            LinkProtocol.hello("", "Nord 4", "test")
        ).getString("device")

        assertEquals("Nord 4", device)
    }

    @Test
    fun `imu line carries both clocks raw values and sequence`() {
        val sample = ImuSample(
            kind = SensorKind.ACCELEROMETER,
            values = floatArrayOf(0.25f, -9.81f, 1.5f),
            accuracy = 3,
            sensorTimestampNs = 1_000_000_000L,
            arrivalElapsedNs = 1_010_000_000L,
            sequence = 42L
        )

        val line = LinkProtocol.imuLine(sample)
        val obj = JSONObject(line)

        assertEquals("imu", obj.getString("type"))
        assertEquals("accelerometer", obj.getString("kind"))
        assertEquals(1_000_000_000L, obj.getLong("t"))
        assertEquals(1_010_000_000L, obj.getLong("a"))
        assertEquals(42L, obj.getLong("seq"))
        assertEquals(3, obj.getInt("accuracy"))
        assertEquals(3, obj.getJSONArray("v").length())
        assertEquals(-9.81, obj.getJSONArray("v").getDouble(1), 1e-6)
        assertTrue("line must stay on one line", !line.contains('\n'))
    }

    @Test
    fun `frame header declares exact payload size and rotation`() {
        val header = JSONObject(
            LinkProtocol.frameHeader(
                index = 7L,
                cameraTimestampNs = 900_000_000L,
                arrivalElapsedNs = 910_000_000L,
                width = 960,
                height = 720,
                rotationDegrees = 90,
                bytes = 48_213
            )
        )

        assertEquals("frame", header.getString("type"))
        assertEquals(7L, header.getLong("n"))
        assertEquals(900_000_000L, header.getLong("t"))
        assertEquals(910_000_000L, header.getLong("a"))
        assertEquals(960, header.getInt("w"))
        assertEquals(720, header.getInt("h"))
        assertEquals(90, header.getInt("rotation"))
        assertEquals("jpeg", header.getString("format"))
        assertEquals(48_213, header.getInt("bytes"))
    }

    @Test
    fun `ping includes id and send timestamp`() {
        val ping = JSONObject(LinkProtocol.pingLine(id = 3L, sentAtNs = 123_456_789L))

        assertEquals("ping", ping.getString("type"))
        assertEquals(3L, ping.getLong("id"))
        assertEquals(123_456_789L, ping.getLong("t"))
    }

    @Test
    fun `parse reads a welcome reply`() {
        val msg = LinkProtocol.parse(
            """{"type":"welcome","protocol":"prism-link","version":1,"receiver":"prism-receiver-py"}"""
        )

        assertEquals("welcome", msg?.type)
        assertEquals("prism-link", msg?.protocol)
        assertEquals(1, msg?.version)
        assertEquals("prism-receiver-py", msg?.receiver)
        assertNull(msg?.message)
    }

    @Test
    fun `parse reads pong id`() {
        val msg = LinkProtocol.parse("""{"type":"pong","id":11,"t":500}""")

        assertEquals("pong", msg?.type)
        assertEquals(11L, msg?.id)
    }

    @Test
    fun `parse returns null for garbage instead of throwing`() {
        assertNull(LinkProtocol.parse("not json"))
        assertNull(LinkProtocol.parse(""))
        assertNull(LinkProtocol.parse("[1,2,3]"))
    }

    @Test
    fun `parse turns empty optional strings into null`() {
        val msg = LinkProtocol.parse("""{"type":"welcome","protocol":"","receiver":""}""")

        assertEquals("welcome", msg?.type)
        assertNull(msg?.protocol)
        assertNull(msg?.receiver)
        assertNull(msg?.id)
    }
}
