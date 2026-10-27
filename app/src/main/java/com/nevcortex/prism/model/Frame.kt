package com.nevcortex.prism.model

import com.nevcortex.prism.core.coords.Frame

/**
 * A camera frame observation. We do not retain full frame bitmaps in memory for live plots; we
 * keep metadata here and the bytes are written to disk during recording.
 *
 * Camera frame timestamps come from the camera HAL time domain, NOT guaranteed to match the IMU
 * clock. We record both [cameraTimestampNs] and [arrivalElapsedNs] so the offset can be measured.
 */
data class FrameSample(
    val frameIndex: Long,
    val cameraTimestampNs: Long,   // ImageProxy.imageInfo.timestamp
    val arrivalElapsedNs: Long,    // elapsedRealtimeNanos at callback entry
    val width: Int,
    val height: Int,
    val format: Int,               // ImageFormat
    val source: FrameSource,
    val rotationDegrees: Int,
    val iso: Int,
    val exposureNs: Long,
    val aperture: Float,           // 0 if unknown
    val frame: Frame = Frame.CAMERA
)

enum class FrameSource { ANALYSIS, STILL_CAPTURE, VIDEO }
