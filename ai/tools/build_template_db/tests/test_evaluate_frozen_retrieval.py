from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np

import evaluate_frozen_retrieval as frozen


def report(macro1=.5, macro5=.7, micro5=.8, teacher=.2):
    return {"raw": {"scene_macro_recall_at_k": {"1": macro1, "5": macro5},
                    "scene_micro_recall_at_k": {"5": micro5}, "teacher_top5_overlap": teacher}}


def row(name, split):
    return {"photo_id": name, "source_sha256": hashlib.sha256(name.encode()).hexdigest(),
            "source_group_id": f"group:{name}", "source_path": f"images/{name}.jpg", "split": split}


class GateTests(unittest.TestCase):
    def test_all_four_metrics_must_not_regress(self):
        base = report()
        for candidate in (report(macro1=.49), report(macro5=.69), report(micro5=.79), report(teacher=.19)):
            with self.subTest(candidate=candidate):
                self.assertFalse(frozen.gate(candidate, base))
        self.assertTrue(frozen.gate(base, base))
        self.assertTrue(frozen.gate(report(teacher=.3), base))

    def test_invalid_or_nonfinite_metric_cannot_pass(self):
        for value in (float("nan"), float("inf"), -.1, 1.1):
            self.assertFalse(frozen.gate(report(teacher=value), report()))
            self.assertFalse(frozen.gate(report(), report(teacher=value)))


