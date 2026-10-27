package com.nevcortex.prism.persistence

import android.content.Context
import android.content.SharedPreferences
import com.nevcortex.prism.core.coords.MountingConfig
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.CameraStabilization
import com.nevcortex.prism.model.SessionConfig
import com.nevcortex.prism.stream.StreamQuality
import com.nevcortex.prism.link.LinkProtocol
import java.util.UUID

/**
 * Persistent user defaults that seed [SessionConfig]. Uses SharedPreferences for reliability.
 * These are REQUESTED values; achieved rates are always measured at runtime.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("prism_settings", Context.MODE_PRIVATE)

    var imuRateHz: Int
        get() = prefs.getInt(K_IMU_RATE, 400)
        set(v) = prefs.edit().putInt(K_IMU_RATE, v).apply()

    var captureMode: CameraCaptureMode
        get() = CameraCaptureMode.valueOf(prefs.getString(K_MODE, CameraCaptureMode.VIDEO.name)!!)
        set(v) = prefs.edit().putString(K_MODE, v.name).apply()

    var lensFacing: Int
        get() = prefs.getInt(K_LENS, 0) // 0 = back
        set(v) = prefs.edit().putInt(K_LENS, v).apply()

    var stabilization: CameraStabilization
        get() = CameraStabilization.valueOf(prefs.getString(K_STAB, CameraStabilization.OFF.name)!!)
        set(v) = prefs.edit().putString(K_STAB, v.name).apply()

    var enableGps: Boolean
        get() = prefs.getBoolean(K_GPS, false)
        set(v) = prefs.edit().putBoolean(K_GPS, v).apply()

    var targetAnalysisFps: Float
        get() = prefs.getFloat(K_FPS, 20f)
        set(v) = prefs.edit().putFloat(K_FPS, v).apply()

    var mountingName: String
        get() = prefs.getString(K_MOUNT, MountingConfig.IDENTITY.name) ?: MountingConfig.IDENTITY.name
        set(v) = prefs.edit().putString(K_MOUNT, v).apply()

    // --- Live link to the PC receiver ---------------------------------------------------------

    /** Hostname or IPv4 address of the PC running the PRISM receiver. Empty means "not set up". */
    var pcHost: String
        get() = prefs.getString(K_PC_HOST, "").orEmpty()
        set(v) = prefs.edit().putString(K_PC_HOST, v.trim()).apply()

    var pcPort: Int
        get() = prefs.getInt(K_PC_PORT, LinkProtocol.DEFAULT_PORT)
        set(v) = prefs.edit().putInt(K_PC_PORT, v.coerceIn(1, 65535)).apply()

    var streamQuality: StreamQuality
        get() = StreamQuality.fromName(prefs.getString(K_STREAM_QUALITY, null))
        set(v) = prefs.edit().putString(K_STREAM_QUALITY, v.name).apply()

    // --- App updates over the local network ---------------------------------------------------

    /** Hostname or IPv4 address of the PC running the PRISM updater. Empty means "not set up". */
    var updateHost: String
        get() = prefs.getString(K_UPDATE_HOST, "").orEmpty()
        set(v) = prefs.edit().putString(K_UPDATE_HOST, v.trim()).apply()

    var updatePort: Int
        get() = prefs.getInt(K_UPDATE_PORT, com.nevcortex.prism.update.UpdateDefaults.DEFAULT_PORT)
        set(v) = prefs.edit().putInt(K_UPDATE_PORT, v.coerceIn(1, 65535)).apply()

    /** Wall-clock time of the last successful update check, for the Settings display. */
    var lastUpdateCheckAtMs: Long
        get() = prefs.getLong(K_UPDATE_LAST_CHECK, 0L)
        set(v) = prefs.edit().putLong(K_UPDATE_LAST_CHECK, v).apply()

    /** Build a fresh SessionConfig from the stored defaults. */
    fun newSessionConfig(label: String): SessionConfig = SessionConfig(
        id = UUID.randomUUID().toString(),
        label = label,
        createdAtMs = System.currentTimeMillis(),
        imuRateHz = imuRateHz,
        captureMode = captureMode,
        cameraLensFacing = lensFacing,
        stabilization = stabilization,
        enableGpsGroundTruth = enableGps,
        targetAnalysisFps = targetAnalysisFps,
        mounting = when (mountingName) {
            MountingConfig.FLAT_SCREEN_UP.name -> MountingConfig.FLAT_SCREEN_UP
            else -> MountingConfig.IDENTITY
        }
    )

    companion object {
        private const val K_IMU_RATE = "imuRateHz"
        private const val K_MODE = "captureMode"
        private const val K_LENS = "lensFacing"
        private const val K_STAB = "stabilization"
        private const val K_GPS = "enableGps"
        private const val K_FPS = "targetAnalysisFps"
        private const val K_MOUNT = "mounting"
        private const val K_PC_HOST = "pcHost"
        private const val K_PC_PORT = "pcPort"
        private const val K_STREAM_QUALITY = "streamQuality"
        private const val K_UPDATE_HOST = "updateHost"
        private const val K_UPDATE_PORT = "updatePort"
        private const val K_UPDATE_LAST_CHECK = "lastUpdateCheckAtMs"
    }
}
