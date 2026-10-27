package com.nevcortex.prism.calibration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.core.util.Time
import com.nevcortex.prism.model.CalibrationProvenance
import com.nevcortex.prism.model.ImuCalibration
import com.nevcortex.prism.model.SensorKind
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * IMU calibration. We are explicit about what a given collection can and cannot estimate:
 *
 *  - GYROSCOPE bias is the mean of the gyroscope while the device is still. Estimable from a
 *    single stationary window.
 *  - ACCELEROMETER bias cannot be separated from gravity with a single orientation, because the
 *    measured mean equals bias + gravity-in-device-frame. We report the measured gravity vector
 *    and |g|, and mark accel bias NaN unless multiple orientations were collected.
 *  - For multi-orientation, we estimate accel bias as the average of per-window means, which is
 *    valid only for SYMMETRIC orientation coverage. We record that assumption and the count.
 *
 * We never claim a "complete" calibration. Every result carries provenance = ESTIMATED and an
 * uncertainty note describing exactly what was and was not estimated.
 */
class ImuCalibration(private val context: Context) {

    private val sm: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Collected data + computed stats for one stationary window. */
    data class Window(
        val accel: List<FloatArray>,
        val gyro: List<FloatArray>,
        val mag: List<FloatArray>,
        val durationNs: Long,
        val stationary: Boolean,
        val accelMean: FloatArray,
        val accelStd: FloatArray,
        val gyroMean: FloatArray,
        val gyroStd: FloatArray,
        val magMean: FloatArray,
        val gravityMag: Float,
        val gravityMagStd: Float,
        val note: String
    )

    /** Collect one window at [rateHz] for [durationMs]. The device must be still for a bias result. */
    suspend fun collectWindow(durationMs: Long, rateHz: Int = 200): Window {
        val accel = ArrayList<FloatArray>()
        val gyro = ArrayList<FloatArray>()
        val mag = ArrayList<FloatArray>()
        val thread = HandlerThread("prism-cal").also { it.start() }
        val handler = Handler(thread.looper)
        val aSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val mSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> accel.add(event.values.copyOf())
                    Sensor.TYPE_GYROSCOPE -> gyro.add(event.values.copyOf())
                    Sensor.TYPE_MAGNETIC_FIELD -> mag.add(event.values.copyOf())
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val periodUs = (1_000_000f / rateHz).toInt()
        aSensor?.let { sm.registerListener(listener, it, periodUs, handler) }
        gSensor?.let { sm.registerListener(listener, it, periodUs, handler) }
        mSensor?.let { sm.registerListener(listener, it, periodUs, handler) }
        delay(durationMs)
        sm.unregisterListener(listener)
        thread.quitSafely()

        val dur = durationMs * 1_000_000L
        val aMean = mean3(accel)
        val aStd = std3(accel, aMean)
        val gMean = mean3(gyro)
        val gStd = std3(gyro, gMean)
        val mMean = if (mag.isNotEmpty()) mean3(mag) else FloatArray(3)
        val gMag = norm(aMean)
        val gMagStd = if (accel.isNotEmpty()) {
            val mags = accel.map { norm(it) }
            sqrt(mags.map { (it - mags.average()) * (it - mags.average()) }.average()).toFloat()
        } else 0f
        // stationary: low accel magnitude variance + low gyro magnitude
        val gMagStdCheck = gMagStd < 0.35f
        val gyroMag = norm(gMean)
        val gyroQuiet = gyroMag < 0.08f
        val stationary = gMagStdCheck && gyroQuiet
        val note = if (stationary) "Stationary window accepted." else
            "WARNING: not stationary (accel|g| std=%.3f, gyro|.|=%.3f). Bias not reliable."
                .format(gMagStd, gyroMag)
        return Window(accel, gyro, mag, dur, stationary, aMean, aStd, gMean, gStd, mMean,
            gMag, gMagStd, note)
    }

