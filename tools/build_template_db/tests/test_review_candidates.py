import unittest

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


if __name__ == "__main__":
    unittest.main()
