import importlib.util
import tempfile
import unittest
from pathlib import Path

import build_usearch_index
from test_validate_template_index import write_valid_index


@unittest.skipIf(importlib.util.find_spec("usearch") is None, "usearch is not installed")
class BuildUSearchIndexTests(unittest.TestCase):
    def test_builds_usearch_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_valid_index(root)
            summary = build_usearch_index.build_usearch_index(root)
            self.assertEqual(summary["count"], 2)
            self.assertTrue((root / "templates.usearch.idx").exists())
            self.assertTrue((root / "templates.usearch.json").exists())


if __name__ == "__main__":
    unittest.main()
