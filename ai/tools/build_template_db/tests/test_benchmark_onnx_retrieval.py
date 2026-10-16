import unittest
import tempfile
from pathlib import Path

import numpy as np

from benchmark_onnx_retrieval import non_regression, paired_cosine, prepare_batch, write_json


class BenchmarkOnnxRetrievalTests(unittest.TestCase):
    def test_hashed_report_uses_portable_lf_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "report.json"
            write_json(path, {"count": 1})
            self.assertEqual(path.read_bytes(), b'{\n  "count": 1\n}\n')

    def test_raw_pixels_map_to_expected_rgb_nchw_range(self):
        pixels = np.array([[[[0, 255]], [[255, 0]], [[128, 128]]]], dtype=np.uint8)
        tensor = prepare_batch(pixels)
        self.assertEqual(tensor.dtype, np.float32)
        self.assertEqual(tensor.shape, (1, 3, 1, 2))
        self.assertTrue(tensor.flags.c_contiguous)
        np.testing.assert_array_equal(tensor[0, :2], [[[-1, 1]], [[1, -1]]])
        self.assertAlmostEqual(float(tensor[0, 2, 0, 0]), 1 / 255, places=6)
        self.assertEqual(int(pixels[0, 0, 0, 1]), 255)

    def test_paired_cosine_reports_numerical_drift_separately(self):
        reference = np.array([[1.0, 0.0], [0.0, 1.0]])
        candidate = np.array([[2.0, 0.0], [1.0, 0.0]])
        report = paired_cosine(reference, candidate)
        self.assertEqual(report["count"], 2)
        self.assertEqual(report["mean"], 0.5)
        self.assertEqual(report["minimum"], 0.0)
        self.assertIn("not_retrieval_quality", report["scope"])

    def test_retrieval_gate_rejects_one_recall_regression_despite_other_gains(self):
        baseline = {"raw": {"scene_macro_recall_at_k": {"1": 0.8, "5": 0.9}, "teacher_top5_overlap": 0.3}}
        candidate = {"raw": {"scene_macro_recall_at_k": {"1": 0.9, "5": 0.89}, "teacher_top5_overlap": 0.5}}
        for item in (baseline, candidate):
            item["raw"]["scene_micro_recall_at_k"] = {"5": 0.9}
        report = non_regression(candidate, baseline)
        self.assertFalse(report["passed"])
        self.assertLess(report["deltas"]["scene_macro_recall_at_5"], 0)

    def test_retrieval_gate_accepts_identical_baseline_and_rejects_teacher_regression(self):
        baseline = {"raw": {"scene_macro_recall_at_k": {"1": 0.8, "5": 0.9}, "teacher_top5_overlap": 0.3}}
        baseline["raw"]["scene_micro_recall_at_k"] = {"5": 0.9}
        self.assertTrue(non_regression(baseline, baseline)["passed"])
        candidate = {"raw": {"scene_macro_recall_at_k": {"1": 0.8, "5": 0.9}, "teacher_top5_overlap": 0.29}}
        candidate["raw"]["scene_micro_recall_at_k"] = {"5": 0.9}
        self.assertFalse(non_regression(candidate, baseline)["passed"])

    def test_micro_recall_regression_and_invalid_metrics_cannot_pass(self):
        baseline = {"raw": {"scene_macro_recall_at_k": {"1": 0.8, "5": 0.9},
                            "scene_micro_recall_at_k": {"5": 0.9}, "teacher_top5_overlap": 0.3}}
        for micro in (0.89, float("nan"), float("inf"), 1.1):
            candidate = {"raw": dict(baseline["raw"], scene_micro_recall_at_k={"5": micro})}
            with self.subTest(micro=micro):
                self.assertFalse(non_regression(candidate, baseline)["passed"])


if __name__ == "__main__":
    unittest.main()
