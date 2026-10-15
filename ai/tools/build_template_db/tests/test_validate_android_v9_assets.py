import hashlib
import copy
import io
import json
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import validate_android_v9_assets


def write_json(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")


def write_assets(root):
    model = b"fixture model bytes"
    (root / "student_model.static_int8.onnx").write_bytes(model)
    digest = hashlib.sha256(model).hexdigest()
    config = {
        "runtime": {"model_file": "student_model.static_int8.onnx"},
        "model": {"input_size": 224, "embedding_dimension": 512,
                  "input_shape": ["batch", 3, 224, 224], "output_shape": ["batch", 512],
                  "input_dtype": "float32", "output_dtype": "float32",
                  "input_tensor": "image", "output_tensor": "embedding"},
        "postprocess": {"search_top_k": 8, "display_top_k": 5, "l2_normalize_embedding": True},
        "preprocess": {"color_order": "RGB", "layout": "NCHW"},
        "offline_quality": {"v9_static_int8_onnx_top5_same_scene_pct": 100.0,
                            "commercial_gate_top5_same_scene_pct": 95.0},
        "artifacts": {"model_sha256": digest},
    }
    manifest = {
        "static_int8_onnx": {"sha256": digest, "bytes": len(model)},
        "input": {"shape": ["batch", 3, 224, 224], "dtype": "float32", "name": "image", "color": "RGB"},
        "output": {"shape": ["batch", 512], "dtype": "float32", "name": "embedding"},
    }
    write_json(root / "android_runtime_config.json", config)
    write_json(root / "android_onnx_runtime_manifest.json", manifest)
    index = root / "template_index"
    index.mkdir()
    records = [{"template_id": str(i), "scene_category": "自然风景",
                "embedding": {"dimension": 512, "status": "ready",
                              "model_id": "models/student_model.static_int8.onnx"}}
               for i in range(6)]
    (index / "templates.metadata.jsonl").write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in records), encoding="utf-8")
    write_json(index / "templates.keys.json", {
        "count": 6, "dimension": 512, "metric": "cosine",
        "items": [{"key": i + 1, "template_id": str(i)} for i in range(6)]})
    values = [float(i == j) for i in range(6) for j in range(512)]
    (index / "templates.embeddings.f32").write_bytes(struct.pack(f"<{len(values)}f", *values))
    return config, manifest, records


def write_reviewed_assets(root):
    config, manifest, records = write_assets(root)
    (root / "student_model.static_int8.onnx").rename(root / "student_model.onnx")
    config["runtime"]["model_file"] = "student_model.onnx"
    config["offline_quality"] = {
        "purpose": "index_diagnostic_not_release_gate", "index_leave_one_out_top5_same_scene_pct": 100.0}
    manifest["deployment_onnx"] = manifest.pop("static_int8_onnx")
    for row in records:
        row["split"] = "train"
        row["source_sha256"] = hashlib.sha256(row["template_id"].encode()).hexdigest()
        row["reference_review"] = {"status": "candidate_clean", "source_sha256": row["source_sha256"],
                                   "provenance": {"method": "ai_visual_review"}}
        row["embedding"].update(model_id="student_model.onnx", model_sha256=config["artifacts"]["model_sha256"])
    raw = {"query_count": 53, "gallery_count": 579,
           "scene_macro_recall_at_k": {"1": 0.5, "5": 0.7},
           "scene_micro_recall_at_k": {"1": 0.6, "5": 0.8}, "teacher_top5_overlap": 0.3}
    evaluation = {"schema_version": 1,
                  "protocol": {"query_gallery_photo_id_disjoint": True,
                               "query_gallery_source_sha256_disjoint": True}, "raw": raw}
    report = {"schema_version": 1, "candidate_sha256": config["artifacts"]["model_sha256"],
              "baseline_sha256": "a" * 64, "selection_sha256": "b" * 64,
              "model_test_non_regression_passed": True, "test_used_for_selection": False, "fit_performed": False,
              "dataset_sha256": {"train": "c" * 64, "test": "d" * 64},
              "candidate": copy.deepcopy(evaluation), "baseline": copy.deepcopy(evaluation)}
    save_reviewed_assets(root, config, manifest, records, report)
    return config, manifest, records, report


def save_reviewed_assets(root, config, manifest, records, report):
    write_json(root / "model_evaluation.json", report)
    config["model_evaluation"] = {"file": "model_evaluation.json",
                                  "sha256": hashlib.sha256((root / "model_evaluation.json").read_bytes()).hexdigest()}
    write_json(root / "android_runtime_config.json", config)
    write_json(root / "android_onnx_runtime_manifest.json", manifest)
    (root / "template_index/templates.metadata.jsonl").write_text(
        "\n".join(json.dumps(row) for row in records), encoding="utf-8")


