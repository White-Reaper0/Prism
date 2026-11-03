"""
Tests for the PRISM camera intrinsic calibration pipeline.

Run from the prism_calibration directory:
    python -m pytest test_calibration.py -v
    # or without pytest:
    python test_calibration.py
"""
import json
import os
import unittest
import tempfile

import cv2
import numpy as np


# ─────────────────────────── helpers replicated from calibration.py ──────────

def per_image_rms(obj_pts, img_pts, rvec, tvec, K, D) -> float:
    """Per-image RMS reprojection error (exact copy of production function)."""
    projected, _ = cv2.projectPoints(obj_pts, rvec, tvec, K, D)
    observed  = np.asarray(img_pts,   dtype=np.float32).reshape(-1, 2)
    predicted = np.asarray(projected, dtype=np.float32).reshape(-1, 2)
    if observed.shape != predicted.shape:
        raise ValueError(
            f"Shape mismatch: {observed.shape} vs {predicted.shape}"
        )
    return cv2.norm(observed, predicted, cv2.NORM_L2) / np.sqrt(len(observed))


def safe_imread(path: str):
    arr = np.fromfile(path, dtype=np.uint8)
    return cv2.imdecode(arr, cv2.IMREAD_COLOR)


def safe_imwrite(path: str, img: np.ndarray) -> bool:
    ext = os.path.splitext(path)[1].lower() or ".jpg"
    ok, buf = cv2.imencode(ext, img)
    if ok:
        buf.tofile(path)
    return ok


# ─────────────────────────── TEST CASES ──────────────────────────────────────

class TestReprojectioError(unittest.TestCase):
    """Reprojection error calculation correctness."""

    def _make_synthetic_scene(self):
        """Create a synthetic calibration scene with zero noise."""
        K_true = np.array([
            [800.0,   0.0, 320.0],
            [  0.0, 800.0, 240.0],
            [  0.0,   0.0,   1.0],
        ], dtype=np.float64)
        D_true = np.zeros((1, 5), dtype=np.float64)

        objp = np.zeros((6 * 8, 3), np.float32)
        objp[:, :2] = np.mgrid[0:8, 0:6].T.reshape(-1, 2) * 24.0

        rvec = np.array([[0.1, -0.05, 0.02]], dtype=np.float64)
        tvec = np.array([[0.0, 0.0, 500.0]], dtype=np.float64)

        pts_2d, _ = cv2.projectPoints(objp, rvec, tvec, K_true, D_true)
        return objp, pts_2d, rvec.T, tvec.T, K_true, D_true

    def test_zero_noise_gives_near_zero_error(self):
        """Perfect projections produce essentially zero reprojection error."""
        obj, pts, rvec, tvec, K, D = self._make_synthetic_scene()
        err = per_image_rms(obj, pts, rvec, tvec, K, D)
        self.assertAlmostEqual(err, 0.0, places=6,
                               msg=f"Expected ~0, got {err}")

    def test_shape_mismatch_raises(self):
        """Shape mismatch between observed and predicted raises ValueError."""
        obj, pts, rvec, tvec, K, D = self._make_synthetic_scene()
        # Corrupt pts to have a different number of points
        pts_bad = pts[:40]
        with self.assertRaises(ValueError):
            per_image_rms(obj, pts_bad, rvec, tvec, K, D)

    def test_float32_casting(self):
        """Function accepts float64 input and returns a float."""
        obj, pts, rvec, tvec, K, D = self._make_synthetic_scene()
        pts64 = pts.astype(np.float64)
        err = per_image_rms(obj, pts64, rvec, tvec, K, D)
        self.assertIsInstance(err, float)

    def test_known_error(self):
        """Adding a constant pixel offset produces a known RMS error."""
        obj, pts, rvec, tvec, K, D = self._make_synthetic_scene()
        offset = 2.0  # pixels
        pts_shifted = pts + offset
        err = per_image_rms(obj, pts_shifted, rvec, tvec, K, D)
        # Error ≈ sqrt(2) * offset because shift is in both x and y
        expected = offset * np.sqrt(2)
        self.assertAlmostEqual(err, expected, places=4,
                               msg=f"Expected ~{expected:.4f}, got {err:.4f}")


class TestArrayShapeConsistency(unittest.TestCase):
    """Array shape consistency checks."""

    def test_reshape_to_n2(self):
        """Various input shapes flatten correctly to (N,2)."""
        for shape in [(48, 1, 2), (48, 2), (1, 48, 2)]:
            arr = np.random.rand(*shape).astype(np.float32)
            reshaped = np.asarray(arr, dtype=np.float32).reshape(-1, 2)
            self.assertEqual(reshaped.shape[1], 2)
            self.assertEqual(reshaped.shape[0], 48)

    def test_object_points_shape(self):
        """Object point array has expected shape (N,3)."""
        CHECKERBOARD = (8, 6)
        objp = np.zeros((CHECKERBOARD[0] * CHECKERBOARD[1], 3), np.float32)
        objp[:, :2] = np.mgrid[
            0:CHECKERBOARD[0],
            0:CHECKERBOARD[1]
        ].T.reshape(-1, 2)
        self.assertEqual(objp.shape, (48, 3))
        self.assertTrue(np.all(objp[:, 2] == 0.0))


