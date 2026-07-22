import math
import struct
import tempfile
import unittest
from pathlib import Path

import validate_embeddings


class ValidateEmbeddingsTests(unittest.TestCase):
    def test_normalized_embedding_passes(self):
        value = 1 / math.sqrt(256)
        self.assertEqual(validate_embeddings.validate_embedding_values([value] * 256), [])

    def test_wrong_dimension_fails(self):
        errors = validate_embeddings.validate_embedding_values([1.0] * 10)
        self.assertTrue(any("expected 256" in error for error in errors))

    def test_unnormalized_embedding_fails(self):
        errors = validate_embeddings.validate_embedding_values([1.0] * 256)
        self.assertTrue(any("not L2-normalized" in error for error in errors))

    def test_reads_little_endian_float32(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "embedding.f32"
            path.write_bytes(struct.pack("<2f", 1.0, 2.0))
            self.assertEqual(validate_embeddings.read_f32(path), [1.0, 2.0])


if __name__ == "__main__":
    unittest.main()
