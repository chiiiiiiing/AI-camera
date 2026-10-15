#!/usr/bin/env python3
"""Fail-fast integrity and offline-quality gate for the packaged Android V9 assets."""

from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import math
import re
from pathlib import Path

from build_template_db import validate_template_index
from validate_core_recommendation_assets import validate as validate_core_recommendation


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def valid_sha256(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value) is not None


def validate_model_evaluation(asset_root: Path, config: dict, actual_model_hash: str) -> None:
    """Bind the frozen test result to the shipped model and recompute its gate.

    The sidecar records measurements from a separate evaluator. This validates
    their integrity and comparison, not the unavailable private test photographs.
    """
    artifact = config.get("model_evaluation")
    require(isinstance(artifact, dict) and artifact.get("file") == "model_evaluation.json",
            "reviewed profile requires model_evaluation.json")
    expected_hash = artifact.get("sha256")
    require(valid_sha256(expected_hash), "invalid model_evaluation SHA-256")
    path = asset_root / "model_evaluation.json"
    require(path.is_file(), "missing model_evaluation.json")
    require(sha256(path) == expected_hash.lower(), "model_evaluation SHA-256 mismatch")
    report = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(report, dict) and type(report.get("schema_version")) is int
            and report["schema_version"] == 1,
            "invalid model_evaluation schema")
    require(report.get("candidate_sha256") == actual_model_hash,
            "model_evaluation candidate SHA-256 differs from packaged model")
    for field in ("baseline_sha256", "selection_sha256"):
        require(valid_sha256(report.get(field)), f"invalid model_evaluation {field}")
    require(report.get("test_used_for_selection") is False and report.get("fit_performed") is False,
            "model_evaluation must use a frozen candidate without fitting or test selection")
    require(report.get("model_test_non_regression_passed") is True,
            "model_evaluation reports a failed non-regression gate")
    datasets = report.get("dataset_sha256")
    require(isinstance(datasets, dict) and all(valid_sha256(datasets.get(split)) for split in ("train", "test")),
            "model_evaluation requires valid train/test dataset SHA-256")
    require(datasets["train"].lower() != datasets["test"].lower(),
            "model_evaluation train and test datasets must differ")

    measurements = {}
    for name in ("candidate", "baseline"):
        evaluation = report.get(name)
        require(isinstance(evaluation, dict) and type(evaluation.get("schema_version")) is int
                and evaluation["schema_version"] == 1,
                f"invalid model_evaluation {name} report")
        protocol = evaluation.get("protocol")
        require(isinstance(protocol, dict) and protocol.get("query_gallery_photo_id_disjoint") is True
                and protocol.get("query_gallery_source_sha256_disjoint") is True,
                f"model_evaluation {name} requires disjoint query/gallery identities and hashes")
        raw = evaluation.get("raw")
        require(isinstance(raw, dict), f"missing model_evaluation {name} raw metrics")
        for field in ("query_count", "gallery_count"):
            require(type(raw.get(field)) is int and raw[field] > 0,
                    f"model_evaluation {name} requires positive raw.{field}")
        metrics = []
        for field, key in (("scene_macro_recall_at_k", "1"), ("scene_macro_recall_at_k", "5"),
                           ("scene_micro_recall_at_k", "5"), ("teacher_top5_overlap", None)):
            container = raw if key is None else raw.get(field, {})
            value = container.get(field if key is None else key) if isinstance(container, dict) else None
            require(type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1,
                    f"invalid model_evaluation {name} raw.{field}{'.' + key if key else ''}")
            metrics.append(value)
        measurements[name] = (raw["query_count"], raw["gallery_count"], metrics)
    candidate, baseline = measurements["candidate"], measurements["baseline"]
    require(candidate[:2] == baseline[:2], "model_evaluation candidate/baseline populations differ")
    require(all(after + 1e-12 >= before for after, before in zip(candidate[2], baseline[2])),
            "model_evaluation recomputed non-regression gate failed")


