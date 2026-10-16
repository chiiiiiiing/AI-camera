#!/usr/bin/env python3
"""Evaluate one frozen candidate against the deployed baseline, once on test.

The selection file must already bind both ONNX hashes. No fitting or candidate
selection is performed here; failed test gates reject promotion rather than
triggering another search on this holdout.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import onnxruntime as ort

from benchmark_onnx_retrieval import encode, load_images, load_teachers, write_json
from evaluate_retrieval import evaluate_retrieval
from quantize_onnx_static import load_jsonl, require_disjoint, require_split, sha256_file


def gate(candidate: dict, baseline: dict) -> bool:
    new, old = candidate["raw"], baseline["raw"]
    metrics = [(new["scene_macro_recall_at_k"][k], old["scene_macro_recall_at_k"][k]) for k in ("1", "5")]
    metrics.extend([(new["scene_micro_recall_at_k"]["5"], old["scene_micro_recall_at_k"]["5"]),
                    (new["teacher_top5_overlap"], old["teacher_top5_overlap"])])
    return all(math.isfinite(value) and 0 <= value <= 1 for pair in metrics for value in pair) and all(
        after + 1e-12 >= before for after, before in metrics
    )


def validate_records(records: dict[str, list[dict]], base: Path) -> dict:
    """Verify identity and known group isolation; missing groups are unverified."""
    normalized = {}
    unknown_groups = {}
    for split in ("train", "test"):
        items = require_split(records[split], split)
        hashes = set()
        normalized[split] = []
        unknown_groups[split] = 0
        for item in items:
            digest = item.get("source_sha256")
            if not isinstance(digest, str) or not digest.strip():
                raise ValueError(f"{split}: missing source_sha256")
            digest = digest.strip().lower()
            if digest in hashes:
                raise ValueError(f"{split}: duplicate source_sha256")
            hashes.add(digest)
            normalized_item = dict(item)
            group = item.get("source_group_id")
            if group is not None and not isinstance(group, str):
                raise ValueError("source_group_id must be a string or null")
            if str(group or "").strip().lower() in {"", "unknown", "none", "null", "n/a"}:
                unknown_groups[split] += 1
                normalized_item["source_group_id"] = None
            normalized[split].append(normalized_item)
    require_disjoint(normalized["train"], normalized["test"], base, base)
    return {"photo_id_disjoint": True, "source_sha256_disjoint": True, "known_source_group_disjoint": True,
            "unknown_source_group_rows": unknown_groups, "unknown_groups_prove_independence": False,
            "near_duplicate_independence_verified": False}


def evaluate(args: argparse.Namespace) -> dict:
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        raise ValueError("preserve the first frozen evaluation; output directory must be empty")
    frozen = json.loads(args.selection.read_text(encoding="utf-8"))
    models = {"baseline": args.baseline_model, "candidate": args.candidate_model}
    for name, path in models.items():
        if sha256_file(path) != frozen[f"{name}_sha256"]:
            raise ValueError(f"{name} differs from frozen selection")
    if frozen.get("selection_split") != "val" or frozen.get("test_used_for_selection") is not False:
        raise ValueError("selection must precede test evaluation")
    records = {split: load_jsonl(args.dataset_dir / f"{split}.jsonl") for split in ("train", "test")}
    isolation = validate_records(records, args.dataset_dir)
    # Create the output before any test inference; a partial attempt cannot silently overwrite results.
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_json(args.output_dir / "frozen-selection.json", frozen)
    images = {split: load_images(items, 224) for split, items in records.items()}
    teachers = {split: load_teachers(items) for split, items in records.items()}
    reports = {}
    for name, path in models.items():
        options = ort.SessionOptions()
        options.intra_op_num_threads, options.inter_op_num_threads = 2, 1
        session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        vectors = {split: encode(session, pixels, 16)[0] for split, pixels in images.items()}
        reports[name] = evaluate_retrieval(vectors["test"], vectors["train"], records["test"], records["train"],
            teacher_query_embeddings=teachers["test"], teacher_gallery_embeddings=teachers["train"])
        write_json(args.output_dir / f"{name}.json", reports[name])
    result = {"schema_version": 1, "candidate_sha256": frozen["candidate_sha256"],
              "baseline_sha256": frozen["baseline_sha256"], "selection_sha256": sha256_file(args.selection),
              "model_test_non_regression_passed": gate(reports["candidate"], reports["baseline"]),
              "candidate": reports["candidate"], "baseline": reports["baseline"],
              "dataset_sha256": {split: sha256_file(args.dataset_dir / f"{split}.jsonl") for split in records},
              "dataset_isolation": isolation,
              "fit_performed": False, "test_used_for_selection": False, "android_device_tested": False,
              "limitations": ["unknown source groups remain; one perceptual train/test duplicate candidate identified",
                              "baseline historical quantization calibration included holdout images",
                              "scene labels are weak semantic labels, not aesthetic judgments"]}
    write_json(args.output_dir / "summary.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--baseline-model", type=Path, required=True)
    parser.add_argument("--candidate-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    result = evaluate(parser.parse_args())
    print(json.dumps({"model_test_non_regression_passed": result["model_test_non_regression_passed"],
                      "candidate": result["candidate"]["raw"], "baseline": result["baseline"]["raw"]}, indent=2))
    return 0 if result["model_test_non_regression_passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
