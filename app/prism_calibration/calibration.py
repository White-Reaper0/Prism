"""
Nevcortex P.R.I.S.M. — Camera Intrinsic Calibration
=====================================================

Target:   Standard checkerboard, 9×7 squares = (8,6) inner corners
Images:   app/prism_calibration/images/
Output:   app/prism_calibration/output/

Two resolutions are present in the image set:
  - 1600×715  (landscape, 73 images)
  - 715×1600  (portrait,  32 images)

This script calibrates the LANDSCAPE orientation (the majority set) because
camera-intrinsic calibration requires a consistent image resolution.  If you
need portrait calibration, re-run with CALIBRATE_LANDSCAPE = False.

Physical square size (SQUARE_SIZE_MM):
  The target was displayed on a Samsung monitor.  The 24 mm value stated in
  the project description has NOT been independently measured against a ruler.
  Until the physical size is verified by measurement, all metric estimates
  (focal length in mm, etc.) must be treated as UNVERIFIED SCALE.  Pixel-level
  quantities (reprojection error, normalised intrinsics) are not affected by
  the square size and remain valid regardless.

NOTE: cv2.imread fails silently on Windows paths that contain spaces.
      All I/O uses np.fromfile + cv2.imdecode / cv2.imencode + tofile.
"""

import cv2
import numpy as np
import glob
import os
import json
import datetime as _dt

# ─────────────────────────── CONFIGURATION ───────────────────────────────────

# Inner-corner count confirmed by visual inspection of annotated images.
# The board has 9 columns × 7 rows of squares → 8×6 internal corners.
CHECKERBOARD = (8, 6)

# Physical square size in millimetres.
# ⚠  UNVERIFIED — stated value; target displayed on Samsung monitor.
#    Mark as None to make the unverified status explicit in outputs.
SQUARE_SIZE_MM_STATED  = 24.0
SQUARE_SIZE_MM_VERIFIED = None   # set to a float once physically measured

# Which orientation to calibrate (two resolutions exist in the dataset)
CALIBRATE_LANDSCAPE = True        # True → 1600×715;  False → 715×1600

IMAGE_DIR  = "images"
OUTPUT_DIR = "output"

os.makedirs(OUTPUT_DIR, exist_ok=True)

# ─────────────────────────── HELPERS ─────────────────────────────────────────

def safe_imread(path: str) -> np.ndarray | None:
    """Read image bypassing cv2.imread's silent failure on paths with spaces."""
    try:
        arr = np.fromfile(path, dtype=np.uint8)
        return cv2.imdecode(arr, cv2.IMREAD_COLOR)
    except Exception:
        return None


def safe_imwrite(path: str, img: np.ndarray) -> bool:
    ext = os.path.splitext(path)[1].lower() or ".jpg"
    ok, buf = cv2.imencode(ext, img)
    if ok:
        buf.tofile(path)
    return ok


def per_image_rms(obj_pts, img_pts, rvec, tvec, K, D) -> float:
    """
    RMS reprojection error for a single image.

    Both observed and projected points are cast to float32 with shape (N,2)
    before calling cv2.norm, which requires identical types.
    """
    projected, _ = cv2.projectPoints(obj_pts, rvec, tvec, K, D)

    observed = np.asarray(img_pts,  dtype=np.float32).reshape(-1, 2)
    predicted = np.asarray(projected, dtype=np.float32).reshape(-1, 2)

    if observed.shape != predicted.shape:
        raise ValueError(
            f"Reprojection shape mismatch: "
            f"observed={observed.shape} vs predicted={predicted.shape}"
        )

    return cv2.norm(observed, predicted, cv2.NORM_L2) / np.sqrt(len(observed))

# ─────────────────────────── OBJECT POINTS ───────────────────────────────────

# 3-D coordinates of inner corners.  Z = 0 (flat target).
# Units: millimetres (using stated square size).
objp = np.zeros((CHECKERBOARD[0] * CHECKERBOARD[1], 3), np.float32)
objp[:, :2] = np.mgrid[
    0:CHECKERBOARD[0],
    0:CHECKERBOARD[1]
].T.reshape(-1, 2)
objp *= SQUARE_SIZE_MM_STATED   # scale; label as unverified below

