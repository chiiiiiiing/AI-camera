import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

import evaluate_retrieval


def record(photo_id, scene, **extra):
    return {
        "photo_id": photo_id,
        "source_sha256": hashlib.sha256(photo_id.encode()).hexdigest(),
        "scene_category": scene,
        **extra,
    }


class EvaluateRetrievalTests(unittest.TestCase):
    def evaluate(self, query=None, gallery=None, query_records=None, gallery_records=None, **kwargs):
        return evaluate_retrieval.evaluate_retrieval(
            [[1, 0]] if query is None else query,
            [[1, 0], [0, 1]] if gallery is None else gallery,
            [record("q", "a")] if query_records is None else query_records,
            [record("g1", "a"), record("g2", "b")] if gallery_records is None else gallery_records,
            **kwargs,
        )

    def test_scene_micro_macro_mrr_include_missing_gallery_scene(self):
        report = self.evaluate(
            query=[[1, 0], [0, 1], [1, 0], [0, 1], [1, 0]],
            query_records=[record(f"q{i}", scene) for i, scene in enumerate(["a", "a", "a", "b", "c"])],
        )
        raw = report["raw"]
        self.assertAlmostEqual(raw["scene_micro_recall_at_k"]["1"], 0.6)
        self.assertAlmostEqual(raw["scene_macro_recall_at_k"]["1"], 5 / 9)
        self.assertAlmostEqual(raw["scene_micro_recall_at_k"]["5"], 0.8)
        self.assertAlmostEqual(raw["scene_macro_recall_at_k"]["5"], 2 / 3)
        self.assertAlmostEqual(raw["mrr"], 0.7)
        self.assertEqual(raw["eligible_query_count"], 4)
        self.assertEqual(raw["effective_k"], {"1": 1, "5": 2})
        self.assertEqual(raw["per_scene"]["c"]["gallery_count"], 0)
        self.assertFalse(report["protocol"]["source_group_disjointness_verified"])
        self.assertNotIn("teacher_top5_overlap", raw)
        json.dumps(report, allow_nan=False)

    def test_normalizes_extreme_nonzero_vectors_without_mutation(self):
        query = np.array([[1e-300, 0]])
        gallery = np.array([[1e300, 0], [0, 2e300]])
        before = query.copy(), gallery.copy()
        report = self.evaluate(query=query, gallery=gallery)
        self.assertEqual(report["raw"]["scene_micro_recall_at_k"]["1"], 1)
        np.testing.assert_array_equal(query, before[0])
        np.testing.assert_array_equal(gallery, before[1])

    def test_raw_score_ties_resolve_by_gallery_row_order(self):
        records = [record("g1", "b"), record("g2", "a")]
        report = self.evaluate(gallery=[[1, 0], [1, 0]], gallery_records=records)
        self.assertEqual(report["raw"]["scene_micro_recall_at_k"]["1"], 0)
        self.assertEqual(report["raw"]["mrr"], 0.5)

    def test_teacher_overlap_uses_independent_teacher_dimensions(self):
        report = self.evaluate(
            query=[[1, 0]],
            gallery=[[6, 1], [5, 1], [4, 1], [3, 1], [2, 1], [1, 1]],
            gallery_records=[record(f"g{i}", "a") for i in range(6)],
            teacher_query_embeddings=[[1, 0, 0]],
            teacher_gallery_embeddings=[[i, 1, 0] for i in range(1, 7)],
        )
        self.assertAlmostEqual(report["raw"]["teacher_top5_overlap"], 0.8)
        self.assertEqual(report["raw"]["teacher_overlap_effective_k"], 5)

    def test_teacher_small_gallery_overlap_denominator_is_effective_k(self):
        report = self.evaluate(teacher_query_embeddings=[[0, 1]],
                               teacher_gallery_embeddings=[[1, 0], [0, 1]])
        self.assertEqual(report["raw"]["teacher_top5_overlap"], 1)
        self.assertEqual(report["raw"]["teacher_overlap_effective_k"], 2)

    def test_rejects_id_hash_overlap_and_duplicate_ids(self):
        for field in ("photo_id", "source_sha256"):
            query = record("q", "a")
            query[field] = record("g1", "a")[field]
            if field == "source_sha256":
                query[field] = query[field].upper()
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, field + " overlap"):
                self.evaluate(query_records=[query])
        with self.assertRaisesRegex(ValueError, "duplicate photo_id"):
            self.evaluate(gallery_records=[record("g", "a"), record("g", "b")])
        with self.assertRaisesRegex(ValueError, "duplicate photo_id"):
            self.evaluate(query=[[1, 0], [1, 0]], query_records=[record("q", "a"), record("q", "a")])

    def test_rejects_missing_and_unknown_record_fields(self):
        for field in ("photo_id", "source_sha256", "scene_category"):
            query = record("q", "a")
            del query[field]
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, field):
                self.evaluate(query_records=[query])
        with self.assertRaisesRegex(ValueError, "known label"):
            self.evaluate(query_records=[record("q", "unknown")])
        with self.assertRaisesRegex(ValueError, "records for"):
            self.evaluate(query_records=[])

    def test_rejects_invalid_matrices_and_k(self):
        for bad in ([[0, 0]], [[float("nan"), 1]], [[1, float("inf")]],
                    [], [1, 0], [[True, False]], [[1 + 1j, 0]], [["1", "0"]]):
            with self.subTest(matrix=bad), self.assertRaises(ValueError):
                self.evaluate(query=bad)
        with self.assertRaisesRegex(ValueError, "dimensions differ"):
            self.evaluate(query=[[1, 0, 0]])
        for bad_ks in ([], [0], [-1], [True], [1.5]):
            with self.subTest(ks=bad_ks), self.assertRaisesRegex(ValueError, "positive integers"):
                self.evaluate(ks=bad_ks)

    def test_rejects_partial_or_misaligned_teacher_arrays(self):
        with self.assertRaisesRegex(ValueError, "together"):
            self.evaluate(teacher_query_embeddings=[[1, 0]])
        with self.assertRaisesRegex(ValueError, "row counts"):
            self.evaluate(teacher_query_embeddings=[[1, 0], [1, 0]],
                          teacher_gallery_embeddings=[[1, 0], [0, 1]])
        with self.assertRaisesRegex(ValueError, "dimensions differ"):
            self.evaluate(teacher_query_embeddings=[[1, 0, 0]],
                          teacher_gallery_embeddings=[[1, 0], [0, 1]])
        with self.assertRaisesRegex(ValueError, "finite"):
            self.evaluate(teacher_query_embeddings=[[float("nan"), 0]],
                          teacher_gallery_embeddings=[[1, 0], [0, 1]])

    def test_android_self_voting_can_remove_only_correct_top5_candidate(self):
        # Scene a dominates the neighborhood and votes scene b out of top5.
        cosines = [0.99, 0.98, 0.97, 0.96, 0.95, 0.94, 0.93, 0.92]
        gallery = [[value, np.sqrt(1 - value ** 2)] for value in cosines]
        rows = [record(f"g{i}", "b" if i == 4 else "a", photo_type="landscape") for i in range(8)]
        report = self.evaluate(query_records=[record("q", "b")], gallery=gallery,
                               gallery_records=rows, compare_android_rerank=True)
        self.assertEqual(report["raw"]["scene_micro_recall_at_k"]["5"], 1)
        self.assertEqual(report["android_rerank"]["scene_micro_recall_at_k"]["5"], 0)
        self.assertEqual(report["raw"]["mrr"], 0.2)
        self.assertEqual(report["android_rerank"]["mrr"], 0)
        self.assertEqual(report["android_rerank"]["top1_score_at_least_one_count"], 1)
        self.assertFalse(report["android_rerank"]["comparison_config"]["lighting_bonus_included"])
        self.assertEqual(report["android_rerank"]["mrr_scope"], "returned_top5")

    def test_android_context_does_not_read_query_ground_truth_or_lighting(self):
        gallery = [record("g1", "a", photo_type="landscape", annotation={"lighting": {"quality": "soft"}}),
                   record("g2", "b", photo_type="portrait", annotation={"lighting": {"quality": "hard"}})]
        first = self.evaluate(gallery_records=gallery, compare_android_rerank=True)
        gallery[0]["annotation"]["lighting"]["quality"] = "hard"
        second = self.evaluate(gallery_records=gallery, compare_android_rerank=True)
        self.assertEqual(first, second)
        changed_label = self.evaluate(gallery_records=gallery, query_records=[record("q", "b")],
                                      compare_android_rerank=True)
        self.assertEqual(changed_label["android_rerank"]["top1_changed_count"],
                         first["android_rerank"]["top1_changed_count"])
        self.assertEqual(changed_label["android_rerank"]["scene_micro_recall_at_k"]["1"], 0)

    def test_cli_reads_pickle_free_npz_and_writes_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            np.savez(root / "vectors.npz", query_embeddings=[[1, 0]], gallery_embeddings=[[1, 0]])
            (root / "queries.jsonl").write_text(json.dumps(record("q", "a")) + "\n", encoding="utf-8")
            (root / "gallery.jsonl").write_text(json.dumps(record("g", "a")) + "\n", encoding="utf-8")
            output = root / "reports" / "eval.json"
            self.assertEqual(evaluate_retrieval.main([
                "--embeddings", str(root / "vectors.npz"),
                "--query-records", str(root / "queries.jsonl"),
                "--gallery-records", str(root / "gallery.jsonl"),
                "--output", str(output), "--compare-android-rerank",
            ]), 0)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["raw"]["scene_macro_recall_at_k"], {"1": 1.0, "5": 1.0})
            self.assertIn("android_rerank", report)


if __name__ == "__main__":
    unittest.main()
