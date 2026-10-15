import unittest
import math
import struct
import tempfile
import copy
import json
from pathlib import Path

import build_template_metadata
import validate_template_metadata
from test_validate_annotations import valid_annotation


def valid_metadata():
    manifest_record = {
        "photo_id": "photo_1",
        "photo_type": "landscape",
        "scene_category": "natural_landscape",
        "file_path": "landscape/photo.jpg",
        "sha256": "a" * 64,
        "width": 1024,
        "height": 768,
        "source": {"landing_url": "https://example.test", "creator": "A"},
        "rights": {"license": "cc0"},
        "review": {"quality_status": "accepted"},
    }
    job = {
        "photo_id": "photo_1",
        "photo_type": "landscape",
        "scene_category": "natural_landscape",
        "source_path": "raw/photo.jpg",
        "source_sha256": "a" * 64,
        "pose_required": False,
        "output": {"embedding_path": "embedding.f32"},
    }
    preprocessed = {
        "schema_version": 1,
        "photo_id": "photo_1",
        "photo_type": "landscape",
        "scene_category": "natural_landscape",
        "source_sha256": "a" * 64,
        "pose_detected": False,
        "pose_required": False,
    }
    annotation = valid_annotation()
    annotation["photo_id"] = "photo_1"
    return build_template_metadata.build_metadata_record(
        manifest_record,
        job,
        preprocessed,
        annotation,
    )


