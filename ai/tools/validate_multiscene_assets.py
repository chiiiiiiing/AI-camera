#!/usr/bin/env python3
"""Validate optional, hash-bound object and scene-layout guidance artifacts."""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import re

MODEL_SHA256 = "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
MODEL_BYTES = 3_800_954
MODEL_BUDGET = 4 * 1024 * 1024
LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
PREPROCESSING = "viewport_rgb224_bilinear416_bgr_mean_std_v1"
NATIVE_PREPROCESSING = "viewport_native_letterbox416_bgr_mean_std_v2"
LAYOUT = "rgb224_rec601_grid3_forward_edges_v1"
ALLOWED_CLASSES = {39, 40, 41, *range(45, 56), 73, 74, 77}
FILES = {"detector": "object_detector.onnx", "references": "multi_scene_references.json",
         "detector_manifest": "object_detector_manifest.json"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    def reject(value):
        raise ValueError("non-finite JSON: " + value)
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject)


def number(value, low=0, high=1):
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def public(value):
    if isinstance(value, dict):
        for key, child in value.items():
            require(key not in {"image_path", "source_path", "checkpoint_path"}, "private path in multiscene artifact")
            public(child)
    elif isinstance(value, list):
        for child in value:
            public(child)
    elif isinstance(value, str):
        require(not re.match(r"^[A-Za-z]:[\\/]", value), "absolute local path in multiscene artifact")


def validate(asset_root: Path, config=None, metadata=None):
    config = config if config is not None else read_json(asset_root / "android_runtime_config.json")
    profile = config.get("multi_scene_recommendation")
    if profile is None:
        require(not any((asset_root / name).exists() for name in FILES.values()), "unbound multiscene assets")
        return {"multiscene_enabled": False}
    require(profile.get("schema_version") == 1 and profile.get("enabled") is True, "invalid multiscene profile")
    for key, filename in FILES.items():
        descriptor = profile.get(key, {})
        path = asset_root / filename
        require(descriptor.get("file") == filename and path.is_file(), "missing multiscene artifact: " + filename)
        require(path.stat().st_size <= (MODEL_BUDGET if key == "detector" else 4_000_000), "oversized multiscene artifact")
        require(descriptor.get("sha256") == sha256(path), "multiscene hash mismatch: " + filename)
    model = asset_root / FILES["detector"]
    require(sha256(model) == MODEL_SHA256 and model.stat().st_size == MODEL_BYTES, "unrecognized object detector")
    manifest = read_json(asset_root / FILES["detector_manifest"])
    public(manifest)
    require(manifest.get("model_sha256") == MODEL_SHA256, "detector manifest model identity mismatch")
    require(manifest.get("preprocessing") in {PREPROCESSING, NATIVE_PREPROCESSING}
            and manifest.get("source_commit") == "47534e27c9851bb1128ccc0102f1145e27f23f98"
            and manifest.get("license") == "Apache-2.0", "detector provenance/preprocessing mismatch")
    license_file = asset_root / "LICENSE.object_detector.txt"
    require(license_file.is_file() and sha256(license_file) == manifest.get("license_sha256") == LICENSE_SHA256,
            "missing or modified detector license")
    if metadata is None:
        rows = [json.loads(line) for line in (asset_root / "template_index/templates.metadata.jsonl")
                .read_text(encoding="utf-8").splitlines() if line.strip()]
        metadata = {row["template_id"]: row for row in rows}
    references = validate_references(asset_root / FILES["references"], metadata, manifest["preprocessing"])
    return {"multiscene_enabled": True, "object_detector_bytes": model.stat().st_size, **references}


