import json
import tempfile
import unittest
from pathlib import Path

import merge_annotation_results
import pack_annotation_batches


def request(photo_id):
    return {
        "schema_version": 1,
        "photo_id": photo_id,
        "photo_type": "landscape",
        "scene_category": "natural_landscape",
        "image_path": f"{photo_id}.jpg",
        "prompt": "label this photo",
    }


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


class AnnotationBatchTests(unittest.TestCase):
    def test_pack_batches_writes_manifest_and_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            requests = root / "requests.jsonl"
            requests.write_text(
                "\n".join(json.dumps(request(f"photo_{index}")) for index in range(5)) + "\n",
                encoding="utf-8",
            )
            summary = pack_annotation_batches.pack_batches(requests, root / "batches", max_items=2)
            self.assertEqual(summary["total_requests"], 5)
            self.assertEqual(len(summary["batches"]), 3)
            self.assertTrue((root / "batches" / "annotation_batch_0001.jsonl").exists())
            self.assertTrue((root / "batches" / "batch_manifest.json").exists())

    def test_duplicate_request_id_fails(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            pack_annotation_batches.validate_request_ids([request("photo_1"), request("photo_1")])


class MergeAnnotationResultsTests(unittest.TestCase):
    def test_merge_orders_annotations_by_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            requests = root / "requests.jsonl"
            requests.write_text(
                json.dumps(request("photo_1")) + "\n" + json.dumps(request("photo_2")) + "\n",
                encoding="utf-8",
            )
            first = valid_annotation()
            first["photo_id"] = "photo_2"
            second = valid_annotation()
            second["photo_id"] = "photo_1"
            results = root / "results.jsonl"
            results.write_text(json.dumps(first) + "\n" + json.dumps(second) + "\n", encoding="utf-8")
            merged, summary, errors = merge_annotation_results.merge_results(requests, [results])
        self.assertEqual(errors, [])
        self.assertEqual(summary["merged"], 2)
        self.assertEqual([row["photo_id"] for row in merged], ["photo_1", "photo_2"])

    def test_missing_annotation_fails_without_allow_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            requests = root / "requests.jsonl"
            requests.write_text(json.dumps(request("photo_1")) + "\n", encoding="utf-8")
            results = root / "results.jsonl"
            results.write_text("", encoding="utf-8")
            _, summary, errors = merge_annotation_results.merge_results(requests, [results])
        self.assertEqual(summary["missing"], 1)
        self.assertTrue(any("missing annotations" in error for error in errors))

    def test_unknown_annotation_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            requests = root / "requests.jsonl"
            requests.write_text(json.dumps(request("photo_1")) + "\n", encoding="utf-8")
            annotation = valid_annotation()
            annotation["photo_id"] = "photo_2"
            results = root / "results.jsonl"
            results.write_text(json.dumps(annotation) + "\n", encoding="utf-8")
            _, summary, errors = merge_annotation_results.merge_results(
                requests, [results], allow_missing=True
            )
        self.assertEqual(summary["unknown"], 1)
        self.assertTrue(any("unknown annotation" in error for error in errors))

    def test_extracts_annotation_from_text_field(self):
        annotation = valid_annotation()
        wrapped = {
            "photo_id": annotation["photo_id"],
            "response_text": "```json\n" + json.dumps(annotation) + "\n```",
        }
        extracted = merge_annotation_results.annotation_from_record(wrapped)
        self.assertEqual(extracted["photo_id"], annotation["photo_id"])
        self.assertEqual(extracted["template_value"], "high")

    def test_extracts_annotation_from_annotation_field(self):
        annotation = valid_annotation()
        wrapped = {"annotation": annotation}
        extracted = merge_annotation_results.annotation_from_record(wrapped)
        self.assertEqual(extracted["photo_id"], annotation["photo_id"])


if __name__ == "__main__":
    unittest.main()
