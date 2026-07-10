import unittest

import apply_review_ids
import make_review_pack


class ReviewPackTests(unittest.TestCase):
    def test_assigns_short_ids_by_photo_type(self):
        rows = make_review_pack.assign_short_ids(
            [
                {"photo_id": "portrait_1", "photo_type": "portrait"},
                {"photo_id": "portrait_2", "photo_type": "portrait"},
                {"photo_id": "landscape_1", "photo_type": "landscape"},
            ]
        )
        self.assertEqual([row["short_id"] for row in rows], ["P001", "P002", "L001"])

    def test_filter_records_by_status_and_type(self):
        records = [
            {"photo_id": "a", "photo_type": "portrait", "review": {"quality_status": "accepted"}},
            {"photo_id": "b", "photo_type": "landscape", "review": {"quality_status": "accepted"}},
            {"photo_id": "c", "photo_type": "portrait", "review": {"quality_status": "rejected"}},
        ]
        selected = make_review_pack.selected_records(records, "portrait", "accepted")
        self.assertEqual([record["photo_id"] for record in selected], ["a"])


class ApplyReviewIdsTests(unittest.TestCase):
    def test_rejects_by_short_id(self):
        records = [{"photo_id": "photo_1", "review": {"quality_status": "accepted"}}]
        changed = apply_review_ids.apply_decisions(
            records,
            {"P001": "photo_1"},
            ["p001"],
            "rejected",
            "not_portrait",
            "2026-07-09T00:00:00+00:00",
        )
        self.assertEqual(changed, ["photo_1"])
        self.assertEqual(records[0]["review"]["quality_status"], "rejected")
        self.assertEqual(records[0]["review"]["rejection_reason"], "not_portrait")

    def test_unknown_short_id_fails(self):
        with self.assertRaisesRegex(ValueError, "unknown short IDs"):
            apply_review_ids.apply_decisions([], {}, ["P999"], "rejected", "bad")


if __name__ == "__main__":
    unittest.main()
