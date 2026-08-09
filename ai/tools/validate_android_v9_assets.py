#!/usr/bin/env python3
"""Fail-fast integrity and offline-quality gate for the packaged Android V9 assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from pathlib import Path


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
    config = json.loads((asset_root / "android_runtime_config.json").read_text())
    manifest = json.loads((asset_root / "android_onnx_runtime_manifest.json").read_text())
    model = config["model"]
    quality = config["offline_quality"]
    artifacts = config["artifacts"]

    dimension = int(model["embedding_dimension"])
    require(model["input_size"] == 224, "V9 input_size must remain 224")
    require(dimension == 512, "V9 embedding dimension must remain 512")
    require(config["postprocess"]["search_top_k"] >= 5, "search_top_k must cover display top5")

    expected_hash = manifest["static_int8_onnx"]["sha256"]
    require(expected_hash == artifacts["model_sha256"], "runtime config and manifest model hashes differ")
    actual_hash = sha256(asset_root / "student_model.static_int8.onnx")
    require(actual_hash == expected_hash, "packaged ONNX model SHA-256 mismatch")

    quality_score = float(quality["v9_static_int8_onnx_top5_same_scene_pct"])
    quality_gate = float(quality["commercial_gate_top5_same_scene_pct"])
    require(quality_score >= quality_gate, f"top5 same-scene quality {quality_score} is below gate {quality_gate}")

    index_dir = asset_root / "template_index"
    keys = json.loads((index_dir / "templates.keys.json").read_text())
    require(int(keys["dimension"]) == dimension, "index key dimension mismatch")
    items = keys["items"]
    ids = [(item.get("template_id") or item.get("photo_id") or "").strip() for item in items]
    require(len(items) == int(keys["count"]) and len(ids) == len(set(ids)), "invalid or duplicate template keys")
    require(all(ids), "blank template key")

    metadata: dict[str, object] = {}
    for line_number, line in enumerate((index_dir / "templates.metadata.jsonl").read_text().splitlines(), start=1):
        if not line.strip():
            continue
        record = json.loads(line)
        identifier = (record.get("template_id") or record.get("photo_id") or "").strip()
        require(identifier and identifier not in metadata, f"invalid metadata id at line {line_number}")
        metadata[identifier] = record
    require(set(ids).issubset(metadata), "template keys reference missing metadata")

    embedding_path = index_dir / "templates.embeddings.f32"
    raw = embedding_path.read_bytes()
    bytes_per_embedding = dimension * 4
    require(len(raw) % bytes_per_embedding == 0, "embedding file has a partial row")
    embedding_count = len(raw) // bytes_per_embedding
    require(embedding_count == len(ids), "embedding count and template key count differ")
    for offset in range(0, len(raw), bytes_per_embedding):
        values = struct.unpack_from(f"<{dimension}f", raw, offset)
        norm_squared = sum(value * value for value in values)
        require(math.isfinite(norm_squared) and norm_squared > 1e-16, "invalid template embedding")

    print(
        f"V9 asset gate passed: {embedding_count} aligned embeddings; "
        f"top5 same-scene={quality_score:.2f}% (gate={quality_gate:.2f}%)."
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset-root", type=Path, required=True)
    args = parser.parse_args()
    validate(args.asset_root)


if __name__ == "__main__":
    main()
