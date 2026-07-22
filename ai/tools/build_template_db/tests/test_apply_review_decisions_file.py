import unittest

import apply_review_decisions_file


class ReviewDecisionsFileTests(unittest.TestCase):
    def test_parse_ranges_and_default_prefix(self):
        markdown = """
## portrait 审核结果

- 拒绝：p001-003，005不好看；p010和p012-013都是多张组合，裁剪一下
- 备注：
"""
        decisions = apply_review_decisions_file.parse_decisions(markdown)
        self.assertEqual(decisions["P001"]["action"], "reject")
        self.assertEqual(decisions["P001"]["reason"], "manual_quality_reject")
        self.assertEqual(decisions["P005"]["reason"], "manual_not_selected")
        self.assertEqual(decisions["P010"]["action"], "needs_crop")
        self.assertEqual(decisions["P012"]["action"], "needs_crop")
        self.assertEqual(decisions["P013"]["reason"], "needs_crop_multi_image")

    def test_crop_portrait_note_sets_target_type(self):
        markdown = """
## still_life 审核结果

- 拒绝：s157-159是多张人像拼到一起，裁剪一下放到人像
- 备注：
"""
        decisions = apply_review_decisions_file.parse_decisions(markdown)
        self.assertEqual(decisions["S157"]["target_photo_type"], "portrait")

    def test_apply_defaults_unlisted_review_items_to_accepted(self):
        records = [
            {
                "photo_id": "photo_1",
                "photo_type": "portrait",
                "rights": {"license": "unknown"},
                "review": {"quality_status": "pending"},
            },
            {"photo_id": "photo_2", "photo_type": "portrait", "review": {"quality_status": "pending"}},
            {"photo_id": "photo_3", "photo_type": "landscape", "review": {"quality_status": "rejected"}},
        ]
        short_to_index = {
            "P001": {"photo_id": "photo_1", "short_id": "P001"},
            "P002": {"photo_id": "photo_2", "short_id": "P002"},
        }
        photo_to_short = {"photo_1": "P001", "photo_2": "P002"}
        decisions = {"P001": {"action": "reject", "reason": "manual_quality_reject", "note": "p001", "target_photo_type": "portrait"}}
        reviewed, crop, summary = apply_review_decisions_file.apply_review(
            records,
            short_to_index,
            photo_to_short,
            decisions,
            "2026-07-18T00:00:00+00:00",
        )
        self.assertEqual(crop, [])
        self.assertEqual(reviewed[0]["review"]["quality_status"], "rejected")
        self.assertEqual(reviewed[1]["review"]["quality_status"], "accepted")
        self.assertEqual(reviewed[2]["review"]["quality_status"], "rejected")
        self.assertEqual(reviewed[0]["review"]["rights_status"], "accepted")
        self.assertEqual(reviewed[0]["review"]["portrait_release_status"], "accepted")
        self.assertEqual(reviewed[2]["review"]["portrait_release_status"], "not_applicable")
        self.assertEqual(reviewed[0]["rights"]["license"], "local_authorized")
        self.assertEqual(reviewed[1]["rights"]["license_url"], "local://ai-camera/rights/local_authorized")
        self.assertEqual(summary["counts"]["accepted"], 1)
        self.assertEqual(summary["counts"]["rejected"], 2)

    def test_crop_candidates_are_reported(self):
        records = [
            {
                "photo_id": "photo_1",
                "photo_type": "landscape",
                "scene_category": "natural_landscape",
                "file_path": "photo.jpg",
                "rights": {"license": "cc-by"},
                "review": {"quality_status": "pending"},
            }
        ]
        reviewed, crop, _ = apply_review_decisions_file.apply_review(
            records,
            {"L001": {"photo_id": "photo_1", "short_id": "L001", "absolute_path": "photo.jpg"}},
            {"photo_1": "L001"},
            {
                "L001": {
                    "action": "needs_crop",
                    "reason": "needs_crop_instructional_text",
                    "note": "l001是加了文字教学的图，可以单独把图抠出来",
                    "target_photo_type": "landscape",
                }
            },
            "2026-07-18T00:00:00+00:00",
        )
        self.assertEqual(reviewed[0]["review"]["quality_status"], "rejected")
        self.assertEqual(crop[0]["short_id"], "L001")
        self.assertTrue(crop[0]["can_use_visible_tip_text_as_training_hint"])


if __name__ == "__main__":
    unittest.main()
