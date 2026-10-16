import copy
import unittest

import numpy as np
from PIL import Image

from build_reference_structures import group_sources, perceptual_hashes, quality_metrics, separator_regions, structure_reasons


def source(identity, **kwargs):
    row = {"photo_id": identity, "image_sha256": identity.ljust(64, "0"),
        "original_source_group_id": None, "original_split": "train",
        "phash": "0000000000000000", "dhash": "0000000000000000"}
    row.update(kwargs)
    return row


class ReferenceStructureTests(unittest.TestCase):
    def test_parent_and_near_duplicate_merge_transitively_and_mark_old_holdout(self):
        a = source("a", original_source_group_id="post1")
        b = source("b", original_source_group_id="post1", phash="ffffffffffffffff", dhash="ffffffffffffffff")
        c = source("c", parent_photo_id="b", parent_source_sha256=b["image_sha256"], phash="000000000fffffff", dhash="000000000fffffff")
        d = source("d", original_split="test", phash="000000000ffffff0", dhash="000000000ffffff0")
        rows, links = group_sources([a, b, c, d])
        self.assertEqual(len({r["source_group_id"] for r in rows}), 1)
        self.assertTrue(all(r["historical_holdout_group"] for r in rows))
        self.assertTrue(all(r["group_historical_splits"] == ["test", "train"] for r in rows))
        self.assertEqual({link["reason"] for link in links}, {"source", "derived_parent", "near_duplicate_phash6_dhash8"})

    def test_group_is_independent_of_row_order(self):
        rows = [source("a"), source("b"), source("c", phash="ffffffffffffffff", dhash="ffffffffffffffff")]
        forward, _ = group_sources(copy.deepcopy(rows))
        reverse, _ = group_sources(list(reversed(copy.deepcopy(rows))))
        self.assertEqual({r["photo_id"]: r["source_group_id"] for r in forward}, {r["photo_id"]: r["source_group_id"] for r in reverse})
        self.assertNotEqual(forward[0]["source_group_id"], forward[2]["source_group_id"])

    def test_wrong_parent_hash_rejected(self):
        with self.assertRaisesRegex(ValueError, "parent hash"):
            group_sources([source("a"), source("b", parent_photo_id="a", parent_source_sha256="wrong")])

    def test_missing_parent_and_duplicate_identity_rejected(self):
        with self.assertRaisesRegex(ValueError, "parent missing"):
            group_sources([source("b", parent_photo_id="missing", parent_source_sha256="wrong")])
        with self.assertRaisesRegex(ValueError, "duplicate source"):
            group_sources([source("a"), source("a")])

    def test_phash_is_stable_and_distinguishes_texture(self):
        rng = np.random.default_rng(9)
        image = Image.fromarray(rng.integers(0, 256, (256, 256, 3), dtype=np.uint8))
        self.assertEqual(perceptual_hashes(image), perceptual_hashes(image.copy()))
        self.assertNotEqual(perceptual_hashes(image), perceptual_hashes(image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)))

    def test_quality_preserves_units_and_does_not_call_normal_scenes_severe(self):
        black = quality_metrics(Image.new("RGB", (256, 256), "black"))
        self.assertEqual(black["underexposed_fraction"], 1)
        self.assertEqual(black["mean_luminance"], 0)
        self.assertTrue(black["severe_quality_issue"])
        white = quality_metrics(Image.new("RGB", (256, 256), "white"))
        self.assertEqual(white["overexposed_fraction"], 1)
        self.assertTrue(white["severe_quality_issue"])
        middle = quality_metrics(Image.new("RGB", (256, 256), (128, 128, 128)))
        self.assertFalse(middle["severe_quality_issue"])

    def test_quality_region_actually_samples_bbox(self):
        image = Image.new("RGB", (256, 256), "black")
        image.paste("white", (128, 0, 256, 256))
        self.assertAlmostEqual(quality_metrics(image, [0.5, 0, 0.5, 1])["mean_luminance"], 1)
        self.assertAlmostEqual(quality_metrics(image, [0, 0, 0.5, 1])["mean_luminance"], 0)

    def test_separator_only_candidates_with_full_span_gutter_and_sufficient_size(self):
        image = Image.new("RGB", (600, 1000), "red")
        image.paste("blue", (0, 510, 600, 1000))
        image.paste("white", (0, 490, 600, 510))
        regions = separator_regions(image)
        self.assertEqual(len(regions), 2)
        self.assertLessEqual(regions[0][3], 510)
        self.assertGreaterEqual(regions[1][1], 490)
        self.assertFalse(separator_regions(Image.new("RGB", (600, 1000), "white")))

    def test_single_face_requires_review_real_photo_confidence_size_and_boundaries(self):
        row = {"reference_eligible": True, "synthetic": False, "photo_type": "portrait"}
        faces = [{"bbox": [0.4, 0.2, 0.2, 0.3], "confidence": 0.9, "clipped": False}]
        quality = {"severe_quality_issue": False}
        self.assertEqual(structure_reasons(row, faces, quality), [])
        self.assertIn("multiple_faces", structure_reasons(row, faces * 2, quality))
        self.assertIn("no_face", structure_reasons(row, [], quality))
        self.assertIn("synthetic_reference", structure_reasons({**row, "synthetic": True}, faces, quality))
        self.assertIn("not_existing_hash_reviewed_reference", structure_reasons({**row, "reference_eligible": False}, faces, quality))
        self.assertIn("face_confidence_below_0.75", structure_reasons(row, [{**faces[0], "confidence": 0.7}], quality))
        self.assertIn("face_too_small", structure_reasons(row, [{**faces[0], "bbox": [0.4, 0.2, 0.03, 0.03]}], quality))
        self.assertIn("face_touches_boundary", structure_reasons(row, [{**faces[0], "clipped": True}], quality))
        self.assertIn("severe_quality_issue", structure_reasons(row, faces, {"severe_quality_issue": True}))


if __name__ == "__main__":
    unittest.main()