    /** Single-orientation calibration: gyro bias + gravity vector; accel bias NaN (needs multi-orient). */
    fun stationaryCalibration(w: Window): ImuCalibration {
        if (!w.stationary) Logger.w(TAG, "stationaryCalibration on a non-stationary window")
        return ImuCalibration(
            createdAtMs = Time.wallMs(),
            provenance = CalibrationProvenance.ESTIMATED,
            method = "stationary mean (single orientation)",
            durationNs = w.durationNs,
            orientationCount = 1,
            accelerometerBiasMs2 = FloatArray(3) { Float.NaN },
            accelerometerNoiseStdMs2 = w.accelStd,
            gyroscopeBiasRadS = if (w.stationary) w.gyroMean else FloatArray(3) { Float.NaN },
            gyroscopeNoiseStdRadS = w.gyroStd,
            magnetometerBiasUt = FloatArray(3) { Float.NaN },
            gravityMagnitudeMs2 = w.gravityMag,
            gravityMagnitudeStdMs2 = w.gravityMagStd,
            driftNote = "Single window; gyro bias estimated from stillness. Long-term drift not measured.",
            uncertaintyNote = "Accelerometer bias NOT estimable from one orientation (bias+gravity " +
                "are inseparable). Gyroscope bias valid only if the device was truly still. " +
                "No scale/nonlinearity/temperature dependence characterized."
        )
    }

    /**
     * Multi-orientation calibration. Accel bias is the average of per-window means — valid ONLY
     * for symmetric orientation coverage. We record the assumption and the window count. More
     * diverse orientations reduce the bias estimate error; this is a documented limitation.
     */
    fun multiOrientationCalibration(windows: List<Window>): ImuCalibration {
        val n = windows.size
        require(n >= 2) { "need >= 2 orientations" }
        val accepted = windows.filter { it.stationary }
        val note = if (accepted.size < n) "Used ${accepted.size}/$n stationary windows." else ""
        val use = accepted.ifEmpty { windows }
        val accelBias = meanOfMeans(use.map { it.accelMean })     // assumes symmetric coverage
        val gyroBias = meanOfMeans(use.map { it.gyroMean })
        val accelStd = pooledStd(use.map { it.accelStd })
        val gyroStd = pooledStd(use.map { it.gyroStd })
        val gMagAvg = use.map { it.gravityMag }.average().toFloat()
        val gMagStdAvg = use.map { it.gravityMagStd }.maxOrNull() ?: 0f
        val durTotal = use.sumOf { it.durationNs }
        return ImuCalibration(
            createdAtMs = Time.wallMs(),
            provenance = CalibrationProvenance.ESTIMATED,
            method = "multi-orientation mean ($n windows, symmetric-coverage assumption)",
            durationNs = durTotal,
            orientationCount = n,
            accelerometerBiasMs2 = accelBias,
            accelerometerNoiseStdMs2 = accelStd,
            gyroscopeBiasRadS = gyroBias,
            gyroscopeNoiseStdRadS = gyroStd,
            magnetometerBiasUt = FloatArray(3) { Float.NaN },
            gravityMagnitudeMs2 = gMagAvg,
            gravityMagnitudeStdMs2 = gMagStdAvg,
            driftNote = "$note Bias assumes symmetric orientation coverage; non-symmetric " +
                "coverage biases the result. Temperature/long-term drift not characterized.",
            uncertaintyNote = "Accelerometer bias is the average of per-orientation means and is " +
                "only correct for symmetric coverage. Gyro bias is the still-window mean. Scale, " +
                "nonlinearity, cross-axis sensitivity, and temperature dependence NOT modeled."
        )
    }

    private fun mean3(xs: List<FloatArray>): FloatArray {
        if (xs.isEmpty()) return FloatArray(3)
        val s = FloatArray(3)
        xs.forEach { for (i in 0..2) s[i] += it[i] }
        for (i in 0..2) s[i] /= xs.size
        return s
    }

    private fun std3(xs: List<FloatArray>, mean: FloatArray): FloatArray {
        if (xs.size < 2) return FloatArray(3)
        val v = FloatArray(3)
        xs.forEach { for (i in 0..2) v[i] += (it[i] - mean[i]) * (it[i] - mean[i]) }
        for (i in 0..2) v[i] = sqrt(v[i] / (xs.size - 1))
        return v
    }

    private fun norm(a: FloatArray): Float = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

    private fun meanOfMeans(means: List<FloatArray>): FloatArray {
        if (means.isEmpty()) return FloatArray(3) { Float.NaN }
        val s = FloatArray(3)
        means.forEach { for (i in 0..2) s[i] += it[i] }
        for (i in 0..2) s[i] /= means.size
        return s
    }

    private fun pooledStd(stds: List<FloatArray>): FloatArray {
        if (stds.isEmpty()) return FloatArray(3)
        val s = FloatArray(3)
        stds.forEach { for (i in 0..2) s[i] += it[i] * it[i] }
        for (i in 0..2) s[i] = sqrt(s[i] / stds.size)
        return s
    }

    companion object { private const val TAG = "ImuCalibration" }
}
