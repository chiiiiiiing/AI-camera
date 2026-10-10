import json
import tempfile
import unittest
from pathlib import Path

import validate_preprocessed


class ValidatePreprocessedTests(unittest.TestCase):
    def test_non_portrait_record_passes(self):
        job = {
            "photo_id": "photo_1",
            "photo_type": "landscape",
            "scene_category": "natural_landscape",
            "source_sha256": "a" * 64,
            "pose_required": False,
        }
        record = {
            "schema_version": 1,
            "photo_id": "photo_1",
            "photo_type": "landscape",
            "scene_category": "natural_landscape",
            "source_sha256": "a" * 64,
            "pose_detected": False,
            "pose_required": False,
        }
        self.assertEqual(validate_preprocessed.validate_record(job, record), [])

    def test_portrait_requires_17_keypoints(self):
        job = {
            "photo_id": "photo_1",
            "photo_type": "portrait",
            "scene_category": "closeup_portrait",
            "source_sha256": "a" * 64,
            "pose_required": True,
        }
        record = {
            "schema_version": 1,
            "photo_id": "photo_1",
            "photo_type": "portrait",
            "scene_category": "closeup_portrait",
            "source_sha256": "a" * 64,
            "pose_detected": True,
            "keypoints_coco17": [],
            "keypoints_normalized": [],
            "joint_angles_degrees": {},
        }
        errors = validate_preprocessed.validate_record(job, record)
        self.assertTrue(any("17 keypoints" in error for error in errors))

    def test_validate_jobs_reads_record_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record_path = root / "preprocessed.json"
            record_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "photo_id": "photo_1",
                        "photo_type": "still_life",
                        "scene_category": "food_still_life",
                        "source_sha256": "a" * 64,
                        "pose_detected": False,
                        "pose_required": False,
                    }
                ),
                encoding="utf-8",
            )
            jobs_path = root / "jobs.jsonl"
            jobs_path.write_text(
                json.dumps(
                    {
                        "photo_id": "photo_1",
                        "photo_type": "still_life",
                        "scene_category": "food_still_life",
                        "source_sha256": "a" * 64,
                        "pose_required": False,
                        "output": {"record_path": str(record_path)},
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            errors, summary = validate_preprocessed.validate_jobs(jobs_path)
        self.assertEqual(errors, [])
        self.assertEqual(summary["records"], 1)


if __name__ == "__main__":
    unittest.main()
