import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import validate_multiscene_assets as gate


class MultiSceneAssetGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "references.json"
        self.metadata = {"one": {"source_sha256": "1"*64, "photo_type": "still_life", "scene_category": "food_still_life"}}
        self.row = {"template_id": "one", "image_sha256": "1"*64, "coordinate_space": "exif_upright_full_frame",
                    "image_width": 640, "image_height": 480, "synthetic": False, "photo_type": "still_life",
                    "scene_category": "food_still_life", "object_eligible": True, "object_reasons": [],
                    "primary_object_index": 0, "objects": [{"class_id": 41, "confidence": .8,
                    "bbox": [.2, .2, .3, .3], "clipped": False}], "layout": {
                        "algorithm": gate.LAYOUT, "input_size": 224, "grid_size": 3, "luma_grid": [.4]*9,
                        "edge_grid": [.05]*9, "rgb_mean_grid": [.4]*27,
                        "is_aesthetic_score": False, "horizon_available": False}}
        self.doc = {"schema_version": 1, "coordinate_space": "exif_upright_full_frame", "model_sha256": gate.MODEL_SHA256,
                    "preprocessing": gate.PREPROCESSING, "layout_algorithm": gate.LAYOUT,
                    "object_policy": "single_complete_coco_still_life_v1", "human_reviewed": False,
                    "aesthetic_ground_truth": False, "records": [self.row]}
        self.doc.update(object_review_policy="hash_bound_ai_visual_object_review_v1", object_review_sha256="3"*64)
        self.row["object_review"] = {"template_id": "one", "image_sha256": "1"*64,
            "model_sha256": gate.MODEL_SHA256, "method": "ai_visual_review", "human_reviewed": False,
            "status": "accepted", "reason_codes": []}

    def validate(self):
        review_bytes = "".join(json.dumps(row["object_review"], ensure_ascii=False, allow_nan=False) + "\n"
                               for row in self.doc["records"] if row.get("object_review") is not None).encode("utf-8")
        self.doc["object_review_sha256"] = hashlib.sha256(review_bytes).hexdigest()
        self.path.write_text(json.dumps(self.doc), encoding="utf-8")
        return gate.validate_references(self.path, self.metadata)

    def test_real_complete_subject_can_be_packaged(self):
        self.assertEqual(1, self.validate()["object_eligible_count"])

    def test_boolean_eligibility_cannot_override_ambiguous_or_truncated_subject(self):
        for patch in ({"class_id": 75}, {"confidence": .49}, {"clipped": True}, {"bbox": [.01, .2, .3, .3]},
                      {"bbox": [.1, .1, .89, .8]}, {"bbox": [.9, .1, .3, .3]}):
            with self.subTest(patch=patch):
                old = copy.deepcopy(self.row["objects"])
                self.row["objects"][0].update(patch)
                with self.assertRaises(ValueError):
                    self.validate()
                self.row["objects"] = old
        self.row["objects"].append({"class_id": 45, "confidence": .8, "bbox": [.6, .2, .25, .3], "clipped": False})
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            self.validate()

    def test_synthetic_and_false_scene_evidence_are_rejected(self):
        for field, value in (("synthetic", True), ("scene_category", "natural_landscape"),
                             ("image_sha256", "2"*64), ("primary_object_index", 1)):
            old = self.row[field]
            self.row[field] = value
            with self.assertRaises(ValueError):
                self.validate()
            self.row[field] = old

    def test_malformed_layout_and_private_path_never_ship(self):
        self.row["layout"]["luma_grid"][0] = float("nan")
        with self.assertRaises(ValueError):
            self.validate()
        self.row["layout"]["luma_grid"][0] = .5
        self.row["source_path"] = "private photograph"
        with self.assertRaisesRegex(ValueError, "private"):
            self.validate()

    def test_unknown_or_duplicate_references_do_not_sneak_into_bundle(self):
        self.doc["records"].append(copy.deepcopy(self.row))
        with self.assertRaisesRegex(ValueError, "count"):
            self.validate()

    def test_optional_capability_is_absent_on_legacy_bundle(self):
        self.assertEqual({"multiscene_enabled": False}, gate.validate(self.path.parent, {}, {}))
        (self.path.parent / "object_detector.onnx").write_bytes(b"orphan")
        with self.assertRaisesRegex(ValueError, "unbound"):
            gate.validate(self.path.parent, {}, {})

    def test_missing_or_wrong_visual_review_cannot_enable_a_consistent_false_detection(self):
        for field, value in (("status", "excluded"), ("model_sha256", "0"*64),
                             ("image_sha256", "0"*64), ("human_reviewed", True), ("reason_codes", ["wrong_class"])):
            old = self.row["object_review"][field]
            self.row["object_review"][field] = value
            with self.assertRaisesRegex(ValueError, "visual evidence"):
                self.validate()
            self.row["object_review"][field] = old

    def test_review_hash_binds_actual_embedded_notes(self):
        self.validate()
        document = json.loads(self.path.read_text(encoding="utf-8"))
        document["records"][0]["object_review"]["notes"] = "modified review evidence"
        self.path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "content hash"):
            gate.validate_references(self.path, self.metadata)


class MultiScenePackagingPathTests(unittest.TestCase):
    def test_removing_core_profile_cannot_bypass_multiscene_validation(self):
        from validate_core_recommendation_assets import validate
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "android_runtime_config.json").write_text('{"multi_scene_recommendation":{"enabled":true}}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "require the core"):
                validate(root)

    def test_staging_cannot_overwrite_input_or_previous_experiment(self):
        from package_multiscene_recommendation import package
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            run = project / "local-output/run"
            detector = run / "detector"
            references = run / "references/multi_scene_references.json"
            detector.mkdir(parents=True)
            references.parent.mkdir()
            references.write_text("preserve original annotations", encoding="utf-8")
            for staging in (run, detector, references.parent):
                with self.assertRaisesRegex(ValueError, "separate"):
                    package(project, detector, references, staging)
            previous = run / "previous-staging"
            previous.mkdir()
            (previous / "report.json").write_text("earlier evidence", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "empty"):
                package(project, detector, references, previous, False)
            self.assertEqual("preserve original annotations", references.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