# ─────────────────────────── IMAGE DISCOVERY ─────────────────────────────────

all_images = sorted(
    glob.glob(os.path.join(IMAGE_DIR, "*.jpg"))  +
    glob.glob(os.path.join(IMAGE_DIR, "*.jpeg")) +
    glob.glob(os.path.join(IMAGE_DIR, "*.png"))
)

if not all_images:
    raise RuntimeError(f"No images found in {IMAGE_DIR}/")

print(f"Found {len(all_images)} images in {IMAGE_DIR}/")

# Determine the target resolution
target_size = (1600, 715) if CALIBRATE_LANDSCAPE else (715, 1600)
orientation_label = "landscape" if CALIBRATE_LANDSCAPE else "portrait"
print(f"Calibrating {orientation_label} orientation: {target_size[0]}×{target_size[1]}")

# ─────────────────────────── CORNER DETECTION ────────────────────────────────

object_points = []   # 3-D points (same objp repeated per accepted image)
image_points  = []   # 2-D corner observations
accepted      = []   # filenames accepted
rejected      = []   # (filename, reason) tuples

for filename in all_images:
    image = safe_imread(filename)
    basename = os.path.basename(filename)

    if image is None:
        rejected.append((basename, "imread failed (corrupt or unreadable)"))
        print(f"  SKIP  {basename}: unreadable")
        continue

    h, w = image.shape[:2]
    current_size = (w, h)

    if current_size != target_size:
        rejected.append((basename, f"wrong resolution {w}×{h}, expected {target_size[0]}×{target_size[1]}"))
        print(f"  SKIP  {basename}: resolution {w}×{h} != {target_size[0]}×{target_size[1]}")
        continue

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

    found, corners = cv2.findChessboardCornersSB(
        gray,
        CHECKERBOARD,
        flags=cv2.CALIB_CB_NORMALIZE_IMAGE
    )

    if found:
        object_points.append(objp.copy())
        image_points.append(corners)
        accepted.append(filename)
        print(f"  OK    {basename}")
    else:
        rejected.append((basename, "corners not found"))
        print(f"  FAIL  {basename}: corners not found")

print(f"\nDetection complete — accepted: {len(accepted)}, rejected: {len(rejected)}")

if len(accepted) < 10:
    raise RuntimeError(
        f"Only {len(accepted)} images accepted; need ≥10 for reliable calibration."
    )

# ─────────────────────────── CALIBRATION ─────────────────────────────────────

print("\nRunning cv2.calibrateCamera …")

rms_overall, camera_matrix, distortion, rvecs, tvecs = cv2.calibrateCamera(
    object_points,
    image_points,
    target_size,   # (width, height)
    None,
    None
)

print(f"Overall RMS reprojection error: {rms_overall:.4f} px")

# ─────────────────────────── PER-IMAGE ERRORS ────────────────────────────────

per_view_errors = []
for i in range(len(object_points)):
    err = per_image_rms(
        object_points[i], image_points[i],
        rvecs[i], tvecs[i],
        camera_matrix, distortion
    )
    per_view_errors.append(float(err))

per_view_errors_arr = np.array(per_view_errors)
mean_err = float(np.mean(per_view_errors_arr))
std_err  = float(np.std(per_view_errors_arr))
max_err  = float(np.max(per_view_errors_arr))
max_err_idx = int(np.argmax(per_view_errors_arr))

# Flag outlier images (error > mean + 2*std)
outlier_threshold = mean_err + 2.0 * std_err
outliers = [
    (os.path.basename(accepted[i]), per_view_errors[i])
    for i in range(len(accepted))
    if per_view_errors[i] > outlier_threshold
]

