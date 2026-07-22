import json
import math
import hashlib
import tempfile
import unittest
from pathlib import Path

import preprocess


def make_pose() -> tuple[preprocess.Keypoint, ...]:
    points = [preprocess.Keypoint(float(i), float(i), 0.9) for i in range(17)]
    points[5] = preprocess.Keypoint(0.0, 0.0, 0.9)
    points[6] = preprocess.Keypoint(2.0, 0.0, 0.9)
    points[7] = preprocess.Keypoint(0.0, 1.0, 0.9)
    points[8] = preprocess.Keypoint(2.0, 1.0, 0.9)
    points[9] = preprocess.Keypoint(0.0, 2.0, 0.9)
    points[10] = preprocess.Keypoint(2.0, 2.0, 0.9)
    points[11] = preprocess.Keypoint(0.0, 2.0, 0.9)
    points[12] = preprocess.Keypoint(2.0, 2.0, 0.9)
    points[13] = preprocess.Keypoint(0.0, 3.0, 0.9)
    points[14] = preprocess.Keypoint(2.0, 3.0, 0.9)
    points[15] = preprocess.Keypoint(0.0, 4.0, 0.9)
    points[16] = preprocess.Keypoint(2.0, 4.0, 0.9)
    return tuple(points)


class PoseMathTests(unittest.TestCase):
    def test_primary_person_uses_largest_box(self):
        pose = make_pose()
        small = preprocess.DetectedPerson((0, 0, 10, 10), 0.99, pose)
        large = preprocess.DetectedPerson((0, 0, 20, 20), 0.7, pose)
        self.assertIs(preprocess.select_primary_person([small, large]), large)

    def test_pose_is_root_centered(self):
        normalized = preprocess.normalize_keypoints(make_pose())
        hip_midpoint_x = (normalized[11]["x"] + normalized[12]["x"]) / 2
        hip_midpoint_y = (normalized[11]["y"] + normalized[12]["y"]) / 2
        self.assertAlmostEqual(hip_midpoint_x, 0.0)
        self.assertAlmostEqual(hip_midpoint_y, 0.0)

    def test_straight_joint_is_180_degrees(self):
        angles = preprocess.compute_joint_angles(make_pose())
        self.assertAlmostEqual(angles["left_elbow"], 180.0)
        self.assertAlmostEqual(angles["right_knee"], 180.0)

    def test_embedding_is_l2_normalized(self):
        values = preprocess.validate_embedding([1.0] * 256)
        self.assertAlmostEqual(math.sqrt(sum(value * value for value in values)), 1.0)


class JobTests(unittest.TestCase):
    def test_rejected_records_are_not_scheduled(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "photo.jpg"
            image.write_bytes(b"test")
            image_sha = hashlib.sha256(b"test").hexdigest()
            records = [
                {
                    "photo_id": "accepted",
                    "photo_type": "portrait",
                    "scene_category": "portrait",
                    "file_path": "photo.jpg",
                    "sha256": image_sha,
                    "review": {"quality_status": "accepted"},
                },
                {
                    "photo_id": "rejected",
                    "photo_type": "landscape",
                    "scene_category": "landscape",
                    "file_path": "missing.jpg",
                    "sha256": "b" * 64,
                    "review": {"quality_status": "rejected"},
                },
            ]
            manifest = root / "manifest.jsonl"
            manifest.write_text("\n".join(json.dumps(record) for record in records) + "\n", encoding="utf-8")
            jobs = preprocess.prepare_jobs(manifest, root / "out")
        self.assertEqual([job["photo_id"] for job in jobs], ["accepted"])
        self.assertTrue(jobs[0]["pose_required"])

    def test_hash_mismatch_is_not_scheduled(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "photo.jpg"
            image.write_bytes(b"test")
            record = {
                "photo_id": "accepted",
                "photo_type": "portrait",
                "scene_category": "portrait",
                "file_path": "photo.jpg",
                "sha256": "0" * 64,
                "review": {"quality_status": "accepted"},
            }
            manifest = root / "manifest.jsonl"
            manifest.write_text(json.dumps(record) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "sha256 mismatch"):
                preprocess.prepare_jobs(manifest, root / "out")


if __name__ == "__main__":
    unittest.main()
