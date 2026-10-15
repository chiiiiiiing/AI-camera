from __future__ import annotations

import base64
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import numpy as np
from PIL import Image

import train_guidance_quality as quality


class StructureAndFeaturesTests(unittest.TestCase):
    def test_black_white_and_missing_face_match_mobile_contract(self):
        black = quality.color_features(Image.new("RGB", (30, 20), (0, 0, 0)))
        white = quality.color_features(Image.new("RGB", (30, 20), (255, 255, 255)))
        np.testing.assert_allclose(black, [0, 0, 1, 0, 0, 0, 0, 0], atol=1e-6)
        np.testing.assert_allclose(white, [1, 0, 0, 1, 0, 0, 0, 0], atol=1e-6)

    def test_face_feature_order_and_geometry(self):
        values = quality.color_features(Image.new("RGB", (2, 2), (255, 0, 0)), [.2, .3, .2, .4])
        np.testing.assert_allclose(values, [.299, 0, 0, 0, .08, .3, .5, 1], atol=1e-6)

    def test_face_requires_single_reliable_unclipped_detection(self):
        face = {"bbox": [.2, .1, .3, .4], "confidence": .9, "clipped": False}
        self.assertIsNotNone(quality.valid_face({"face_count": 1, "faces": [face]}))
        for row in ({"face_count": 2, "faces": [face, face]}, {"faces": [{**face, "clipped": True}]},
                    {"faces": [{**face, "confidence": .7}]}, {"faces": [{**face, "bbox": [0, 0, float('nan'), .4]}]}):
            self.assertIsNone(quality.valid_face(row))

    def test_crop_candidates_keep_whole_detected_face(self):
        face = [.4, .25, .2, .2]
        crops = quality.safe_crops(face)
        self.assertEqual(len(crops), 2)
        for crop in crops:
            x, y, w, h = quality.transformed_face(face, crop)
            self.assertGreaterEqual(min(x, y), 0)
            self.assertLessEqual(max(x + w, y + h), 1)
        self.assertEqual(quality.safe_crops(None), [])
        self.assertEqual(quality.safe_crops([0, 0, 1, 1]), [])

    def test_cut_subject_is_rejected_not_clamped(self):
        with self.assertRaises(ValueError):
            quality.transformed_face([.1, .1, .6, .6], (.2, .2, .8, .8))


