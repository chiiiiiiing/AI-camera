import json
import struct
import tempfile
import unittest
from pathlib import Path

import validate_template_index


def write_valid_index(root: Path) -> None:
    (root / "templates.embeddings.f32").write_bytes(
        struct.pack("<8f", 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    )
    metadata = [
        {"template_id": "template_1"},
        {"template_id": "template_2"},
    ]
    (root / "templates.metadata.jsonl").write_text(
        "\n".join(json.dumps(row) for row in metadata) + "\n",
        encoding="utf-8",
    )
    keys = {
        "schema_version": 1,
        "metric": "cosine",
        "dimension": 4,
        "count": 2,
        "items": [
            {"key": 1, "template_id": "template_1"},
            {"key": 2, "template_id": "template_2"},
        ],
    }
    (root / "templates.keys.json").write_text(json.dumps(keys), encoding="utf-8")


class ValidateTemplateIndexTests(unittest.TestCase):
    def test_rejects_invalid_counts_dimensions_and_metric(self):
        for field, value in (("count", 0), ("count", -1), ("count", 2.5), ("dimension", True), ("metric", "l2")):
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                write_valid_index(root)
                path = root / "templates.keys.json"
                keys = json.loads(path.read_text())
                keys[field] = value
                path.write_text(json.dumps(keys))
                if field == "metric":
                    errors, _ = validate_template_index.validate_index(root)
                    self.assertTrue(any("metric" in error for error in errors))
                else:
                    with self.assertRaises(ValueError):
                        validate_template_index.validate_index(root)

    def test_rejects_non_object_keys_boolean_keys_and_conflicting_labels(self):
        for item in (None, {"key": True, "template_id": "template_1"},
                     {"key": 1, "template_id": "template_1", "photo_type": "portrait"}):
            with self.subTest(item=item), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                write_valid_index(root)
                path = root / "templates.keys.json"
                keys = json.loads(path.read_text())
                keys["items"][0] = item
                path.write_text(json.dumps(keys))
                errors, _ = validate_template_index.validate_index(root)
                self.assertTrue(errors)

    def test_valid_index_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_valid_index(root)
            errors, summary = validate_template_index.validate_index(root)
        self.assertEqual(errors, [])
        self.assertEqual(summary["count"], 2)

    def test_rejects_metadata_key_order_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_valid_index(root)
            rows = [{"template_id": "template_2"}, {"template_id": "template_1"}]
            (root / "templates.metadata.jsonl").write_text(
                "\n".join(json.dumps(row) for row in rows) + "\n",
                encoding="utf-8",
            )
            errors, _ = validate_template_index.validate_index(root)
        self.assertTrue(any("does not match metadata" in error for error in errors))

    def test_rejects_wrong_embedding_byte_count(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_valid_index(root)
            (root / "templates.embeddings.f32").write_bytes(b"bad")
            with self.assertRaisesRegex(ValueError, "expected 32 bytes"):
                validate_template_index.validate_index(root)


if __name__ == "__main__":
    unittest.main()
