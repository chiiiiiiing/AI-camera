import json
import math
import struct
import tempfile
import unittest
from pathlib import Path

import evaluate_template_index_quality


def embedding(*values):
    magnitude = math.sqrt(sum(value * value for value in values))
    return [value / magnitude for value in values]


def metadata_record(template_id, photo_type, scene, subject, horizon, lighting):
    return {
        "schema_version": 1,
        "template_id": template_id,
        "photo_id": template_id,
        "photo_type": photo_type,
        "scene_category": scene,
        "annotation": {
            "aesthetic_score": 4,
            "template_value": "high",
            "visual_summary": f"{scene} summary",
            "composition": {
                "subject_position": subject,
                "horizon_position": horizon,
                "leading_lines": True,
                "negative_space": "medium",
            },
            "lighting": {
                "quality": lighting,
                "direction": "side",
                "time_of_day": "day",
                "contrast": "medium",
            },
            "camera": {
                "angle": "eye_level",
                "distance": "wide",
                "orientation": "landscape",
            },
        },
    }


def write_index(root: Path) -> None:
    records = [
        metadata_record("a", "landscape", "natural_landscape", "center", "upper_third", "soft"),
        metadata_record("b", "landscape", "natural_landscape", "center", "upper_third", "soft"),
        metadata_record("c", "landscape", "urban_landscape", "right", "lower_third", "hard"),
        metadata_record("d", "still_life", "food_still_life", "center", "not_visible", "soft"),
    ]
    (root / "templates.metadata.jsonl").write_text(
        "\n".join(json.dumps(record) for record in records) + "\n",
        encoding="utf-8",
    )
    vectors = [
        embedding(1, 0, 0, 0),
        embedding(0.96, 0.28, 0, 0),
        embedding(0, 1, 0, 0),
        embedding(0, 0, 1, 0),
    ]
    packed = [value for vector in vectors for value in vector]
    (root / "templates.embeddings.f32").write_bytes(struct.pack("<16f", *packed))
    keys = {
        "schema_version": 1,
        "metric": "cosine",
        "dimension": 4,
        "count": 4,
        "items": [
            {"key": index + 1, "template_id": record["template_id"]}
            for index, record in enumerate(records)
        ],
    }
    (root / "templates.keys.json").write_text(json.dumps(keys), encoding="utf-8")


class EvaluateTemplateIndexQualityTests(unittest.TestCase):
    def test_build_report_computes_retrieval_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_index(root)
            report = evaluate_template_index_quality.build_report(root, top_k=3, sample_size=2, seed=7)

        self.assertEqual(report["summary"]["count"], 4)
        self.assertEqual(report["metrics"]["top1"]["same_photo_type_hit_rate_pct"], 75.0)
        self.assertEqual(report["metrics"]["top3"]["same_scene_hit_rate_eligible_pct"], 100.0)
        self.assertEqual(len(report["sample_queries"]), 2)
        self.assertIn(report["usearch_check"]["status"], {"skipped", "passed"})

    def test_problem_samples_prioritize_cross_type_top1(self):
        query = {"key": 1, "photo_type": "landscape", "scene_category": "natural_landscape"}
        candidate = {"key": 2, "photo_type": "still_life", "scene_category": "food_still_life"}
        evaluations = [
            {
                "record": query,
                "same_scene_available": True,
                "neighbors": [
                    {
                        "similarity": 0.95,
                        "distance": 0.05,
                        "same_photo_type": False,
                        "same_scene_category": False,
                        "composition_score": 1.0,
                        "lighting_score": 1.0,
                        "record": candidate,
                    }
                ],
            }
        ]

        samples = evaluate_template_index_quality.problem_samples(evaluations)

        self.assertEqual(samples[0]["reason"], "top1_photo_type_mismatch")

    def test_field_match_score_ignores_unknown_values(self):
        left = metadata_record("a", "landscape", "natural_landscape", "center", "unknown", "soft")
        right = metadata_record("b", "landscape", "natural_landscape", "center", "upper_third", "soft")

        score = evaluate_template_index_quality.field_match_score(
            left,
            right,
            evaluate_template_index_quality.COMPOSITION_FIELDS,
        )

        self.assertEqual(score["available"], 6)
        self.assertAlmostEqual(score["score"], 1.0)


if __name__ == "__main__":
    unittest.main()
