#!/usr/bin/env python3
"""Build a train-only, visually reviewed reference index with fresh ONNX vectors.

Review means suitability as a single reference image, not aesthetic ground truth.
Recovered images require their own hash-bound review, train parent and new vectors.
The output is a new directory; callers explicitly promote a complete bundle.
"""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort

from benchmark_onnx_retrieval import encode, load_images
from build_template_index import TemplateVector, write_portable_bundle
from quantize_onnx_static import sha256_file


def rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def unique(records: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    result = {}
    for row in records:
        identity = row["photo_id"]
        if identity in result:
            raise ValueError(f"duplicate photo_id: {identity}")
        result[identity] = row
    return result


def public_review(review: dict[str, Any]) -> dict[str, Any]:
    """Export evidence without copying workstation paths from local review sheets."""
    fields = ("schema_version", "photo_id", "source_sha256", "split", "status", "reason",
              "reason_codes", "aesthetic_score", "parent_photo_id", "parent_source_sha256",
              "synthetic", "photo_type", "scene_category")
    result = {key: copy.deepcopy(review[key]) for key in fields if key in review}
    provenance_fields = ("method", "human_reviewed", "review_scope", "review_scale",
                         "page", "slot", "observation", "synthetic")
    provenance = review.get("provenance", {})
    result["provenance"] = {key: copy.deepcopy(provenance[key]) for key in provenance_fields
                            if key in provenance}
    return result


def select_records(train: list[dict[str, Any]], reviews: list[dict[str, Any]],
                   recovered: list[dict[str, Any]]) -> list[dict[str, Any]]:
    parents = unique(train)
    if any(row.get("split") != "train" for row in train):
        raise ValueError("only train references may enter the reviewed bundle")
    all_records = unique(train + recovered)
    review_map = unique(reviews)
    for item in recovered:
        parent = parents.get(item.get("parent_photo_id"))
        if (parent is None or item.get("split") != "train" or
                item.get("parent_source_sha256") != parent.get("source_sha256")):
            raise ValueError(f"invalid train parent: {item['photo_id']}")
        expected_group = parent.get("source_group_id") or f"parent_sha256:{parent['source_sha256']}"
        if item.get("source_group_id") != expected_group:
            raise ValueError(f"derived source group mismatch: {item['photo_id']}")
    selected = []
    for identity, review in review_map.items():
        item = all_records.get(identity)
        if item is None or review.get("source_sha256") != item.get("source_sha256"):
            raise ValueError(f"review image identity/hash mismatch: {identity}")
        if "split" in review and review["split"] != "train":
            raise ValueError(f"review split must be train: {identity}")
        if review.get("status") != "candidate_clean":
            continue
        if review.get("provenance", {}).get("method") != "ai_visual_review":
            raise ValueError(f"missing review provenance: {identity}")
        result = copy.deepcopy(item)
        if identity not in parents:
            parent = parents[item["parent_photo_id"]]
            # Category is only inherited semantic context; no old composition/pose is reused.
            result["photo_type"] = review.get("photo_type", parent["photo_type"])
            result["scene_category"] = review.get("scene_category", parent["scene_category"])
        result["reference_review"] = public_review(review)
        selected.append(result)
    if not selected:
        raise ValueError("no reviewed reference candidates")
    return sorted(selected, key=lambda item: item["photo_id"])


def build(args: argparse.Namespace) -> dict[str, Any]:
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        raise ValueError("output directory must be new or empty")
    recovered = [item for path in args.recovered_manifest for item in rows(path)]
    reviews = [item for path in args.reviews for item in rows(path)]
    selected = select_records(rows(args.train_manifest), reviews, recovered)
    originals = unique(rows(args.original_metadata))
    anchors = unique(rows(args.face_anchors)) if args.face_anchors else {}
    model_hash = sha256_file(args.onnx_model)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.onnx_model), sess_options=options,
                                   providers=["CPUExecutionProvider"])
    vectors, elapsed = encode(session, load_images(selected, 224), 16)
    if vectors.shape[1] != 512:
        raise ValueError("Android V9 requires 512-dimensional vectors")
    vectors /= np.linalg.norm(vectors, axis=1, keepdims=True)
    bundle = []
    for index, item in enumerate(selected):
        identity = item["photo_id"]
        original = originals.get(identity)
        parent_metadata = originals.get(item.get("parent_photo_id"), {})
        metadata = copy.deepcopy(original) if original else {
            "schema_version": 1, "photo_id": identity, "template_id": identity,
            "photo_type": item["photo_type"], "scene_category": item["scene_category"],
            "source_sha256": item["source_sha256"], "width": item["width"], "height": item["height"],
            "annotation": {}, "pose": {"required": False, "detected": False,
                "keypoints_coco17": None, "keypoints_normalized": None, "joint_angles_degrees": None},
            "parent_photo_id": item["parent_photo_id"], "synthetic": item.get("synthetic", False),
            "recovery": item.get("recovery", {}),
            "license": parent_metadata.get("license"), "landing_url": parent_metadata.get("landing_url"),
            "creator": parent_metadata.get("creator"),
        }
        if metadata["source_sha256"] != item["source_sha256"]:
            raise ValueError(f"metadata source hash mismatch: {identity}")
        # Original private photos stay local; only their identity/provenance enters the index.
        metadata["source_path"] = f"local-reference://{identity}"
        metadata["split"] = "train"
        metadata["source_group_id"] = item.get("source_group_id") or f"parent_sha256:{item['source_sha256']}"
        metadata["reference_review"] = item["reference_review"]
        metadata["annotation"]["provenance"] = {
            "method": "legacy_scene_rule_draft" if original else "not_annotated_after_recovery",
            "human_reviewed": False, "composition_ground_truth": False, "aesthetic_ground_truth": False}
        metadata["annotation"].update(aesthetic_score=None, template_value="unrated", shooting_guidance=[])
        if identity in anchors:
            anchor = anchors[identity]
            if anchor["source_sha256"] != item["source_sha256"]:
                raise ValueError(f"face anchor source mismatch: {identity}")
            metadata["reference_geometry"] = anchor
        metadata["embedding"] = {"status": "ready", "dimension": 512, "role": "student",
                                 "model_id": args.onnx_model.name, "model_sha256": model_hash,
                                 "path": f"bundle://templates.embeddings.f32#row={index}"}
        bundle.append(TemplateVector(index + 1, metadata, vectors[index].tolist()))
    report = write_portable_bundle(bundle, args.output_dir, 512)
    report.update({"model_sha256": model_hash, "encoding_seconds": elapsed,
                   "reviewed_recovered_count": sum("parent_photo_id" in row for row in selected),
                   "synthetic_count": sum(bool(row.get("synthetic")) for row in selected),
                   "face_anchor_count": sum("reference_geometry" in vector.record for vector in bundle),
                   "aesthetic_improvement_measured": False, "test_used": False,
                   "train_manifest_sha256": sha256_file(args.train_manifest),
                   "review_sha256": [sha256_file(path) for path in args.reviews]})
    (args.output_dir / "build-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--train-manifest", type=Path, required=True)
    parser.add_argument("--original-metadata", type=Path, required=True)
    parser.add_argument("--reviews", type=Path, action="append", required=True)
    parser.add_argument("--recovered-manifest", type=Path, action="append", default=[])
    parser.add_argument("--face-anchors", type=Path)
    parser.add_argument("--onnx-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    print(json.dumps(build(parser.parse_args()), indent=2))


if __name__ == "__main__":
    main()
