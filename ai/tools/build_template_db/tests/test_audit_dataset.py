import hashlib
import tempfile
import unittest
from pathlib import Path

from PIL import Image

import audit_dataset


class AuditTests(unittest.TestCase):
    def test_valid_candidate_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image_path = root / "photo.jpg"
            Image.new("RGB", (960, 640), "blue").save(image_path)
            digest = hashlib.sha256(image_path.read_bytes()).hexdigest()
            record = {
                "_manifest_line": 1,
                "photo_id": "photo_1",
                "photo_type": "landscape",
                "scene_category": "natural_landscape",
                "file_path": "photo.jpg",
                "sha256": digest,
                "width": 960,
                "height": 640,
                "rights": {"license": "cc0"},
                "review": {
                    "quality_status": "pending",
                    "rights_status": "pending",
                    "portrait_release_status": "not_applicable",
                },
            }
            errors, summary = audit_dataset.audit_records([record], root)
        self.assertEqual(errors, [])
        self.assertEqual(summary["existing_files"], 1)

    def test_missing_rejected_file_is_allowed(self):
        record = {
            "_manifest_line": 1,
            "photo_id": "photo_1",
            "photo_type": "landscape",
            "scene_category": "natural_landscape",
            "file_path": "missing.jpg",
            "sha256": "a" * 64,
            "width": 960,
            "height": 640,
            "rights": {"license": "pdm"},
            "review": {
                "quality_status": "rejected",
                "rights_status": "pending",
                "portrait_release_status": "not_applicable",
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            errors, _ = audit_dataset.audit_records([record], Path(directory))
        self.assertEqual(errors, [])

    def test_hash_mismatch_rejected_file_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image_path = root / "photo.jpg"
            Image.new("RGB", (960, 640), "blue").save(image_path)
            record = {
                "_manifest_line": 1,
                "photo_id": "photo_1",
                "photo_type": "landscape",
                "scene_category": "natural_landscape",
                "file_path": "photo.jpg",
                "sha256": "0" * 64,
                "width": 960,
                "height": 640,
                "rights": {"license": "cc0"},
                "review": {
                    "quality_status": "rejected",
                    "rights_status": "pending",
                    "portrait_release_status": "not_applicable",
                },
            }
            errors, summary = audit_dataset.audit_records([record], root)
        self.assertEqual(errors, [])
        self.assertEqual(summary["existing_files"], 1)


if __name__ == "__main__":
    unittest.main()
