package com.nevcortex.prism.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.model.CameraCaptureMode
import com.nevcortex.prism.model.SessionConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Wraps CameraX so the rest of the app doesn't touch CameraX lifecycle complexity.
 *
 * Timestamp honesty: we never assume the camera HAL clock equals the IMU clock. The
 * [FrameCollector] records both the camera timestamp and the alignment-clock arrival time so the
 * offset can be measured. See [com.nevcortex.prism.sync.SyncDiagnostics].
 *
 * Stabilization: by default [com.nevcortex.prism.model.CameraStabilization.OFF] (raw sensor
 * capture). If the user enables video stabilization we record that fact in the session metadata
 * and warn that it changes pixel correspondence, which affects VIO.
 */
class CameraRepository(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var boundPreview: Preview? = null
    private var boundAnalysis: ImageAnalysis? = null
    private var boundVideo: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    /**
     * Analysis must not run on the main thread: it touches every camera frame and, while live
     * streaming, also compresses JPEGs. A single thread keeps frame order and bounds CPU.
     */
    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "prism-camera-analysis").apply { isDaemon = true }
    }

    suspend fun init() {
        if (provider != null) return
        provider = awaitProvider()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { Logger.e(TAG, "camera provider failed", it); cont.cancel(it) }
            }, mainExecutor)
        }

    fun availableLenses(): List<Int> {
        val p = provider ?: return listOf(CameraSelector.LENS_FACING_BACK)
        val list = mutableListOf<Int>()
        if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) list.add(CameraSelector.LENS_FACING_BACK)
        if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) list.add(CameraSelector.LENS_FACING_FRONT)
        return list.ifEmpty { listOf(CameraSelector.LENS_FACING_BACK) }
    }

    /**
     * The facing a bind should use: the requested lens when the device reports it, otherwise the
     * first lens it does report. A stored "front" preference on a single-lens device must not
     * attempt a front bind; it degrades to the available lens with the caller free to explain.
     */
    fun resolveLensFacing(requested: Int): Int = CameraLens.resolve(requested, availableLenses())

    /**
     * Bind PREVIEW + analysis to [lifecycleOwner] for live view. No video use case.
     */
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        sessionConfig: SessionConfig,
        analyzer: FrameCollector
    ) {
        val p = provider ?: error("CameraRepository.init() not called")
        p.unbindAll()
        boundPreview = null
        boundAnalysis = null
        boundVideo = null

        val selector = selectorFor(sessionConfig)
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // RGBA lets the live encoder produce a JPEG without a YUV conversion dependency.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        try {
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (t: Throwable) {
            Logger.e(TAG, "bindPreview failed", t)
            throw t
        }
        boundPreview = preview
        boundAnalysis = analysis
    }

    /**
     * Headless recording binding: analysis + (optional) video, NO preview. Bound to the
     * RecordingService lifecycle so capture survives the screen turning off.
     */
    fun bindRecording(
        lifecycleOwner: LifecycleOwner,
        sessionConfig: SessionConfig,
        analyzer: FrameCollector
    ) {
        val p = provider ?: error("CameraRepository.init() not called")
        p.unbindAll()
        boundPreview = null
        boundAnalysis = null
        boundVideo = null

        val selector = selectorFor(sessionConfig)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        val useCases = mutableListOf(analysis as androidx.camera.core.UseCase)
        if (sessionConfig.captureCamera && sessionConfig.captureMode == CameraCaptureMode.VIDEO) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            boundVideo = VideoCapture.withOutput(recorder)
            useCases.add(boundVideo!!)
        }
        try {
            p.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
        } catch (t: Throwable) {
            Logger.e(TAG, "bindRecording failed", t)
            throw t
        }
        boundAnalysis = analysis
    }

    private fun selectorFor(sessionConfig: SessionConfig): CameraSelector =
        when (CameraLens.resolve(sessionConfig.cameraLensFacing, availableLenses())) {
            CameraSelector.LENS_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }

    fun unbindAll() {
        activeRecording?.stop()
        activeRecording = null
        provider?.unbindAll()
        boundPreview = null
        boundAnalysis = null
        boundVideo = null
    }

    /** Start writing video to [file]. Audio is NOT recorded. */
    fun startRecording(file: File) {
        val vc = boundVideo ?: error("VideoCapture not bound (mode != VIDEO or not init)")
        val opts = FileOutputOptions.Builder(file).build()
        activeRecording = vc.output
            .prepareRecording(context, opts)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        Logger.d(TAG, "recording status: ${event.recordingStats.recordedDurationNanos} ns")
                    is VideoRecordEvent.Finalize ->
                        if (event.hasError()) Logger.e(TAG, "recording error: ${event.error}")
                        else Logger.i(TAG, "recording finalized: ${event.recordingStats.recordedDurationNanos} ns")
                    else -> Unit
                }
            }
    }

    /** Stop an active recording. Returns 0L; actual duration arrives in the Finalize event. */
    fun stopRecording(): Long {
        activeRecording?.stop()
        activeRecording = null
        return 0L
    }

    companion object { private const val TAG = "CameraRepository" }
}
