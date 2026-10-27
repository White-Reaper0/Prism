package com.nevcortex.prism.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.link.LinkClient
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.FrameSample
import com.nevcortex.prism.model.SensorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

enum class CameraStreamState { IDLE, STARTING, PREVIEW_READY, ERROR, PERMISSION_DENIED, UNAVAILABLE }

enum class SensorStreamState { NOT_STARTED, STARTING, WORKING, PARTIAL, UNAVAILABLE }

data class LiveStreamState(
    val active: Boolean = false,
    val camera: CameraStreamState = CameraStreamState.IDLE,
    val sensors: SensorStreamState = SensorStreamState.NOT_STARTED,
    val cameraDetail: String? = null,
    val sensorDetail: String? = null,
    val encodedFrames: Long = 0L,
    val lastEncodedAtElapsedMs: Long = 0L,
    val startedAtElapsedMs: Long = 0L,
    /** Lens currently bound (CameraLens.BACK / FRONT); drives the front/rear selector UI. */
    val lensFacing: Int = com.nevcortex.prism.camera.CameraLens.BACK,
    /** Lenses the device actually reports, once known; empty until init() has run. */
    val availableLenses: List<Int> = emptyList()
)

/**
 * Owns the live camera preview, the bounded JPEG encoder, and the sensor listeners used by
 * Live Studio. Streaming itself is optional: the preview and motion readout work without a PC
 * connection, which keeps the "camera ready but not connected" state honest and testable.
 */
