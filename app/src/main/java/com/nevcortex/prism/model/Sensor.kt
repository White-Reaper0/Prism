package com.nevcortex.prism.model

/**
 * Logical kinds of sensor streams. We distinguish RAW hardware measurements from Android
 * sensor-fusion DERIVED outputs so the UI and downstream pipelines never confuse them.
 */
enum class SensorKind(val label: String, val derived: Boolean) {
    ACCELEROMETER("Accelerometer (raw)", derived = false),
    GYROSCOPE("Gyroscope (raw)", derived = false),
    MAGNETOMETER("Magnetometer (raw)", derived = false),
    ROTATION_VECTOR("Rotation vector (Android fusion)", derived = true),
    GAME_ROTATION_VECTOR("Game rotation vector (Android fusion)", derived = true),
    GEOMAGNETIC_ROTATION_VECTOR("Geomagnetic rotation vector (Android fusion)", derived = true),
    GRAVITY("Gravity (Android fusion)", derived = true),
    LINEAR_ACCELERATION("Linear acceleration (Android fusion)", derived = true),
    SIGNIFICANT_MOTION("Significant motion (trigger)", derived = false);

    val isImu: Boolean get() = this != SIGNIFICANT_MOTION
}

/** Identity of a concrete sensor instance detected on the device. */
data class SensorIdentity(
    val kind: SensorKind,
    val type: Int,                 // Sensor.TYPE_*
    val name: String,              // vendor-provided name
    val vendor: String,
    val version: Int,
    val minDelayUs: Int,           // 0 if non-continuous
    val maxDelayUs: Int,
    val reportingMode: Int,
    val highestRate: Boolean,      // sensor.isHighestSpeed
    val wakeUp: Boolean,
    val resolution: Float,         // smallest detectable change in sensor units
    val powerMa: Float,
    val fifoMaxEventCount: Int,
    val id: Int                    // sensor.id (API 36+); -1 if unavailable on older API
)

/** Per-stream availability and observed quality. */
data class SensorStatus(
    val identity: SensorIdentity,
    val available: Boolean,
    val notes: String = ""
)
