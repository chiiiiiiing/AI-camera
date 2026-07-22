import json
import tempfile
import unittest
from pathlib import Path

import build_annotation_requests


class AnnotationRequestTests(unittest.TestCase):
    def test_builds_request_for_accepted_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prompt = root / "prompt.md"
            prompt.write_text("metadata {{METADATA_JSON}}", encoding="utf-8")
            manifest = root / "manifest.jsonl"
            record = {
                "photo_id": "photo_1",
                "photo_type": "landscape",
                "scene_category": "natural_landscape",
                "query": "mountain",
                "file_path": "landscape/photo.jpg",
                "width": 1024,
                "height": 768,
                "source": {"title": "Mountain", "tags": ["lake"]},
                "review": {"quality_status": "accepted"},
            }
            manifest.write_text(json.dumps(record) + "\n", encoding="utf-8")
            requests = build_annotation_requests.build_requests(manifest, prompt)
        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0]["photo_id"], "photo_1")
        self.assertIn('"scene_category": "natural_landscape"', requests[0]["prompt"])

    def test_default_prompt_has_placeholder(self):
        self.assertIn("{{METADATA_JSON}}", build_annotation_requests.default_prompt_template())

    def test_rejects_template_without_placeholder(self):
        with tempfile.TemporaryDirectory() as directory:
            prompt = Path(directory) / "prompt.md"
            prompt.write_text("no placeholder", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "METADATA_JSON"):
                build_annotation_requests.load_prompt_template(prompt)


if __name__ == "__main__":
    unittest.main()
