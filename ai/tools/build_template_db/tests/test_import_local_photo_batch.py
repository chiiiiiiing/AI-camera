import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image

import import_local_photo_batch as importer
from validate_manifest import validate_core


class ImportLocalPhotoBatchTests(unittest.TestCase):
    def test_imports_local_combined_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_dir = root / "source"
            source_dir.mkdir()
            image_path = source_dir / "photo_00001_portrait_xhs_sample.jpg"
            Image.new("RGB", (1440, 1080), (100, 120, 140)).save(image_path)
            combined = root / "source_manifest.json"
            combined.write_text(
                json.dumps(
                    {
                        "items": [
                            {
                                "index": 1,
                                "category": "portrait",
                                "file": str(image_path),
                                "source_file": str(image_path),
                                "source": "xhs",
                                "width": 1440,
                                "height": 1080,
                                "title": "golden hour portrait",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            output = root / "out"

            report = importer.import_batch(
                source_dir,
                output,
                combined_manifest=combined,
                source_root=root,
                batch_id="new_batch",
                mode="copy",
            )

            self.assertEqual(report["records"], 1)
            manifest = output / "manifest.jsonl"
            record = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(validate_core(record), [])
            self.assertEqual(record["scene_category"], "golden_hour_portrait")
            self.assertEqual(record["rights"]["license"], "local_authorized")
            self.assertTrue((output / record["file_path"]).exists())


if __name__ == "__main__":
    unittest.main()
