package com.nevcortex.prism.stream

/**
 * User-facing live-video presets. These are *targets*: the encoder scales the camera frame to
 * [targetWidth] and sends at most [maxFps]. The achieved frame rate is measured and shown in
 * Live Studio, never assumed from this enum.
 */
enum class StreamQuality(
    val targetWidth: Int,
    val jpegQuality: Int,
    val maxFps: Int
) {
    DATA_SAVER(640, 50, 8),
    BALANCED(960, 60, 12),
    SHARP(1280, 65, 15);

    companion object {
        fun fromName(name: String?): StreamQuality =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
