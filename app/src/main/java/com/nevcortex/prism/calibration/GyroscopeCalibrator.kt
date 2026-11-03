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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Collects one 60-second stationary gyroscope window on a private background thread.
 * Raw samples are never modified; this only measures the zero-rate bias.
 *
 * Live progress is exposed through [progress] (StateFlow) for the Settings UI. Collection is
 * single-flight: starting while running is ignored, and [cancel] stops it without persisting
 * anything.
 */
class GyroscopeCalibrator(private val context: Context) {

    private val sm: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    data class Progress(
        val running: Boolean = false,
        val elapsedMs: Long = 0,
        val sampleCount: Int = 0,
        val cancelRequested: Boolean = false
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val samples = ArrayList<GyroBiasEstimator.GyroSample>()

    @Volatile private var cancelled = false
    @Volatile private var active = false

    /** True while a calibration is collecting; duplicate starts are ignored. */
    val isRunning: Boolean get() = active

    /**
     * Collect for [durationMs] and return the outcome. Never throws: sensor or analysis problems
     * surface as [GyroBiasEstimator.Outcome.Rejected]. The caller decides whether to persist.
     */
    suspend fun collect(durationMs: Long = 60_000L): GyroBiasEstimator.Outcome {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: return GyroBiasEstimator.Outcome.Rejected(
                "this phone reports no gyroscope sensor"
            )
        synchronized(this) {
            if (active) return GyroBiasEstimator.Outcome.Rejected("a calibration is already running")
            active = true
        }
        samples.clear()
        cancelled = false
        _progress.value = Progress(running = true, elapsedMs = 0, sampleCount = 0)

        val thread = HandlerThread("prism-gyro-cal").also { it.start() }
        val handler = Handler(thread.looper)
        val startedNs = System.nanoTime()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
                // Keep the original SensorEvent.timestamp — do not re-stamp arrival time.
                samples.add(
                    GyroBiasEstimator.GyroSample(
                        timestampNs = event.timestamp,
                        xRadS = event.values[0],
                        yRadS = event.values[1],
                        zRadS = event.values[2]
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // 5 ms period (≈200 Hz); the analyzer does not depend on the rate, only on the stamps.
        sm.registerListener(listener, gyro, 5_000, handler)
        try {
            val endNs = System.nanoTime() + durationMs * 1_000_000L
            while (!cancelled && System.nanoTime() < endNs) {
                _progress.update {
                    it.copy(
                        elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L,
                        sampleCount = samples.size
                    )
                }
                delay(200)
            }
        } finally {
            sm.unregisterListener(listener)
            handler.removeCallbacksAndMessages(null)  // drop any pending dispatch
            thread.quitSafely()
        }

        val elapsed = (System.nanoTime() - startedNs) / 1_000_000L
        _progress.update {
            it.copy(
                running = false,
                elapsedMs = elapsed,
                sampleCount = samples.size,
                cancelRequested = cancelled
            )
        }
        val wasCancelled = cancelled
        active = false

        if (wasCancelled) {
            Logger.i(TAG, "gyroscope calibration cancelled by user after ${samples.size} samples")
            return GyroBiasEstimator.Outcome.Rejected("cancelled by user; nothing was saved")
        }
        return GyroBiasEstimator.analyze(
            samples = samples.toList(),
            createdAtMs = Time.wallMs()
        )
    }

    /** Ask the running collection to stop; the outcome is reported as cancelled and never saved. */
    fun cancel() {
        cancelled = true
        _progress.update { it.copy(cancelRequested = true) }
    }

    companion object { private const val TAG = "GyroscopeCalibrator" }
}