print(f"\nPer-image error — mean: {mean_err:.4f}, std: {std_err:.4f}, max: {max_err:.4f} px")
if outliers:
    print(f"Outliers (> {outlier_threshold:.4f} px): {len(outliers)}")
    for name, e in sorted(outliers, key=lambda x: -x[1]):
        print(f"  {name}: {e:.4f} px")

# ─────────────────────────── OPTIONAL REFINED CALIBRATION ────────────────────
# If there are outliers, run a second calibration excluding them and report
# both results.  We do NOT discard the original result.

refined_rms = None
refined_K   = None
refined_D   = None
refined_accepted_count = None

if outliers:
    outlier_basenames = {name for name, _ in outliers}
    keep = [
        i for i, f in enumerate(accepted)
        if os.path.basename(f) not in outlier_basenames
    ]
    if len(keep) >= 10:
        print(f"\nRefined calibration excluding {len(outliers)} outlier(s) …")
        refined_rms, refined_K, refined_D, _, _ = cv2.calibrateCamera(
            [object_points[i] for i in keep],
            [image_points[i]  for i in keep],
            target_size,
            None, None
        )
        refined_accepted_count = len(keep)
        print(f"Refined RMS: {refined_rms:.4f} px  (from {refined_accepted_count} images)")
    else:
        print("Too few images after outlier removal — skipping refined calibration.")

# ─────────────────────────── SAVE ARTIFACTS ──────────────────────────────────

# 1. NPZ
npz_path = os.path.join(OUTPUT_DIR, "camera_calibration.npz")
save_dict = dict(
    camera_matrix            = camera_matrix,
    distortion_coefficients  = distortion,
    image_width              = np.int32(target_size[0]),
    image_height             = np.int32(target_size[1]),
    rms_error                = np.float64(rms_overall),
    square_size_mm_stated    = np.float64(SQUARE_SIZE_MM_STATED),
    square_size_mm_verified  = np.array([SQUARE_SIZE_MM_VERIFIED
                                         if SQUARE_SIZE_MM_VERIFIED is not None
                                         else np.nan]),
    per_view_errors          = per_view_errors_arr,
    checkerboard             = np.array(CHECKERBOARD),
    orientation              = np.bytes_(orientation_label),
)
if refined_K is not None:
    save_dict["refined_camera_matrix"]           = refined_K
    save_dict["refined_distortion_coefficients"] = refined_D
    save_dict["refined_rms_error"]               = np.float64(refined_rms)

np.savez(npz_path, **save_dict)
print(f"\nSaved: {npz_path}")

# 2. JSON
scale_status = (
    "VERIFIED" if SQUARE_SIZE_MM_VERIFIED is not None else "UNVERIFIED_STATED_VALUE"
)
json_result = {
    "schema":                    "prism.camera_calibration.v1",
    "calibration_date":          _dt.datetime.now(_dt.timezone.utc).isoformat(),
    "calibration_status":        "PENDING_PHYSICAL_SCALE_VERIFICATION",
    "orientation":               orientation_label,
    "image_width":               target_size[0],
    "image_height":              target_size[1],
    "checkerboard_inner_corners": list(CHECKERBOARD),
    "square_size_mm_stated":     SQUARE_SIZE_MM_STATED,
    "square_size_mm_verified":   SQUARE_SIZE_MM_VERIFIED,
    "scale_status":              scale_status,
    "accepted_images":           len(accepted),
    "rejected_images":           len(rejected),
    "rms_reprojection_error_px": round(rms_overall, 6),
    "camera_matrix":             camera_matrix.tolist(),
    "distortion_coefficients":   distortion.tolist(),
    "per_view_errors_px": {
        os.path.basename(accepted[i]): round(per_view_errors[i], 6)
        for i in range(len(accepted))
    },
    "per_view_stats": {
        "mean_px":  round(mean_err, 6),
        "std_px":   round(std_err, 6),
        "max_px":   round(max_err, 6),
        "max_image": os.path.basename(accepted[max_err_idx]),
    },
    "outliers": [{"file": n, "error_px": round(e, 6)} for n, e in outliers],
    "rejected_files": [{"file": r[0], "reason": r[1]} for r in rejected],
    "accepted_files": [os.path.basename(f) for f in accepted],
}
if refined_K is not None:
    json_result["refined_calibration"] = {
        "rms_reprojection_error_px": round(refined_rms, 6),
        "accepted_images":           refined_accepted_count,
        "camera_matrix":             refined_K.tolist(),
        "distortion_coefficients":   refined_D.tolist(),
    }

