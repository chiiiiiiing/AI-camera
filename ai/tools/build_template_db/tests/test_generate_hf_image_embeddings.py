import math
import struct
import tempfile
import unittest
from pathlib import Path

import generate_hf_image_embeddings


class HuggingFaceEmbeddingTests(unittest.TestCase):
    def test_normalize_returns_unit_vector(self):
        values = generate_hf_image_embeddings.normalize([3.0, 4.0])
        self.assertAlmostEqual(math.sqrt(sum(value * value for value in values)), 1.0)

    def test_existing_dimension_reads_f32_length(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "embedding.f32"
            path.write_bytes(struct.pack("<3f", 1.0, 2.0, 3.0))
            self.assertEqual(generate_hf_image_embeddings.existing_dimension(path), 3)

    def test_embedding_output_path_is_stable(self):
        root = Path("out")
        self.assertEqual(
            generate_hf_image_embeddings.embedding_output_path(root, "photo_1"),
            root / "photo_1" / "embedding.f32",
        )


if __name__ == "__main__":
    unittest.main()
