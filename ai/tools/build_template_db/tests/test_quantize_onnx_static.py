import json
import tempfile
import unittest
from pathlib import Path

import quantize_onnx_static


class QuantizeOnnxStaticTests(unittest.TestCase):
    def test_sha256_file_and_size_are_recorded_from_real_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.onnx"
            path.write_bytes(b"android-runtime")

            self.assertEqual(quantize_onnx_static.file_size(path), len(b"android-runtime"))
            self.assertEqual(
                quantize_onnx_static.sha256_file(path),
                "1613be868c5ef3251d8c78d44bf486af6a81b0389a83faaa7a905b9145c2713f",
            )

    def test_load_jsonl_rejects_non_object_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.jsonl"
            path.write_text(json.dumps(["not", "an", "object"]) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "line is not an object"):
                quantize_onnx_static.load_jsonl(path)


if __name__ == "__main__":
    unittest.main()