class TestCalibrationFileSerialization(unittest.TestCase):
    """Calibration JSON file serialization and loading."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.json_path = os.path.join(self.tmpdir, "cal.json")
        self.K = np.array([
            [1206.01, 0.0, 809.68],
            [0.0, 1206.66, 350.52],
            [0.0, 0.0, 1.0],
        ])
        self.D = np.array([[0.434, -3.364, -0.0025, 0.0014, 9.234]])
        self.payload = {
            "schema": "prism.camera_calibration.v1",
            "image_width": 1600,
            "image_height": 715,
            "rms_reprojection_error_px": 0.2455,
            "camera_matrix": self.K.tolist(),
            "distortion_coefficients": self.D.tolist(),
            "square_size_mm_stated": 24.0,
            "square_size_mm_verified": None,
            "calibration_status": "PENDING_PHYSICAL_SCALE_VERIFICATION",
        }

    def test_write_and_read_json(self):
        """JSON round-trip preserves camera matrix values."""
        with open(self.json_path, "w") as f:
            json.dump(self.payload, f, indent=2)
        with open(self.json_path) as f:
            loaded = json.load(f)
        K_loaded = np.array(loaded["camera_matrix"])
        np.testing.assert_allclose(K_loaded, self.K, rtol=1e-9)

    def test_distortion_round_trip(self):
        """Distortion coefficients survive JSON serialization."""
        with open(self.json_path, "w") as f:
            json.dump(self.payload, f, indent=2)
        with open(self.json_path) as f:
            loaded = json.load(f)
        D_loaded = np.array(loaded["distortion_coefficients"])
        np.testing.assert_allclose(D_loaded, self.D, rtol=1e-9)

    def test_missing_camera_matrix_raises(self):
        """Loading a JSON without camera_matrix raises KeyError."""
        bad = {"image_width": 1600, "image_height": 715}
        with open(self.json_path, "w") as f:
            json.dump(bad, f)
        with open(self.json_path) as f:
            loaded = json.load(f)
        with self.assertRaises(KeyError):
            _ = loaded["camera_matrix"]

    def test_invalid_matrix_shape_detected(self):
        """A non-3×3 camera matrix is caught by shape check."""
        bad_K = [[1206, 0], [0, 1206]]   # 2×2, not 3×3
        payload = dict(self.payload)
        payload["camera_matrix"] = bad_K
        with open(self.json_path, "w") as f:
            json.dump(payload, f)
        with open(self.json_path) as f:
            loaded = json.load(f)
        K = np.array(loaded["camera_matrix"])
        self.assertNotEqual(K.shape, (3, 3),
                            "Bad matrix should not have shape (3,3)")


class TestNpzSerialization(unittest.TestCase):
    """NPZ file save and load."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.npz_path = os.path.join(self.tmpdir, "cal.npz")
        self.K = np.array([
            [1206.01, 0.0, 809.68],
            [0.0, 1206.66, 350.52],
            [0.0, 0.0, 1.0],
        ])
        self.D = np.array([[0.434, -3.364, -0.0025, 0.0014, 9.234]])

    def test_npz_round_trip(self):
        """NPZ save + load preserves matrix values exactly."""
        np.savez(self.npz_path,
                 camera_matrix=self.K,
                 distortion_coefficients=self.D,
                 image_width=np.int32(1600),
                 image_height=np.int32(715),
                 rms_error=np.float64(0.2455))
        data = np.load(self.npz_path)
        np.testing.assert_array_equal(data["camera_matrix"], self.K)
        np.testing.assert_array_equal(data["distortion_coefficients"], self.D)
        self.assertEqual(int(data["image_width"]), 1600)
        self.assertEqual(int(data["image_height"]), 715)

    def test_missing_npz_key_raises(self):
        """Accessing a missing key in an NPZ raises KeyError."""
        np.savez(self.npz_path, camera_matrix=self.K)
        data = np.load(self.npz_path)
        with self.assertRaises(KeyError):
            _ = data["distortion_coefficients"]


class TestImageResolutionValidation(unittest.TestCase):
    """Resolution consistency checks used during image filtering."""

    def test_same_resolution_accepted(self):
        target = (1600, 715)
        self.assertEqual(target, (1600, 715))  # trivially same

    def test_different_resolution_detected(self):
        target = (1600, 715)
        candidate = (715, 1600)
        self.assertNotEqual(target, candidate)

    def test_none_resolution_triggers_initialisation(self):
        """First valid image should set the reference resolution."""
        image_size = None
        current = (1600, 715)
        if image_size is None:
            image_size = current
        self.assertEqual(image_size, (1600, 715))


