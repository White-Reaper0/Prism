package com.nevcortex.prism.perception

/**
 * Perception model slots. The earlier brief referred to "existing" YOLO / Depth Anything V2-Small /
 * SegFormer-B0 / P.R.I.S.M. traversability models. This workspace contained no such code, so these
 * are SLOTS with an honest [available] = false until a model artifact + runtime are wired in
 * (ONNX/TFLite). The app never claims a model ran when it did not.
 *
 * The intent is that perception, visual-inertial localization, mapping, and uncertainty are one
 * coherent system; perception results should be consumed by the evidence/mapping modules through
 * defined interfaces, not by feeding IMU values into an unrelated model.
 */
interface PerceptionModel {
    val id: String
    val name: String
    val available: Boolean
    val note: String
    /** Inputs/outputs declared so downstream modules can be wired without a hidden coupling. */
    val consumesFrameTimestamps: Boolean
    val consumesImu: Boolean
}

object YoloDetector : PerceptionModel {
    override val id = "yolo_detector"
    override val name = "YOLO detector"
    override val available = false
    override val note = "No model artifact/runtime bundled. Add ONNX/TFLite weights + a delegate."
    override val consumesFrameTimestamps = true
    override val consumesImu = false
}

object DepthAnythingV2Small : PerceptionModel {
    override val id = "depth_anything_v2_small"
    override val name = "Depth Anything V2-Small"
    override val available = false
    override val note = "No model artifact/runtime bundled."
    override val consumesFrameTimestamps = true
    override val consumesImu = false
}

object SegFormerB0Terrain : PerceptionModel {
    override val id = "segformer_b0_terrain"
    override val name = "SegFormer-B0 terrain segmentation"
    override val available = false
    override val note = "No model artifact/runtime bundled."
    override val consumesFrameTimestamps = true
    override val consumesImu = false
}

/** Registry the UI and evidence modules inspect. */
object PerceptionPipeline {
    val models: List<PerceptionModel> = listOf(YoloDetector, DepthAnythingV2Small, SegFormerB0Terrain)
    fun status(): String =
        if (models.none { it.available }) "No perception models are bundled. Add model artifacts " +
            "and wire their runtimes before enabling inference."
        else "${models.count { it.available }} model(s) available."
}
