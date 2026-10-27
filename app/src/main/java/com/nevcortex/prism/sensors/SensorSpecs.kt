package com.nevcortex.prism.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.os.Build
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.model.SensorIdentity
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SensorStatus

/**
 * Maps logical [SensorKind]s to Android [Sensor] types and enumerates what the device actually
 * exposes. We never assume a sensor exists; availability is checked here and surfaced to the UI.
 */
class SensorSpecs(private val context: Context) {

    private val sm: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun manager(): SensorManager = sm

    /** Android Sensor.TYPE_* for a logical kind. */
    fun androidType(kind: SensorKind): Int = when (kind) {
        SensorKind.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
        SensorKind.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
        SensorKind.MAGNETOMETER -> Sensor.TYPE_MAGNETIC_FIELD
        SensorKind.ROTATION_VECTOR -> Sensor.TYPE_ROTATION_VECTOR
        SensorKind.GAME_ROTATION_VECTOR -> Sensor.TYPE_GAME_ROTATION_VECTOR
        SensorKind.GEOMAGNETIC_ROTATION_VECTOR -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        SensorKind.GRAVITY -> Sensor.TYPE_GRAVITY
        SensorKind.LINEAR_ACCELERATION -> Sensor.TYPE_LINEAR_ACCELERATION
        SensorKind.SIGNIFICANT_MOTION -> Sensor.TYPE_SIGNIFICANT_MOTION
    }

    /** Expected value dimensionality for a kind. */
    fun expectedDim(kind: SensorKind): Int = when (kind) {
        SensorKind.ROTATION_VECTOR,
        SensorKind.GAME_ROTATION_VECTOR,
        SensorKind.GEOMAGNETIC_ROTATION_VECTOR -> 4   // x,y,z, scalar (cos(theta/2))
        else -> 3
    }

    /** Resolve the default (non-wakeup) sensor for a kind, if present. */
    fun resolve(kind: SensorKind): Sensor? =
        runCatching { sm.getDefaultSensor(androidType(kind), false) }
            .getOrElse { Logger.w("SensorSpecs", "resolve failed $kind", it); null }

    /** Build an identity record from a Sensor, with API-guarded fields. */
    fun identity(kind: SensorKind, sensor: Sensor?): SensorIdentity? {
        if (sensor == null) return null
        return runCatching {
            SensorIdentity(
                kind = kind,
                type = sensor.type,
                name = sensor.name ?: "",
                vendor = sensor.vendor ?: "",
                version = sensor.version,
                minDelayUs = sensor.minDelay,
                maxDelayUs = sensor.maxDelay,
                reportingMode = sensor.reportingMode,
                highestRate = if (Build.VERSION.SDK_INT >= 26)
                    sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER)
                else false,
                wakeUp = sensor.isWakeUpSensor,
                resolution = sensor.resolution,
                powerMa = sensor.power,
                fifoMaxEventCount = sensor.fifoMaxEventCount,
                id = if (Build.VERSION.SDK_INT >= 36) sensor.id else -1
            )
        }.getOrElse { Logger.w("SensorSpecs", "identity failed $kind", it); null }
    }

    /** Enumerate all relevant kinds with availability and identity. */
    fun enumerate(kinds: List<SensorKind> = SensorKind.values().toList()): List<SensorStatus> =
        kinds.map { kind ->
            val sensor = resolve(kind)
            SensorStatus(
                identity = identity(kind, sensor) ?: SensorIdentity(
                    kind = kind, type = androidType(kind), name = "absent", vendor = "",
                    version = 0, minDelayUs = 0, maxDelayUs = 0, reportingMode = -1,
                    highestRate = false, wakeUp = false, resolution = Float.NaN,
                    powerMa = Float.NaN, fifoMaxEventCount = 0, id = -1
                ),
                available = sensor != null,
                notes = if (sensor == null) "No default sensor for type ${androidType(kind)}" else ""
            )
        }

    /** Highest achievable rate in Hz for a kind (from minDelayUs), 0 if non-continuous. */
    fun maxRateHz(kind: SensorKind): Int {
        val s = resolve(kind) ?: return 0
        val us = s.minDelay
        if (us <= 0) return 0
        return (1_000_000 / us).toInt()
    }
}
