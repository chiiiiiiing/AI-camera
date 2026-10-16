#!/usr/bin/env python3
"""Dependency-free release gate for the optional relative framing head.

The existing retrieval/quality acceptance remains immutable. A disabled policy is
a valid result; changing a flag cannot promote a failed experiment to deployment.
"""
from __future__ import annotations

import base64
import math
from pathlib import Path
import re
import struct

from validate_core_recommendation_assets import check_public, number, read_json, require, sha256

PROTOCOL = "embedding512_rgb64_grid4_subject_scene_v1"
CROP_PREPROCESSING = "viewport_native416_halfpixel_round_crop_rgb224_halfpixel_round_v2"
LEGACY_CROP_PREPROCESSING = "viewport_native_max416_then_crop_bilinear224_v1"
POLICY = "framing_preference_policy.json"
HEAD = "framing_preference_head.json"
INPUT_DIM, HIDDEN_DIM = 605, 64
WEIGHT_COUNT = INPUT_DIM * HIDDEN_DIM + 2 * HIDDEN_DIM + 1
SCENES = {"portrait", "landscape", "food", "still_life"}


def hash_value(value):
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) is not None


def validate(asset_root: Path, config=None, encoder_sha=None):
    config = config if config is not None else read_json(asset_root / "android_runtime_config.json")
    profile = config.get("framing_preference")
    if profile is None:
        require(not any((asset_root / f).exists() for f in (POLICY, HEAD)), "unbound framing preference assets")
        return {"framing_preference_enabled": False, "framing_preference_policy_present": False}
    require(profile.get("schema_version") == 1, "invalid framing preference profile")
    descriptor = profile.get("policy", {})
    path = asset_root / POLICY
    require(descriptor.get("file") == POLICY and path.is_file() and 0 < path.stat().st_size <= 65536,
            "missing or oversized framing preference policy")
    require(descriptor.get("sha256") == sha256(path), "framing preference policy hash mismatch")
    policy = read_json(path)
    check_public(policy)
    require(policy.get("schema_version") == 1 and type(policy.get("enabled")) is bool,
            "invalid framing preference policy")
    require(policy.get("feature_protocol") == PROTOCOL
            and policy.get("crop_preprocessing") in {CROP_PREPROCESSING, LEGACY_CROP_PREPROCESSING},
            "framing preference preprocessing mismatch")
    encoder_sha = encoder_sha or sha256(asset_root / "student_model.onnx")
    require(policy.get("encoder_sha256") == encoder_sha, "framing preference encoder mismatch")
    require(policy.get("weak_teacher_only") is True and policy.get("crop_advice_enabled") is False
            and policy.get("physical_movement_prediction_enabled") is False,
            "crop preferences cannot certify human beauty or physical movement")
    require(hash_value(policy.get("training_protocol_sha256")) and hash_value(policy.get("acceptance_report_sha256")),
            "framing preference requires frozen protocol and acceptance provenance")
    require(policy.get("historical_holdout_excluded") is True and policy.get("previous_quality_acceptance_excluded") is True,
            "framing preference reused consumed acceptance")
    if not policy["enabled"]:
        require(not policy.get("enabled_scenes") and bool(policy.get("reasons")), "disabled framing policy needs reasons")
        require("head" not in profile and not (asset_root / HEAD).exists(), "failed framing head must remain local")
        return {"framing_preference_enabled": False, "framing_preference_policy_present": True,
                "framing_preference_reasons": policy["reasons"]}
    metrics = policy.get("metrics", {})
    require(policy.get("crop_preprocessing") == CROP_PREPROCESSING, "legacy framing preprocessing cannot be enabled")
    require(metrics.get("preprocessing_parity_passed") is True, "framing preference training/runtime parity not verified")
    require(type(metrics.get("acceptance_cluster_count")) is int and metrics["acceptance_cluster_count"] >= 30,
            "insufficient framing acceptance clusters")
    agreement, lower, baseline = (metrics.get(k) for k in
                                 ("pairwise_agreement", "bootstrap_lower95", "baseline_pairwise_agreement"))
    require(number(agreement, .7, 1) and number(lower, 0, 1) and lower > .5,
            "framing preference teacher agreement failed")
    require(number(baseline, 0, 1) and agreement > baseline, "framing preference must beat photography-rule baseline")
    keep = metrics.get("keep_current_pairwise_agreement")
    require(number(keep, 0, 1) and agreement > keep, "framing preference must beat keep-current baseline")
    scenes = policy.get("enabled_scenes")
    require(isinstance(scenes, list) and scenes and len(set(scenes)) == len(scenes) and set(scenes) <= SCENES,
            "invalid framing preference scenes")
    counts = metrics.get("scene_cluster_counts", {})
    require(all(type(counts.get(s)) is int and counts[s] >= 10 for s in scenes), "insufficient framing per-scene clusters")
    for scene in scenes:
        measured = policy.get("per_scene", {}).get(scene, {})
        score, rule, keep = (measured.get(k) for k in
                            ("pairwise_agreement", "baseline_pairwise_agreement", "keep_current_pairwise_agreement"))
        require(type(measured.get("acceptance_cluster_count")) is int and measured["acceptance_cluster_count"] >= 10
                and number(score, .7, 1) and number(rule, 0, 1) and score > rule
                and number(keep, 0, 1) and score > keep and number(measured.get("bootstrap_lower95"), 0, 1)
                and measured["bootstrap_lower95"] > .5, "framing preference failed enabled-scene admission")
    require(metrics.get("hard_constraints_passed") is True and metrics.get("severe_defect_non_regression") is True,
            "framing preference violates subject/defect protection")
    require(number(metrics.get("coverage"), 0, 1) and number(metrics.get("rejection_rate"), 0, 1)
            and abs(metrics["coverage"] + metrics["rejection_rate"] - 1) < 1e-6, "invalid framing coverage/rejection")
    require(number(policy.get("minimum_score_margin"), .1), "framing preference lacks an abstention margin")
    descriptor, path = profile.get("head", {}), asset_root / HEAD
    require(descriptor.get("file") == HEAD and path.is_file() and 0 < path.stat().st_size <= 262144,
            "missing or oversized framing preference head")
    require(descriptor.get("sha256") == policy.get("model_sha256") == sha256(path), "framing preference head hash mismatch")
    head = read_json(path)
    check_public(head)
    require(head.get("schema_version") == 1 and head.get("input_dim") == INPUT_DIM and head.get("hidden_dim") == HIDDEN_DIM
            and head.get("activation") == "relu" and head.get("feature_protocol") == PROTOCOL
            and head.get("crop_preprocessing") == CROP_PREPROCESSING and head.get("encoder_sha256") == encoder_sha
            and head.get("training_protocol_sha256") == policy["training_protocol_sha256"], "framing head architecture/provenance mismatch")
    require(head.get("weight_encoding") == "float32-le-base64", "unsupported framing weights")
    try:
        raw = base64.b64decode(head.get("weights_f32_base64", ""), validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError("invalid framing weight encoding") from error
    require(len(raw) == WEIGHT_COUNT * 4 and all(math.isfinite(x) for x in struct.unpack(f"<{WEIGHT_COUNT}f", raw)),
            "invalid framing weight count/values")
    for name, minimum in (("feature_mean", None), ("feature_std", .05)):
        values = head.get(name)
        require(isinstance(values, list) and len(values) == INPUT_DIM and all(number(x, minimum) for x in values),
                "invalid framing feature normalization")
    return {"framing_preference_enabled": True, "framing_preference_policy_present": True,
            "framing_preference_scenes": scenes, "framing_preference_head_bytes": path.stat().st_size}
