#!/usr/bin/env python3
"""Recover archived new_batch paths and audit the existing retrieval split.

This does not relabel, reshuffle, train, or modify the source archive. Known
source-post overlap can be excluded; unidentified groups remain unknown.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import re
import struct
import tempfile
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath
from typing import Any

SPLITS = ("train", "val", "test")
POST_ID = re.compile(r"(?<![0-9a-f])([0-9a-f]{24})(?![0-9a-f])")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        if not isinstance(row, dict):
            raise ValueError(f"{path.name}:{number}: expected an object")
        rows.append(row)
    return rows


def index_rows(rows: list[dict[str, Any]], name: str) -> dict[str, dict[str, Any]]:
    result = {}
    for row in rows:
        photo_id = row.get("photo_id")
        if not isinstance(photo_id, str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*", photo_id):
            raise ValueError(f"{name}: invalid photo_id")
        if photo_id in result:
            raise ValueError(f"{name}: duplicate photo_id {photo_id}")
        result[photo_id] = row
    return result


def archived_image_path(value: Any, data_root: Path) -> Path:
    """Resolve original tools-relative paths or an old absolute tools path."""
    if not isinstance(value, str) or not value.strip():
        raise ValueError("missing source_path")
    portable = value.replace("\\", "/")
    if "/tools/" in portable:
        candidate = data_root / portable.rsplit("/tools/", 1)[1]
    elif PurePosixPath(portable).is_absolute() or re.match(r"^[A-Za-z]:/", portable):
        candidate = Path(value)
    else:
        candidate = data_root / "build_template_db" / portable
    resolved = candidate.resolve()
    if not resolved.is_relative_to((data_root / "raw_photos").resolve()):
        raise ValueError("source_path must resolve inside the archive raw_photos directory")
    if not resolved.is_file():
        raise ValueError(f"source image does not exist: {resolved.name}")
    return resolved


def source_group_id(raw_record: dict[str, Any]) -> str | None:
    """Conservatively identify XHS posts, never guess a group for an opaque asset ID."""
    source = raw_record.get("source") or {}
    if not isinstance(source, dict) or source.get("provider") != "local_xhs_export":
        return None
    ids = set()
    for field in ("provider_id", "landing_url"):
        ids.update(POST_ID.findall(str(source.get(field) or "").lower()))
    if len(ids) != 1:
        return None
    # The audit needs stable grouping, not public post URLs or titles.
    return "xhs_post:" + hashlib.sha256(next(iter(ids)).encode("ascii")).hexdigest()


def validate_teacher(path: Path, dimension: Any) -> tuple[str, float]:
    if type(dimension) is not int or dimension <= 0:
        raise ValueError("teacher dimension must be a positive integer")
    if not path.is_file():
        raise ValueError("teacher embedding file does not exist")
    data = path.read_bytes()
    if len(data) != dimension * 4:
        raise ValueError("teacher embedding byte size does not match dimension")
    values = struct.unpack(f"<{dimension}f", data)
    if not all(math.isfinite(value) for value in values):
        raise ValueError("teacher embedding contains non-finite values")
    norm = math.sqrt(sum(value * value for value in values))
    if abs(norm - 1.0) > 0.01:
        raise ValueError("teacher embedding is not L2-normalized")
    return hashlib.sha256(data).hexdigest(), norm


def distribution(splits: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    return {
        split: {
            "count": len(rows),
            "scene_category": dict(sorted(Counter(str(row.get("scene_category")) for row in rows).items())),
            "photo_type": dict(sorted(Counter(str(row.get("photo_type")) for row in rows).items())),
        }
        for split, rows in splits.items()
    }


def group_audit(splits: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    groups = {split: {row["source_group_id"] for row in rows if row["source_group_id"]}
              for split, rows in splits.items()}
    counts = Counter(row["source_group_id"] for rows in splits.values() for row in rows if row["source_group_id"])
    return {
        "known_rows": {split: sum(bool(row["source_group_id"]) for row in rows) for split, rows in splits.items()},
        "unknown_rows": {split: sum(not row["source_group_id"] for row in rows) for split, rows in splits.items()},
        "known_groups": len(counts),
        "multi_image_known_groups": sum(count > 1 for count in counts.values()),
        "max_known_group_size": max(counts.values(), default=0),
        "cross_split_known_groups": {f"{left}/{right}": len(groups[left] & groups[right])
                                     for left, right in (("train", "val"), ("train", "test"), ("val", "test"))},
        "queries_with_known_train_group": {
            split: sum(row["source_group_id"] in groups["train"] for row in splits[split])
            for split in ("val", "test")
        },
        "unknown_groups_are_independent": False,
        "near_duplicate_detection": "not_performed",
    }


def prepare_dataset(data_root: Path, output_dir: Path, exclude_known_group_overlap: bool = False) -> dict[str, Any]:
    data_root, output_dir = data_root.resolve(), output_dir.resolve()
    if not data_root.is_dir():
        raise ValueError("data-root does not exist")
    if output_dir.is_relative_to(data_root):
        raise ValueError("output-dir must be outside the source archive")
    if output_dir.exists():
        raise ValueError("output-dir already exists; choose a new directory to preserve prior experiments")

    preprocessed = data_root / "preprocessed"
    original_dir = preprocessed / "distillation.siglip2_new_batch"
    paths = {
        "metadata": preprocessed / "template_metadata.new_batch.siglip2-teacher.jsonl",
        "raw_manifest": data_root / "raw_photos/new_batch/manifest.accepted.jsonl",
        "manifest": original_dir / "manifest.json",
        **{split: original_dir / f"{split}.jsonl" for split in SPLITS},
    }
    metadata = index_rows(load_jsonl(paths["metadata"]), "metadata")
    raw = index_rows(load_jsonl(paths["raw_manifest"]), "raw manifest")
    manifest = json.loads(paths["manifest"].read_text(encoding="utf-8-sig"))
    splits = {split: load_jsonl(paths[split]) for split in SPLITS}
    all_rows = [row for rows in splits.values() for row in rows]
    indexed = index_rows(all_rows, "all splits")
    if set(indexed) != set(metadata):
        raise ValueError("split photo_ids must exactly match teacher metadata")
    if manifest.get("total") != len(all_rows) or manifest.get("splits") != {split: len(rows) for split, rows in splits.items()}:
        raise ValueError("source manifest counts do not match split files")

    seen: dict[str, dict[str, str]] = {field: {} for field in ("template_id", "source_path", "source_sha256")}
    teacher_spaces = set()
    norms = []
    teacher_hashes: dict[str, list[str]] = defaultdict(list)
    for split, rows in splits.items():
        if not rows:
            raise ValueError(f"{split} has no rows")
        for row in rows:
            photo_id = row["photo_id"]
            if row.get("split") != split:
                raise ValueError(f"{photo_id}: split field disagrees with input filename")
            original = metadata[photo_id]
            if photo_id not in raw:
                raise ValueError(f"{photo_id}: missing raw manifest record")
            for field in ("template_id", "scene_category", "photo_type"):
                if not row.get(field) or row[field] != original.get(field):
                    raise ValueError(f"{photo_id}: {field} disagrees with metadata")
            image_path = archived_image_path(original.get("source_path"), data_root)
            if archived_image_path(row.get("source_path"), data_root) != image_path:
                raise ValueError(f"{photo_id}: source_path disagrees with metadata")
            actual_sha = sha256_file(image_path)
            for declared in (original.get("source_sha256"), raw[photo_id].get("sha256"), row.get("source_sha256", actual_sha)):
                if not isinstance(declared, str) or declared.lower() != actual_sha:
                    raise ValueError(f"{photo_id}: source_sha256 does not match image bytes")
            row["source_path"], row["source_sha256"] = str(image_path), actual_sha
            for field in seen:
                value = str(row[field])
                if value in seen[field]:
                    raise ValueError(f"duplicate {field} in input splits: {photo_id} and {seen[field][value]}")
                seen[field][value] = photo_id
            teacher = row.get("teacher_embedding")
            reference = original.get("embedding")
            if not isinstance(teacher, dict) or not isinstance(reference, dict) or reference.get("status") != "ready":
                raise ValueError(f"{photo_id}: missing ready teacher embedding metadata")
            for field in ("dimension", "model_id"):
                if teacher.get(field) != reference.get(field):
                    raise ValueError(f"{photo_id}: teacher {field} disagrees with metadata")
            if not isinstance(teacher.get("model_id"), str) or not teacher["model_id"].strip():
                raise ValueError("teacher model_id must be nonempty")
            embedding_path = preprocessed / "teacher_embeddings.siglip2_new_batch" / photo_id / "embedding.f32"
            embedding_path = embedding_path.resolve()
            if not embedding_path.is_relative_to((preprocessed / "teacher_embeddings.siglip2_new_batch").resolve()):
                raise ValueError("teacher embedding must remain inside the source archive")
            digest, norm = validate_teacher(embedding_path, teacher.get("dimension"))
            if teacher.get("sha256", digest) != digest:
                raise ValueError(f"{photo_id}: teacher sha256 does not match vector bytes")
            teacher["path"], teacher["sha256"] = str(embedding_path), digest
            teacher_hashes[digest].append(photo_id)
            teacher_spaces.add((teacher["dimension"], teacher["model_id"]))
            norms.append(norm)
            row["source_group_id"] = source_group_id(raw[photo_id])
    if len(teacher_spaces) != 1:
        raise ValueError("teacher embeddings must share a dimension and model_id")

    original_distribution, original_groups = distribution(splits), group_audit(splits)
    exclusions = []
    if exclude_known_group_overlap:
        claimed_groups: dict[str, str] = {}
        for split in SPLITS:
            retained = []
            for row in splits[split]:
                group = row["source_group_id"]
                if group and group in claimed_groups:
                    exclusions.append({"photo_id": row["photo_id"], "split": split, "source_group_id": group,
                                       "overlaps_split": claimed_groups[group], "reason": "known_source_post_overlap"})
                else:
                    retained.append(row)
            splits[split] = retained
            for row in retained:
                if row["source_group_id"]:
                    claimed_groups[row["source_group_id"]] = split
        if any(not rows for rows in splits.values()):
            raise ValueError("known-group exclusion emptied a split; collect independent queries")

    counts = {split: len(rows) for split, rows in splits.items()}
    audit = {
        "schema_version": 1,
        "scope": "archived_new_batch_path_recovery_and_split_integrity",
        "status": "passed_with_limitations",
        "source_data_modified": False,
        "split_policy": "preserve_original_order_and_membership_except_requested_known_group_exclusions",
        "exclude_known_group_overlap": exclude_known_group_overlap,
        "inputs": {name: {"path": str(path), "sha256": sha256_file(path)} for name, path in paths.items()},
        "original_distribution": original_distribution,
        "output_distribution": distribution(splits),
        "exact_duplicate_checks": {"photo_id": "passed", "template_id": "passed", "source_path": "passed", "source_sha256": "passed"},
        "teacher": {"dimension": next(iter(teacher_spaces))[0], "model_id": next(iter(teacher_spaces))[1],
                    "norm_min": min(norms), "norm_max": max(norms),
                    "duplicate_vector_hash_groups": [ids for ids in teacher_hashes.values() if len(ids) > 1]},
        "source_groups": {"rule": "one_unique_24_hex_XHS_post_id_in_provider_id_or_landing_url",
                          "before": original_groups, "after": group_audit(splits)},
        "excluded_counts": dict(Counter(item["split"] for item in exclusions)),
        "excluded_records": exclusions,
        "label_policy": {"original_labels_preserved": True, "composition_supervision_allowed": False,
                         "composition_lighting_camera_provenance": "category_and_image_heuristics_not_independent_human_labels"},
        "limitations": [
            "Unknown source groups are not proven independent; near-duplicate image search has not been performed.",
            "Known-group exclusion does not remove any prior training exposure of reused checkpoints.",
            "Image bytes and teacher vectors are validated; image decoding is left to inference/training.",
            "Same-scene retrieval is a coarse semantic proxy, not an independent photographic composition assessment.",
        ],
    }
    output_manifest = copy.deepcopy(manifest)
    output_manifest.update({"source_metadata": str(paths["metadata"]), "source_manifest": str(paths["manifest"]),
                            "output_dir": str(output_dir), "total": sum(counts.values()), "splits": counts,
                            "teacher_dimension": next(iter(teacher_spaces))[0], "teacher_model_id": next(iter(teacher_spaces))[1],
                            "exclude_known_group_overlap": exclude_known_group_overlap, "audit": "audit.json"})
    for split in SPLITS:
        output_manifest[f"{split}_ratio"] = counts[split] / sum(counts.values())
    serialized = {f"{split}.jsonl": "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows)
                  for split, rows in splits.items()}
    serialized["manifest.json"] = json.dumps(output_manifest, ensure_ascii=False, indent=2) + "\n"
    audit["outputs"] = {name: {"sha256": hashlib.sha256(content.encode("utf-8")).hexdigest()} for name, content in serialized.items()}
    serialized["audit.json"] = json.dumps(audit, ensure_ascii=False, indent=2) + "\n"
    # All validation completes before publishing a complete new output directory.
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".prepare-retrieval-", dir=output_dir.parent) as temp_dir:
        staging = Path(temp_dir) / "dataset"
        staging.mkdir()
        for name, content in serialized.items():
            (staging / name).write_bytes(content.encode("utf-8"))
        staging.replace(output_dir)
    return audit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, required=True, help="Archived private-data/tools directory")
    parser.add_argument("--output-dir", type=Path, required=True, help="New directory outside the source archive")
    parser.add_argument("--exclude-known-group-overlap", action="store_true")
    args = parser.parse_args()
    audit = prepare_dataset(args.data_root, args.output_dir, args.exclude_known_group_overlap)
    print(json.dumps({"output_dir": str(args.output_dir.resolve()), "status": audit["status"],
                      "splits": {split: details["count"] for split, details in audit["output_distribution"].items()},
                      "excluded_counts": audit["excluded_counts"],
                      "unknown_source_group_rows": audit["source_groups"]["after"]["unknown_rows"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
