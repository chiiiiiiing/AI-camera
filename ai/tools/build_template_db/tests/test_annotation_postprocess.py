import json
import tempfile
import unittest
from pathlib import Path

import build_template_metadata
import quarantine_annotation_results
from test_validate_annotations import valid_annotation


class QuarantineAnnotationTests(unittest.TestCase):
    def test_low_annotation_rejects_record(self):
        records = [{"photo_id": "photo_1", "review": {"quality_status": "accepted"}}]
        annotation = valid_annotation()
        annotation["photo_id"] = "photo_1"
        annotation["template_value"] = "low"
        updated, summary = quarantine_annotation_results.quarantine_records(records, [annotation])
        self.assertEqual(summary["newly_rejected"], 1)
        self.assertEqual(updated[0]["review"]["quality_status"], "rejected")
        self.assertIn("annotation_low", updated[0]["review"]["rejection_reason"])

    def test_high_annotation_keeps_record(self):
        records = [{"photo_id": "photo_1", "review": {"quality_status": "accepted"}}]
        annotation = valid_annotation()
        annotation["photo_id"] = "photo_1"
        updated, summary = quarantine_annotation_results.quarantine_records(records, [annotation])
        self.assertEqual(summary["newly_rejected"], 0)
        self.assertEqual(updated[0]["review"]["quality_status"], "accepted")


class BuildTemplateMetadataTests(unittest.TestCase):
    def test_builds_non_portrait_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record_path = root / "preprocessed.json"
            record_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "photo_id": "photo_1",
                        "photo_type": "landscape",
                        "scene_category": "natural_landscape",
                        "source_sha256": "a" * 64,
                        "pose_detected": False,
                        "pose_required": False,
                    }
                ),
                encoding="utf-8",
            )
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
                "output": {
                    "record_path": str(record_path),
                    "embedding_path": str(root / "embedding.f32"),
                },
            }
            annotation = valid_annotation()
            annotation["photo_id"] = "photo_1"
            metadata = build_template_metadata.build_metadata_record(
                manifest_record,
                job,
                json.loads(record_path.read_text(encoding="utf-8")),
                annotation,
            )
        self.assertEqual(metadata["template_id"], "photo_1")
        self.assertEqual(metadata["embedding"]["status"], "pending")
        self.assertFalse(metadata["pose"]["required"])


if __name__ == "__main__":
    unittest.main()
