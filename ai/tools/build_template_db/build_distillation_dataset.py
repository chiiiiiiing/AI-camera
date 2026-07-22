#!/usr/bin/env python3
"""Build train/val/test manifests for student embedding distillation."""

from __future__ import annotations

import argparse
import json
import random
import struct
import sys
from collections import defaultdict
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
                raise ValueError(f"{path}:{line_number}: line is not an object")
            rows.append(value)
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def resolve_path(path: str, base_dir: Path) -> Path:
    value = Path(path)
    if value.is_absolute():
        return value
    return (base_dir / value).resolve()


def validate_teacher_embedding(record: dict[str, Any], base_dir: Path) -> tuple[Path, int]:
    photo_id = record.get("photo_id", "<unknown>")
    embedding = record.get("embedding")
    if not isinstance(embedding, dict):
        raise ValueError(f"{photo_id}: missing embedding object")
    if embedding.get("status") != "ready":
        raise ValueError(f"{photo_id}: embedding is not ready")
    dimension = int(embedding.get("dimension") or 0)
    if dimension <= 0:
        raise ValueError(f"{photo_id}: invalid teacher embedding dimension")
    path = resolve_path(str(embedding.get("path") or ""), base_dir)
    if not path.exists():
        raise ValueError(f"{photo_id}: teacher embedding file not found: {path}")
    expected_size = dimension * struct.calcsize("<f")
    actual_size = path.stat().st_size
    if actual_size != expected_size:
        raise ValueError(
            f"{photo_id}: teacher embedding byte size {actual_size} != expected {expected_size}"
        )
    return path, dimension


def distillation_record(record: dict[str, Any], split: str, base_dir: Path) -> dict[str, Any]:
    embedding_path, dimension = validate_teacher_embedding(record, base_dir)
    embedding = record["embedding"]
    annotation = record.get("annotation") if isinstance(record.get("annotation"), dict) else {}
    return {
        "schema_version": 1,
        "split": split,
        "photo_id": record.get("photo_id"),
        "template_id": record.get("template_id"),
        "photo_type": record.get("photo_type"),
        "scene_category": record.get("scene_category"),
        "source_path": record.get("source_path"),
        "width": record.get("width"),
        "height": record.get("height"),
        "teacher_embedding": {
            "dimension": dimension,
            "path": str(embedding_path),
            "model_id": embedding.get("model_id"),
            "role": embedding.get("role", "teacher"),
        },
        "labels": {
            "aesthetic_score": annotation.get("aesthetic_score"),
            "template_value": annotation.get("template_value"),
            "composition": annotation.get("composition"),
            "lighting": annotation.get("lighting"),
            "camera": annotation.get("camera"),
            "shooting_guidance": annotation.get("shooting_guidance"),
        },
    }


def split_group(items: list[dict[str, Any]], train_ratio: float, val_ratio: float) -> dict[str, list[dict[str, Any]]]:
    count = len(items)
    if count == 0:
        return {"train": [], "val": [], "test": []}
    if count == 1:
        return {"train": items, "val": [], "test": []}
    if count == 2:
        return {"train": items[:1], "val": items[1:], "test": []}

    train_count = max(1, int(count * train_ratio))
    val_count = int(count * val_ratio)
    test_count = count - train_count - val_count
    if val_count == 0:
        val_count = 1
        train_count -= 1
    if test_count == 0:
        test_count = 1
        train_count -= 1
    if train_count < 1:
        train_count = 1
        if val_count > test_count:
            val_count -= 1
        else:
            test_count -= 1

    return {
        "train": items[:train_count],
        "val": items[train_count:train_count + val_count],
        "test": items[train_count + val_count:],
    }


def build_splits(
    rows: list[dict[str, Any]],
    seed: int,
    train_ratio: float,
    val_ratio: float,
) -> dict[str, list[dict[str, Any]]]:
    if not 0.0 < train_ratio < 1.0:
        raise ValueError("train_ratio must be between 0 and 1")
    if not 0.0 <= val_ratio < 1.0:
        raise ValueError("val_ratio must be between 0 and 1")
    if train_ratio + val_ratio >= 1.0:
        raise ValueError("train_ratio + val_ratio must be less than 1")

    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        key = (str(row.get("photo_type")), str(row.get("scene_category")))
        groups[key].append(row)

    rng = random.Random(seed)
    splits = {"train": [], "val": [], "test": []}
    for key in sorted(groups):
        items = sorted(groups[key], key=lambda item: str(item.get("photo_id")))
        rng.shuffle(items)
        group_splits = split_group(items, train_ratio, val_ratio)
        for split_name, split_rows in group_splits.items():
            splits[split_name].extend(split_rows)
    for split_name in splits:
        splits[split_name].sort(key=lambda item: str(item.get("photo_id")))
    return splits


def build_dataset(
    metadata: Path,
    output_dir: Path,
    seed: int = 20260718,
    train_ratio: float = 0.8,
    val_ratio: float = 0.1,
) -> dict[str, Any]:
    rows = load_jsonl(metadata)
    base_dir = Path.cwd()
    split_rows = build_splits(rows, seed, train_ratio, val_ratio)
    output_dir.mkdir(parents=True, exist_ok=True)

    counts: dict[str, int] = {}
    for split_name, records in split_rows.items():
        converted = [distillation_record(record, split_name, base_dir) for record in records]
        write_jsonl(output_dir / f"{split_name}.jsonl", converted)
        counts[split_name] = len(converted)

    manifest = {
        "schema_version": 1,
        "source_metadata": str(metadata),
        "output_dir": str(output_dir),
        "seed": seed,
        "train_ratio": train_ratio,
        "val_ratio": val_ratio,
        "test_ratio": round(1.0 - train_ratio - val_ratio, 6),
        "total": len(rows),
        "splits": counts,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20260718)
    parser.add_argument("--train-ratio", type=float, default=0.8)
    parser.add_argument("--val-ratio", type=float, default=0.1)
    args = parser.parse_args()
    try:
        summary = build_dataset(
            args.metadata,
            args.output_dir,
            seed=args.seed,
            train_ratio=args.train_ratio,
            val_ratio=args.val_ratio,
        )
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
