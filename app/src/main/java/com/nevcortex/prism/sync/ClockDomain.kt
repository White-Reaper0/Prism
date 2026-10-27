package com.nevcortex.prism.sync

/**
 * Documented clock domains. Every recorded timestamp belongs to one of these. The alignment clock
 * ([Domain.ELAPSED]) is the reference; the offset between each source clock and the alignment
 * clock is MEASURED, not assumed.
 *
 * On Android since API 19, sensor timestamps are in the [SystemClock.elapsedRealtimeNanos]
 * timebase. Camera HAL timestamps are produced by the camera driver and historically are relative
 * to camera-open / a different base; on some devices they align with the sensor timebase, on
 * others they do not. We record the observed offset and only mark [Domain] clocks as aligned when
 * a controlled test proves it.
 */
enum class Domain {
    /** [android.os.SystemClock.elapsedRealtimeNanos] — boot-since, monotonic, excludes deep sleep. */
    ELAPSED,
    /** Sensor event timestamp ([SensorEvent.timestamp]). */
    SENSOR,
    /** Camera HAL frame timestamp ([ImageProxy.imageInfo.timestamp]). */
    CAMERA,
    /** [System.nanoTime] — boot-since including deep sleep. */
    UPTIME,
    /** [System.currentTimeMillis] — wall clock, metadata only. */
    WALL
}

/** A measured relationship between a source clock and the alignment clock. */
data class ClockAlignment(
    val source: Domain,
    val offsetMedianNs: Long,    // source - aligned, median over session
    val offsetJitterNs: Long,    // range; large => unstable / different domain
    val sampleCount: Long,
    /** True only if a controlled test confirmed a shared origin. Defaults false. */
    val verifiedSharedOrigin: Boolean,
    val note: String
) {
    val appearsShared: Boolean
        get() = verifiedSharedOrigin || (offsetJitterNs < 2_000_000L && offsetMedianNs != 0L)
}
