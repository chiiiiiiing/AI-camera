import json
import math
import struct
import tempfile
import unittest
from pathlib import Path

import generate_mobileclip_embeddings


class FakeBackend:
    dimension = 256

    def encode_image(self, image_path):
        self.last_image_path = image_path
        value = 1 / math.sqrt(256)
        return [value] * 256


class GenerateMobileClipEmbeddingsTests(unittest.TestCase):
    def test_uses_source_image_when_background_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "raw" / "photo.jpg"
            image.parent.mkdir()
            image.write_bytes(b"image")
            jobs = root / "preprocessed" / "jobs.jsonl"
            jobs.parent.mkdir()
            embedding = root / "preprocessed" / "photo" / "embedding.f32"
            row = {
                "photo_id": "photo",
                "source_path": "../raw/photo.jpg",
                "output": {
                    "background_path": str(root / "missing-background.jpg"),
                    "embedding_path": str(embedding),
                },
            }
            jobs.write_text(json.dumps(row) + "\n", encoding="utf-8")

            backend = FakeBackend()
            summary = generate_mobileclip_embeddings.generate_embeddings(jobs, backend)

            self.assertEqual(summary["written"], 1)
            self.assertEqual(summary["errors"], [])
            self.assertEqual(backend.last_image_path.resolve(), image.resolve())
            self.assertEqual(embedding.stat().st_size, 256 * 4)

    def test_skips_existing_embedding_without_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "photo.jpg"
            image.write_bytes(b"image")
            embedding = root / "embedding.f32"
            embedding.write_bytes(struct.pack("<256f", *([0.0] * 256)))
            jobs = root / "jobs.jsonl"
            row = {
                "photo_id": "photo",
                "source_path": str(image),
                "output": {"embedding_path": str(embedding)},
            }
            jobs.write_text(json.dumps(row) + "\n", encoding="utf-8")

            summary = generate_mobileclip_embeddings.generate_embeddings(jobs, FakeBackend())

            self.assertEqual(summary["written"], 0)
            self.assertEqual(summary["skipped_existing"], 1)

    def test_reports_wrong_dimension_without_writing(self):
        class BadBackend:
            dimension = 10

            def encode_image(self, image_path):
                return [1.0] * 10

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "photo.jpg"
            image.write_bytes(b"image")
            embedding = root / "embedding.f32"
            jobs = root / "jobs.jsonl"
            row = {
                "photo_id": "photo",
                "source_path": str(image),
                "output": {"embedding_path": str(embedding)},
            }
            jobs.write_text(json.dumps(row) + "\n", encoding="utf-8")

            summary = generate_mobileclip_embeddings.generate_embeddings(jobs, BadBackend())

            self.assertEqual(summary["written"], 0)
            self.assertTrue(any("expected embedding dimension 256" in error for error in summary["errors"]))
            self.assertFalse(embedding.exists())

    def test_pairwise_projection_maps_512_to_256(self):
        values = [1.0] * 512
        projected = generate_mobileclip_embeddings.project_embedding(
            values,
            expected_dimension=256,
            projection="pairwise",
        )
        self.assertEqual(len(projected), 256)
        self.assertAlmostEqual(projected[0], math.sqrt(2))

    def test_limit_counts_failed_attempts(self):
        class BadBackend:
            dimension = 10

            def encode_image(self, image_path):
                return [1.0] * 10

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "photo.jpg"
            image.write_bytes(b"image")
            jobs = root / "jobs.jsonl"
            rows = [
                {
                    "photo_id": f"photo_{index}",
                    "source_path": str(image),
                    "output": {"embedding_path": str(root / f"embedding_{index}.f32")},
                }
                for index in range(3)
            ]
            jobs.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            summary = generate_mobileclip_embeddings.generate_embeddings(
                jobs,
                BadBackend(),
                limit=1,
            )

            self.assertEqual(summary["attempted"], 1)
            self.assertEqual(len(summary["errors"]), 1)


if __name__ == "__main__":
    unittest.main()
