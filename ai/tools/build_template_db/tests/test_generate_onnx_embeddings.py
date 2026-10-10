import json
import struct
import tempfile
import unittest
import sys
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch

import numpy as np
from PIL import Image

import generate_onnx_embeddings


class GenerateOnnxEmbeddingsTests(unittest.TestCase):
    def test_image_cache_preserves_first_inference_pixels(self):
        # Test the image-cache contract without needing a torch installation.
        transforms = SimpleNamespace(
            cache_image_path=lambda root, split, photo_id, size, train: root / f"{photo_id}.jpg",
            load_resized_image=lambda path, size, train: Image.open(path).convert("RGB").resize((size, size)),
            transform_for_split=lambda *args, **kwargs: lambda image: SimpleNamespace(numpy=lambda: np.asarray(image)),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "image.png"
            Image.fromarray(np.arange(16 * 16 * 3, dtype=np.uint8).reshape(16, 16, 3)).save(source)
            record = {"photo_id": "photo", "source_path": str(source)}
            with patch.dict(sys.modules, {"train_student_embedding": transforms}):
                first = generate_onnx_embeddings.load_tensor(record, 16, root, root / "cache")
                second = generate_onnx_embeddings.load_tensor(record, 16, root, root / "cache")
            self.assertTrue(np.array_equal(first, second))
            self.assertEqual(len(list((root / "cache").glob("*.png"))), 1)

    def test_generation_binds_cache_to_model_and_image(self):
        class Session:
            def get_inputs(self):
                return [type("Input", (), {"name": "image"})()]

            def get_outputs(self):
                return [type("Output", (), {"name": "embedding", "shape": [None, 2]})()]

            def run(self, *args):
                return [np.array([[3.0, 4.0]], dtype=np.float32)]

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "photo.jpg"
            source.write_bytes(b"source image")
            model = root / "model.onnx"
            model.write_bytes(b"model v1")
            metadata = root / "metadata.jsonl"
            metadata.write_text(json.dumps({"photo_id": "photo", "template_id": "photo",
                                            "source_path": str(source)}), encoding="utf-8")
            args = (metadata, model, root / "embeddings", root / "updated.jsonl", root / "manifest.jsonl")
            with patch.object(generate_onnx_embeddings, "create_session", return_value=Session()), \
                 patch.object(generate_onnx_embeddings, "load_tensor", return_value=np.zeros((3, 224, 224))):
                generate_onnx_embeddings.generate_embeddings(*args)
                generate_onnx_embeddings.generate_embeddings(*args)
                model.write_bytes(b"model v2 same filename")
                with self.assertRaisesRegex(ValueError, "changed; use --overwrite"):
                    generate_onnx_embeddings.generate_embeddings(*args)
                generate_onnx_embeddings.generate_embeddings(*args, overwrite=True)
                source.write_bytes(b"new image same filename")
                with self.assertRaisesRegex(ValueError, "changed; use --overwrite"):
                    generate_onnx_embeddings.generate_embeddings(*args)

    def test_source_hash_mismatch_fails_before_reuse_or_inference(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "image.jpg"
            source.write_bytes(b"changed source")
            with self.assertRaisesRegex(ValueError, "source_sha256"):
                generate_onnx_embeddings.cache_identity({"source_path": str(source), "source_sha256": "a" * 64}, "b" * 64, 224)

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
            (root / "photo.jpg").write_bytes(b"image bytes")
            identity = generate_onnx_embeddings.cache_identity(
                {"source_path": str(root / "photo.jpg")}, generate_onnx_embeddings.file_hash(model), 224)
            provenance = {**identity, "image_cache_enabled": False,
                          "embedding_sha256": generate_onnx_embeddings.file_hash(embedding_path)}
            embedding_path.with_suffix(".provenance.json").write_text(json.dumps(provenance), encoding="utf-8")

            with patch.object(generate_onnx_embeddings, "create_session", return_value=FailingSession()):
                summary = generate_onnx_embeddings.generate_embeddings(
                    metadata,
                    model,
                    embedding_dir,
                    root / "metadata.out.jsonl",
                    root / "manifest.out.jsonl",
                )

            self.assertEqual(summary["items"], 1)
            self.assertEqual(summary["written_or_ready"], 1)
            self.assertEqual(summary["errors"], [])
            updated = json.loads((root / "metadata.out.jsonl").read_text(encoding="utf-8"))
            self.assertEqual(updated["embedding"]["dimension"], 2)
            self.assertEqual(updated["embedding"]["model_sha256"], generate_onnx_embeddings.file_hash(model))

    def test_cache_rejects_changed_model_image_preprocess_or_vector(self):
        for change in ("missing", "model_sha256", "source_sha256", "image_size", "vector"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "embedding.f32"
                path.write_bytes(struct.pack("<2f", 1, 0))
                identity = {"model_sha256": "a" * 64, "source_sha256": "b" * 64, "image_size": 224}
                provenance = {**identity, "embedding_sha256": generate_onnx_embeddings.file_hash(path)}
                if change in identity:
                    provenance[change] = "changed"
                if change != "missing":
                    path.with_suffix(".provenance.json").write_text(json.dumps(provenance), encoding="utf-8")
                if change == "vector":
                    path.write_bytes(struct.pack("<2f", 0, 1))
                with self.assertRaisesRegex(ValueError, "overwrite"):
                    generate_onnx_embeddings.validate_cached_embedding(path, identity, 2)

    def test_cache_rejects_invalid_dimension_and_nonfinite_values(self):
        for values in ([1, 0, 0], [float("nan"), 0], [2, 0], [0, 0]):
            with self.subTest(values=values), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "embedding.f32"
                path.write_bytes(struct.pack(f"<{len(values)}f", *values))
                identity = {"model_sha256": "a" * 64}
                path.with_suffix(".provenance.json").write_text(json.dumps(
                    {**identity, "embedding_sha256": generate_onnx_embeddings.file_hash(path)}), encoding="utf-8")
                with self.assertRaises(ValueError):
                    generate_onnx_embeddings.validate_cached_embedding(path, identity, 2)

    def test_photo_id_cannot_escape_embedding_directory(self):
        for value in ("../other", "..", "a/b", "a\\b", "C:evil", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                generate_onnx_embeddings.embedding_output_path(Path("unused"), value)


if __name__ == "__main__":
    unittest.main()
