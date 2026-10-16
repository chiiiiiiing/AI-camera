import base64
import json
import sys
import tempfile
from types import SimpleNamespace
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from framing_preference import (FEATURE_PROTOCOL, HIDDEN_DIM, INPUT_DIM, candidates,
                                decode_head, features, map_subject, predict, rule_baseline)
from train_framing_preference import build_pairs, cluster_metrics
from train_framing_preference import prepare
from audit_framing_preference import keep_current_accuracy
from framing_preference_preprocessing import crop_native, encoder_rgb, encoder_tensor


class FramingPreferenceTest(unittest.TestCase):
    def test_uniform_color_features_and_scene(self):
        values = features(Image.new("RGB", (17, 31), (255, 0, 0)), None, "landscape")
        self.assertEqual(values.shape, (93,))
        np.testing.assert_allclose(values[:80].reshape(16, 5), np.tile([1, 0, 0, 0, 0], (16, 1)))
        self.assertAlmostEqual(float(values[80]), .299, places=6)
        np.testing.assert_allclose(values[84:89], 0)
        np.testing.assert_array_equal(values[89:], [0, 1, 0, 0])

    def test_sampling_uses_full_non_square_frame(self):
        rgb = np.zeros((8, 16, 3), dtype=np.uint8)
        rgb[:, 0:4, 0] = 255
        rgb[:, 12:, 2] = 255
        grid = features(Image.fromarray(rgb), None, "food")[:80].reshape(4, 4, 5)
        np.testing.assert_allclose(grid[:, 0, 0], 1)
        np.testing.assert_allclose(grid[:, 3, 2], 1)
        np.testing.assert_allclose(grid[:, 1:3, :3], 0)

    def test_subject_cut_and_margin_are_rejected(self):
        with self.assertRaises(ValueError):
            map_subject([.01, .1, .8, .8], [.05, .05, .95, .95])
        protected = list(candidates(Image.new("RGB", (100, 100)), [.01, .01, .99, .99]))
        self.assertEqual([v[0] for v in protected], ["current"])

    def test_candidate_count_and_coordinates(self):
        variants = list(candidates(Image.new("RGB", (101, 79)), [.3, .25, .6, .65]))
        self.assertEqual(len(variants), 3)
        for _, image, subject, crop in variants:
            self.assertGreater(image.width, 0)
            self.assertLessEqual(subject[2], 1)
            self.assertLessEqual(subject[3], 1)
            self.assertGreaterEqual(crop[0], 0)

    def test_baseline_accepts_spatial_or_semantic_spatial(self):
        spatial = features(Image.new("RGB", (64, 64)), [.2, .2, .8, .8], "food")
        self.assertAlmostEqual(rule_baseline(spatial), rule_baseline(np.concatenate([np.ones(512), spatial])), places=7)

    def test_teacher_ambiguity_cannot_become_crop_ground_truth(self):
        records = [{"kind": kind, "source_group_id": "g", "split": "acceptance", "scene": "landscape"}
                   for kind in ("current", "crop_0", "crop_1")]
        # First reverses under JPEG, second has too little teacher margin.
        pairs, attempted = build_pairs(records, np.asarray([[5, 5], [5.3, 4.7], [5.05, 5.04]]))
        self.assertEqual(pairs, [])
        self.assertEqual(attempted, {"acceptance:crop": 2})

    def test_clusters_not_duplicate_pair_count_define_accuracy(self):
        pairs = [{"a": 1, "b": 0, "sign": 1, "source_group_id": "a", "split": "acceptance", "scene": "food", "kind": "crop"}] * 10
        pairs += [{"a": 2, "b": 0, "sign": 1, "source_group_id": "b", "split": "acceptance", "scene": "food", "kind": "crop"}]
        metrics = cluster_metrics(np.asarray([0., 1., -1.]), np.zeros((3, INPUT_DIM)), pairs, "acceptance")
        self.assertEqual(metrics["acceptance_cluster_count"], 2)
        self.assertEqual(metrics["pairwise_agreement"], .5)

    def test_model_shape_and_standard_deviation_validation(self):
        weights = np.zeros(HIDDEN_DIM * INPUT_DIM + HIDDEN_DIM * 2 + 1, dtype="<f4")
        weights[-1] = .75
        head = {"schema_version": 1, "feature_protocol": FEATURE_PROTOCOL,
                "input_dim": INPUT_DIM, "hidden_dim": HIDDEN_DIM, "activation": "relu",
                "weight_encoding": "float32-le-base64", "weights_f32_base64": base64.b64encode(weights).decode(),
                "feature_mean": [0.] * INPUT_DIM, "feature_std": [1.] * INPUT_DIM}
        self.assertEqual(float(predict(head, np.zeros(INPUT_DIM))), .75)
        head["feature_std"][2] = 0.
        with self.assertRaises(ValueError):
            decode_head(head)

    def test_keep_current_baseline_is_cluster_macro(self):
        base = {"split": "acceptance", "kind": "crop", "scene": "food"}
        pairs = [dict(base, source_group_id="a", sign=-1)] * 10
        pairs += [dict(base, source_group_id="b", sign=1)]
        self.assertEqual(keep_current_accuracy(pairs), .5)

    def test_canonical_encoder_tensor_constant_color(self):
        image = Image.new("RGB", (173, 291), (255, 0, 128))
        rgb, tensor = encoder_rgb(image), encoder_tensor(image)
        self.assertEqual(rgb.shape, (224, 224, 3))
        self.assertEqual(tensor.shape, (1, 3, 224, 224))
        np.testing.assert_array_equal(rgb[0, 0], [255, 0, 128])
        np.testing.assert_allclose(tensor[0, :, 0, 0], [1., -1., 128/127.5-1], atol=1e-7)

    def test_canonical_crop_matches_kotlin_rounding(self):
        view, pixels = crop_native(Image.new("RGB", (101, 79)), [.5, 0., 1., 1.])
        self.assertEqual(pixels, [51, 0, 101, 79])
        self.assertEqual(view.size, (50, 79))

    def test_sources_exclude_old_acceptance_by_hash_and_history(self):
        root = Path(__file__).resolve().parents[4] / "local-output" / "framing-preference-test-temp"
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as temporary:
            path = Path(temporary)
            base = dict(photo_type="landscape", scene_category="natural_landscape", reference_eligible=True,
                        synthetic=False, original_split="train", historical_holdout_group=False)
            rows = []
            for name in ("good", "blocked_by_old_hash", "blocked_history", "old_group", "duplicate", "duplicate"):
                index = len(rows)
                rows.append(dict(base, template_id=str(index), image_sha256=f"image{index}",
                                 source_sha256="old_hash" if name == "blocked_by_old_hash" else f"source{index}",
                                 source_group_id=name, historical_holdout_group=name == "blocked_history"))
            source = path / "source.jsonl"
            source.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            previous = path / "previous.json"
            previous.write_text(json.dumps({"group_assignments": {"old_group": "acceptance"}}))
            previous_views = path / "previous_views.json"
            previous_views.write_text(json.dumps([{"source_sha256": "old_hash", "split": "acceptance"}]))
            dummy = path / "dummy.json"
            dummy.write_text("{}")
            (path / "teacher-provenance.json").write_text("{}")
            args = SimpleNamespace(source_manifest=source, previous_protocol=previous, previous_views=previous_views,
                                   object_structures=dummy, vendor_dir=path, encoder=dummy, output_dir=path)
            selected, protocol = prepare(args)
            self.assertEqual({r["source_group_id"] for r in selected}, {"good", "duplicate"})
            self.assertEqual(len(selected), 2)
            self.assertTrue(protocol["previous_quality_acceptance_excluded"])


if __name__ == "__main__":
    unittest.main()
