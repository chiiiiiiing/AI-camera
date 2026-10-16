from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import prepare_quality_teacher as teacher


class TeacherDownloadTests(unittest.TestCase):
    def test_verified_cache_avoids_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights"
            path.write_bytes(b"pinned")
            with mock.patch.object(teacher.urllib.request, "urlopen") as request:
                record = teacher.download("https://example.invalid/pinned", path, hashlib.sha256(b"pinned").hexdigest())
            request.assert_not_called()
            self.assertEqual(record["bytes"], 6)

    def test_bad_download_has_two_attempts_and_never_promotes_partial(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights"
            with mock.patch.object(teacher.urllib.request, "urlopen", side_effect=lambda *a, **k: io.BytesIO(b"bad")) as request, \
                 mock.patch.object(teacher.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "SHA256 mismatch"):
                    teacher.download("https://example.invalid/pinned", path, "0" * 64)
            self.assertEqual(request.call_count, 2)
            self.assertFalse(path.exists())
            self.assertTrue(path.with_suffix(".partial").exists())

    def test_modified_cached_code_is_rejected_before_import(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            names = ["__init__.py", "clip.py", "model.py", "simple_tokenizer.py", "bpe_simple_vocab_16e6.txt.gz",
                     "CLIP-LICENSE", "aesthetic-LICENSE", "simple_inference.py", "sac+logos+ava1-l14-linearMSE.pth", "ViT-L-14.pt"]
            for name in names:
                (root / name).write_bytes(b"fixed")
            (root / "clip.py").write_bytes(b"modified")
            provenance = {"clip_commit": teacher.CLIP_COMMIT, "aesthetic_commit": teacher.AESTHETIC_COMMIT,
                          "files": [{"file": name, "bytes": 5, "sha256": hashlib.sha256(b"fixed").hexdigest()} for name in names]}
            (root / "teacher-provenance.json").write_text(json.dumps(provenance))
            with self.assertRaisesRegex(ValueError, "artifact changed"):
                teacher.verify_teacher(root)


if __name__ == "__main__":
    unittest.main()