class TemplateMetadataValidationTests(unittest.TestCase):
    @staticmethod
    def unrated_metadata():
        metadata = valid_metadata()
        metadata["annotation"] = {"aesthetic_score": None, "template_value": "unrated",
                                  "shooting_guidance": [],
                                  "provenance": {"aesthetic_ground_truth": False}}
        metadata["reference_review"] = {"photo_id": metadata["photo_id"],
                                        "source_sha256": metadata["source_sha256"],
                                        "status": "candidate_clean",
                                        "provenance": {"method": "ai_visual_review"}}
        metadata["split"] = "train"
        metadata["source_group_id"] = "parent_sha256:" + "a" * 64
        metadata["synthetic"] = False
        return metadata

    def test_hash_bound_review_allows_unrated_without_fake_aesthetic_or_guidance(self):
        record = self.unrated_metadata()
        self.assertEqual(validate_template_metadata.validate_core(record), [])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "reviewed.jsonl"
            path.write_text(json.dumps(record) + "\n", encoding="utf-8")
            schema = Path(validate_template_metadata.__file__).parent / "schema/template_metadata.schema.json"
            errors, summary = validate_template_metadata.validate_jsonl(path, schema)
        self.assertEqual(errors, [])
        self.assertEqual(summary["records"], 1)

    def test_unrated_requires_matching_review_identity_and_ai_method(self):
        for changes in ({"photo_id": "another"}, {"source_sha256": "b" * 64},
                        {"status": "recoverable_crop"}, {"provenance": {"method": "rule"}},
                        {"provenance": None}):
            record = self.unrated_metadata()
            record["reference_review"].update(changes)
            with self.subTest(changes=changes):
                self.assertTrue(validate_template_metadata.validate_core(record))
        record = self.unrated_metadata()
        del record["reference_review"]
        self.assertTrue(validate_template_metadata.validate_core(record))

    def test_unrated_requires_explicit_null_false_and_empty_guidance(self):
        for changes in ({"aesthetic_score": 4}, {"shooting_guidance": ["move left"]},
                        {"shooting_guidance": None}, {"provenance": {}},
                        {"provenance": {"aesthetic_ground_truth": True}}):
            record = self.unrated_metadata()
            record["annotation"].update(changes)
            with self.subTest(changes=changes):
                self.assertTrue(validate_template_metadata.validate_core(record))
        record = self.unrated_metadata()
        del record["annotation"]["aesthetic_score"]
        self.assertTrue(validate_template_metadata.validate_core(record))

    def test_matching_invalid_hash_cannot_open_unrated_profile(self):
        for digest in (None, "", "not-a-hash"):
            record = self.unrated_metadata()
            record["source_sha256"] = digest
            record["reference_review"]["source_sha256"] = digest
            with self.subTest(digest=digest):
                self.assertTrue(validate_template_metadata.validate_core(record))

    def test_review_cannot_bypass_embedding_or_pose_validation(self):
        for field, changes in (("embedding", {"dimension": 0}), ("embedding", {"path": ""}),
                               ("pose", {"required": True, "detected": False})):
            record = self.unrated_metadata()
            record[field].update(changes)
            with self.subTest(field=field, changes=changes):
                self.assertTrue(validate_template_metadata.validate_core(record))

    def test_reviewed_schema_keeps_legacy_required_fields_and_limits(self):
        record = self.unrated_metadata()
        for field in ("license", "width", "pose", "embedding"):
            candidate = copy.deepcopy(record)
            del candidate[field]
            with self.subTest(field=field):
                self.assertTrue(validate_template_metadata.validate_core(candidate))
        record = valid_metadata()
        record["annotation"]["aesthetic_score"] = None
        record["annotation"]["shooting_guidance"] = []
        self.assertTrue(validate_template_metadata.validate_core(record))

    def test_valid_non_portrait_metadata_passes(self):
        self.assertEqual(validate_template_metadata.validate_core(valid_metadata()), [])

    def test_accepts_local_authorized_license(self):
        metadata = valid_metadata()
        metadata["license"] = "local_authorized"
        self.assertEqual(validate_template_metadata.validate_core(metadata), [])

    def test_accepts_teacher_embedding_metadata(self):
        metadata = valid_metadata()
        metadata["embedding"]["dimension"] = 768
        metadata["embedding"]["model_id"] = "google/siglip2-base-patch16-224"
        metadata["embedding"]["role"] = "teacher"
        self.assertEqual(validate_template_metadata.validate_core(metadata), [])

    def test_rejects_low_template_value(self):
        metadata = valid_metadata()
        metadata["annotation"]["template_value"] = "low"
        errors = validate_template_metadata.validate_core(metadata)
        self.assertIn("annotation.template_value must be medium or high", errors)

    def test_rejects_detected_pose_for_non_pose_template(self):
        metadata = valid_metadata()
        metadata["pose"]["detected"] = True
        errors = validate_template_metadata.validate_core(metadata)
        self.assertIn("non-pose template must not set pose.detected=true", errors)

    def test_rejects_wrong_embedding_dimension(self):
        metadata = valid_metadata()
        metadata["embedding"]["dimension"] = 0
        errors = validate_template_metadata.validate_core(metadata)
        self.assertIn("embedding.dimension must be a positive integer", errors)

    def test_build_metadata_marks_existing_valid_embedding_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            embedding_path = Path(directory) / "embedding.f32"
            value = 1 / math.sqrt(256)
            embedding_path.write_bytes(struct.pack("<256f", *([value] * 256)))
            metadata = valid_metadata()
            job = {
                "photo_id": "photo_1",
                "photo_type": "landscape",
                "scene_category": "natural_landscape",
                "source_path": "raw/photo.jpg",
                "source_sha256": "a" * 64,
                "pose_required": False,
                "output": {"embedding_path": str(embedding_path)},
            }
            rebuilt = build_template_metadata.build_metadata_record(
                {
                    "photo_id": "photo_1",
                    "photo_type": "landscape",
                    "scene_category": "natural_landscape",
                    "sha256": "a" * 64,
                    "width": 1024,
                    "height": 768,
                    "source": {"landing_url": "https://example.test", "creator": "A"},
                    "rights": {"license": "cc0"},
                },
                job,
                {
                    "pose_detected": False,
                    "keypoints_coco17": None,
                    "keypoints_normalized": None,
                    "joint_angles_degrees": None,
                },
                metadata["annotation"],
            )
        self.assertEqual(rebuilt["embedding"]["status"], "ready")


if __name__ == "__main__":
    unittest.main()
