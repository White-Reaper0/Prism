package com.nevcortex.prism.core.util

import android.os.SystemClock

/**
 * Time conventions used across P.R.I.S.M.
 *
 *  - `sensorTimestampNs` (accel/gyro/mag/rotation): [SensorEvent.timestamp], nanoseconds. On
 *    Android, sensor event timestamps are in the same time base as [SystemClock.elapsedRealtimeNanos]
 *    (boot-since monotonic clock, excludes deep-sleep). We treat this as the authoritative
 *    sensor clock unless diagnostics prove otherwise.
 *  - `cameraTimestampNs`: [androidx.camera.core.ImageProxy.getImageInfo].getTimestamp(), in
 *    nanoseconds. Camera frame timestamps are produced by the camera HAL and are NOT guaranteed
 *    to share a clock origin with sensor timestamps. We capture an `arrivalElapsedNs` alongside
 *    every measurement so the relative clock offset can be measured (see [com.nevcortex.prism.sync]).
 *  - `wallClockMs`: [System.currentTimeMillis] UNIX epoch millis. Used only as human-readable
 *    metadata and session labels, NEVER for sensor alignment.
 *
 * Nothing here hides the difference between clocks. Callers that compare two timestamps must be
 * aware of which domain each belongs to.
 */
object Time {
    /** Monotonic nanoseconds since boot, excludes deep sleep. Primary alignment clock. */
    fun elapsedNanos(): Long = SystemClock.elapsedRealtimeNanos()

    /** Monotonic nanoseconds since boot including deep sleep. Available for cross-check only. */
    fun uptimeNanos(): Long = System.nanoTime()

    /** Wall-clock UNIX epoch milliseconds. Metadata only. */
    fun wallMs(): Long = System.currentTimeMillis()

    /** Format nanos as seconds with fixed precision for logs/CSV. */
    fun nsToSecString(ns: Long, decimals: Int = 9): String {
        val factor = pow10(decimals)
        val whole = ns / 1_000_000_000L
        val frac = (ns % 1_000_000_000L) / (1_000_000_000L / factor)
        val fracStr = frac.toString().padStart(decimals, '0')
        return "$whole.$fracStr"
    }

    private fun pow10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }
}
