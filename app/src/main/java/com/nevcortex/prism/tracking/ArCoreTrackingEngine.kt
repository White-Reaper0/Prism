package com.nevcortex.prism.tracking

import android.app.Activity
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.nevcortex.prism.core.util.Logger
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Minimal ARCore probe. It runs tracking in a dedicated thread with an off-screen EGL context, so
 * diagnostics never contend with the CameraX live preview. The provider is labeled ARCore in the
 * UI; its pose is a local, relative, metric frame and is never converted to GPS coordinates.
 *
 * Failure is always surfaced. If ARCore is missing, unsupported, or cannot start, no pose is shown.
 */
class ArCoreTrackingEngine(
    private val context: Context,
    private val repository: TrackingRepository
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun availability(): TrackingAvailability = availabilityOf(context)

    fun start(rotationDegrees: Int, width: Int, height: Int) {
        if (running) return
        val availability = availability()
        if (availability != TrackingAvailability.SUPPORTED) {
            repository.set(
                TrackingSnapshot(
                    phase = when (availability) {
                        TrackingAvailability.UNSUPPORTED -> TrackingPhase.UNSUPPORTED
                        TrackingAvailability.SUPPORTED_NOT_INSTALLED -> TrackingPhase.UNSUPPORTED
                        else -> TrackingPhase.ERROR
                    },
                    availability = availability,
                    provider = "ARCore",
                    updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                    failureDetail = availabilityDetail(availability)
                )
            )
            return
        }

        running = true
        val started = SystemClock.elapsedRealtime()
        repository.set(
            TrackingSnapshot(
                phase = TrackingPhase.INITIALIZING,
                availability = availability,
                running = true,
                provider = "ARCore",
                updatedAtElapsedMs = started,
                sessionStartedAtElapsedMs = started,
                note = "Starting the ARCore camera and motion tracker."
            )
        )
        thread = Thread(
            { runLoop(rotationDegrees, width, height) },
            "prism-arcore"
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { thread?.join(2000) }
        thread = null
        repository.update {
            it.copy(
                running = false,
                phase = if (it.phase == TrackingPhase.TRACKING) TrackingPhase.STOPPED else it.phase,
                updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                note = "Tracking test stopped."
            )
        }
    }

    private fun runLoop(rotationDegrees: Int, width: Int, height: Int) {
        var egl: OffscreenEgl? = null
        var session: Session? = null
        var hasTracked = false
        var lastTimestampNs = 0L
        var gaps = 0L
        var lastGapMs = 0L
        var lastPublishedAt = 0L
        try {
            egl = OffscreenEgl.create()
            session = Session(context)
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                depthMode = Config.DepthMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                focusMode = Config.FocusMode.AUTO
            }
            session.configure(config)
            session.setCameraTextureName(egl.textureId)
            session.setDisplayGeometry(rotationDegrees, width, height)
            session.resume()
            repository.update {
                it.copy(
                    phase = TrackingPhase.INITIALIZING,
                    updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                    note = "Point the phone at a textured surface and move it slowly."
                )
            }

            while (running) {
                val frame = session.update()
                val camera = frame.camera
                val timestamp = frame.timestamp
                if (lastTimestampNs != 0L) {
                    val delta = timestamp - lastTimestampNs
                    if (delta <= 0L || delta > 200_000_000L) {
                        gaps++
                        lastGapMs = (delta / 1_000_000L).coerceAtLeast(0L)
                    }
                }
                lastTimestampNs = timestamp

                val phase = when (camera.trackingState) {
                    TrackingState.TRACKING -> {
                        hasTracked = true
                        TrackingPhase.TRACKING
                    }
                    TrackingState.PAUSED ->
                        if (hasTracked) TrackingPhase.LOST else TrackingPhase.INITIALIZING
                    TrackingState.STOPPED -> TrackingPhase.LOST
                    else -> TrackingPhase.LOST
                }
                val failure = if (camera.trackingState == TrackingState.TRACKING) {
                    null
                } else {
                    mapFailure(camera.trackingFailureReason)
                }
                val pose = if (camera.trackingState == TrackingState.TRACKING) {
                    poseOf(camera.pose)
                } else {
                    null
                }

                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastPublishedAt >= 100L) {
                    lastPublishedAt = nowMs
                    repository.update {
                        it.copy(
                            phase = phase,
                            running = true,
                            availability = TrackingAvailability.SUPPORTED,
                            failure = failure,
                            failureDetail = failure?.let { reason -> failureText(reason) },
                            pose = pose,
                            poseTimestampNs = timestamp,
                            timestampGapCount = gaps,
                            lastTimestampGapMs = lastGapMs,
                            updatedAtElapsedMs = nowMs,
                            note = when (phase) {
                                TrackingPhase.TRACKING ->
                                    "Tracking is active. Pose is ARCore's local frame, not GPS."
                                TrackingPhase.INITIALIZING ->
                                    "Tracking is initializing. Move the phone slowly across a textured surface."
                                TrackingPhase.LOST ->
                                    "Tracking was lost. Point at a textured surface and move slowly."
                                else -> null
                            }
                        )
                    }
                }
                Thread.sleep(33L)
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "ARCore tracking failed", t)
            repository.set(
                TrackingSnapshot(
                    phase = TrackingPhase.ERROR,
                    availability = availabilityOf(context),
                    running = false,
                    provider = "ARCore",
                    failureDetail = startFailureText(t),
                    updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                    sessionStartedAtElapsedMs = 0L,
                    note = "ARCore could not start on this device."
                )
            )
        } finally {
            runCatching { session?.pause() }
            runCatching { session?.close() }
            egl?.release()
            running = false
        }
    }

    private fun poseOf(pose: com.google.ar.core.Pose): TrackingPose {
        val t = pose.translation
        val q = pose.rotationQuaternion
        val qx = q[0].toDouble()
        val qy = q[1].toDouble()
        val qz = q[2].toDouble()
        val qw = q[3].toDouble()
        val yaw = Math.toDegrees(atan2(2.0 * (qw * qy + qx * qz), 1.0 - 2.0 * (qy * qy + qz * qz)))
        val pitch = Math.toDegrees(asin((2.0 * (qw * qx - qy * qz)).coerceIn(-1.0, 1.0)))
        val roll = Math.toDegrees(atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qx * qx + qz * qz)))
        return TrackingPose(
            x = t[0], y = t[1], z = t[2],
            qx = q[0], qy = q[1], qz = q[2], qw = q[3],
            yawDeg = yaw.toFloat(), pitchDeg = pitch.toFloat(), rollDeg = roll.toFloat()
        )
    }

    private fun mapFailure(reason: TrackingFailureReason): TrackingFailure = when (reason) {
        TrackingFailureReason.BAD_STATE -> TrackingFailure.BAD_STATE
        TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingFailure.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION -> TrackingFailure.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingFailure.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingFailure.CAMERA_UNAVAILABLE
        else -> TrackingFailure.UNKNOWN
    }

    private fun failureText(reason: TrackingFailure): String = when (reason) {
        TrackingFailure.INSUFFICIENT_LIGHT -> "There is not enough light to track. Move to a brighter area."
        TrackingFailure.EXCESSIVE_MOTION -> "The phone moved too fast. Move slowly and try again."
        TrackingFailure.INSUFFICIENT_FEATURES -> "The camera cannot see enough detail. Point at a textured surface."
        TrackingFailure.CAMERA_UNAVAILABLE -> "The camera is not available to ARCore. Close other camera apps and try again."
        TrackingFailure.BAD_STATE -> "ARCore is not ready. Stop the test, wait a moment, and start again."
        TrackingFailure.UNKNOWN -> "Tracking is not available right now. Move slowly and try again."
    }

    private fun availabilityDetail(availability: TrackingAvailability): String = when (availability) {
        TrackingAvailability.SUPPORTED_NOT_INSTALLED ->
            "ARCore is supported by this phone but is not installed. Install it to run the tracking test."
        TrackingAvailability.UNSUPPORTED ->
            "This phone is not supported by ARCore. Camera and motion streaming still work; tracking diagnostics cannot run."
        TrackingAvailability.ERROR ->
            "ARCore availability could not be checked. Check the network and Google Play Services for AR."
        else -> ""
    }

    private fun startFailureText(t: Throwable): String = when (t) {
        is UnavailableArcoreNotInstalledException ->
            "Google Play Services for AR is not installed. Install ARCore and try again."
        is UnavailableApkTooOldException ->
            "Google Play Services for AR is too old. Update ARCore and try again."
        is UnavailableSdkTooOldException ->
            "This version of the ARCore SDK is too old for the installed ARCore service."
        is UnavailableDeviceNotCompatibleException ->
            "This phone cannot run ARCore tracking."
        else ->
            "ARCore could not start. Close other camera apps, then try again. (${t.javaClass.simpleName})"
    }

    /** Off-screen EGL surface: ARCore needs a current GL context and an external texture. */
    private class OffscreenEgl(
        private val display: EGLDisplay,
        private val context: EGLContext,
        private val surface: EGLSurface,
        val textureId: Int
    ) {
        fun release() {
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            runCatching { EGL14.eglDestroySurface(display, surface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }

        companion object {
            fun create(): OffscreenEgl {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

                val configAttribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(
                    EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) &&
                        count[0] > 0
                ) { "No suitable EGL config" }

                val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                val context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    contextAttribs,
                    0
                )
                check(context != null && context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

                val surfaceAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
                )
                val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
                check(surface != null && surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
                check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                check(textures[0] != 0) { "glGenTextures failed" }
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST
                )
                return OffscreenEgl(display, context, surface, textures[0])
            }
        }
    }

    companion object {
        private const val TAG = "ArCoreTracking"

        fun availabilityOf(context: Context): TrackingAvailability {
            val availability = runCatching {
                ArCoreApk.getInstance().checkAvailability(context)
            }.getOrNull() ?: return TrackingAvailability.ERROR
            return when {
                availability == ArCoreApk.Availability.SUPPORTED_INSTALLED -> TrackingAvailability.SUPPORTED
                availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
                    availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                    TrackingAvailability.SUPPORTED_NOT_INSTALLED
                availability.isUnsupported -> TrackingAvailability.UNSUPPORTED
                availability.isTransient -> TrackingAvailability.CHECKING
                else -> TrackingAvailability.ERROR
            }
        }

        /**
         * Ask the Play Store to install/update ARCore. Must be called from an Activity on the main
         * thread. Returns true if ARCore is already installed.
         */
        fun requestInstall(activity: Activity): Boolean = runCatching {
            ArCoreApk.getInstance()
                .requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED
        }.getOrDefault(false)

        /** Convenience for UI labels: 1 decimal place without locale-dependent formatting. */
        fun metres(value: Float): String =
            ((value * 10f).roundToInt() / 10f).toString()
    }
}
