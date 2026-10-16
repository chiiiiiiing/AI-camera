#!/usr/bin/env python3
"""Validate the optional new preference policy in a separate local staging bundle."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil

from validate_core_recommendation_assets import validate, read_json, sha256
from validate_framing_preference_assets import POLICY, HEAD


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8", newline="\n")


def package(project, experiment, staging, deploy=False):
    project, experiment, staging = (Path(p).resolve() for p in (project, experiment, staging))
    output = project / "local-output"
    if not all(p.is_relative_to(output) and p != output for p in (experiment, staging)):
        raise ValueError("experiment and staging must be children of project/local-output")
    if staging == experiment or staging.is_relative_to(experiment) or experiment.is_relative_to(staging):
        raise ValueError("staging and experiment must be separate")
    if staging.exists() and (not staging.is_dir() or any(staging.iterdir())):
        raise ValueError("staging must be empty; keep previous experiment evidence")
    assets = project / "aicamera-app/app/src/main/assets/ai/v9"
    shutil.copytree(assets, staging)
    policy = read_json(experiment / POLICY)
    write(staging / POLICY, policy)
    config = read_json(staging / "android_runtime_config.json")
    profile = {"schema_version": 1, "policy": {"file": POLICY, "sha256": sha256(staging / POLICY)}}
    if policy["enabled"]:
        shutil.copy2(experiment / HEAD, staging / HEAD)
        profile["head"] = {"file": HEAD, "sha256": sha256(staging / HEAD)}
    elif (staging / HEAD).exists():
        # Exact reproducible asset in our newly created staging folder, never user data.
        (staging / HEAD).unlink()
    config["framing_preference"] = profile
    config["android_budget"]["framing_preference_head_bytes"] = (staging / HEAD).stat().st_size if policy["enabled"] else 0
    write(staging / "android_runtime_config.json", config)
    result = validate(staging)
    if deploy:
        for name in (POLICY, "android_runtime_config.json"):
            shutil.copy2(staging / name, assets / name)
        if policy["enabled"]:
            shutil.copy2(staging / HEAD, assets / HEAD)
        elif (assets / HEAD).exists():
            (assets / HEAD).unlink()
        validate(assets)
    report = {"deployed": deploy, "validation": result, "retrieval_encoder_sha256": sha256(staging / "student_model.onnx")}
    write(staging.parent / "framing-package-report.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--experiment", type=Path, required=True)
    parser.add_argument("--staging", type=Path, required=True)
    parser.add_argument("--deploy", action="store_true")
    args = parser.parse_args()
    print(json.dumps(package(args.project_root, args.experiment, args.staging, args.deploy), ensure_ascii=False))
