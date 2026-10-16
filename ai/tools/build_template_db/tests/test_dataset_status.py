import json
import tempfile
import unittest
from pathlib import Path

import dataset_status


class DatasetStatusTests(unittest.TestCase):
    def test_manifest_summary_counts_accepted_types(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.jsonl"
            rows = [
                {
                    "photo_id": "a",
                    "photo_type": "landscape",
                    "scene_category": "natural_landscape",
                    "review": {"quality_status": "accepted"},
                },
                {
                    "photo_id": "b",
                    "photo_type": "portrait",
                    "scene_category": "closeup_portrait",
                    "review": {"quality_status": "rejected"},
                },
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            summary = dataset_status.manifest_summary(path)
        self.assertEqual(summary["records"], 2)
        self.assertEqual(summary["accepted_photo_types"], {"landscape": 1})

    def test_missing_artifact_is_empty(self):
        summary = dataset_status.jobs_summary(Path("/tmp/does-not-exist.jsonl"))
        self.assertEqual(summary["jobs"], 0)

    def test_annotation_batch_summary_reads_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "batch_manifest.json").write_text(
                json.dumps(
                    {
                        "total_requests": 3,
                        "batches": [
                            {"batch_id": "a", "items": 2},
                            {"batch_id": "b", "items": 1},
                        ],
                    }
                ),
                encoding="utf-8",
            )
            summary = dataset_status.annotation_batch_summary(root)
        self.assertEqual(summary["batches"], 2)
        self.assertEqual(summary["requests"], 3)

    def test_annotation_summary_counts_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "annotations.jsonl"
            rows = [
                {"photo_id": "a", "photo_type": "landscape", "template_value": "high"},
                {"photo_id": "b", "photo_type": "still_life", "template_value": "medium"},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            summary = dataset_status.annotation_summary(path)
        self.assertEqual(summary["records"], 2)
        self.assertEqual(summary["template_value"], {"high": 1, "medium": 1})

    def test_embedding_summary_counts_missing_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "jobs.jsonl"
            rows = [
                {"photo_id": "a", "output": {"embedding_path": str(Path(directory) / "a.f32")}},
                {"photo_id": "b", "output": {"embedding_path": str(Path(directory) / "b.f32")}},
            ]
            Path(directory, "a.f32").write_bytes(b"1234")
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            summary = dataset_status.embedding_summary(path)
        self.assertEqual(summary["present"], 1)
        self.assertEqual(summary["missing"], 1)


if __name__ == "__main__":
    unittest.main()
