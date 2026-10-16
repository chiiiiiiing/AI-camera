import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import numpy as np

import quantize_onnx_static


class QuantizeOnnxStaticTests(unittest.TestCase):
    @staticmethod
    def row(photo_id, scene="portrait", split="train", **extra):
        return {"photo_id": photo_id, "source_path": f"images/{photo_id}.jpg",
                "photo_type": scene, "scene_category": scene, "split": split, **extra}

    @staticmethod
    def session(outputs=None, output_dim=3, image_size=8, batch="batch"):
        session = mock.Mock()
        session.get_inputs.return_value = [SimpleNamespace(
            name="image", shape=[batch, 3, image_size, image_size], type="tensor(float)")]
        session.get_outputs.return_value = [SimpleNamespace(
            name="embedding", shape=[batch, output_dim], type="tensor(float)")]
        if outputs is not None:
            session.run.side_effect = [[np.asarray(value, dtype=np.float32)] for value in outputs]
        return session

    def test_sha256_file_and_size_are_recorded_from_real_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.onnx"
            path.write_bytes(b"android-runtime")

            self.assertEqual(quantize_onnx_static.file_size(path), len(b"android-runtime"))
            self.assertEqual(
                quantize_onnx_static.sha256_file(path),
                "1613be868c5ef3251d8c78d44bf486af6a81b0389a83faaa7a905b9145c2713f",
            )

    def test_load_jsonl_rejects_non_object_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.jsonl"
            path.write_text(json.dumps(["not", "an", "object"]) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "line is not an object"):
                quantize_onnx_static.load_jsonl(path)

    def test_unknown_split_requires_explicit_source_declaration(self):
        rows = [self.row("a", split=None)]
        with self.assertRaisesRegex(ValueError, "expected split=train"):
            quantize_onnx_static.require_split(rows, "train")
        self.assertEqual(quantize_onnx_static.require_split(rows, "train", "train"), rows)

    def test_declared_source_cannot_override_val_or_test_rows(self):
        for split in ("val", "test"):
            with self.subTest(split=split), self.assertRaisesRegex(ValueError, "expected split=train"):
                quantize_onnx_static.require_split([self.row("a", split=split)], "train", "train")
        with self.assertRaisesRegex(ValueError, "expected split=val"):
            quantize_onnx_static.require_split([self.row("a", split="test")], "val", "val")

    def test_rejects_duplicate_or_missing_calibration_identity(self):
        for rows in ([self.row("a"), self.row("a")], [self.row("")]):
            with self.assertRaisesRegex(ValueError, "photo_id"):
                quantize_onnx_static.require_split(rows, "train")

    def test_stratified_sampling_covers_rare_categories_and_is_order_independent(self):
        rows = ([self.row(f"food_{i}", "food") for i in range(100)] +
                [self.row(f"portrait_{i}", "portrait") for i in range(10)] +
                [self.row("plant", "plant"), self.row("landscape", "landscape")])
        selected = quantize_onnx_static.stratified_sample(rows, 12, 42)
        self.assertEqual(len(selected), 12)
        self.assertEqual(len({row["photo_id"] for row in selected}), 12)
        self.assertEqual({row["scene_category"] for row in selected}, {"food", "portrait", "plant", "landscape"})
        self.assertEqual(selected, quantize_onnx_static.stratified_sample(list(reversed(rows)), 12, 42))
        self.assertNotEqual(selected, quantize_onnx_static.stratified_sample(rows, 12, 43))

    def test_sampling_rejects_insufficient_coverage_budget(self):
        with self.assertRaisesRegex(ValueError, "cannot cover"):
            quantize_onnx_static.stratified_sample([self.row("a", "food"), self.row("b", "plant")], 1, 0)

    def test_validation_overlap_rejected_by_identity_path_or_hash(self):
        root = Path.cwd()
        train = [self.row("train", template_id="template-a", source_sha256="ABCD", source_group_id="post-a")]
        for extra in ({"photo_id": "train"}, {"template_id": "template-a"},
                      {"source_path": "images/train.jpg"}, {"source_sha256": "abcd"},
                      {"source_group_id": "post-a"}):
            validation = self.row("val", split="val") | extra
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, "train/val overlap"):
                quantize_onnx_static.require_disjoint(train, [validation], root, root)
        quantize_onnx_static.require_disjoint(train, [self.row("val", split="val")], root, root)

    def compare(self, fp32, quantized, **kwargs):
        original = self.session(fp32)
        compressed = self.session(quantized)
        rows = [self.row(f"v{i}", scene="food" if i == 0 else "landscape", split="val")
                for i in range(len(fp32))]
        with mock.patch.object(quantize_onnx_static.ort, "InferenceSession", side_effect=[original, compressed]), \
             mock.patch.object(quantize_onnx_static, "prepare_image", return_value=np.zeros((1, 3, 8, 8))):
            result = quantize_onnx_static.compare_outputs(Path("fp.onnx"), Path("q.onnx"), rows, 8, **kwargs)
        self.assertEqual(original.run.call_count, len(rows))
        self.assertEqual(compressed.run.call_count, len(rows))
        return result

    def test_all_validation_images_pass_and_report_per_class_metrics(self):
        outputs = [[[1, 0, 0]], [[0, 1, 0]], [[0, 0, 1]]]
        result = self.compare(outputs, outputs)
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["validation_items"], 3)
        self.assertEqual(result["metrics"]["cosine_mean"], 1.0)
        self.assertEqual(result["metrics"]["cosine_p05"], 1.0)
        self.assertEqual(len(result["per_stratum"]), 2)
        self.assertEqual(result["retrieval_quality"], "not_evaluated")
        self.assertEqual(result["scope"], "paired_embedding_numerical_consistency")

    def test_bad_later_sample_rejects_even_if_first_image_passes(self):
        result = self.compare([[[1, 0, 0]], [[0, 1, 0]]], [[[1, 0, 0]], [[1, 0, 0]]])
        self.assertEqual(result["status"], "rejected")
        self.assertEqual(result["failures"], [{"photo_id": "v1", "reason": "cosine below minimum"}])

    def test_mean_threshold_can_fail_while_individual_threshold_passes(self):
        unit = [0.96, float(np.sqrt(1 - 0.96 ** 2)), 0]
        result = self.compare([[[1, 0, 0]]] * 2, [[unit]] * 2)
        self.assertEqual(result["status"], "rejected")
        self.assertEqual(result["failures"], [])
        self.assertFalse(result["mean_threshold_passed"])

    def test_invalid_embeddings_never_pass_consistency_gate(self):
        for bad, reason in (([[float("nan"), 0, 0]], "non-finite"),
                            ([[0, 0, 0]], "zero"), ([[1, 0]], "shape"),
                            ([[2, 0, 0]], "unit-normalized")):
            with self.subTest(reason=reason):
                result = self.compare([[[1, 0, 0]]] * 2, [[[1, 0, 0]], bad])
                self.assertEqual(result["status"], "rejected")
                self.assertIn(reason, result["failures"][0]["reason"])
                json.dumps(result, allow_nan=False)

    def test_zero_or_one_image_cannot_mark_verification_passed(self):
        for rows in ([], [self.row("v", split="val")]):
            with mock.patch.object(quantize_onnx_static.ort, "InferenceSession") as session:
                result = quantize_onnx_static.compare_outputs(Path("fp"), Path("int8"), rows, 8)
            self.assertEqual(result["status"], "not_validated")
            session.assert_not_called()

    def test_model_contract_reads_real_embedding_dimension_and_input_size(self):
        model = self.session(output_dim=192, image_size=160)
        _, output_info, size = quantize_onnx_static.model_contract(model, None)
        self.assertEqual(size, 160)
        self.assertEqual(output_info.shape, ["batch", 192])
        with self.assertRaisesRegex(ValueError, "does not match"):
            quantize_onnx_static.model_contract(model, 224)

    def test_invalid_thresholds_rejected_before_inference(self):
        for settings in ({"min_cosine": float("nan")}, {"min_mean_cosine": 0.9},
                         {"norm_tolerance": -1.0}):
            with self.subTest(settings=settings), self.assertRaises(ValueError):
                quantize_onnx_static.compare_outputs(Path("fp"), Path("int8"), [], 8, **settings)

    def test_quantization_manifest_records_train_only_selection_and_dynamic_dimension(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata, validation = root / "train.jsonl", root / "val.jsonl"
            train_rows = [self.row("a", "food"), self.row("b", "landscape"), self.row("c", "food")]
            val_rows = [self.row("v1", "food", "val"), self.row("v2", "landscape", "val")]
            metadata.write_text("\n".join(map(json.dumps, train_rows)), encoding="utf-8")
            validation.write_text("\n".join(map(json.dumps, val_rows)), encoding="utf-8")
            output_model = root / "quantized.onnx"
            def quantize(**kwargs):
                output_model.write_bytes(b"quantized-model")
                self.assertEqual(kwargs["calibration_data_reader"].batch_size, 1)
                self.assertEqual(kwargs["op_types_to_quantize"], ["Conv", "Gemm", "MatMul"])
            def compare(*args, **kwargs):
                return {"status": "passed" if args[2] else "not_validated", "retrieval_quality": "not_evaluated"}
            with mock.patch.object(quantize_onnx_static.ort, "InferenceSession", return_value=self.session(output_dim=192, batch=1)), \
                 mock.patch.object(quantize_onnx_static.onnx.checker, "check_model"), \
                 mock.patch.object(quantize_onnx_static, "quantize_static", side_effect=quantize), \
                 mock.patch.object(quantize_onnx_static, "compare_outputs", side_effect=compare) as compared:
                report = quantize_onnx_static.quantize_android_onnx(
                    root / "fp.onnx", metadata, output_model, root / "reports" / "runtime.json",
                    calibration_items=2, validation_manifest=validation, seed=42,
                )
            self.assertEqual(report["output"]["shape"], [1, 192])
            self.assertEqual(report["input"]["shape"], [1, 3, 8, 8])
            self.assertEqual(report["status"], "consistency_passed")
            self.assertEqual(report["static_int8_onnx"]["op_types_quantized"], ["Conv", "Gemm", "MatMul"])
            self.assertEqual(report["static_int8_onnx"]["calibration_distribution"]["scene_category"], {"food": 1, "landscape": 1})
            self.assertTrue(set(report["static_int8_onnx"]["calibration_photo_ids"]) <= {"a", "b", "c"})
            self.assertEqual(compared.call_args.args[2], val_rows)
            self.assertEqual(json.loads((root / "reports" / "runtime.json").read_text("utf-8")), report)

    def test_cli_rejected_or_unvalidated_artifact_returns_failure(self):
        for status, expected in (("passed", 0), ("rejected", 2), ("not_validated", 2)):
            with self.subTest(status=status), \
                 mock.patch("sys.argv", ["quantize", "fp.onnx", "--metadata", "train.jsonl",
                                         "--output-model", "int8.onnx", "--manifest-output", "report.json"]), \
                 mock.patch.object(quantize_onnx_static, "quantize_android_onnx", return_value={"verification": {"status": status}}), \
                 mock.patch("builtins.print"):
                self.assertEqual(quantize_onnx_static.main(), expected)

    def test_explicit_conv_only_cli_preserves_mixed_precision_request(self):
        with mock.patch("sys.argv", ["quantize", "fp.onnx", "--metadata", "train.jsonl",
                                    "--output-model", "mixed.onnx", "--manifest-output", "report.json",
                                    "--quantize-ops", "Conv"]), \
             mock.patch.object(quantize_onnx_static, "quantize_android_onnx",
                               return_value={"verification": {"status": "passed"}}) as quantized, \
             mock.patch("builtins.print"):
            self.assertEqual(quantize_onnx_static.main(), 0)
        self.assertEqual(quantized.call_args.kwargs["quantize_ops"], ["Conv"])

    def test_invalid_operator_subsets_rejected_before_reading_data(self):
        for operators in ([], ["Conv", "Conv"], ["Add"]):
            with self.subTest(operators=operators), \
                 mock.patch.object(quantize_onnx_static, "load_jsonl") as reader, \
                 self.assertRaisesRegex(ValueError, "quantize_ops"):
                quantize_onnx_static.quantize_android_onnx(
                    Path("fp.onnx"), Path("train.jsonl"), Path("mixed.onnx"), Path("report.json"),
                    quantize_ops=operators,
                )
            reader.assert_not_called()


if __name__ == "__main__":
    unittest.main()