def validate(asset_root: Path) -> None:
    config = json.loads((asset_root / "android_runtime_config.json").read_text(encoding="utf-8"))
    manifest = json.loads((asset_root / "android_onnx_runtime_manifest.json").read_text(encoding="utf-8"))
    model = config["model"]
    quality = config["offline_quality"]
    artifacts = config["artifacts"]

    dimension = int(model["embedding_dimension"])
    require(model["input_size"] == 224, "V9 input_size must remain 224")
    require(dimension == 512, "V9 embedding dimension must remain 512")
    require(config["postprocess"]["search_top_k"] >= 5, "search_top_k must cover display top5")
    require(config["postprocess"]["search_top_k"] >= config["postprocess"]["display_top_k"],
            "search_top_k must cover display_top_k")
    require(config["postprocess"]["l2_normalize_embedding"] is True, "L2 normalization must be enabled")
    model_filename = config["runtime"]["model_file"]
    require(model_filename in {"student_model.static_int8.onnx", "student_model.onnx"}, "unexpected runtime model file")
    for side in ("input", "output"):
        for field in ("shape", "dtype"):
            require(model[f"{side}_{field}"] == manifest[side][field],
                    f"config and manifest {side} {field} differ")
        require(model[f"{side}_tensor"] == manifest[side]["name"],
                f"config and manifest {side} tensor names differ")
    require(model["input_shape"] == ["batch", 3, 224, 224], "unexpected V9 input shape")
    require(model["output_shape"] == ["batch", dimension], "unexpected V9 output shape")
    require(config["preprocess"]["color_order"] == manifest["input"]["color"] == "RGB", "RGB input required")
    require(config["preprocess"]["layout"] == "NCHW", "NCHW input required")

    deployment = manifest.get("deployment_onnx", manifest.get("static_int8_onnx"))
    require(isinstance(deployment, dict), "missing deployment model manifest")
    expected_hash = deployment["sha256"]
    require(expected_hash == artifacts["model_sha256"], "runtime config and manifest model hashes differ")
    actual_hash = sha256(asset_root / model_filename)
    require(actual_hash == expected_hash, "packaged ONNX model SHA-256 mismatch")
    require((asset_root / model_filename).stat().st_size == deployment["bytes"],
            "packaged ONNX model size differs from manifest")

    quality_score = float(quality.get("index_leave_one_out_top5_same_scene_pct",
                                    quality.get("v9_static_int8_onnx_top5_same_scene_pct")))
    diagnostic_only = quality.get("purpose") == "index_diagnostic_not_release_gate"
    if model_filename == "student_model.onnx" or "deployment_onnx" in manifest:
        require(diagnostic_only, "deployment model requires the reviewed holdout profile")
    if diagnostic_only:
        validate_model_evaluation(asset_root, config, actual_hash)
    quality_gate = float(quality.get("commercial_gate_top5_same_scene_pct", 95.0))
    require(math.isfinite(quality_score) and 0 <= quality_score <= 100, "invalid reported quality percentage")
    require(math.isfinite(quality_gate) and 0 <= quality_gate <= 100, "invalid quality gate percentage")
    if not diagnostic_only:
        require(quality_score >= quality_gate, f"top5 same-scene quality {quality_score} is below gate {quality_gate}")

    index_dir = asset_root / "template_index"
    errors, summary = validate_template_index.validate_index(index_dir, check_self_similarity=False)
    require(not errors, "; ".join(errors))
    require(summary["dimension"] == dimension, "index key dimension mismatch")
    embedding_count = summary["count"]
    require(embedding_count > 5, "top5 quality gate requires at least six templates")
    metadata = validate_template_index.load_jsonl(index_dir / "templates.metadata.jsonl")
    for record in metadata:
        if diagnostic_only:
            review = record.get("reference_review")
            require(isinstance(review, dict) and record.get("split") == "train"
                    and review.get("status") == "candidate_clean"
                    and valid_sha256(record.get("source_sha256"))
                    and valid_sha256(review.get("source_sha256"))
                    and review.get("source_sha256") == record.get("source_sha256")
                    and isinstance(review.get("provenance"), dict)
                    and review["provenance"].get("method") == "ai_visual_review",
                    f"unreviewed or non-training reference: {record['template_id']}")
        embedding = record.get("embedding", {})
        require(isinstance(embedding, dict) and embedding.get("status") == "ready"
                and embedding.get("dimension") == dimension, f"invalid embedding metadata: {record['template_id']}")
        model_id = str(embedding.get("model_id", "")).replace("\\", "/")
        require(model_id.split("/")[-1] == config["runtime"]["model_file"],
                f"index embedding model differs from packaged model: {record['template_id']}")
        if diagnostic_only:
            require(valid_sha256(embedding.get("model_sha256")),
                    f"missing or invalid index embedding model SHA-256: {record['template_id']}")
        if "model_sha256" in embedding:
            require(embedding["model_sha256"] == expected_hash,
                    f"index embedding model SHA-256 mismatch: {record['template_id']}")
        scene = record.get("scene_category")
        require(isinstance(scene, str) and scene.strip().lower() not in {"", "unknown", "none"},
                f"missing scene label: {record['template_id']}")
    vectors = validate_template_index.read_embeddings(index_dir / "templates.embeddings.f32", embedding_count, dimension)
    # Recompute against the shipped bytes. A stale number copied into runtime config
    # is not evidence for the current model/index bundle.
    hits = 0
    for query_index, query in enumerate(vectors):
        neighbors = heapq.nsmallest(5, (
            (-validate_template_index.cosine(query, candidate), candidate_index)
            for candidate_index, candidate in enumerate(vectors) if candidate_index != query_index
        ))
        hits += any(metadata[index]["scene_category"] == metadata[query_index]["scene_category"]
                    for _, index in neighbors)
    measured_quality = round(100.0 * hits / embedding_count, 2)
    if not diagnostic_only:
        require(measured_quality >= quality_gate,
                f"recomputed top5 same-scene quality {measured_quality} is below gate {quality_gate}")
    require(abs(measured_quality - quality_score) <= 0.01,
            f"reported quality {quality_score} differs from packaged index measurement {measured_quality}")

    validate_core_recommendation(asset_root)

    print(
        f"V9 asset gate passed: {embedding_count} aligned embeddings; "
        f"recomputed leave-one-out top5 same-scene={measured_quality:.2f}%. "
        "This is an index diagnostic, not a held-out generalization result."
    )
    missing_model_hashes = sum("model_sha256" not in record["embedding"] for record in metadata)
    if missing_model_hashes:
        print(f"Legacy provenance: {missing_model_hashes} embeddings have no generating-model SHA-256; "
              "their exact model lineage cannot be verified until regenerated.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset-root", type=Path, required=True)
    args = parser.parse_args()
    validate(args.asset_root)


if __name__ == "__main__":
    main()
