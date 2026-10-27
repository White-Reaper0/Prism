package com.nevcortex.prism.ui.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** Human-readable formatting for values that appear on ordinary screens. */
object Formats {

    fun duration(ns: Long): String {
        if (ns <= 0L) return "0 s"
        val seconds = ns / 1_000_000_000.0
        return when {
            seconds < 60.0 -> "%.1f s".format(Locale.US, seconds)
            seconds < 3600.0 -> {
                val total = seconds.roundToLong()
                "%d min %02d s".format(Locale.US, total / 60, total % 60)
            }
            else -> {
                val total = seconds.roundToLong()
                "%d h %02d min".format(Locale.US, total / 3600, (total % 3600) / 60)
            }
        }
    }

    fun milliseconds(ns: Long): String =
        if (ns <= 0L) "—" else "%.1f ms".format(Locale.US, ns / 1_000_000.0)

    fun hertz(hz: Float): String =
        if (hz <= 0f) "—" else "%.1f Hz".format(Locale.US, hz)

    fun fps(fps: Float): String =
        if (fps <= 0f) "—" else "%.1f fps".format(Locale.US, fps)

    fun bytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes} B"
        else "%.1f %s".format(Locale.US, value, units[unit])
    }

    fun ago(elapsedMs: Long, nowMs: Long = android.os.SystemClock.elapsedRealtime()): String {
        if (elapsedMs <= 0L) return "not yet"
        val delta = ((nowMs - elapsedMs).coerceAtLeast(0L)) / 1000L
        return when {
            delta < 2L -> "just now"
            delta < 60L -> "$delta s ago"
            delta < 3600L -> "${delta / 60L} min ago"
            else -> "${delta / 3600L} h ago"
        }
    }

    fun timestamp(epochMs: Long): String =
        if (epochMs <= 0L) "Unknown"
        else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun signedMicros(ns: Long): String =
        "%+.1f ms".format(Locale.US, ns / 1_000_000.0)

    fun metres(value: Float): String = "%.2f m".format(Locale.US, value)

    fun degrees(value: Float): String = "%.0f°".format(Locale.US, value)
}
