#!/usr/bin/env python3
"""Build deterministic template index inputs from ready template metadata."""

from __future__ import annotations

import argparse
import json
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import validate_embeddings
import validate_template_metadata


@dataclass(frozen=True)
class TemplateVector:
    key: int
    record: dict[str, Any]
    values: list[float]


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"line {line_number} is not an object")
            rows.append(value)
    return rows


def load_ready_vectors(metadata_path: Path, expected_dimension: int = 256) -> list[TemplateVector]:
    vectors: list[TemplateVector] = []
    seen_template_ids: set[str] = set()
    for line_number, record in enumerate(load_jsonl(metadata_path), 1):
        core_errors = validate_template_metadata.validate_core(record)
        if core_errors:
            raise ValueError(f"line {line_number}: {'; '.join(core_errors)}")
        template_id = record["template_id"]
        if template_id in seen_template_ids:
            raise ValueError(f"line {line_number}: duplicate template_id: {template_id}")
        seen_template_ids.add(template_id)

        embedding = record["embedding"]
        if embedding.get("status") != "ready":
            raise ValueError(f"line {line_number}: embedding for {template_id} is not ready")
        if embedding.get("dimension") != expected_dimension:
            raise ValueError(
                f"line {line_number}: expected embedding dimension {expected_dimension}, "
                f"got {embedding.get('dimension')}"
            )
        embedding_path = Path(embedding["path"])
        if not embedding_path.exists():
            raise ValueError(f"line {line_number}: missing embedding file: {embedding_path}")
        values = validate_embeddings.read_f32(embedding_path)
        value_errors = validate_embeddings.validate_embedding_values(values, expected_dimension)
        if value_errors:
            raise ValueError(f"line {line_number}: invalid embedding for {template_id}: {'; '.join(value_errors)}")
        vectors.append(TemplateVector(key=len(vectors) + 1, record=record, values=values))
    if not vectors:
        raise ValueError("metadata contains no ready template embeddings")
    return vectors


def write_portable_bundle(vectors: list[TemplateVector], output_dir: Path, dimension: int) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    metadata_path = output_dir / "templates.metadata.jsonl"
    embeddings_path = output_dir / "templates.embeddings.f32"
    keys_path = output_dir / "templates.keys.json"

    with metadata_path.open("w", encoding="utf-8") as handle:
        for vector in vectors:
            handle.write(json.dumps(vector.record, ensure_ascii=False, sort_keys=True) + "\n")
    with embeddings_path.open("wb") as handle:
        for vector in vectors:
            handle.write(struct.pack(f"<{dimension}f", *vector.values))

    keys = {
        "schema_version": 1,
        "metric": "cosine",
        "dimension": dimension,
        "count": len(vectors),
        "items": [
            {
                "key": vector.key,
                "template_id": vector.record["template_id"],
                "photo_id": vector.record["photo_id"],
                "photo_type": vector.record["photo_type"],
                "scene_category": vector.record["scene_category"],
            }
            for vector in vectors
        ],
    }
    keys_path.write_text(json.dumps(keys, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return {
        "metadata": str(metadata_path),
        "embeddings": str(embeddings_path),
        "keys": str(keys_path),
        "count": len(vectors),
        "dimension": dimension,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--dimension", type=int, default=256)
    args = parser.parse_args()
    try:
        vectors = load_ready_vectors(args.metadata, args.dimension)
        summary = write_portable_bundle(vectors, args.output_dir, args.dimension)
    except (OSError, ValueError, KeyError, struct.error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
