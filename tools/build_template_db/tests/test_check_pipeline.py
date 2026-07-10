import json
import tempfile
import unittest
from pathlib import Path

import check_pipeline


class CheckPipelineTests(unittest.TestCase):
    def test_missing_required_artifacts_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            report = check_pipeline.run_checks(Path(directory))
        self.assertGreaterEqual(report["summary"]["failures"], 2)
        failed = {check["name"] for check in report["checks"] if check["status"] == "fail"}
        self.assertIn("manifest", failed)
        self.assertIn("preprocessed_jobs", failed)

    def test_empty_optional_annotations_are_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "tools" / "raw_photos"
            preprocessed = root / "tools" / "preprocessed"
            raw.mkdir(parents=True)
            preprocessed.mkdir(parents=True)
            image = raw / "photo.jpg"
            image.write_bytes(b"not a real image")
            manifest = {
                "schema_version": 1,
                "photo_id": "photo_1",
                "photo_type": "landscape",
                "scene_category": "natural_landscape",
                "query": "mountain",
                "file_path": "photo.jpg",
                "sha256": "a" * 64,
                "width": 1,
                "height": 1,
                "reported_width": 1,
                "reported_height": 1,
                "source": {
                    "provider": "test",
                    "provider_id": "1",
                    "landing_url": "https://example.test",
                    "asset_url": "https://example.test/photo.jpg",
                    "creator": None,
                    "creator_url": None,
                },
                "rights": {
                    "license": "cc0",
                    "license_url": "https://example.test/license",
                    "attribution": "",
                    "verification_status": "provider_declared_pending_review",
                },
                "review": {
                    "quality_status": "rejected",
                    "rights_status": "pending",
                    "portrait_release_status": "not_applicable",
                },
            }
            (raw / "manifest.visual-clean.jsonl").write_text(
                json.dumps(manifest) + "\n",
                encoding="utf-8",
            )
            (preprocessed / "jobs.visual-clean.jsonl").write_text("", encoding="utf-8")
            report = check_pipeline.run_checks(root)
        pending = {check["name"] for check in report["checks"] if check["status"] == "pending"}
        self.assertIn("annotations", pending)
        self.assertIn("template_metadata", pending)


if __name__ == "__main__":
    unittest.main()
