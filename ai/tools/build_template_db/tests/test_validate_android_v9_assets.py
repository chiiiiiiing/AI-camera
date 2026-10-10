import hashlib
import io
import json
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import validate_android_v9_assets


def write_json(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")


def write_assets(root):
    model = b"fixture model bytes"
    (root / "student_model.static_int8.onnx").write_bytes(model)
    digest = hashlib.sha256(model).hexdigest()
    config = {
        "runtime": {"model_file": "student_model.static_int8.onnx"},
        "model": {"input_size": 224, "embedding_dimension": 512,
                  "input_shape": ["batch", 3, 224, 224], "output_shape": ["batch", 512],
                  "input_dtype": "float32", "output_dtype": "float32",
                  "input_tensor": "image", "output_tensor": "embedding"},
        "postprocess": {"search_top_k": 8, "display_top_k": 5, "l2_normalize_embedding": True},
        "preprocess": {"color_order": "RGB", "layout": "NCHW"},
        "offline_quality": {"v9_static_int8_onnx_top5_same_scene_pct": 100.0,
                            "commercial_gate_top5_same_scene_pct": 95.0},
        "artifacts": {"model_sha256": digest},
    }
    manifest = {
        "static_int8_onnx": {"sha256": digest, "bytes": len(model)},
        "input": {"shape": ["batch", 3, 224, 224], "dtype": "float32", "name": "image", "color": "RGB"},
        "output": {"shape": ["batch", 512], "dtype": "float32", "name": "embedding"},
    }
    write_json(root / "android_runtime_config.json", config)
    write_json(root / "android_onnx_runtime_manifest.json", manifest)
    index = root / "template_index"
    index.mkdir()
    records = [{"template_id": str(i), "scene_category": "自然风景",
                "embedding": {"dimension": 512, "status": "ready",
                              "model_id": "models/student_model.static_int8.onnx"}}
               for i in range(6)]
    (index / "templates.metadata.jsonl").write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in records), encoding="utf-8")
    write_json(index / "templates.keys.json", {
        "count": 6, "dimension": 512, "metric": "cosine",
        "items": [{"key": i + 1, "template_id": str(i)} for i in range(6)]})
    values = [float(i == j) for i in range(6) for j in range(512)]
    (index / "templates.embeddings.f32").write_bytes(struct.pack(f"<{len(values)}f", *values))
    return config, manifest, records


class AndroidAssetValidationTests(unittest.TestCase):
    def test_valid_bundle_recomputes_quality(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_assets(root)
            output = io.StringIO()
            with redirect_stdout(output):
                validate_android_v9_assets.validate(root)
            self.assertIn("recomputed leave-one-out", output.getvalue())

    def test_rejects_stale_quality_even_when_config_claims_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, _, records = write_assets(root)
            for i, record in enumerate(records):
                record["scene_category"] = f"unique_{i}"
            (root / "template_index/templates.metadata.jsonl").write_text(
                "\n".join(json.dumps(row) for row in records), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "recomputed.*below gate"):
                validate_android_v9_assets.validate(root)

    def test_rejects_non_normalized_vectors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_assets(root)
            path = root / "template_index/templates.embeddings.f32"
            path.write_bytes(struct.pack("<f", 2.0) + path.read_bytes()[4:])
            with self.assertRaisesRegex(ValueError, "not L2-normalized"):
                validate_android_v9_assets.validate(root)

    def test_rejects_metadata_row_permutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, _, records = write_assets(root)
            (root / "template_index/templates.metadata.jsonl").write_text(
                "\n".join(json.dumps(row) for row in reversed(records)), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match metadata"):
                validate_android_v9_assets.validate(root)

    def test_rejects_wrong_model_and_tensor_contract(self):
        for mismatch in ("model", "shape"):
            with self.subTest(mismatch=mismatch), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, manifest, records = write_assets(root)
                if mismatch == "model":
                    records[0]["embedding"]["model_id"] = "student_model.fp32.onnx"
                    (root / "template_index/templates.metadata.jsonl").write_text(
                        "\n".join(json.dumps(row) for row in records), encoding="utf-8")
                else:
                    manifest["output"]["shape"] = ["batch", 256]
                    write_json(root / "android_onnx_runtime_manifest.json", manifest)
                with self.assertRaisesRegex(ValueError, "differ"):
                    validate_android_v9_assets.validate(root)

    def test_rejects_non_finite_quality(self):
        for value in (float("inf"), float("nan"), -1.0, 101.0):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, _, _ = write_assets(root)
                config["offline_quality"]["v9_static_int8_onnx_top5_same_scene_pct"] = value
                write_json(root / "android_runtime_config.json", config)
                with self.assertRaisesRegex(ValueError, "quality percentage"):
                    validate_android_v9_assets.validate(root)


if __name__ == "__main__":
    unittest.main()
