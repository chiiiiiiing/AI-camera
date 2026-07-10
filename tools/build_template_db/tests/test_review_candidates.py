import unittest

import quarantine_cv_failures
import quarantine_integrity_failures
import quarantine_metadata_terms
import review_candidates


class ReviewTests(unittest.TestCase):
    def test_reject_sets_reason_and_timestamp(self):
        records = [{"photo_id": "photo_1", "review": {"quality_status": "pending"}}]
        record = review_candidates.set_quality_status(
            records, "photo_1", "rejected", "composition_not_selected"
        )
        self.assertEqual(record["review"]["quality_status"], "rejected")
        self.assertEqual(record["review"]["rejection_reason"], "composition_not_selected")
        self.assertIn("+00:00", record["review"]["reviewed_at"])

    def test_unknown_photo_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "found 0"):
            review_candidates.set_quality_status([], "missing", "accepted", None)


class QuarantineIntegrityTests(unittest.TestCase):
    def test_hash_mismatch_is_rejected(self):
        records = [
            {
                "photo_id": "photo_1",
                "file_path": "photo.jpg",
                "sha256": "0" * 64,
                "review": {"quality_status": "accepted"},
            }
        ]
        with self.subTest("quarantine"):
            import tempfile
            from pathlib import Path

            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "photo.jpg").write_bytes(b"test")
                updated, summary = quarantine_integrity_failures.quarantine_records(
                    records, root, "2026-07-09T00:00:00+00:00"
                )
        self.assertEqual(summary["newly_rejected"], 1)
        self.assertEqual(updated[0]["review"]["quality_status"], "rejected")
        self.assertEqual(updated[0]["review"]["rejection_reason"], "sha256_mismatch")

    def test_existing_rejection_is_not_rewritten(self):
        records = [
            {
                "photo_id": "photo_1",
                "file_path": "missing.jpg",
                "sha256": "0" * 64,
                "review": {
                    "quality_status": "rejected",
                    "rejection_reason": "manual_quality",
                },
            }
        ]
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as directory:
            updated, summary = quarantine_integrity_failures.quarantine_records(
                records, Path(directory), "2026-07-09T00:00:00+00:00"
            )
        self.assertEqual(summary["already_rejected"], 1)
        self.assertEqual(updated[0]["review"]["rejection_reason"], "manual_quality")


class QuarantineCvFailureTests(unittest.TestCase):
    def test_cv_failure_is_rejected(self):
        records = [
            {
                "photo_id": "photo_1",
                "review": {"quality_status": "accepted"},
            }
        ]
        report = [
            {
                "photo_id": "photo_1",
                "status": "error",
                "reason": "torso anchors not confident enough",
            }
        ]
        updated, summary = quarantine_cv_failures.quarantine_records(
            records, report, "2026-07-09T00:00:00+00:00"
        )
        self.assertEqual(summary["newly_rejected"], 1)
        self.assertEqual(updated[0]["review"]["quality_status"], "rejected")
        self.assertEqual(updated[0]["review"]["rejection_reason"], "cv_preprocess_error")
        self.assertEqual(
            updated[0]["review"]["cv_failure_reason"],
            "torso anchors not confident enough",
        )

    def test_success_status_is_kept(self):
        records = [
            {
                "photo_id": "photo_1",
                "review": {"quality_status": "accepted"},
            }
        ]
        report = [{"photo_id": "photo_1", "status": "pose_detected"}]
        updated, summary = quarantine_cv_failures.quarantine_records(records, report)
        self.assertEqual(summary["newly_rejected"], 0)
        self.assertEqual(updated[0]["review"]["quality_status"], "accepted")


class QuarantineMetadataTests(unittest.TestCase):
    def test_sensitive_title_is_rejected(self):
        records = [
            {
                "photo_id": "photo_1",
                "source": {"title": "Sexy sheer outdoor portrait", "tags": []},
                "review": {"quality_status": "accepted"},
            }
        ]
        updated, summary = quarantine_metadata_terms.quarantine_records(
            records, {"sexy", "sheer"}, "2026-07-09T00:00:00+00:00"
        )
        self.assertEqual(summary["newly_rejected"], 1)
        self.assertEqual(updated[0]["review"]["quality_status"], "rejected")
        self.assertEqual(
            updated[0]["review"]["rejection_reason"],
            "metadata_excluded_terms:sexy,sheer",
        )

    def test_clean_title_is_kept(self):
        records = [
            {
                "photo_id": "photo_1",
                "source": {"title": "Natural light portrait", "tags": ["city"]},
                "review": {"quality_status": "accepted"},
            }
        ]
        updated, summary = quarantine_metadata_terms.quarantine_records(records, {"sexy"})
        self.assertEqual(summary["newly_rejected"], 0)
        self.assertEqual(updated[0]["review"]["quality_status"], "accepted")


if __name__ == "__main__":
    unittest.main()
