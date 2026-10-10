#!/usr/bin/env python3
"""Fail-fast integrity and offline-quality gate for the packaged Android V9 assets."""

from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import math
from pathlib import Path

from build_template_db import validate_template_index


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


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
    require(config["runtime"]["model_file"] == "student_model.static_int8.onnx", "unexpected runtime model file")
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

    expected_hash = manifest["static_int8_onnx"]["sha256"]
    require(expected_hash == artifacts["model_sha256"], "runtime config and manifest model hashes differ")
    actual_hash = sha256(asset_root / "student_model.static_int8.onnx")
    require(actual_hash == expected_hash, "packaged ONNX model SHA-256 mismatch")
    require((asset_root / "student_model.static_int8.onnx").stat().st_size == manifest["static_int8_onnx"]["bytes"],
            "packaged ONNX model size differs from manifest")

    quality_score = float(quality["v9_static_int8_onnx_top5_same_scene_pct"])
    quality_gate = float(quality["commercial_gate_top5_same_scene_pct"])
    require(math.isfinite(quality_score) and 0 <= quality_score <= 100, "invalid reported quality percentage")
    require(math.isfinite(quality_gate) and 0 <= quality_gate <= 100, "invalid quality gate percentage")
    require(quality_score >= quality_gate, f"top5 same-scene quality {quality_score} is below gate {quality_gate}")

    index_dir = asset_root / "template_index"
    errors, summary = validate_template_index.validate_index(index_dir, check_self_similarity=False)
    require(not errors, "; ".join(errors))
    require(summary["dimension"] == dimension, "index key dimension mismatch")
    embedding_count = summary["count"]
    require(embedding_count > 5, "top5 quality gate requires at least six templates")
    metadata = validate_template_index.load_jsonl(index_dir / "templates.metadata.jsonl")
    for record in metadata:
        embedding = record.get("embedding", {})
        require(isinstance(embedding, dict) and embedding.get("status") == "ready"
                and embedding.get("dimension") == dimension, f"invalid embedding metadata: {record['template_id']}")
        model_id = str(embedding.get("model_id", "")).replace("\\", "/")
        require(model_id.split("/")[-1] == config["runtime"]["model_file"],
                f"index embedding model differs from packaged model: {record['template_id']}")
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
    require(measured_quality >= quality_gate,
            f"recomputed top5 same-scene quality {measured_quality} is below gate {quality_gate}")
    require(abs(measured_quality - quality_score) <= 0.01,
            f"reported quality {quality_score} differs from packaged index measurement {measured_quality}")

    print(
        f"V9 asset gate passed: {embedding_count} aligned embeddings; "
        f"recomputed leave-one-out top5 same-scene={measured_quality:.2f}% (gate={quality_gate:.2f}%). "
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
