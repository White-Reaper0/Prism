package com.nevcortex.prism.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nevcortex.prism.PrismApp
import com.nevcortex.prism.R
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.model.SessionConfig
import kotlinx.coroutines.launch

/**
 * Foreground service that owns an active recording session. It is a [LifecycleService] so the
 * camera can bind to its lifecycle and capture continues when the screen turns off (CameraX
 * pauses capture when the activity lifecycle stops, so headless recording must run here).
 *
 * Foreground service type is CAMERA while recording (with LOCATION added only if the user enables
 * GPS ground truth). IMU-only calibration sessions do NOT use this service; they run in-activity
 * with the screen on, so no background sensor type abuse occurs.
 */
class RecordingService : LifecycleService() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private var started = false

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prism:recording")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                Logger.i(TAG, "stop requested")
                app().container.recordingState.value = RecordingState(
                    phase = RecordingPhase.STOPPING,
                    detail = "Finishing the recording and saving it on the phone."
                )
                lifecycleScope.launch {
                    runCatching { app().container.recorder.stop() }
                        .onSuccess {
                            Logger.i(TAG, "stopped; frames=${it.sync.cameraFrameCount}")
                            app().container.lastSessionSummary = it
                            app().container.recordingState.value = RecordingState(
                                phase = RecordingPhase.SAVED,
                                summary = it,
                                detail = "Recording saved."
                            )
                        }
                        .onFailure {
                            Logger.e(TAG, "stop failed", it)
                            app().container.recordingState.value = RecordingState(
                                phase = RecordingPhase.FAILED,
                                detail = "The recording could not be saved."
                            )
                        }
                    teardown()
                }
            }
            ACTION_START -> {
                if (started) return START_NOT_STICKY
                started = true
                val config = app().container.pendingSessionConfig
                    ?: error("RecordingService requires pendingSessionConfig on AppContainer")
                app().container.recordingState.value = RecordingState(
                    phase = RecordingPhase.STARTING,
                    detail = "Starting the camera and motion sensors."
                )
                startForegroundWithCamera(config)
                if (!wakeLock.isHeld) wakeLock.acquire(60 * 60 * 1000L) // 1h max; recording renews
                // Ensure IMU listeners are running with the configured kinds/rate for this session.
                runCatching {
                    val kinds = buildList {
                        if (config.recordAccelerometer) add(com.nevcortex.prism.model.SensorKind.ACCELEROMETER)
                        if (config.recordGyroscope) add(com.nevcortex.prism.model.SensorKind.GYROSCOPE)
                        if (config.recordMagnetometer) add(com.nevcortex.prism.model.SensorKind.MAGNETOMETER)
                        if (config.recordRotationVector) add(com.nevcortex.prism.model.SensorKind.ROTATION_VECTOR)
                        if (config.recordGameRotationVector) add(com.nevcortex.prism.model.SensorKind.GAME_ROTATION_VECTOR)
                        if (config.recordGravity) add(com.nevcortex.prism.model.SensorKind.GRAVITY)
                        if (config.recordLinearAcceleration) add(com.nevcortex.prism.model.SensorKind.LINEAR_ACCELERATION)
                    }
                    app().container.sensorRepository.start(kinds, config.imuRateHz)
                }
                lifecycleScope.launch {
                    runCatching { app().container.recorder.start(config, this@RecordingService) }
                        .onSuccess {
                            app().container.recordingState.value = RecordingState(
                                phase = RecordingPhase.RECORDING,
                                detail = "Recording camera frames and motion data."
                            )
                        }
                        .onFailure {
                            Logger.e(TAG, "start failed", it)
                            app().container.recordingState.value = RecordingState(
                                phase = RecordingPhase.FAILED,
                                detail = "The recording could not start. Check camera permission and try again."
                            )
                            teardown()
                        }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithCamera(config: SessionConfig) {
        val notif = buildNotification(config)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (config.enableGpsGroundTruth) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            t
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun teardown() {
        if (wakeLock.isHeld) wakeLock.release()
        runCatching { app().container.sensorRepository.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        started = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) wakeLock.release()
        runCatching { app().container.recorder.shutdown() }
    }

    private fun buildNotification(config: SessionConfig): Notification {
        val text = if (config.captureCamera) "Camera + IMU capture" else "IMU capture"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.recording_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = getString(R.string.recording_channel_desc) }
                )
            }
        }
    }

    private fun app(): PrismApp = applicationContext as PrismApp

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.nevcortex.prism.action.START"
        const val ACTION_STOP = "com.nevcortex.prism.action.STOP"
        const val EXTRA_CONFIG = "config"
        const val CHANNEL_ID = "prism.recording"
        const val NOTIF_ID = 4242

        fun start(ctx: Context, config: SessionConfig) {
            (ctx.applicationContext as PrismApp).container.pendingSessionConfig = config
            val i = Intent(ctx, RecordingService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, RecordingService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }
}
