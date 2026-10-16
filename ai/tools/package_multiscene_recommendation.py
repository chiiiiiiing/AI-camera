#!/usr/bin/env python3
"""Stage, validate and optionally deploy the local multi-scene recommendation bundle.

No original photographs or experimental weights are copied. The frozen retrieval
encoder and its acceptance report remain unchanged. All staging stays in local-output.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil

from validate_android_v9_assets import validate as validate_assets
from validate_core_recommendation_assets import validate as validate_core
from validate_multiscene_assets import FILES, MODEL_BUDGET, sha256


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                    encoding="utf-8", newline="\n")


def package(project, detector_dir, references, staging, deploy=False):
    project, detector_dir, references, staging = (Path(p).resolve() for p in
                                                 (project, detector_dir, references, staging))
    output = project / "local-output"
    if not all(p.is_relative_to(output) for p in (detector_dir, references, staging)):
        raise ValueError("model, annotations and staging must stay inside project/local-output")
    assets = project / "aicamera-app/app/src/main/assets/ai/v9"
    if staging in (output, detector_dir) or staging.is_relative_to(detector_dir) or any(
            source.is_relative_to(staging) for source in (detector_dir, references)):
        raise ValueError("staging must be a separate child of local-output")
    if staging.exists() and (not staging.is_dir() or any(staging.iterdir())):
        raise ValueError("staging must be empty; preserve earlier experiment evidence")
    shutil.copytree(assets, staging, dirs_exist_ok=True)
    sources = {
        "object_detector.onnx": detector_dir / "object_detection_nanodet_2022nov.onnx",
        "object_detector_manifest.json": detector_dir / "object_detector_manifest.json",
        "LICENSE.object_detector.txt": detector_dir / "LICENSE",
        "multi_scene_references.json": references,
    }
    for name, source in sources.items():
        shutil.copy2(source, staging / name)
        if name.endswith(".json"):
            artifact = staging / name
            artifact.write_bytes(artifact.read_bytes().replace(b"\r\n", b"\n"))
    config = json.loads((staging / "android_runtime_config.json").read_text(encoding="utf-8"))
    config["multi_scene_recommendation"] = {
        "schema_version": 1, "enabled": True,
        **{key: {"file": filename, "sha256": sha256(staging / filename)} for key, filename in FILES.items()},
        "detector_budget_bytes": MODEL_BUDGET,
        "reference_limit": 8,
        "human_aesthetic_improvement_verified": False,
        "physical_device_performance_verified": False,
    }
    config["ranking"]["notes"] = (
        "Top32 semantic recall, at most Top8 measured face/object compatibility and 3x3 scene layout. "
        "Only fresh, unambiguous observed geometry can support one-step framing actions. "
        "Layout similarity is not aesthetic quality or a measured horizon.")
    config["android_budget"]["auxiliary_detector_bytes"] = (staging / "object_detector.onnx").stat().st_size
    config["android_budget"]["combined_model_bytes"] = sum((staging / name).stat().st_size for name in
                                                          ("student_model.onnx", "object_detector.onnx"))
    write(staging / "android_runtime_config.json", config)
    validate_assets(staging)
    validation = validate_core(staging)
    if deploy:
        for name in (*sources, "android_runtime_config.json"):
            shutil.copy2(staging / name, assets / name)
        validate_assets(assets)
    report = {"deployed": deploy, "validation": validation,
              "retrieval_encoder_sha256": sha256(staging / "student_model.onnx"),
              "model_evaluation_sha256": sha256(staging / "model_evaluation.json")}
    write(staging.parent / "package_report.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--detector-dir", type=Path, required=True)
    parser.add_argument("--references", type=Path, required=True)
    parser.add_argument("--staging", type=Path, required=True)
    parser.add_argument("--deploy", action="store_true")
    args = parser.parse_args()
    print(json.dumps(package(args.project_root, args.detector_dir, args.references, args.staging, args.deploy), ensure_ascii=False))


if __name__ == "__main__":
    main()
