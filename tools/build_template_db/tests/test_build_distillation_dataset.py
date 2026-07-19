import json
import struct
import tempfile
import unittest
from pathlib import Path

import build_distillation_dataset


def write_embedding(path: Path, dimension: int = 4) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(struct.pack(f"<{dimension}f", *([0.5] * dimension)))


def metadata_record(root: Path, index: int, photo_type: str, scene_category: str) -> dict:
    embedding_path = root / "embeddings" / f"p{index}" / "embedding.f32"
    write_embedding(embedding_path)
    return {
        "schema_version": 1,
        "template_id": f"p{index}",
        "photo_id": f"p{index}",
        "photo_type": photo_type,
        "scene_category": scene_category,
        "source_path": str(root / "images" / f"p{index}.jpg"),
        "width": 1024,
        "height": 768,
        "annotation": {
            "aesthetic_score": 4,
            "template_value": "high",
            "composition": {"subject_position": "center"},
            "lighting": {"quality": "soft"},
            "camera": {"angle": "eye_level"},
            "shooting_guidance": "Keep the subject clear.",
        },
        "embedding": {
            "status": "ready",
            "dimension": 4,
            "path": str(embedding_path),
            "model_id": "local-teacher",
            "role": "teacher",
        },
    }


class BuildDistillationDatasetTests(unittest.TestCase):
    def test_builds_stratified_distillation_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [
                metadata_record(root, 1, "landscape", "natural_landscape"),
                metadata_record(root, 2, "landscape", "natural_landscape"),
                metadata_record(root, 3, "landscape", "natural_landscape"),
                metadata_record(root, 4, "still_life", "food_still_life"),
                metadata_record(root, 5, "still_life", "food_still_life"),
                metadata_record(root, 6, "still_life", "food_still_life"),
            ]
            metadata = root / "metadata.jsonl"
            metadata.write_text(
                "\n".join(json.dumps(row) for row in rows) + "\n",
                encoding="utf-8",
            )

            summary = build_distillation_dataset.build_dataset(
                metadata,
                root / "distill",
                seed=7,
                train_ratio=0.6,
                val_ratio=0.2,
            )

            self.assertEqual(summary["total"], 6)
            self.assertEqual(summary["splits"], {"train": 2, "val": 2, "test": 2})
            for split in ("train", "val", "test"):
                path = root / "distill" / f"{split}.jsonl"
                self.assertTrue(path.exists())
                records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
                self.assertEqual(len(records), 2)
                self.assertTrue(all(record["split"] == split for record in records))
                self.assertTrue(all(record["teacher_embedding"]["dimension"] == 4 for record in records))
            self.assertTrue((root / "distill" / "manifest.json").exists())

    def test_rejects_missing_teacher_embedding_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = metadata_record(root, 1, "landscape", "natural_landscape")
            Path(row["embedding"]["path"]).unlink()
            metadata = root / "metadata.jsonl"
            metadata.write_text(json.dumps(row) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "teacher embedding file not found"):
                build_distillation_dataset.build_dataset(metadata, root / "distill")


if __name__ == "__main__":
    unittest.main()