class LiveStreamSession(
    private val container: AppContainer,
    private val link: LinkClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(LiveStreamState())
    val state: StateFlow<LiveStreamState> = _state.asStateFlow()

    private var encoder: JpegFrameEncoder? = null
    private var active = false

    fun start(owner: LifecycleOwner, previewView: PreviewView) {
        if (active) return
        val context: Context = container.appContext

        active = true
        _state.value = LiveStreamState(
            active = true,
            camera = CameraStreamState.STARTING,
            sensors = SensorStreamState.STARTING,
            startedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        )

        // Motion readout is useful even without camera permission, so start it first. Camera
        // streaming below still requires the permission.
        startSensors()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _state.update {
                it.copy(
                    camera = CameraStreamState.PERMISSION_DENIED,
                    cameraDetail = "Camera permission is not granted."
                )
            }
            return
        }

        val quality = container.settingsStore.streamQuality
        encoder = JpegFrameEncoder(quality, link) { frames, lastAt ->
            _state.update { it.copy(encodedFrames = frames, lastEncodedAtElapsedMs = lastAt) }
        }
        container.frameCollector.expectedTargetFps = quality.maxFps.toFloat()
        container.frameCollector.frameListener = { image, sample ->
            encoder?.encode(image, sample)
        }

        scope.launch {
            try {
                container.cameraRepository.init()
                val lenses = container.cameraRepository.availableLenses()
                val cfg = container.settingsStore.newSessionConfig("Live Studio").copy(
                    captureCamera = true,
                    captureImu = true,
                    captureMode = CameraCaptureMode.VIDEO,
                    // Bind a lens the device reports; a stored "front" preference on a
                    // single-lens device must not fail the whole bind.
                    cameraLensFacing = container.cameraRepository.resolveLensFacing(
                        container.settingsStore.lensFacing
                    )
                )
                container.cameraRepository.bindPreview(
                    owner,
                    previewView,
                    cfg,
                    container.frameCollector
                )
                _state.update {
                    it.copy(
                        camera = CameraStreamState.PREVIEW_READY,
                        cameraDetail = null,
                        availableLenses = lenses,
                        lensFacing = cfg.cameraLensFacing
                    )
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "live camera start failed", t)
                _state.update {
                    it.copy(
                        camera = CameraStreamState.ERROR,
                        cameraDetail = t.message ?: t.javaClass.simpleName
                    )
                }
            }
        }
    }

    /**
     * Switch front/rear while live. Only the camera use cases rebind: analysis callbacks of the
     * old session are released by [CameraRepository.bindPreview] (which unbinds everything
     * before rebinding), preview reuses the same [previewView], and the sensor listeners keep
     * running so IMU timestamps and streaming are never interrupted by a lens change.
     */
    fun switchLens(lensFacing: Int, owner: LifecycleOwner, previewView: PreviewView) {
        if (!_state.value.active) return
        if (_state.value.camera != CameraStreamState.PREVIEW_READY) return
        // Availability is the camera provider's answer, never an assumption.
        val resolved = container.cameraRepository.resolveLensFacing(lensFacing)
        val current = _state.value.lensFacing
        if (resolved == current) {
            // The device lacks one lens or the selection did not change: say so honestly
            // rather than pretending a switch happened, and keep the working lens active.
            _state.update {
                it.copy(
                    cameraDetail = "Requested lens ${describeLens(lensFacing)} " +
                        "is not available on this phone; keeping " +
                        describeLens(current) + "."
                )
            }
            return
        }
        container.settingsStore.lensFacing = resolved
        _state.update {
            it.copy(camera = CameraStreamState.STARTING, cameraDetail = null)
        }
        scope.launch {
            try {
                val cfg = container.settingsStore.newSessionConfig("Live Studio").copy(
                    captureCamera = true,
                    captureImu = true,
                    captureMode = CameraCaptureMode.VIDEO,
                    cameraLensFacing = resolved
                )
                container.cameraRepository.bindPreview(owner, previewView, cfg, container.frameCollector)
                _state.update {
                    it.copy(
                        camera = CameraStreamState.PREVIEW_READY,
                        cameraDetail = null,
                        lensFacing = resolved,
                        availableLenses = container.cameraRepository.availableLenses()
                    )
                }
            } catch (t: Throwable) {
                // A failed rebind must not leave the UI claiming a live camera. Old use cases
                // were already unbound, so no callbacks can leak onto a released camera.
                Logger.e(TAG, "lens switch failed", t)
                _state.update {
                    it.copy(
                        camera = CameraStreamState.ERROR,
                        cameraDetail = t.message ?: t.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun describeLens(facing: Int): String =
        if (facing == com.nevcortex.prism.camera.CameraLens.FRONT) "front" else "rear"

    private fun startSensors() {
        val all = listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE, SensorKind.MAGNETOMETER)
        val available = all.filter { kind ->
            container.sensorRepository.enumerate(listOf(kind)).firstOrNull()?.available == true
        }
        if (available.isEmpty()) {
            _state.update {
                it.copy(
                    sensors = SensorStreamState.UNAVAILABLE,
                    sensorDetail = "No motion sensors were reported by this phone."
                )
            }
            return
        }
        container.sensorRepository.collector().streamListener = { sample ->
            link.offerImu(sample)
        }
        // 200 Hz is enough for meaningful live motion feedback and is gentler on battery than 400 Hz.
        val rate = container.settingsStore.imuRateHz.coerceIn(50, 200)
        container.sensorRepository.start(available, rate)

        val hasAccel = SensorKind.ACCELEROMETER in available
        val hasGyro = SensorKind.GYROSCOPE in available
        _state.update {
            it.copy(
                sensors = if (hasAccel && hasGyro) SensorStreamState.WORKING else SensorStreamState.PARTIAL,
                sensorDetail = when {
                    hasAccel && hasGyro -> null
                    hasAccel -> "This phone did not report a gyroscope. Motion direction cannot be measured."
                    hasGyro -> "This phone did not report an accelerometer. Movement forces cannot be measured."
                    else -> "Only the magnetometer is available."
                }
            )
        }
    }

    fun stop() {
        if (!active) return
        active = false
        container.frameCollector.frameListener = null
        container.sensorRepository.collector().streamListener = null
        container.sensorRepository.stop()
        runCatching { container.cameraRepository.unbindAll() }
            .onFailure { Logger.w(TAG, "camera unbind failed", it) }
        encoder = null
        _state.value = LiveStreamState(camera = CameraStreamState.IDLE, sensors = SensorStreamState.NOT_STARTED)
    }

    companion object {
        private const val TAG = "LiveStream"
    }
}

/**
 * Converts CameraX RGBA frames into bounded JPEGs at the selected preset. Frames are throttled
 * before compression so the phone does not spend CPU encoding frames that would be discarded by
 * the network queue anyway.
 */
private class JpegFrameEncoder(
    private val quality: StreamQuality,
    private val link: LinkClient,
    private val onEncoded: (frames: Long, lastAtElapsedMs: Long) -> Unit
) {
    private var lastEncodedAtNs = 0L
    private var frameCount = 0L
    private var errorReported = false

    fun encode(image: ImageProxy, sample: FrameSample) {
        // Do not spend CPU compressing frames when no receiver is connected.
        if (!link.status.value.isLive) return
        val minIntervalNs = 1_000_000_000L / quality.maxFps.coerceAtLeast(1)
        if (sample.arrivalElapsedNs - lastEncodedAtNs < minIntervalNs) return
        lastEncodedAtNs = sample.arrivalElapsedNs

        var source: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            source = image.toBitmap()
            if (source.width <= 0 || source.height <= 0) return
            scaled = if (source.width > quality.targetWidth) {
                val ratio = quality.targetWidth.toFloat() / source.width.toFloat()
                Bitmap.createScaledBitmap(
                    source,
                    quality.targetWidth,
                    (source.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                source
            }
            val bytes = ByteArrayOutputStream(64 * 1024).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, out)
                out.toByteArray()
            }
            val offered = link.offerFrame(
                jpeg = bytes,
                cameraTimestampNs = sample.cameraTimestampNs,
                arrivalElapsedNs = sample.arrivalElapsedNs,
                width = scaled.width,
                height = scaled.height,
                rotationDegrees = sample.rotationDegrees
            )
            if (offered) {
                frameCount++
                onEncoded(frameCount, android.os.SystemClock.elapsedRealtime())
            }
        } catch (t: Throwable) {
            if (!errorReported) {
                errorReported = true
                Logger.e("PrismStream", "JPEG encoding failed", t)
            }
        } finally {
            if (scaled != null && scaled !== source) scaled.recycle()
            source?.recycle()
        }
    }
}
