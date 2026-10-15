import unittest

import numpy as np
from PIL import Image

from build_multiscene_references import (
    ALLOWED_CLASSES, box_iou, canonical_objects, coverage_report,
    crop_preserves_objects, layout_descriptor, object_eligibility, retained_fraction,
    reviewed_object_eligibility,
    live_uncertain_competitors, live_uncertainty_replay,
)


def ref(**kwargs):
    return {"reference_eligible": True, "synthetic": False,
            "scene_category": "food_still_life", **kwargs}


def obj(class_id=41, box=None, confidence=.9):
    return {"class_id": class_id, "label": "cup", "confidence": confidence,
            "bbox": box or [.3, .3, .3, .3], "clipped": False}


class MultiSceneReferenceTests(unittest.TestCase):
    def test_layout_exact_flat_color_values_and_no_edges(self):
        result = layout_descriptor(Image.new("RGB", (100, 150), "red"))
        self.assertEqual(len(result["luma_grid"]), 9)
        self.assertEqual(result["luma_grid"], [.299] * 9)
        self.assertEqual(result["edge_grid"], [0.] * 9)
        self.assertEqual(result["rgb_mean_grid"], [1., 0., 0.] * 9)
        self.assertFalse(result["is_aesthetic_score"])
        self.assertFalse(result["horizon_available"])

    def test_layout_uses_whole_frame_and_bounded_difference_edges(self):
        array = np.zeros((224, 224, 3), dtype=np.uint8)
        array[:, 224 // 3:] = 255
        result = layout_descriptor(Image.fromarray(array))
        self.assertEqual(result["luma_grid"], [0., 1., 1.] * 3)
        self.assertAlmostEqual(result["edge_grid"][0], .5 / 74, places=6)
        self.assertEqual(result["edge_grid"][1], 0.)
        self.assertAlmostEqual(result["dark_fraction"], 74 / 224, places=6)

    def test_only_single_complete_supported_still_life_is_eligible(self):
        good, reasons, index = object_eligibility(ref(), [obj()])
        self.assertTrue(good)
        self.assertFalse(reasons)
        self.assertEqual(index, 0)
        for row, objects, reason in [
            (ref(synthetic=True), [obj()], "synthetic_reference"),
            (ref(reference_eligible=False), [obj()], "not_hash_reviewed_reference"),
            (ref(scene_category="natural_landscape"), [obj()], "scene_not_supported_for_object_target"),
            (ref(scene_category="plant_still_life"), [obj(58)], "scene_not_supported_for_object_target"),
            (ref(), [obj(75)], "primary_class_not_supported"),
            (ref(), [obj(58)], "primary_class_not_supported"),
            (ref(), [obj(box=[0., .2, .4, .4])], "primary_touches_frame_boundary"),
            (ref(), [obj(box=[.02, .02, .94, .94])], "primary_too_large"),
            (ref(), [obj(), obj(0, [.7, .7, .15, .15])], "person_context"),
            (ref(), [obj(), obj(39, [.6, .6, .2, .2])], "multiple_competing_objects"),
            (ref(), [obj(confidence=.49)], "no_confident_supported_size_object"),
        ]:
            with self.subTest(reason=reason, row=row):
                good, reasons, _ = object_eligibility(row, objects)
                self.assertFalse(good)
                self.assertIn(reason, reasons)

    def test_primary_largest_object_cannot_ignore_unknown_class(self):
        good, reasons, index = object_eligibility(ref(), [obj(), obj(60, [.1, .1, .7, .7])])
        self.assertFalse(good)
        self.assertEqual(index, 1)
        self.assertIn("primary_class_not_supported", reasons)

    def test_uncertain_tiny_object_does_not_prevent_valid_primary(self):
        self.assertTrue(object_eligibility(ref(), [obj(), obj(39, [.8, .8, .1, .1])])[0])
        self.assertTrue(object_eligibility(ref(), [obj(), obj(39, [.7, .7, .2, .2], .49)])[0])

    def test_high_key_low_key_styles_are_not_global_exposure_rejections(self):
        for value in (.05, .95):
            row = ref(quality={"mean_luminance": value, "underexposed_fraction": .6,
                               "overexposed_fraction": .6, "severe_quality_issue": False})
            self.assertTrue(object_eligibility(row, [obj()])[0])
        self.assertFalse(object_eligibility(ref(quality={"severe_quality_issue": True}), [obj()])[0])

    def test_canonical_rejects_nonfinite_and_outside_detections(self):
        result = canonical_objects([
            {"class_id": 41, "confidence": .9, "box": [.2, .2, .5, .5]},
            {"class_id": 41, "confidence": float("nan"), "box": [.2, .2, .5, .5]},
            {"class_id": 41, "confidence": .9, "box": [-.2, .2, .5, .5]},
            {"class_id": 41, "confidence": .9, "box": [.5, .2, .2, .5]},
        ])
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["bbox"], [.2, .2, .3, .3])

    def test_crops_preserve_all_confident_objects_including_nonprimary(self):
        objects = [obj(), obj(39, [.8, .8, .15, .15])]
        self.assertTrue(crop_preserves_objects(objects, [0, 0, 1, 1]))
        self.assertFalse(crop_preserves_objects(objects, [0, 0, .8, .8]))
        self.assertFalse(crop_preserves_objects(objects, [.4, 0, 1, 1]))
        self.assertFalse(crop_preserves_objects(objects, [0, 0, 2, 1]))
        self.assertAlmostEqual(retained_fraction([.3, .3, .3, .3], [.3, .3, .45, .6]), .5)

    def test_mirror_box_transform_is_involution_and_iou_known(self):
        a = [.1, .2, .3, .4]
        mirror = lambda b: [1 - b[0] - b[2], b[1], b[2], b[3]]
        self.assertAlmostEqual(box_iou(a, mirror(mirror(a))), 1.)
        self.assertEqual(box_iou(a, [.7, .7, .2, .2]), 0.)
        self.assertAlmostEqual(box_iou([0, 0, .5, 1], [0, 0, 1, 1]), .5)

    def test_coverage_counts_source_cluster_once(self):
        records = [{"scene_category": "food_still_life", "source_cluster_id": "same",
                    "object_eligible": True, "primary_object_index": 0, "objects": [obj()], "object_reasons": []}] * 2
        report = coverage_report(records)
        self.assertEqual(report["reference_count"], 2)
        self.assertEqual(report["source_clusters"], 1)
        self.assertEqual(report["scenes"]["food_still_life"]["eligible_object_clusters"], 1)
        self.assertTrue(report["coverage_is_not_detection_accuracy"])

    def test_visual_review_must_bind_image_and_detector_and_cannot_promote_invalid_box(self):
        row = ref(template_id="one", image_sha256="a" * 64)
        review = {"template_id": "one", "image_sha256": "a" * 64, "model_sha256": "b" * 64,
                  "method": "ai_visual_review", "human_reviewed": False, "status": "accepted", "reason_codes": []}
        self.assertTrue(reviewed_object_eligibility(row, [obj()], review, "b" * 64)[0])
        self.assertFalse(reviewed_object_eligibility(row, [obj(box=[0, .2, .3, .3])], review, "b" * 64)[0])
        self.assertFalse(reviewed_object_eligibility(row, [obj()], None, "b" * 64)[0])
        self.assertFalse(reviewed_object_eligibility(row, [obj()], {**review, "status": "excluded", "reason_codes": ["wrong_object_class"]}, "b" * 64)[0])
        for change in ({"image_sha256": "wrong"}, {"model_sha256": "wrong"}, {"template_id": "wrong"},
                       {"method": "scene_rule"}, {"human_reviewed": True}):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "review identity"):
                reviewed_object_eligibility(row, [obj()], {**review, **change}, "b" * 64)

    def test_live_uncertainty_veto_is_stricter_than_reference_review(self):
        objects = [obj(), obj(0, [.4, .4, .4, .4], .4)]
        self.assertTrue(object_eligibility(ref(), objects)[0])
        self.assertEqual(len(live_uncertain_competitors(objects, 0)), 1)
        overlap = [obj(), obj(41, [.3, .3, .3, .3], .4)]
        self.assertFalse(live_uncertain_competitors(overlap, 0))
        tiny = [obj(), obj(41, [.8, .8, .1, .1], .4)]
        self.assertFalse(live_uncertain_competitors(tiny, 0))
        report = live_uncertainty_replay([{"template_id": "one", "source_cluster_id": "one",
                                          "object_eligible": True, "objects": objects, "primary_object_index": 0}])
        self.assertEqual(report["rejected"], 1)
        self.assertTrue(report["not_query_coverage_or_accuracy"])


if __name__ == "__main__":
    unittest.main()