json_path = os.path.join(OUTPUT_DIR, "camera_calibration.json")
with open(json_path, "w", encoding="utf-8") as fh:
    json.dump(json_result, fh, indent=2)
print(f"Saved: {json_path}")

# 3. Human-readable report
report_lines = [
    "=" * 70,
    "Nevcortex P.R.I.S.M. — Camera Intrinsic Calibration Report",
    "=" * 70,
    f"Date (UTC):         {_dt.datetime.now(_dt.timezone.utc).isoformat()}",
    f"Orientation:        {orientation_label}",
    f"Image resolution:   {target_size[0]} × {target_size[1]} px",
    f"Target geometry:    ({CHECKERBOARD[0]}, {CHECKERBOARD[1]}) inner corners "
    f"(9×7 squares confirmed by visual inspection)",
    "",
    "PHYSICAL SCALE",
    "-" * 40,
    f"  Stated square size:   {SQUARE_SIZE_MM_STATED} mm",
    f"  Verified square size: {'%.2f mm' % SQUARE_SIZE_MM_VERIFIED if SQUARE_SIZE_MM_VERIFIED else 'NOT VERIFIED'}",
    "  ⚠  Target displayed on Samsung monitor.  Physical size has not been",
    "     measured against a ruler.  All metric-scale values (e.g. focal length",
    "     in mm) are proportional to the stated value and are unreliable until",
    "     physically verified.  Pixel-level intrinsics and distortion",
    "     coefficients are unaffected by scale.",
    "",
    "IMAGE STATISTICS",
    "-" * 40,
    f"  Total images found:  {len(all_images)}",
    f"  Accepted:            {len(accepted)}",
    f"  Rejected:            {len(rejected)}",
    "",
    "CALIBRATION RESULTS (primary)",
    "-" * 40,
    f"  Overall RMS error:   {rms_overall:.4f} px",
    f"  Per-image mean:      {mean_err:.4f} px",
    f"  Per-image std:       {std_err:.4f} px",
    f"  Per-image max:       {max_err:.4f} px  ({os.path.basename(accepted[max_err_idx])})",
    "",
    "  Camera matrix K:",
    f"    fx = {camera_matrix[0,0]:.4f} px",
    f"    fy = {camera_matrix[1,1]:.4f} px",
    f"    cx = {camera_matrix[0,2]:.4f} px",
    f"    cy = {camera_matrix[1,2]:.4f} px",
    "",
    f"  Full K matrix:",
    f"    {camera_matrix[0].tolist()}",
    f"    {camera_matrix[1].tolist()}",
    f"    {camera_matrix[2].tolist()}",
    "",
    f"  Distortion coefficients D (k1,k2,p1,p2,k3):",
    f"    {distortion.flatten().tolist()}",
]

if refined_K is not None:
    report_lines += [
        "",
        f"REFINED CALIBRATION (excluding {len(outliers)} outlier images)",
        "-" * 40,
        f"  Refined RMS error:   {refined_rms:.4f} px",
        f"  Accepted images:     {refined_accepted_count}",
        f"  Refined K:",
        f"    fx = {refined_K[0,0]:.4f} px",
        f"    fy = {refined_K[1,1]:.4f} px",
        f"    cx = {refined_K[0,2]:.4f} px",
        f"    cy = {refined_K[1,2]:.4f} px",
        f"  Refined D: {refined_D.flatten().tolist()}",
    ]

report_lines += [
    "",
    "OUTLIERS (per-image error > mean + 2×std)",
    "-" * 40,
]
if outliers:
    for name, e in sorted(outliers, key=lambda x: -x[1]):
        report_lines.append(f"  {name}: {e:.4f} px")