def validate_references(path, metadata, expected_preprocessing=None):
    doc = read_json(path)
    public(doc)
    require(doc.get("schema_version") == 1 and doc.get("coordinate_space") == "exif_upright_full_frame",
            "invalid multiscene coordinate/schema contract")
    require(doc.get("model_sha256") == MODEL_SHA256, "reference detector mismatch")
    preprocessing = doc.get("preprocessing")
    require(preprocessing in {PREPROCESSING, NATIVE_PREPROCESSING}
            and (expected_preprocessing is None or preprocessing == expected_preprocessing)
            and doc.get("layout_algorithm") == LAYOUT
            and doc.get("object_policy") == "single_complete_coco_still_life_v1", "unsupported multiscene preprocessing/policy")
    require(doc.get("human_reviewed") is False and doc.get("aesthetic_ground_truth") is False,
            "automatic detector/layout cannot claim human beauty labels")
    require(doc.get("object_review_policy") == "hash_bound_ai_visual_object_review_v1"
            and re.fullmatch("[0-9a-f]{64}", str(doc.get("object_review_sha256", ""))) is not None,
            "object targets need hash-bound visual review provenance")
    rows = doc.get("records")
    require(isinstance(rows, list) and len(rows) == len(metadata), "multiscene reference count mismatch")
    seen, eligible = set(), 0
    for row in rows:
        identity = row.get("template_id")
        require(identity in metadata and identity not in seen, "unknown/duplicate multiscene reference")
        seen.add(identity)
        require(row.get("image_sha256") == metadata[identity].get("source_sha256"), "multiscene image hash mismatch")
        require(row.get("coordinate_space") == "exif_upright_full_frame"
                and all(type(row.get(k)) is int and row[k] > 0 for k in ("image_width", "image_height")),
                "invalid multiscene dimensions/coordinates")
        require(row.get("scene_category") == metadata[identity].get("scene_category")
                and row.get("photo_type") == metadata[identity].get("photo_type"), "multiscene scene identity mismatch")
        require(type(row.get("synthetic")) is bool and type(row.get("object_eligible")) is bool, "invalid multiscene eligibility flags")
        objects = row.get("objects")
        require(isinstance(objects, list) and len(objects) <= 100, "invalid detection list")
        for obj in objects:
            require(type(obj.get("class_id")) is int and 0 <= obj["class_id"] < 80, "invalid COCO class")
            require(number(obj.get("confidence")) and type(obj.get("clipped")) is bool, "invalid detection confidence/clipping")
            box = obj.get("bbox")
            require(isinstance(box, list) and len(box) == 4 and all(number(x) for x in box), "invalid object box")
            x, y, w, h = box
            require(w > 0 and h > 0 and x+w <= 1.000001 and y+h <= 1.000001, "object outside frame")
        layout = row.get("layout", {})
        require(layout.get("algorithm") == LAYOUT and layout.get("input_size") == 224 and layout.get("grid_size") == 3
                and layout.get("is_aesthetic_score") is False and layout.get("horizon_available") is False,
                "layout must not pretend to be beauty or horizon evidence")
        for key in ("luma_grid", "edge_grid", "rgb_mean_grid"):
            values = layout.get(key)
            require(isinstance(values, list) and len(values) == (27 if key == "rgb_mean_grid" else 9)
                    and all(number(x) for x in values), "invalid scene layout")
        review = row.get("object_review")
        if review is not None and preprocessing == NATIVE_PREPROCESSING:
            observed = hashlib.sha256(json.dumps(objects, sort_keys=True, separators=(",", ":"),
                                                ensure_ascii=False, allow_nan=False).encode("utf-8")).hexdigest()
            require(review.get("preprocessing") == preprocessing and review.get("objects_sha256") == observed,
                    "visual evidence refers to different preprocessing/observations")
        if row["object_eligible"]:
            require(not row["synthetic"] and row.get("photo_type") == "still_life", "only real still-life objects may guide framing")
            require(row.get("scene_category") in {"food_still_life", "lifestyle_still_life"}
                    and not row.get("object_reasons"), "object target carries rejected scene/reasons")
            review = row.get("object_review") or {}
            require(review.get("template_id") == identity and review.get("image_sha256") == row["image_sha256"]
                    and review.get("model_sha256") == MODEL_SHA256 and review.get("method") == "ai_visual_review"
                    and review.get("human_reviewed") is False and review.get("status") == "accepted"
                    and not review.get("reason_codes"), "object target lacks matching accepted visual evidence")
            candidates = sorted(((i, obj) for i, obj in enumerate(objects)
                                 if obj["confidence"] >= .5 and obj["bbox"][2]*obj["bbox"][3] >= .02),
                                key=lambda pair: (-pair[1]["bbox"][2]*pair[1]["bbox"][3], pair[0]))
            require(bool(candidates), "eligible object has no measured subject")
            primary_index, primary = candidates[0]
            x, y, w, h = primary["bbox"]
            require(row.get("primary_object_index") == primary_index and primary["class_id"] in ALLOWED_CLASSES
                    and w*h <= .65 and not primary["clipped"] and min(x, y, 1-x-w, 1-y-h) >= .02-1e-7,
                    "invalid dominant subject promoted to target")
            require(not any(obj["class_id"] == 0 for _, obj in candidates)
                    and not any(obj["bbox"][2]*obj["bbox"][3] >= w*h*.35 for _, obj in candidates[1:]),
                    "ambiguous subjects promoted to target")
            eligible += 1
    review_bytes = "".join(json.dumps(row["object_review"], ensure_ascii=False, allow_nan=False) + "\n"
                           for row in rows if row.get("object_review") is not None).encode("utf-8")
    require(hashlib.sha256(review_bytes).hexdigest() == doc["object_review_sha256"],
            "visual review content hash mismatch")
    return {"multiscene_reference_count": len(rows), "object_eligible_count": eligible}
