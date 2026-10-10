import unittest
import math
import struct
import tempfile
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
