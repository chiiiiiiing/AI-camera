#!/usr/bin/env python3
"""Run local reference annotation, bounded quality training, and verified packaging.

Completed acceptance reports are reused, never re-scored. Outputs and dependency
environments stay under this repository's local-output directory. --deploy copies
only validated public sidecars and updates their hashes; photographs never ship.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

from validate_core_recommendation_assets import validate, validate_quality


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                    encoding="utf-8", newline="\n")


def inside_output(project, path):
    path = path.resolve()
    if not path.is_relative_to(project / "local-output"):
        raise ValueError("run/environment/output must remain inside project local-output")
    return path


def verify_completed_quality(directory, manifest, encoder):
    protocol = read(directory / "frozen-protocol.json")
    if protocol["source_manifest_sha256"] != sha256(manifest) or protocol["encoder_sha256"] != sha256(encoder):
        raise ValueError("completed quality inputs changed; do not reuse frozen acceptance")
    acceptance = read(directory / "acceptance.json")
    if acceptance["frozen_protocol_sha256"] != sha256(directory / "frozen-protocol.json"):
        raise ValueError("frozen quality protocol hash changed")
    policy = read(directory / "quality_policy.json")
    if policy != acceptance["policy"]:
        raise ValueError("quality policy differs from frozen acceptance")
    if policy["head_sha256"] != sha256(directory / "quality_head.json"):
        raise ValueError("quality candidate weights changed after acceptance")
    return policy


def package(assets, data, quality_policy, quality_directory, candidate):
    candidate.mkdir(parents=True, exist_ok=True)
    # Copy the small formal bundle, never private photos, teacher weights or checkpoints.
    for filename in ("student_model.onnx", "android_runtime_config.json", "android_onnx_runtime_manifest.json",
                     "model_evaluation.json", "model_selection.json"):
        source = assets / filename
        if source.exists():
            shutil.copy2(source, candidate / filename)
    shutil.copytree(assets / "template_index", candidate / "template_index", dirs_exist_ok=True)
    shutil.copy2(data / "reference_structures.json", candidate / "reference_structures.json")
    shutil.copy2(quality_policy, candidate / "quality_policy.json")
    policy = read(quality_policy)
    if policy["enabled"]:
        shutil.copy2(quality_directory / "quality_head.json", candidate / "quality_head.json")
    elif (candidate / "quality_head.json").exists():
        (candidate / "quality_head.json").unlink()
    config = read(candidate / "android_runtime_config.json")
    config["postprocess"].update(search_top_k=32, structure_top_k=8)
    config["ranking"]["notes"] = ("Top32 semantic recall, at most Top8 measured-structure compatibility; "
        "single-face targets only. Similarity, geometry and raw preference scores are separate evidence.")
    config["core_recommendation"] = {"schema_version": 1,
        "structures": {"file": "reference_structures.json", "sha256": sha256(candidate / "reference_structures.json")},
        "quality": {"file": "quality_policy.json", "sha256": sha256(candidate / "quality_policy.json")},
        "ready_min_independent_observations": 3, "ready_min_span_ms": 750,
        "quality_enabled": policy["enabled"], "human_aesthetic_improvement_verified": False,
        "physical_device_performance_verified": False}
    write(candidate / "android_runtime_config.json", config)
    result = validate(candidate)
    validate_quality(candidate / "quality_policy.json", candidate, sha256(candidate / "student_model.onnx"))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--quality-dir", type=Path)
    parser.add_argument("--quality-source-manifest", type=Path)
    parser.add_argument("--model-python", type=Path)
    parser.add_argument("--reuse-completed", action="store_true", help="verify completed evidence without annotation or fitting")
    parser.add_argument("--deploy", action="store_true", help="publish validated sidecars into the existing Android bundle")
    args = parser.parse_args()
    project = args.project_root.resolve()
    run_dir = inside_output(project, args.run_dir)
    data = inside_output(project, args.data_dir or run_dir / "data")
    quality = inside_output(project, args.quality_dir or run_dir / "quality/experiment")
    manifest = (args.quality_source_manifest or data / "source_manifest.jsonl").resolve()
    inside_output(project, manifest)
    assets = project / "aicamera-app/app/src/main/assets/ai/v9"
    encoder = assets / "student_model.onnx"
    run_dir.mkdir(parents=True, exist_ok=True)
    temporary = run_dir / "tmp"
    temporary.mkdir(exist_ok=True)
    environment = {**os.environ, "TEMP": str(temporary), "TMP": str(temporary), "TMPDIR": str(temporary),
                   "PYTHONUTF8": "1", "PIP_CACHE_DIR": str(run_dir / "pip-cache"),
                   "TORCH_HOME": str(run_dir / "torch-cache"), "HF_HOME": str(run_dir / "hf-cache")}
    log_dir = run_dir / "orchestration-logs"
    log_dir.mkdir(exist_ok=True)

    def command(name, argv, retries=0):
        for attempt in range(retries+1):
            log = log_dir / f"{name}-{time.time_ns()}.log"
            print(json.dumps({"stage": name, "attempt": attempt+1, "log": str(log)}), flush=True)
            with log.open("w", encoding="utf-8") as stream:
                result = subprocess.run([str(x) for x in argv], cwd=project, env=environment,
                                        stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode == 0:
                return
        raise RuntimeError(f"{name} failed; evidence retained at {log}")

    if not args.reuse_completed:
        data_python = data / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        if not data_python.exists():
            command("create-data-environment", [sys.executable, "-m", "venv", data / ".venv"])
        # A previous pip failure may leave a valid python.exe but incomplete dependencies.
        # Pinned pip installation is idempotent and repairs that state on the next run.
        command("install-data-dependencies", [data_python, "-m", "pip", "install", "-r",
            project / "ai/tools/build_template_db/requirements-reference-structures.txt"], retries=1)
        command("annotate", [data_python, project / "ai/tools/build_template_db/build_reference_structures.py",
                "--project-root", project, "--output-dir", data, "--resume"])

    data_report = read(data / "report.json")
    for name, key in (("reference_structures.json", "reference_structures_sha256"),
                      ("source_manifest.jsonl", "source_manifest_sha256")):
        if sha256(data / name) != data_report[key]:
            raise ValueError("completed structure output changed: " + name)
    quality_policy = quality / "quality_policy.json"
    if (quality / "acceptance.json").exists():
        verify_completed_quality(quality, manifest, encoder)
    elif args.reuse_completed:
        raise ValueError("no completed quality experiment to reuse")
    else:
        default_python = project / "local-output/model-optimization-2026-10-14/.gpu-venv/Scripts/python.exe"
        model_python = args.model_python or (default_python if default_python.exists() else Path(sys.executable))
        vendor, deps = quality.parent / "vendor", quality.parent / "deps"
        try:
            command("install-teacher-dependencies", [model_python, "-m", "pip", "install", "--target", deps,
                "-r", project / "ai/tools/build_template_db/requirements-quality.txt"], retries=1)
            command("prepare-teacher", [model_python, project / "ai/tools/build_template_db/prepare_quality_teacher.py",
                "--output-dir", vendor])
            argv = [model_python, project / "ai/tools/build_template_db/train_guidance_quality.py",
                    "--source-manifest", manifest, "--encoder", encoder, "--output-dir", quality,
                    "--vendor-dir", vendor, "--deps-dir", deps]
            if (quality / "frozen-protocol.json").exists():
                argv.append("--resume-features")
            command("train-and-accept-once", argv)
            verify_completed_quality(quality, manifest, encoder)
        except RuntimeError as failure:
            # Infrastructure failure is distinct from a measured model rejection.
            quality_policy = run_dir / "quality-unavailable-policy.json"
            write(quality_policy, {"schema_version": 1, "enabled": False, "enabled_scenes": [],
                "encoder_sha256": sha256(encoder), "reasons": ["quality_stage_failed"],
                "evaluation_completed": False, "failure_stage": str(failure).split(" failed;")[0]})
    candidate = run_dir / "candidate-assets"
    result = package(assets, data, quality_policy, quality, candidate)
    command("validate-complete-candidate", [sys.executable, project / "ai/tools/validate_android_v9_assets.py",
                                          "--asset-root", candidate])
    if args.deploy:
        for name in ("reference_structures.json", "quality_policy.json", "android_runtime_config.json"):
            shutil.copy2(candidate / name, assets / name)
        if result["quality_enabled"]:
            shutil.copy2(candidate / "quality_head.json", assets / "quality_head.json")
        elif (assets / "quality_head.json").exists():
            (assets / "quality_head.json").unlink()
    write(run_dir / "package-report.json", {"schema_version": 1, **result, "deployed": args.deploy,
        "encoder_sha256": sha256(encoder), "data_report_sha256": sha256(data / "report.json"),
        "quality_policy_sha256": sha256(quality_policy), "human_aesthetic_improvement_verified": False,
        "phone_performance_verified": False})
    print(json.dumps({**result, "deployed": args.deploy}, ensure_ascii=False))


if __name__ == "__main__":
    main()
