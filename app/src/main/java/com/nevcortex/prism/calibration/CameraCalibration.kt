package com.nevcortex.prism.calibration

import android.content.Context
import com.nevcortex.prism.core.util.Time
import com.nevcortex.prism.model.CalibrationProvenance
import com.nevcortex.prism.model.CameraIntrinsics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Camera intrinsic calibration support. On-device nonlinear intrinsic estimation needs a
 * chessboard/ChArUco detector + bundle adjustment (typically OpenCV/Kalibr). Rather than ship a
 * fragile hand-rolled solver, P.R.I.S.M. supports two honest paths:
 *
 *  1. TARGET CAPTURE: save timestamped frames of a calibration target for offline estimation
 *     (OpenCV/Kalibr/MATLAB). [writeTargetManifest] records the captured frames.
 *  2. MANUAL ENTRY: enter intrinsics produced offline, or factory-reported specs, explicitly
 *     tagged with provenance so an ESTIMATE is never confused with a verified calibration.
 *
 * We never fabricate intrinsics. Unknown values stay NaN and are flagged in the UI.
 */
class CameraCalibration(context: Context) {

    private val root: File = File(context.filesDir, "calibration").also { it.mkdirs() }
    private val targetsRoot: File = File(root, "targets").also { it.mkdirs() }

    fun newTargetSessionDir(name: String): File {
        val dir = File(targetsRoot, "${name}_${Time.wallMs()}").also { it.mkdirs() }
        File(dir, "images").mkdirs()
        return dir
    }

    /** Write a manifest listing captured target frames with their timestamps. */
    fun writeTargetManifest(dir: File, frames: List<TargetFrame>): File {
        val manifest = File(dir, "target_manifest.json")
        val arr = JSONArray()
        frames.forEach { f ->
            arr.put(JSONObject().apply {
                put("index", f.index)
                put("file", "images/${f.fileName}")
                put("cameraTimestampNs", f.cameraTimestampNs)
                put("arrivalElapsedNs", f.arrivalElapsedNs)
                put("width", f.width)
                put("height", f.height)
            })
        }
        val json = JSONObject().apply {
            put("schema", "prism.target_capture.v1")
            put("note", "Calibration target frames. Run offline intrinsic estimation (e.g. " +
                "OpenCV calibrateCamera / Kalibr) and import the result via manual entry.")
            put("frameCount", frames.size)
            put("frames", arr)
        }
        manifest.writeText(json.toString(2))
        return manifest
    }

    /** Manual intrinsics entered by the user (typically from an offline calibration run). */
    fun manualIntrinsics(
        w: Int, h: Int,
        fx: Float, fy: Float, cx: Float, cy: Float,
        distortion: FloatArray, reproErrPx: Float, note: String
    ): CameraIntrinsics = CameraIntrinsics(
        createdAtMs = Time.wallMs(),
        provenance = CalibrationProvenance.USER_PROVIDED,
        method = "manual entry (offline calibration result)",
        imageWidth = w, imageHeight = h,
        fx = fx, fy = fy, cx = cx, cy = cy,
        distortionCoeffs = distortion,
        reprojectionErrorPx = reproErrPx,
        uncertaintyNote = if (note.isBlank())
            "User-entered intrinsics. Verify against the same image size used for capture." else note
    )

    /** A factory/unknown placeholder so the UI can show "no intrinsics available" explicitly. */
    fun noIntrinsics(): CameraIntrinsics = CameraIntrinsics(
        createdAtMs = Time.wallMs(),
        provenance = CalibrationProvenance.UNKNOWN,
        method = "none",
        imageWidth = 0, imageHeight = 0,
        fx = Float.NaN, fy = Float.NaN, cx = Float.NaN, cy = Float.NaN,
        distortionCoeffs = FloatArray(0),
        reprojectionErrorPx = -1f,
        uncertaintyNote = "No intrinsics available. Enter offline calibration results or " +
            "factory specs before treating any visual estimate as metric."
    )

    data class TargetFrame(
        val index: Int,
        val fileName: String,
        val cameraTimestampNs: Long,
        val arrivalElapsedNs: Long,
        val width: Int,
        val height: Int
    )
}
