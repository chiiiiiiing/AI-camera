import json
import math
import struct
import tempfile
import unittest
from pathlib import Path

import build_template_index
from test_validate_template_metadata import valid_metadata


def write_embedding(path: Path) -> None:
    value = 1 / math.sqrt(256)
    path.write_bytes(struct.pack("<256f", *([value] * 256)))


class BuildTemplateIndexTests(unittest.TestCase):
    def test_rejects_pending_embedding(self):
        with tempfile.TemporaryDirectory() as directory:
            metadata_path = Path(directory) / "metadata.jsonl"
            metadata_path.write_text(json.dumps(valid_metadata()) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "is not ready"):
                build_template_index.load_ready_vectors(metadata_path)

    def test_writes_portable_bundle_for_ready_embeddings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            embedding_path = root / "embedding.f32"
            write_embedding(embedding_path)
            metadata = valid_metadata()
            metadata["embedding"]["status"] = "ready"
            metadata["embedding"]["path"] = str(embedding_path)
            metadata_path = root / "metadata.jsonl"
            metadata_path.write_text(json.dumps(metadata) + "\n", encoding="utf-8")

            vectors = build_template_index.load_ready_vectors(metadata_path)
            summary = build_template_index.write_portable_bundle(vectors, root / "index", 256)

            self.assertEqual(summary["count"], 1)
            self.assertEqual((root / "index" / "templates.embeddings.f32").stat().st_size, 256 * 4)
            keys = json.loads((root / "index" / "templates.keys.json").read_text(encoding="utf-8"))
            self.assertEqual(keys["items"][0]["template_id"], "photo_1")


if __name__ == "__main__":
    unittest.main()
