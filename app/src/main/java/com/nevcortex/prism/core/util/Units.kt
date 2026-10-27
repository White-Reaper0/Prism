package com.nevcortex.prism.core.util

/** Unit conversions for sensor readouts. We keep raw SI values internally and convert only for
 *  display. */
object Units {
    /** Radians per second -> degrees per second. */
    fun radPerSecToDegPerSec(v: Float): Float = Math.toDegrees(v.toDouble()).toFloat()

    /** Degrees per second -> radians per second. */
    fun degPerSecToRadPerSec(v: Float): Float = Math.toRadians(v.toDouble()).toFloat()

    /** Microtesla (Android magnetometer unit). Returned as-is; we keep µT. */
    const val MICROTESLA_LABEL = "µT"

    /** m/s^2 accelerometer unit label. */
    const val MS2_LABEL = "m/s²"

    /** rad/s gyroscope unit label. */
    const val RAD_S_LABEL = "rad/s"

    /** Approximate g in m/s^2 for reference only; never used to "rescue" integration. */
    const val G_MS2 = 9.80665

    /** π as a named constant (kotlin.math.PI is a Double; kept as val, not const). */
    val PI: Double = kotlin.math.PI
}