class IsolationTests(unittest.TestCase):
    def records(self):
        return {"train": [row("train-a", "train"), row("train-b", "train")],
                "test": [row("test-a", "test")]}

    def test_disjointness_checks_hash_group_and_resolved_source_path(self):
        for field in ("photo_id", "source_sha256", "source_group_id", "source_path"):
            records = self.records()
            records["test"][0][field] = records["train"][0][field]
            if field == "source_sha256":
                records["test"][0][field] = records["test"][0][field].upper()
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "overlap"):
                frozen.validate_records(records, Path("."))

    def test_wrong_split_duplicate_identity_or_hash_are_rejected(self):
        for field in ("photo_id", "source_sha256"):
            records = self.records()
            records["train"][1][field] = records["train"][0][field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                frozen.validate_records(records, Path("."))
        records = self.records()
        records["test"][0]["split"] = "val"
        with self.assertRaises(ValueError):
            frozen.validate_records(records, Path("."))

    def test_missing_hash_or_invalid_group_rejected(self):
        for field, value in (("source_sha256", None), ("source_sha256", ""), ("source_group_id", 123)):
            records = self.records()
            records["test"][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                frozen.validate_records(records, Path("."))

    def test_unknown_groups_are_counted_without_claiming_independence(self):
        records = self.records()
        records["train"][0]["source_group_id"] = "unknown"
        records["test"][0]["source_group_id"] = "unknown"
        audit = frozen.validate_records(records, Path("."))
        self.assertEqual(audit["unknown_source_group_rows"], {"train": 1, "test": 1})
        self.assertFalse(audit["unknown_groups_prove_independence"])
        self.assertFalse(audit["near_duplicate_independence_verified"])


class FrozenEvaluationTests(unittest.TestCase):
    def fixture(self, root):
        dataset = root / "synthetic-dataset"
        dataset.mkdir()
        for split, records in (("train", [row("a", "train"), row("b", "train")]),
                               ("test", [row("c", "test")])):
            (dataset / f"{split}.jsonl").write_text("\n".join(map(json.dumps, records)), encoding="utf-8")
        baseline, candidate = root / "baseline.onnx", root / "candidate.onnx"
        baseline.write_bytes(b"mock baseline model")
        candidate.write_bytes(b"mock candidate model")
        selection = root / "selection.json"
        selection.write_text(json.dumps({"baseline_sha256": frozen.sha256_file(baseline),
                                         "candidate_sha256": frozen.sha256_file(candidate),
                                         "selection_split": "val", "test_used_for_selection": False}), encoding="utf-8")
        return argparse.Namespace(dataset_dir=dataset, selection=selection,
                                  baseline_model=baseline, candidate_model=candidate, output_dir=root / "output")

    def test_both_frozen_model_hashes_checked_before_any_manifest_read(self):
        for changed in ("baseline_model", "candidate_model"):
            with tempfile.TemporaryDirectory(dir=".", prefix="frozen-test-") as temporary:
                args = self.fixture(Path(temporary))
                getattr(args, changed).write_bytes(b"changed after selection")
                with mock.patch.object(frozen, "load_jsonl") as reader, \
                     self.assertRaisesRegex(ValueError, "frozen selection"):
                    frozen.evaluate(args)
                reader.assert_not_called()
                self.assertFalse(args.output_dir.exists())

    def test_test_based_selection_rejected_before_manifest_read(self):
        with tempfile.TemporaryDirectory(dir=".", prefix="frozen-test-") as temporary:
            args = self.fixture(Path(temporary))
            selection = json.loads(args.selection.read_text(encoding="utf-8"))
            selection["test_used_for_selection"] = True
            args.selection.write_text(json.dumps(selection), encoding="utf-8")
            with mock.patch.object(frozen, "load_jsonl") as reader, self.assertRaisesRegex(ValueError, "precede test"):
                frozen.evaluate(args)
            reader.assert_not_called()

    def test_existing_results_rejected_without_reading_manifests(self):
        with tempfile.TemporaryDirectory(dir=".", prefix="frozen-test-") as temporary:
            args = self.fixture(Path(temporary))
            args.output_dir.mkdir()
            prior = args.output_dir / "summary.json"
            prior.write_text("original result", encoding="utf-8")
            with mock.patch.object(frozen, "load_jsonl") as reader, self.assertRaisesRegex(ValueError, "preserve"):
                frozen.evaluate(args)
            reader.assert_not_called()
            self.assertEqual(prior.read_text(encoding="utf-8"), "original result")

    def test_failed_attempt_is_preserved_and_cannot_silently_retry(self):
        with tempfile.TemporaryDirectory(dir=".", prefix="frozen-test-") as temporary:
            args = self.fixture(Path(temporary))
            with mock.patch.object(frozen, "load_images", side_effect=ValueError("synthetic image error")), \
                 self.assertRaisesRegex(ValueError, "synthetic image"):
                frozen.evaluate(args)
            self.assertTrue((args.output_dir / "frozen-selection.json").exists())
            with mock.patch.object(frozen, "load_jsonl") as reader, self.assertRaisesRegex(ValueError, "preserve"):
                frozen.evaluate(args)
            reader.assert_not_called()

    def test_success_uses_each_models_own_gallery_and_queries(self):
        with tempfile.TemporaryDirectory(dir=".", prefix="frozen-test-") as temporary:
            args = self.fixture(Path(temporary))
            vectors = [np.full((count, 2), index, dtype=np.float32)
                       for index, count in enumerate((2, 1, 2, 1), start=1)]
            baseline, candidate = report(), report(teacher=.3)
            events = []
            real_hash, real_read = frozen.sha256_file, frozen.load_jsonl

            def hashed(path):
                events.append(("hash", path.name))
                return real_hash(path)

            def read(path):
                events.append(("read", path.name))
                return real_read(path)

            with mock.patch.object(frozen, "sha256_file", side_effect=hashed), \
                 mock.patch.object(frozen, "load_jsonl", side_effect=read), \
                 mock.patch.object(frozen, "load_images", side_effect=lambda items, size: np.zeros((len(items), 3, 2, 2))), \
                 mock.patch.object(frozen, "load_teachers", side_effect=lambda items: np.ones((len(items), 2))), \
                 mock.patch.object(frozen.ort, "InferenceSession"), \
                 mock.patch.object(frozen, "encode", side_effect=[(array, 0.) for array in vectors]), \
                 mock.patch.object(frozen, "evaluate_retrieval", side_effect=[baseline, candidate]) as evaluator:
                result = frozen.evaluate(args)
            self.assertEqual(events[:4], [("hash", "baseline.onnx"), ("hash", "candidate.onnx"),
                                          ("read", "train.jsonl"), ("read", "test.jsonl")])
            for offset, call in zip((0, 2), evaluator.call_args_list):
                np.testing.assert_array_equal(call.args[0], vectors[offset + 1])
                np.testing.assert_array_equal(call.args[1], vectors[offset])
            self.assertTrue(result["model_test_non_regression_passed"])
            self.assertFalse(result["fit_performed"])
            self.assertFalse(result["test_used_for_selection"])
            self.assertTrue(result["dataset_isolation"]["known_source_group_disjoint"])
            self.assertEqual(json.loads((args.output_dir / "summary.json").read_text(encoding="utf-8")), result)

    def test_cli_returns_nonzero_when_frozen_candidate_regresses(self):
        for passed, expected in ((True, 0), (False, 2)):
            result = {"model_test_non_regression_passed": passed, "candidate": report(), "baseline": report()}
            with mock.patch("sys.argv", ["evaluate", "--dataset-dir", "dataset", "--selection", "selection.json",
                                         "--baseline-model", "baseline.onnx", "--candidate-model", "candidate.onnx",
                                         "--output-dir", "output"]), \
                 mock.patch.object(frozen, "evaluate", return_value=result), mock.patch("builtins.print"):
                self.assertEqual(frozen.main(), expected)


if __name__ == "__main__":
    unittest.main()