class GroupAndPairTests(unittest.TestCase):
    def test_split_is_order_independent_and_group_preserving(self):
        rows = [{"source_group_id": f"g{i}", "photo_type": "portrait"} for i in range(40)]
        assignments = quality.source_splits(rows + [rows[1]])
        self.assertEqual(assignments, quality.source_splits(list(reversed(rows)) + [rows[1]]))
        self.assertEqual(len(assignments), 40)
        self.assertEqual(list(assignments.values()).count("acceptance"), 10)
        self.assertEqual(list(assignments.values()).count("val"), 8)

    def test_missing_source_group_rejected(self):
        with self.assertRaises(ValueError):
            quality.source_splits([{}])

    def test_unstable_teacher_rankings_abstain(self):
        common = {"source_id": "one", "source_group_id": "g", "split": "train", "scene": "portrait"}
        records = [{**common, "kind": "current"}, {**common, "kind": "crop_0"}]
        self.assertEqual(quality.make_pairs(records, np.array([[5., 5.], [5.4, 4.6]])), [])
        pairs = quality.make_pairs(records, np.array([[5., 5.], [5.4, 5.3]]))
        self.assertEqual(pairs[0]["target"], -1)
        self.assertEqual(pairs[0]["kind"], "teacher_preference")

    def test_teacher_and_known_defect_are_distinct_supervision(self):
        common = {"source_id": "one", "source_group_id": "g", "split": "train", "scene": "portrait"}
        records = [{**common, "kind": "current"}, {**common, "kind": "defect_blur"}]
        pairs = quality.make_pairs(records, np.array([[5., 5.], [5.5, 5.5]]))
        self.assertEqual([(p["kind"], p["target"]) for p in pairs], [("teacher_preference", -1), ("known_defect", 1)])

    def test_bootstrap_uses_groups_not_duplicate_rows(self):
        first = quality.bootstrap_lower([True, False], ["a", "b"])
        duplicated = quality.bootstrap_lower([True] * 100 + [False], ["a"] * 100 + ["b"])
        self.assertEqual(first, duplicated)
        self.assertEqual(quality.bootstrap_lower([True] * 100, ["same"] * 100), 0.)

    def test_model_ties_are_incorrect_and_abstained(self):
        pair = {"left": 0, "right": 1, "target": 1, "source_group_id": "g", "scene": "portrait",
                "kind": "teacher_preference", "view_kind": "crop_0"}
        report = quality.pair_metrics(np.array([1., 1.]), [pair])
        self.assertEqual(report["pairwise_agreement"], 0.)
        self.assertEqual(report["coverage"], 0.)
        self.assertEqual(report["rejection_rate"], 1.)

    def test_historical_holdout_and_synthetic_never_enter_head_training(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [{"template_id": f"i{i}", "source_group_id": f"g{i}", "photo_type": "portrait", "original_split": "train",
                     "reference_eligible": True, "source_only": False} for i in range(20)]
            rows.extend([{**rows[0], "template_id": "heldout", "original_split": "test"},
                         {**rows[1], "template_id": "synthetic", "source_group_id": "syn", "synthetic": True},
                         {**rows[2], "template_id": "cross-split", "source_group_id": "historical", "historical_holdout_group": True}])
            source = root / "source.jsonl"
            source.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
            encoder = root / "encoder"
            encoder.write_bytes(b"frozen")
            (root / "teacher-provenance.json").write_text("{}", encoding="utf-8")
            actual = quality.prepare_sources(SimpleNamespace(source_manifest=source, encoder=encoder, vendor_dir=root, output_dir=root))
            self.assertFalse(any(r["source_group_id"] in {"g0", "syn", "historical"} for r in actual))


class DeploymentGateTests(unittest.TestCase):
    def setUp(self):
        self.good = {"acceptance_cluster_count": 35, "scene_cluster_counts": {"portrait": 35}, "pairwise_agreement": .8,
                     "bootstrap_lower95": .7, "defect_agreement_by_kind": {"defect_blur": .9}}
        self.base = {**self.good, "pairwise_agreement": .7, "defect_agreement_by_kind": {"defect_blur": .85}}

    def test_all_required_gates_pass_without_probability_claim(self):
        self.assertTrue(all(quality.deployment_gates(self.good, self.base, True, 180000).values()))

    def test_each_regression_or_missing_coverage_blocks_promotion(self):
        variants = [{**self.good, "acceptance_cluster_count": 29}, {**self.good, "scene_cluster_counts": {"portrait": 9}},
                    {**self.good, "pairwise_agreement": .69}, {**self.good, "bootstrap_lower95": .5},
                    {**self.good, "pairwise_agreement": .7}, {**self.good, "defect_agreement_by_kind": {"defect_blur": .84}}]
        for bad in variants:
            self.assertFalse(all(quality.deployment_gates(bad, self.base, True, 180000).values()))
        self.assertFalse(all(quality.deployment_gates(self.good, self.base, False, 180000).values()))
        self.assertFalse(all(quality.deployment_gates(self.good, self.base, True, 262145).values()))

    def test_export_matches_mobile_rowmajor_math_and_budget(self):
        import torch
        from torch import nn
        torch.manual_seed(1)
        model = nn.Sequential(nn.Linear(520, 64), nn.ReLU(), nn.Linear(64, 1))
        exported = quality.export_head(model, "a" * 64, False)
        weights = np.frombuffer(base64.b64decode(exported["weights_f32_base64"]), dtype="<f4")
        self.assertEqual(len(weights), quality.FLOAT_COUNT)
        self.assertLess(len(json.dumps(exported).encode()), 262144)
        features = np.arange(520, dtype=np.float32) / 520
        w1, b1, w2, b2 = weights[:33280].reshape(64, 520), weights[33280:33344], weights[33344:33408], weights[-1]
        actual = np.maximum(w1 @ features + b1, 0) @ w2 + b2
        expected = model(torch.from_numpy(features)).detach().numpy()[0]
        self.assertAlmostEqual(float(actual), float(expected), places=6)
        self.assertEqual(exported["enabled_scenes"], [])


if __name__ == "__main__":
    unittest.main()