class AndroidAssetValidationTests(unittest.TestCase):
    def test_valid_bundle_recomputes_quality(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_assets(root)
            output = io.StringIO()
            with redirect_stdout(output):
                validate_android_v9_assets.validate(root)
            self.assertIn("recomputed leave-one-out", output.getvalue())

    def test_rejects_stale_quality_even_when_config_claims_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, _, records = write_assets(root)
            for i, record in enumerate(records):
                record["scene_category"] = f"unique_{i}"
            (root / "template_index/templates.metadata.jsonl").write_text(
                "\n".join(json.dumps(row) for row in records), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "recomputed.*below gate"):
                validate_android_v9_assets.validate(root)

    def test_rejects_non_normalized_vectors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_assets(root)
            path = root / "template_index/templates.embeddings.f32"
            path.write_bytes(struct.pack("<f", 2.0) + path.read_bytes()[4:])
            with self.assertRaisesRegex(ValueError, "not L2-normalized"):
                validate_android_v9_assets.validate(root)

    def test_rejects_metadata_row_permutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, _, records = write_assets(root)
            (root / "template_index/templates.metadata.jsonl").write_text(
                "\n".join(json.dumps(row) for row in reversed(records)), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match metadata"):
                validate_android_v9_assets.validate(root)

    def test_rejects_wrong_model_and_tensor_contract(self):
        for mismatch in ("model", "shape"):
            with self.subTest(mismatch=mismatch), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, manifest, records = write_assets(root)
                if mismatch == "model":
                    records[0]["embedding"]["model_id"] = "student_model.fp32.onnx"
                    (root / "template_index/templates.metadata.jsonl").write_text(
                        "\n".join(json.dumps(row) for row in records), encoding="utf-8")
                else:
                    manifest["output"]["shape"] = ["batch", 256]
                    write_json(root / "android_onnx_runtime_manifest.json", manifest)
                with self.assertRaisesRegex(ValueError, "differ"):
                    validate_android_v9_assets.validate(root)

    def test_rejects_non_finite_quality(self):
        for value in (float("inf"), float("nan"), -1.0, 101.0):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, _, _ = write_assets(root)
                config["offline_quality"]["v9_static_int8_onnx_top5_same_scene_pct"] = value
                write_json(root / "android_runtime_config.json", config)
                with self.assertRaisesRegex(ValueError, "quality percentage"):
                    validate_android_v9_assets.validate(root)

    def test_reviewed_profile_uses_frozen_holdout_gate_not_index_diagnostic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config, manifest, records, report = write_reviewed_assets(root)
            for i, row in enumerate(records):
                row["scene_category"] = f"unique_{i}"
            config["offline_quality"]["index_leave_one_out_top5_same_scene_pct"] = 0.0
            save_reviewed_assets(root, config, manifest, records, report)
            with redirect_stdout(io.StringIO()):
                validate_android_v9_assets.validate(root)

    def test_reviewed_profile_requires_hash_bound_evaluation_sidecar(self):
        for problem in ("missing_config", "wrong_filename", "missing_file", "invalid_hash", "changed_file"):
            with self.subTest(problem=problem), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, _, _, _ = write_reviewed_assets(root)
                if problem == "missing_config":
                    config.pop("model_evaluation")
                elif problem == "wrong_filename":
                    config["model_evaluation"]["file"] = "../model_evaluation.json"
                elif problem == "missing_file":
                    (root / "model_evaluation.json").unlink()
                elif problem == "invalid_hash":
                    config["model_evaluation"]["sha256"] = "not-a-hash"
                else:
                    (root / "model_evaluation.json").write_text("{}", encoding="utf-8")
                write_json(root / "android_runtime_config.json", config)
                with self.assertRaisesRegex(ValueError, "model_evaluation"):
                    validate_android_v9_assets.validate(root)

    def test_new_deployment_cannot_bypass_holdout_by_claiming_legacy_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config, _, _, _ = write_reviewed_assets(root)
            config["offline_quality"].pop("purpose")
            config.pop("model_evaluation")
            write_json(root / "android_runtime_config.json", config)
            with self.assertRaisesRegex(ValueError, "requires the reviewed holdout profile"):
                validate_android_v9_assets.validate(root)

    def test_frozen_report_must_match_packaged_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config, manifest, records, report = write_reviewed_assets(root)
            report["candidate_sha256"] = "e" * 64
            save_reviewed_assets(root, config, manifest, records, report)
            with self.assertRaisesRegex(ValueError, "candidate SHA-256"):
                validate_android_v9_assets.validate(root)

    def test_frozen_report_rejects_fitting_selection_or_failed_gate_claims(self):
        for field in ("fit_performed", "test_used_for_selection", "model_test_non_regression_passed"):
            for value in (None, 0, 1, "false", field != "model_test_non_regression_passed"):
                with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    config, manifest, records, report = write_reviewed_assets(root)
                    report[field] = value
                    save_reviewed_assets(root, config, manifest, records, report)
                    with self.assertRaisesRegex(ValueError, "model_evaluation"):
                        validate_android_v9_assets.validate(root)

    def test_true_gate_flag_cannot_hide_any_regressing_metric(self):
        for field, key in (("scene_macro_recall_at_k", "1"), ("scene_macro_recall_at_k", "5"),
                           ("scene_micro_recall_at_k", "5"), ("teacher_top5_overlap", None)):
            with self.subTest(field=field, key=key), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, manifest, records, report = write_reviewed_assets(root)
                raw = report["candidate"]["raw"]
                if key is None:
                    raw[field] -= 0.01
                else:
                    raw[field][key] -= 0.01
                save_reviewed_assets(root, config, manifest, records, report)
                with self.assertRaisesRegex(ValueError, "recomputed non-regression gate failed"):
                    validate_android_v9_assets.validate(root)

    def test_frozen_report_rejects_invalid_metrics_on_both_models(self):
        for model in ("candidate", "baseline"):
            for value in (None, True, "0.5", float("nan"), float("inf"), -0.1, 1.1):
                with self.subTest(model=model, value=value), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    config, manifest, records, report = write_reviewed_assets(root)
                    report[model]["raw"]["teacher_top5_overlap"] = value
                    save_reviewed_assets(root, config, manifest, records, report)
                    with self.assertRaisesRegex(ValueError, "invalid model_evaluation.*teacher_top5"):
                        validate_android_v9_assets.validate(root)

    def test_frozen_report_requires_actual_raw_population_fields(self):
        for model in ("candidate", "baseline"):
            for field in ("query_count", "gallery_count"):
                for value in (None, 0, -1, True, 2.5):
                    with self.subTest(model=model, field=field, value=value), tempfile.TemporaryDirectory() as directory:
                        root = Path(directory)
                        config, manifest, records, report = write_reviewed_assets(root)
                        report[model]["query_count"] = 53  # Wrong location cannot substitute for raw.query_count.
                        report[model]["raw"][field] = value
                        save_reviewed_assets(root, config, manifest, records, report)
                        with self.assertRaisesRegex(ValueError, "positive raw"):
                            validate_android_v9_assets.validate(root)

    def test_frozen_report_requires_matching_populations_and_disjoint_queries(self):
        for problem in ("query_count", "gallery_count", "overlap"):
            with self.subTest(problem=problem), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, manifest, records, report = write_reviewed_assets(root)
                if problem == "overlap":
                    report["candidate"]["protocol"]["query_gallery_source_sha256_disjoint"] = False
                else:
                    report["candidate"]["raw"][problem] += 1
                save_reviewed_assets(root, config, manifest, records, report)
                with self.assertRaisesRegex(ValueError, "populations differ|disjoint query/gallery"):
                    validate_android_v9_assets.validate(root)

    def test_frozen_report_requires_train_and_test_hashes(self):
        for value in ({}, {"train": "a" * 64}, {"train": "a" * 64, "test": "broken"},
                      {"train": "a" * 64, "test": "a" * 64}, None):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, manifest, records, report = write_reviewed_assets(root)
                report["dataset_sha256"] = value
                save_reviewed_assets(root, config, manifest, records, report)
                with self.assertRaisesRegex(ValueError, "dataset"):
                    validate_android_v9_assets.validate(root)

    def test_reviewed_profile_requires_valid_matching_review_and_source_hashes(self):
        for problem in ("missing_both_hashes", "invalid_both_hashes", "mismatched_review_hash",
                        "missing_review", "malformed_provenance", "wrong_review_method", "not_train"):
            with self.subTest(problem=problem), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, manifest, records, report = write_reviewed_assets(root)
                row = records[0]
                if problem == "missing_both_hashes":
                    row.pop("source_sha256")
                    row["reference_review"].pop("source_sha256")
                elif problem == "invalid_both_hashes":
                    row["source_sha256"] = row["reference_review"]["source_sha256"] = "same-invalid-hash"
                elif problem == "mismatched_review_hash":
                    row["reference_review"]["source_sha256"] = "a" * 64
                elif problem == "missing_review":
                    row["reference_review"] = None
                elif problem == "malformed_provenance":
                    row["reference_review"]["provenance"] = None
                elif problem == "wrong_review_method":
                    row["reference_review"]["provenance"]["method"] = "scene_rule"
                else:
                    row["split"] = "test"
                save_reviewed_assets(root, config, manifest, records, report)
                with self.assertRaisesRegex(ValueError, "unreviewed or non-training"):
                    validate_android_v9_assets.validate(root)

    def test_reviewed_profile_requires_generating_model_hash_on_every_row(self):
        for value in (None, "broken", "a" * 64):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config, manifest, records, report = write_reviewed_assets(root)
                if value is None:
                    records[-1]["embedding"].pop("model_sha256")
                else:
                    records[-1]["embedding"]["model_sha256"] = value
                save_reviewed_assets(root, config, manifest, records, report)
                with self.assertRaisesRegex(ValueError, "index embedding model SHA-256"):
                    validate_android_v9_assets.validate(root)


if __name__ == "__main__":
    unittest.main()
