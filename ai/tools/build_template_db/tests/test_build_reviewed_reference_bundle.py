import copy
import unittest

from build_reviewed_reference_bundle import select_records


class ReviewedReferenceSelectionTests(unittest.TestCase):
    def setUp(self):
        self.parent = {"photo_id": "parent", "split": "train", "source_sha256": "a" * 64,
                       "source_group_id": "post:one", "photo_type": "portrait",
                       "scene_category": "closeup_portrait"}
        self.crop = {"photo_id": "crop", "split": "train", "source_sha256": "b" * 64,
                     "parent_photo_id": "parent", "parent_source_sha256": "a" * 64,
                     "source_group_id": "post:one"}

    @staticmethod
    def review(item, status="candidate_clean", method="ai_visual_review"):
        return {"photo_id": item["photo_id"], "source_sha256": item["source_sha256"],
                "status": status, "provenance": {"method": method}}

    def test_only_clean_reviews_are_selected_and_copied(self):
        reviews = [self.review(self.parent, "recoverable_crop"), self.review(self.crop)]
        selected = select_records([self.parent], reviews, [self.crop])
        self.assertEqual([item["photo_id"] for item in selected], ["crop"])
        self.assertEqual(selected[0]["photo_type"], "portrait")
        selected[0]["reference_review"]["status"] = "changed"
        self.assertEqual(reviews[1]["status"], "candidate_clean")

    def test_rejects_non_train_or_missing_training_split(self):
        for split in ("val", "test", None):
            with self.subTest(split=split), self.assertRaisesRegex(ValueError, "only train"):
                select_records([dict(self.parent, split=split)], [self.review(self.parent)], [])

    def test_review_cannot_override_parent_training_split(self):
        for split in ("val", "test", None):
            review = dict(self.review(self.parent), split=split)
            with self.subTest(split=split), self.assertRaisesRegex(ValueError, "review split"):
                select_records([self.parent], [review], [])

    def test_rejects_duplicate_identity_across_parents_crops_or_reviews(self):
        examples = [([self.parent, self.parent], [self.review(self.parent)], []),
                    ([self.parent], [self.review(self.parent)] * 2, []),
                    ([self.parent], [self.review(self.parent)], [dict(self.crop, photo_id="parent")])]
        for parents, reviews, crops in examples:
            with self.subTest(parents=len(parents), reviews=len(reviews), crops=len(crops)):
                with self.assertRaisesRegex(ValueError, "duplicate photo_id"):
                    select_records(parents, reviews, crops)

    def test_rejects_review_for_unknown_image_or_changed_image_bytes(self):
        for review in (dict(self.review(self.parent), photo_id="unknown"),
                       dict(self.review(self.parent), source_sha256="c" * 64)):
            with self.subTest(review=review), self.assertRaisesRegex(ValueError, "identity/hash mismatch"):
                select_records([self.parent], [review], [])

    def test_rejected_reviews_still_require_valid_image_identity(self):
        review = dict(self.review(self.parent, "reject"), source_sha256="c" * 64)
        with self.assertRaisesRegex(ValueError, "identity/hash mismatch"):
            select_records([self.parent], [review], [])

    def test_clean_review_requires_ai_visual_provenance(self):
        for method in (None, "legacy_scene_rule_draft", "human_review"):
            with self.subTest(method=method), self.assertRaisesRegex(ValueError, "provenance"):
                select_records([self.parent], [self.review(self.parent, method=method)], [])

    def test_no_clean_candidates_is_an_error(self):
        with self.assertRaisesRegex(ValueError, "no reviewed reference"):
            select_records([self.parent], [self.review(self.parent, "recoverable_crop")], [])

    def test_recovered_reference_requires_train_parent_and_original_hash(self):
        for changes in ({"split": "val"}, {"parent_photo_id": "unknown"},
                        {"parent_source_sha256": "c" * 64}):
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "invalid train parent"):
                select_records([self.parent], [self.review(self.parent)], [dict(self.crop, **changes)])

    def test_recovered_source_group_cannot_change_or_disappear(self):
        for group in ("post:two", None, ""):
            with self.subTest(group=group), self.assertRaisesRegex(ValueError, "source group mismatch"):
                select_records([self.parent], [self.review(self.crop)], [dict(self.crop, source_group_id=group)])

    def test_unknown_parent_group_uses_parent_hash_not_child_hash(self):
        parent = dict(self.parent, source_group_id=None)
        crop = dict(self.crop, source_group_id=f"parent_sha256:{parent['source_sha256']}")
        selected = select_records([parent], [self.review(crop)], [crop])
        self.assertEqual(selected[0]["source_group_id"], crop["source_group_id"])
        with self.assertRaisesRegex(ValueError, "source group mismatch"):
            select_records([parent], [self.review(crop)],
                           [dict(crop, source_group_id=f"parent_sha256:{crop['source_sha256']}")])

    def test_crop_cannot_inherit_parent_review(self):
        selected = select_records([self.parent], [self.review(self.parent)], [self.crop])
        self.assertEqual([item["photo_id"] for item in selected], ["parent"])

    def test_shipped_review_excludes_workstation_and_contact_sheet_paths(self):
        review = self.review(self.parent)
        review.update(source_path="D:/private/photo.jpg", parent_source_path="D:/private/parent.jpg")
        review["provenance"].update(contact_sheet="D:/private/sheet.png", page=2, slot=3,
                                    observation="Single photograph", human_reviewed=False)
        exported = select_records([self.parent], [review], [])[0]["reference_review"]
        self.assertNotIn("source_path", exported)
        self.assertNotIn("parent_source_path", exported)
        self.assertNotIn("contact_sheet", exported["provenance"])
        self.assertEqual(exported["provenance"]["observation"], "Single photograph")
        self.assertEqual(exported["source_sha256"], self.parent["source_sha256"])

    def test_reviewed_crop_semantics_can_override_parent_but_not_mutate_it(self):
        parent_before = copy.deepcopy(self.parent)
        review = dict(self.review(self.crop), photo_type="still_life", scene_category="food_still_life")
        selected = select_records([self.parent], [review], [self.crop])
        self.assertEqual(selected[0]["photo_type"], "still_life")
        self.assertEqual(selected[0]["scene_category"], "food_still_life")
        self.assertNotIn("teacher_embedding", selected[0])
        self.assertNotIn("annotation", selected[0])
        self.assertEqual(self.parent, parent_before)


if __name__ == "__main__":
    unittest.main()