else:
    report_lines.append("  None")

report_lines += [
    "",
    "REJECTED IMAGES",
    "-" * 40,
]
for rfile, reason in rejected:
    report_lines.append(f"  {rfile}: {reason}")

report_lines += [
    "",
    "PER-IMAGE REPROJECTION ERRORS (accepted images)",
    "-" * 40,
]
for i, fname in enumerate(accepted):
    flag = " ← OUTLIER" if os.path.basename(fname) in {n for n,_ in outliers} else ""
    report_lines.append(f"  [{i:3d}] {os.path.basename(fname)}: {per_view_errors[i]:.4f} px{flag}")

report_lines += [
    "",
    "LIMITATIONS & ASSUMPTIONS",
    "-" * 40,
    "  1. Physical square size is UNVERIFIED (target on a monitor screen).",
    "     Measure the printed/displayed squares with a ruler before trusting",
    "     metric outputs.",
    "  2. This calibration covers the LANDSCAPE (1600×715) images only.",
    "     Portrait images (715×1600) form a separate 32-image set and may",
    "     represent a different camera mode or phone orientation;",
    "     run separately if needed.",
    "  3. findChessboardCornersSB (Saddle-point detector) was used.",
    "     It is more robust than the classic detector for monitor-displayed",
    "     targets with lower contrast.",
    "  4. This is camera INTRINSIC calibration only.",
    "     Camera-to-IMU extrinsic calibration has NOT been performed.",
    "  5. Rolling shutter effects have not been characterised.",
    "  6. These parameters are valid for the resolution and camera mode",
    "     used to capture these images. A different resolution, zoom level,",
    "     or camera app may require re-calibration.",
    "",
    "FILES GENERATED",
    "-" * 40,
    f"  output/camera_calibration.npz   — NumPy archive (primary artifact)",
    f"  output/camera_calibration.json  — Human-readable JSON",
    f"  output/calibration_report.txt   — This report",
    f"  output/undistorted_samples/     — Original vs undistorted comparisons",
    "=" * 70,
]

report_text = "\n".join(report_lines)
print("\n" + report_text)

report_path = os.path.join(OUTPUT_DIR, "calibration_report.txt")
with open(report_path, "w", encoding="utf-8") as fh:
    fh.write(report_text)
print(f"\nSaved: {report_path}")

# 4. Undistorted samples
samples_dir = os.path.join(OUTPUT_DIR, "undistorted_samples")
os.makedirs(samples_dir, exist_ok=True)

# Pick 5 images spread evenly across the accepted set
step = max(1, len(accepted) // 5)
sample_indices = list(range(0, len(accepted), step))[:5]

new_K, roi = cv2.getOptimalNewCameraMatrix(
    camera_matrix, distortion, target_size, 1, target_size
)

for idx in sample_indices:
    fname = accepted[idx]
    img   = safe_imread(fname)
    if img is None:
        continue
    undist = cv2.undistort(img, camera_matrix, distortion, None, new_K)

    # Side-by-side at reduced scale
    scale = min(700 / img.shape[1], 1.0)
    orig_s  = cv2.resize(img,   None, fx=scale, fy=scale)
    und_s   = cv2.resize(undist, None, fx=scale, fy=scale)
    # Add labels
    for label, panel in [("ORIGINAL", orig_s), ("UNDISTORTED", und_s)]:
        cv2.putText(panel, label, (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
    side = np.hstack([orig_s, und_s])

    out_name = os.path.join(
        samples_dir,
        f"sample_{idx:03d}_{os.path.basename(fname)}"
    )
    safe_imwrite(out_name, side)
    print(f"Saved undistorted sample: {out_name}")

print("\n✓ Calibration complete.")
print(f"  RMS reprojection error : {rms_overall:.4f} px")
print(f"  Accepted               : {len(accepted)} images")
print(f"  Rejected               : {len(rejected)} images")
print(f"  Output directory       : {os.path.abspath(OUTPUT_DIR)}")
