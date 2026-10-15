#!/usr/bin/env python3
"""Validate optional guidance assets, independently of the frozen retrieval model.

The release gate recomputes admission from measured metrics. A disabled preference
head is a valid release; a missing or malformed enabled head is never valid.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
from pathlib import Path
import re
import struct

FEATURE_NAMES = ["mean_luma", "luma_std", "dark_fraction", "light_fraction",
                 "face_area", "face_cx", "face_cy", "face_valid"]
WEIGHT_COUNT = 64 * 520 + 64 + 64 + 1


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    def reject_constant(value):
        raise ValueError("non-finite JSON constant: " + value)
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_constant)


def number(value, low=None, high=None):
    return (type(value) in (float, int) and math.isfinite(value)
            and (low is None or value >= low) and (high is None or value <= high))


def check_public(value):
    if isinstance(value, dict):
        for key, item in value.items():
            require(key not in {"image_path", "source_path", "checkpoint_path"},
                    "private local path in public guidance artifact: " + key)
            check_public(item)
    elif isinstance(value, list):
        for item in value:
            check_public(item)
    elif isinstance(value, str):
        require(not re.match(r"^[A-Za-z]:[\\/]", value), "absolute local path in public artifact")


def validate_structures(path, metadata):
    require(path.stat().st_size <= 4_000_000, "structure sidecar exceeds runtime limit")
    document = read_json(path)
    check_public(document)
    require(document.get("schema_version") == 1, "unsupported structure schema")
    require(document.get("coordinate_space") == "exif_upright_full_frame", "invalid structure coordinates")
    provenance = document.get("provenance", {})
    require(provenance.get("method") == "local_mediapipe_image_detection", "unrecognized structure provenance")
    require(provenance.get("human_reviewed") is False and provenance.get("aesthetic_ground_truth") is False,
            "automatic structures must not claim human aesthetic labels")
    for field in ("face_model_sha256", "pose_model_sha256"):
        require(re.fullmatch(r"[a-f0-9]{64}", str(provenance.get(field, ""))) is not None,
                "missing structure detector hash: " + field)
    rows = document.get("records")
    require(isinstance(rows, list) and len(rows) == len(metadata), "structure/reference count mismatch")
    seen, eligible = set(), 0
    for row in rows:
        identity = row.get("template_id")
        require(identity in metadata and identity not in seen, "unknown or duplicate structure identity")
        seen.add(identity)
        require(row.get("image_sha256") == metadata[identity].get("source_sha256"),
                "structure image hash mismatch: " + identity)
        require(row.get("coordinate_space") == "exif_upright_full_frame", "invalid row coordinates")
        require(all(type(row.get(k)) is int and row[k] > 0 for k in ("image_width", "image_height")),
                "invalid reference dimensions")
        faces = row.get("faces")
        require(isinstance(faces, list) and type(row.get("face_count")) is int
                and len(faces) == row["face_count"], "face_count does not match actual detections")
        for face in faces:
            box = face.get("bbox")
            require(isinstance(box, list) and len(box) == 4 and all(number(x, 0, 1) for x in box),
                    "invalid normalized face box")
            x, y, w, h = box
            require(w > 0 and h > 0 and x+w <= 1.000001 and y+h <= 1.000001, "face box outside source")
            require(number(face.get("confidence"), 0, 1), "invalid face confidence")
            yaw = face.get("yaw_degrees")
            require(yaw is None or number(yaw, -180, 180), "invalid face yaw")
        require(type(row.get("structure_eligible")) is bool and type(row.get("synthetic")) is bool,
                "structure flags must be explicit booleans")
        if row["structure_eligible"]:
            require(not row["synthetic"] and len(faces) == 1 and row.get("photo_type") == "portrait",
                    "only real single-face portraits may supply action targets")
            require(faces[0].get("clipped") is False and faces[0]["confidence"] >= .75,
                    "unreliable face promoted to action target")
            require(row.get("quality", {}).get("severe_quality_issue") is False,
                    "severely degraded reference promoted")
            require(not row.get("structure_reasons"), "eligible reference carries rejection reasons")
            eligible += 1
    return {"reference_count": len(rows), "structure_eligible_count": eligible}


def validate_quality(path, asset_root, encoder_sha):
    require(path.stat().st_size <= 65_536, "quality policy exceeds runtime limit")
    policy = read_json(path)
    check_public(policy)
    require(policy.get("schema_version") == 1 and type(policy.get("enabled")) is bool,
            "invalid quality policy schema")
    require(policy.get("encoder_sha256") == encoder_sha, "quality policy uses another encoder")
    if not policy["enabled"]:
        require(not policy.get("enabled_scenes"), "disabled policy must not advertise enabled scenes")
        require(bool(policy.get("reasons") or policy.get("disabled_reasons")), "disabled policy needs recorded reasons")
        return {"quality_enabled": False, "reasons": policy.get("reasons", policy.get("disabled_reasons"))}
    metrics = policy.get("metrics", {})
    require(isinstance(metrics, dict), "enabled policy needs measured metrics")
    require(type(metrics.get("acceptance_cluster_count")) is int and metrics["acceptance_cluster_count"] >= 30,
            "insufficient independent source clusters for quality admission")
    scenes = policy.get("enabled_scenes")
    require(isinstance(scenes, list) and scenes and all(isinstance(s, str) and s for s in scenes),
            "enabled policy needs explicit scenes")
    counts = metrics.get("scene_cluster_counts", {})
    require(all(type(counts.get(s)) is int and counts[s] >= 10 for s in scenes), "insufficient per-scene clusters")
    agreement, lower, baseline = (metrics.get(k) for k in
                                 ("pairwise_agreement", "bootstrap_lower95", "baseline_pairwise_agreement"))
    require(number(agreement, .7, 1) and number(lower, 0, 1) and lower > .5,
            "quality agreement/uncertainty admission failed")
    require(number(baseline, 0, 1) and agreement > baseline, "quality does not beat the structural baseline")
    require(metrics.get("hard_constraints_passed") is True and metrics.get("severe_defect_non_regression") is True,
            "quality violates preservation or defect non-regression")
    require(number(metrics.get("coverage"), 0, 1) and number(metrics.get("rejection_rate"), 0, 1),
            "missing quality coverage/rejection measurements")
    head_file = asset_root / "quality_head.json"
    require(head_file.is_file() and head_file.stat().st_size <= 262_144, "missing or oversized quality head")
    require(policy.get("head_sha256") == sha256(head_file), "quality head hash mismatch")
    head = read_json(head_file)
    check_public(head)
    require(head.get("schema_version") == 1 and head.get("embedding_dim") == 512
            and head.get("hidden_dim") == 64, "quality architecture mismatch")
    require(head.get("enabled") is True and head.get("activation") == "relu"
            and head.get("feature_normalization") == "none", "quality head activation/normalization mismatch")
    require(head.get("encoder_sha256") == encoder_sha and head.get("feature_names") == FEATURE_NAMES,
            "quality feature/encoder contract mismatch")
    require(set(head.get("enabled_scenes", [])) == set(scenes), "quality head/policy scenes differ")
    require(head.get("weight_encoding") == "float32-le-base64", "unsupported quality weight encoding")
    try:
        raw = base64.b64decode(head.get("weights_f32_base64", ""), validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError("invalid quality weight encoding") from error
    require(len(raw) == WEIGHT_COUNT * 4, "quality weight count mismatch")
    require(all(math.isfinite(x) for x in struct.unpack(f"<{WEIGHT_COUNT}f", raw)), "non-finite quality weights")
    require(policy.get("crop_advice_enabled", False) is False,
            "crop advice is unsupported: a digital crop does not prove a reachable camera motion")
    if policy.get("crop_diagnostics_enabled", False):
        require(type(metrics.get("crop_source_cluster_count")) is int and metrics["crop_source_cluster_count"] >= 10
                and type(metrics.get("crop_teacher_pair_count")) is int and metrics["crop_teacher_pair_count"] > 0
                and number(metrics.get("crop_pairwise_agreement"), .7, 1), "unvalidated crop diagnostics")
        require(policy.get("crop_preprocessing") == head.get("crop_preprocessing") == "resize224_then_crop_resize224",
                "crop training/runtime preprocessing mismatch")
    return {"quality_enabled": True, "enabled_scenes": scenes, "head_bytes": head_file.stat().st_size}


def validate(asset_root):
    config = read_json(asset_root / "android_runtime_config.json")
    core = config.get("core_recommendation")
    if core is None:
        require(not (asset_root / "reference_structures.json").exists()
                and not (asset_root / "quality_policy.json").exists(), "unbound guidance assets")
        return {"core_profile": False}
    require(core.get("schema_version") == 1, "invalid core profile")
    for key, filename in (("structures", "reference_structures.json"), ("quality", "quality_policy.json")):
        artifact = core.get(key, {})
        require(artifact.get("file") == filename, "invalid guidance asset name")
        path = asset_root / filename
        require(path.is_file() and artifact.get("sha256") == sha256(path), "guidance asset hash mismatch: " + filename)
    metadata = [json.loads(line) for line in (asset_root / "template_index/templates.metadata.jsonl")
                .read_text(encoding="utf-8").splitlines() if line.strip()]
    model_sha = sha256(asset_root / config["runtime"]["model_file"])
    result = validate_structures(asset_root / "reference_structures.json", {r["template_id"]: r for r in metadata})
    result.update(validate_quality(asset_root / "quality_policy.json", asset_root, model_sha))
    return {"core_profile": True, **result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--asset-root", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(validate(args.asset_root), ensure_ascii=False))


if __name__ == "__main__":
    main()
