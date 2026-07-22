import json
import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np

import generate_onnx_embeddings


class GenerateOnnxEmbeddingsTests(unittest.TestCase):
    def test_l2_normalize_handles_zero_rows(self):
        values = np.array([[3.0, 4.0], [0.0, 0.0]], dtype=np.float32)

        normalized = generate_onnx_embeddings.l2_normalize(values)

        self.assertAlmostEqual(float(np.linalg.norm(normalized[0])), 1.0)
        self.assertEqual(normalized[1].tolist(), [0.0, 0.0])

    def test_reuses_existing_embedding_without_onnx_run(self):
        class FailingSession:
            def __init__(self, *args, **kwargs):
                pass

            def get_inputs(self):
                return [type("Input", (), {"name": "image"})()]

            def get_outputs(self):
                return [type("Output", (), {"name": "embedding", "shape": [None, 2]})()]

            def run(self, *args, **kwargs):
                raise AssertionError("existing embedding should be reused")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            embedding_dir = root / "embeddings"
            embedding_path = embedding_dir / "photo" / "embedding.f32"
            embedding_path.parent.mkdir(parents=True)
            embedding_path.write_bytes(struct.pack("<2f", 1.0, 0.0))
            metadata = root / "metadata.jsonl"
            metadata.write_text(
                json.dumps(
                    {
                        "photo_id": "photo",
                        "template_id": "template",
                        "source_path": str(root / "photo.jpg"),
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            model = root / "model.onnx"
            model.write_bytes(b"fake")

            original_session = generate_onnx_embeddings.ort.InferenceSession
            generate_onnx_embeddings.ort.InferenceSession = FailingSession
            try:
                summary = generate_onnx_embeddings.generate_embeddings(
                    metadata,
                    model,
                    embedding_dir,
                    root / "metadata.out.jsonl",
                    root / "manifest.out.jsonl",
                )
            finally:
                generate_onnx_embeddings.ort.InferenceSession = original_session

            self.assertEqual(summary["items"], 1)
            self.assertEqual(summary["written_or_ready"], 1)
            self.assertEqual(summary["errors"], [])
            updated = json.loads((root / "metadata.out.jsonl").read_text(encoding="utf-8"))
            self.assertEqual(updated["embedding"]["dimension"], 2)


if __name__ == "__main__":
    unittest.main()
