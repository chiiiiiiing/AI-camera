import base64
import copy
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import validate_core_recommendation_assets as gate


class GuidanceAssetGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.encoder_sha = "e" * 64
        self.policy = {"schema_version": 1, "enabled": True, "encoder_sha256": self.encoder_sha,
                       "enabled_scenes": ["portrait"], "metrics": {
                           "acceptance_cluster_count": 30, "scene_cluster_counts": {"portrait": 30},
                           "pairwise_agreement": .8, "bootstrap_lower95": .6,
                           "baseline_pairwise_agreement": .7, "hard_constraints_passed": True,
                           "severe_defect_non_regression": True, "coverage": .5, "rejection_rate": .5}}
        self.head = {"schema_version": 1, "encoder_sha256": self.encoder_sha,
                     "enabled": True, "activation": "relu", "feature_normalization": "none",
                     "embedding_dim": 512, "hidden_dim": 64, "feature_names": gate.FEATURE_NAMES,
                     "enabled_scenes": ["portrait"], "weight_encoding": "float32-le-base64",
                     "weights_f32_base64": base64.b64encode(bytes(gate.WEIGHT_COUNT*4)).decode()}

    def save(self):
        (self.root / "quality_head.json").write_text(json.dumps(self.head), encoding="utf-8")
        self.policy["head_sha256"] = gate.sha256(self.root / "quality_head.json")
        path = self.root / "quality_policy.json"
        path.write_text(json.dumps(self.policy), encoding="utf-8")
        return path

    def test_enabled_head_is_admitted_from_measured_evidence(self):
        self.assertTrue(gate.validate_quality(self.save(), self.root, self.encoder_sha)["quality_enabled"])

    def test_disabled_head_does_not_require_candidate_weights(self):
        self.policy.update(enabled=False, enabled_scenes=[], reasons=["insufficient independent groups"])
        path = self.save()
        (self.root / "quality_head.json").unlink()
        self.assertFalse(gate.validate_quality(path, self.root, self.encoder_sha)["quality_enabled"])

    def test_true_gate_flags_cannot_hide_bad_actual_measurements(self):
        for field, bad in (("acceptance_cluster_count", 29), ("pairwise_agreement", .69),
                           ("bootstrap_lower95", .5), ("baseline_pairwise_agreement", .8),
                           ("hard_constraints_passed", False), ("coverage", float("nan"))):
            with self.subTest(field=field):
                original = copy.deepcopy(self.policy)
                self.policy["gates"] = {"all_passed": True}
                self.policy["metrics"][field] = bad
                with self.assertRaises(ValueError):
                    gate.validate_quality(self.save(), self.root, self.encoder_sha)
                self.policy = original

    def test_scene_gate_does_not_borrow_samples_from_other_scenes(self):
        self.policy["metrics"]["scene_cluster_counts"] = {"portrait": 9, "landscape": 100}
        with self.assertRaisesRegex(ValueError, "per-scene"):
            gate.validate_quality(self.save(), self.root, self.encoder_sha)

    def test_model_identity_feature_order_and_weight_shape_are_checked(self):
        for field, bad in (("encoder_sha256", "a"*64), ("feature_names", list(reversed(gate.FEATURE_NAMES))),
                           ("enabled", False), ("activation", "sigmoid"), ("feature_normalization", "zscore"),
                           ("weights_f32_base64", base64.b64encode(bytes(4)).decode()),
                           ("enabled_scenes", ["landscape"])):
            with self.subTest(field=field):
                original = copy.deepcopy(self.head)
                self.head[field] = bad
                with self.assertRaises(ValueError):
                    gate.validate_quality(self.save(), self.root, self.encoder_sha)
                self.head = original

    def test_nonfinite_binary_weights_fail_even_with_valid_hash(self):
        raw = struct.pack("<f", float("inf")) + bytes((gate.WEIGHT_COUNT-1)*4)
        self.head["weights_f32_base64"] = base64.b64encode(raw).decode()
        with self.assertRaisesRegex(ValueError, "non-finite"):
            gate.validate_quality(self.save(), self.root, self.encoder_sha)

    def test_partial_or_modified_weight_file_is_not_admitted(self):
        policy_path = self.save()
        with (self.root / "quality_head.json").open("a") as target:
            target.write(" ")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            gate.validate_quality(policy_path, self.root, self.encoder_sha)

    def test_defect_only_evidence_cannot_enable_crop_recommendations(self):
        self.policy["crop_advice_enabled"] = True
        with self.assertRaisesRegex(ValueError, "crop advice"):
            gate.validate_quality(self.save(), self.root, self.encoder_sha)
        self.policy["crop_advice_enabled"] = False
        self.policy["crop_diagnostics_enabled"] = True
        with self.assertRaisesRegex(ValueError, "crop diagnostics"):
            gate.validate_quality(self.save(), self.root, self.encoder_sha)

    def test_public_artifact_rejects_nested_private_local_paths(self):
        self.policy["diagnostics"] = {"run": "D:/private/photos"}
        with self.assertRaisesRegex(ValueError, "local path"):
            gate.validate_quality(self.save(), self.root, self.encoder_sha)

    def structure(self):
        face = {"bbox": [.3, .2, .2, .3], "confidence": .9, "clipped": False, "yaw_degrees": None}
        row = {"template_id": "one", "image_sha256": "1"*64, "coordinate_space": "exif_upright_full_frame",
               "image_width": 600, "image_height": 800, "faces": [face], "face_count": 1,
               "photo_type": "portrait", "synthetic": False, "structure_eligible": True,
               "structure_reasons": [], "quality": {"severe_quality_issue": False}}
        doc = {"schema_version": 1, "coordinate_space": "exif_upright_full_frame", "records": [row],
               "provenance": {"method": "local_mediapipe_image_detection", "human_reviewed": False,
                              "aesthetic_ground_truth": False, "face_model_sha256": "2"*64,
                              "pose_model_sha256": "3"*64}}
        return row, doc

    def test_only_hash_bound_real_single_faces_can_be_actionable(self):
        for field, bad in (("synthetic", True), ("face_count", 2), ("image_sha256", "9"*64),
                           ("photo_type", "landscape"), ("structure_reasons", ["unreliable"])):
            with self.subTest(field=field):
                row, doc = self.structure()
                row[field] = bad
                path = self.root / "reference_structures.json"
                path.write_text(json.dumps(doc), encoding="utf-8")
                with self.assertRaises(ValueError):
                    gate.validate_structures(path, {"one": {"source_sha256": "1"*64}})

    def test_valid_structures_count_actual_actionable_references(self):
        _, doc = self.structure()
        path = self.root / "reference_structures.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        self.assertEqual(1, gate.validate_structures(path, {"one": {"source_sha256": "1"*64}})["structure_eligible_count"])


if __name__ == "__main__":
    unittest.main()
