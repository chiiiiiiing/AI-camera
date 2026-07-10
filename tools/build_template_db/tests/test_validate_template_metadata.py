import unittest

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
        metadata["embedding"]["dimension"] = 512
        errors = validate_template_metadata.validate_core(metadata)
        self.assertIn("embedding.dimension must be 256", errors)


if __name__ == "__main__":
    unittest.main()