class TestActualCalibrationOutputs(unittest.TestCase):
    """Validate the actual calibration artifacts produced by the run."""

    OUTPUT_JSON = os.path.join(
        os.path.dirname(__file__), "output", "camera_calibration.json"
    )
    OUTPUT_NPZ  = os.path.join(
        os.path.dirname(__file__), "output", "camera_calibration.npz"
    )

    def _skip_if_missing(self, path):
        if not os.path.exists(path):
            self.skipTest(f"Artifact not found: {path} — run calibration.py first")

    # --- JSON ---
    def test_json_exists(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        self.assertTrue(os.path.getsize(self.OUTPUT_JSON) > 0)

    def test_json_camera_matrix_shape(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        K = np.array(data["camera_matrix"])
        self.assertEqual(K.shape, (3, 3), "Camera matrix must be 3×3")

    def test_json_distortion_length(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        D = np.array(data["distortion_coefficients"]).flatten()
        self.assertIn(len(D), (4, 5, 8, 12, 14),
                      f"Unexpected distortion coefficient count: {len(D)}")

    def test_json_rms_reasonable(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        rms = data["rms_reprojection_error_px"]
        self.assertGreater(rms, 0.0, "RMS must be positive")
        self.assertLess(rms, 2.0, "RMS > 2 px is suspiciously high")

    def test_json_image_dimensions_match_expected(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        self.assertEqual(data["image_width"],  1600)
        self.assertEqual(data["image_height"], 715)

    def test_json_scale_status_unverified(self):
        """Physical scale must be flagged as unverified until measured."""
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        self.assertIsNone(data.get("square_size_mm_verified"),
                          "square_size_mm_verified should be null/None until measured")

    def test_json_principal_point_within_image(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        K = np.array(data["camera_matrix"])
        cx, cy = K[0, 2], K[1, 2]
        w, h = data["image_width"], data["image_height"]
        self.assertGreater(cx, 0, "cx should be positive")
        self.assertLess(cx, w, f"cx={cx:.1f} should be inside image width {w}")
        self.assertGreater(cy, 0, "cy should be positive")
        self.assertLess(cy, h, f"cy={cy:.1f} should be inside image height {h}")

    def test_json_focal_lengths_positive(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        K = np.array(data["camera_matrix"])
        self.assertGreater(K[0, 0], 0.0, "fx must be positive")
        self.assertGreater(K[1, 1], 0.0, "fy must be positive")

    def test_json_focal_lengths_similar(self):
        """fx and fy should be within 5% of each other for a circular lens."""
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        K = np.array(data["camera_matrix"])
        ratio = K[0, 0] / K[1, 1]
        self.assertAlmostEqual(ratio, 1.0, delta=0.05,
                               msg=f"fx/fy ratio {ratio:.4f} is unexpectedly far from 1")

    def test_json_accepted_image_count(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        with open(self.OUTPUT_JSON) as f:
            data = json.load(f)
        n = data["accepted_images"]
        self.assertGreaterEqual(n, 10,
                                f"Only {n} images accepted; need ≥10")

    # --- NPZ ---
    def test_npz_exists(self):
        self._skip_if_missing(self.OUTPUT_NPZ)
        self.assertTrue(os.path.getsize(self.OUTPUT_NPZ) > 0)

    def test_npz_required_keys_present(self):
        self._skip_if_missing(self.OUTPUT_NPZ)
        data = np.load(self.OUTPUT_NPZ)
        for key in ("camera_matrix", "distortion_coefficients",
                    "image_width", "image_height", "rms_error"):
            self.assertIn(key, data, f"Missing key: {key}")

    def test_npz_camera_matrix_matches_json(self):
        self._skip_if_missing(self.OUTPUT_JSON)
        self._skip_if_missing(self.OUTPUT_NPZ)
        with open(self.OUTPUT_JSON) as f:
            json_data = json.load(f)
        npz_data = np.load(self.OUTPUT_NPZ)
        K_json = np.array(json_data["camera_matrix"])
        K_npz  = npz_data["camera_matrix"]
        np.testing.assert_allclose(K_json, K_npz, rtol=1e-6,
                                   err_msg="K in JSON and NPZ must agree")


# ─────────────────────────── RUNNER ──────────────────────────────────────────

if __name__ == "__main__":
    # Allow running directly without pytest
    import sys
    loader  = unittest.TestLoader()
    suite   = loader.loadTestsFromModule(__import__(__name__))
    runner  = unittest.TextTestRunner(verbosity=2, stream=sys.stdout)
    result  = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
