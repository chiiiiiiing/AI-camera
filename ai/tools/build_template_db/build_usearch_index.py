#!/usr/bin/env python3
"""Build a USearch ANN index from validated portable template index inputs."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import validate_template_index


def build_usearch_index(index_dir: Path, output_name: str = "templates.usearch.idx") -> dict[str, object]:
    try:
        import numpy as np
        from usearch.index import Index
    except ImportError as exc:
        raise RuntimeError("install the Python 'usearch' package before building .idx files") from exc

    errors, summary = validate_template_index.validate_index(index_dir)
    if errors:
        raise ValueError("; ".join(errors))

    keys_path = index_dir / "templates.keys.json"
    keys_data = json.loads(keys_path.read_text(encoding="utf-8"))
    dimension = int(keys_data["dimension"])
    count = int(keys_data["count"])
    keys = [int(item["key"]) for item in keys_data["items"]]
    vectors = validate_template_index.read_embeddings(
        index_dir / "templates.embeddings.f32",
        count,
        dimension,
    )

    index = Index(ndim=dimension, metric="cos", dtype="f32")
    for key, vector in zip(keys, vectors):
        index.add(key, np.array(vector, dtype=np.float32))

    failed: list[str] = []
    for key, vector in zip(keys, vectors):
        matches = index.search(np.array(vector, dtype=np.float32), 1)
        if len(matches.keys) != 1 or int(matches.keys[0]) != key:
            failed.append(f"key {key}: top-1 was {matches.keys[0] if len(matches.keys) else '<none>'}")
        elif float(matches.distances[0]) > 1e-4:
            failed.append(f"key {key}: self distance too high: {float(matches.distances[0]):.6f}")
    if failed:
        raise ValueError("; ".join(failed[:10]))

    output_path = index_dir / output_name
    index.save(str(output_path))
    manifest = {
        "schema_version": 1,
        "index": str(output_path),
        "count": count,
        "dimension": dimension,
        "metric": "cos",
        "dtype": "f32",
        "self_check": "top1",
    }
    manifest_path = index_dir / "templates.usearch.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    summary.update(
        {
            "usearch_index": str(output_path),
            "usearch_manifest": str(manifest_path),
        }
    )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("index_dir", type=Path)
    parser.add_argument("--output-name", default="templates.usearch.idx")
    args = parser.parse_args()
    try:
        summary = build_usearch_index(args.index_dir, args.output_name)
    except (OSError, ValueError, RuntimeError, KeyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
