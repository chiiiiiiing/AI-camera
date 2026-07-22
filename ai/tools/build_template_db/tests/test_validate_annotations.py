import unittest

import validate_annotations


def valid_annotation():
    return {
        "schema_version": 1,
        "photo_id": "photo_1",
        "photo_type": "landscape",
        "scene_category": "natural_landscape",
        "visual_summary": "A lake framed by mountains under soft daylight.",
        "composition": {
            "type": "rule_of_thirds",
            "subject_position": "center",
            "horizon_position": "upper_third",
            "leading_lines": False,
            "negative_space": "medium",
        },
        "lighting": {
            "quality": "soft",
            "direction": "ambient",
            "time_of_day": "day",
            "contrast": "medium",
        },
        "camera": {
            "angle": "eye_level",
            "distance": "wide",
            "orientation": "landscape",
        },
        "subject": {
            "primary_type": "landscape",
            "count": 0,
            "pose_description": None,
        },
        "aesthetic_score": 4.2,
        "template_value": "high",
        "shooting_guidance": ["Keep the horizon near the upper third."],
    }


class AnnotationValidationTests(unittest.TestCase):
    def test_valid_annotation_passes_core_validation(self):
        self.assertEqual(validate_annotations.validate_core(valid_annotation()), [])

    def test_rejects_bad_score(self):
        record = valid_annotation()
        record["aesthetic_score"] = 8
        self.assertIn("aesthetic_score must be in [0, 5]", validate_annotations.validate_core(record))

    def test_rejects_missing_fields(self):
        errors = validate_annotations.validate_core({"schema_version": 1})
        self.assertTrue(any("missing fields" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
