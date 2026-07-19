#!/usr/bin/env python3
"""Validate portable template index inputs before USearch packaging."""

from __future__ import annotations

import argparse
import json
import math
import struct
import sys
from pathlib import Path
from typing import Any


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"{path} line {line_number} is not an object")
            rows.append(value)
    return rows


def read_embeddings(path: Path, count: int, dimension: int) -> list[list[float]]:
    payload = path.read_bytes()
    expected_bytes = count * dimension * 4
    if len(payload) != expected_bytes:
        raise ValueError(
            f"{path} expected {expected_bytes} bytes for {count}x{dimension} f32, got {len(payload)}"
        )
    values = struct.unpack(f"<{count * dimension}f", payload)
    return [
        list(values[index * dimension : (index + 1) * dimension])
        for index in range(count)
    ]


def cosine(a: list[float], b: list[float]) -> float:
    return sum(left * right for left, right in zip(a, b))


def validate_vectors(vectors: list[list[float]], dimension: int) -> list[str]:
    errors: list[str] = []
    for index, vector in enumerate(vectors):
        if len(vector) != dimension:
            errors.append(f"vector {index}: expected dimension {dimension}, got {len(vector)}")
            continue
        if not all(math.isfinite(value) for value in vector):
            errors.append(f"vector {index}: contains non-finite values")
            continue
        magnitude = math.sqrt(sum(value * value for value in vector))
        if not 0.99 <= magnitude <= 1.01:
            errors.append(f"vector {index}: not L2-normalized: magnitude={magnitude:.6f}")
    return errors


def validate_self_similarity(vectors: list[list[float]], tolerance: float = 1e-5) -> list[str]:
    errors: list[str] = []
    for index, vector in enumerate(vectors):
        self_score = cosine(vector, vector)
        if self_score < 0.99:
            errors.append(f"vector {index}: self similarity too low: {self_score:.6f}")
            continue
        best_other = max(
            (cosine(vector, other) for other_index, other in enumerate(vectors) if other_index != index),
            default=-1.0,
        )
        if best_other > self_score + tolerance:
            errors.append(
                f"vector {index}: another vector scores above self "
                f"({best_other:.6f} > {self_score:.6f})"
            )
    return errors


def validate_index(index_dir: Path) -> tuple[list[str], dict[str, Any]]:
    keys_path = index_dir / "templates.keys.json"
    metadata_path = index_dir / "templates.metadata.jsonl"
    embeddings_path = index_dir / "templates.embeddings.f32"
    keys = json.loads(keys_path.read_text(encoding="utf-8"))
    items = keys.get("items")
    if not isinstance(items, list):
        raise ValueError("templates.keys.json items must be a list")
    count = int(keys.get("count", len(items)))
    dimension = int(keys.get("dimension", 0))
    if dimension <= 0:
        raise ValueError("templates.keys.json dimension must be positive")
    errors: list[str] = []
    if count != len(items):
        errors.append(f"keys count mismatch: count={count}, items={len(items)}")
    metadata = load_jsonl(metadata_path)
    if len(metadata) != count:
        errors.append(f"metadata count mismatch: expected {count}, got {len(metadata)}")
    vectors = read_embeddings(embeddings_path, count, dimension)

    seen_keys: set[int] = set()
    seen_template_ids: set[str] = set()
    for index, item in enumerate(items):
        key = item.get("key")
        template_id = item.get("template_id")
        if key != index + 1:
            errors.append(f"item {index}: key must be {index + 1}, got {key}")
        if isinstance(key, int):
            if key in seen_keys:
                errors.append(f"duplicate key: {key}")
            seen_keys.add(key)
        if not isinstance(template_id, str) or not template_id:
            errors.append(f"item {index}: template_id must be non-empty")
        elif template_id in seen_template_ids:
            errors.append(f"duplicate template_id: {template_id}")
        else:
            seen_template_ids.add(template_id)
        if index < len(metadata) and metadata[index].get("template_id") != template_id:
            errors.append(
                f"item {index}: key template_id {template_id} does not match metadata "
                f"{metadata[index].get('template_id')}"
            )

    errors.extend(validate_vectors(vectors, dimension))
    errors.extend(validate_self_similarity(vectors))
    return errors, {
        "count": count,
        "dimension": dimension,
        "metadata": str(metadata_path),
        "embeddings": str(embeddings_path),
        "keys": str(keys_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("index_dir", type=Path)
    args = parser.parse_args()
    try:
        errors, summary = validate_index(args.index_dir)
    except (OSError, ValueError, KeyError, json.JSONDecodeError, struct.error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
