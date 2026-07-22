import hashlib
import tempfile
import unittest
from pathlib import Path

import screen_candidates


class ScreeningDecisionTests(unittest.TestCase):
    def test_portrait_requires_a_detected_pose(self):
        passed, reasons = screen_candidates.screening_decision("portrait", 0, True, True)
        self.assertFalse(passed)
        self.assertIn("portrait_without_detected_person", reasons)

    def test_non_portrait_rejects_prominent_person(self):
        passed, reasons = screen_candidates.screening_decision("landscape", 1, True, True)
        self.assertFalse(passed)
        self.assertIn("person_detected_in_non_portrait", reasons)

    def test_all_hard_gates_must_pass(self):
        passed, reasons = screen_candidates.screening_decision("portrait", 1, True, True)
        self.assertTrue(passed)
        self.assertEqual(reasons, [])


class FileIntegrityTests(unittest.TestCase):
    def test_hash_mismatch_is_a_screening_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "photo.jpg"
            image.write_bytes(b"test")
            record = {"sha256": "0" * 64}
            self.assertEqual(
                screen_candidates.file_integrity_reasons(record, image),
                ["sha256_mismatch"],
            )

    def test_matching_hash_has_no_integrity_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "photo.jpg"
            image.write_bytes(b"test")
            record = {"sha256": hashlib.sha256(b"test").hexdigest()}
            self.assertEqual(screen_candidates.file_integrity_reasons(record, image), [])


if __name__ == "__main__":
    unittest.main()
