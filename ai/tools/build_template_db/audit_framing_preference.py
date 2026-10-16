#!/usr/bin/env python3
"""Add conservative deployment checks without rerunning a consumed acceptance set.

The independent keep-current gate is declared in supplemental-protocol.json
before acceptance. The original acceptance report remains byte-for-byte intact.
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from prepare_quality_teacher import sha256
from train_framing_preference import write_json


def keep_current_accuracy(pairs, scene=None):
    groups = defaultdict(list)
    for pair in pairs:
        if pair["split"] == "acceptance" and pair["kind"] == "crop" and (scene is None or pair["scene"] == scene):
            groups[pair["source_group_id"]].append(float(pair["sign"] < 0))
    return sum(sum(v) / len(v) for v in groups.values()) / len(groups) if groups else 0.


def audit(directory: Path):
    def read(name):
        return json.loads((directory / name).read_text(encoding="utf-8"))
    supplemental = read("supplemental-protocol.json")
    if supplemental["protocol_sha256"] != sha256(directory / "frozen-protocol.json"):
        raise ValueError("supplemental gate references another protocol")
    acceptance_path = directory / "acceptance.json"
    acceptance_hash = sha256(acceptance_path)
    acceptance, pairs = read("acceptance.json"), read("pairs.json")
    policy = read("framing_preference_policy.json")
    if policy["acceptance_report_sha256"] != acceptance_hash:
        raise ValueError("acceptance artifact changed")
    snapshot = directory / "framing_preference_policy.before-supplemental.json"
    if not snapshot.exists():
        write_json(snapshot, policy)
    else:
        policy = read(snapshot.name)
    policy["metrics"]["keep_current_pairwise_agreement"] = keep_current_accuracy(pairs)
    policy["per_scene"] = acceptance["per_scene"]
    for scene, metrics in policy["per_scene"].items():
        metrics["keep_current_pairwise_agreement"] = keep_current_accuracy(pairs, scene)
    keep_pass = policy["metrics"]["pairwise_agreement"] > policy["metrics"]["keep_current_pairwise_agreement"]
    policy["gates"]["beats_keep_current_baseline"] = keep_pass
    # This frozen experiment used Pillow thumbnail/resize. Android's explicit
    # half-pixel integer kernel is a different protocol. A head-only numeric
    # fixture does not establish end-to-end preprocessing equivalence.
    policy["metrics"]["preprocessing_parity_passed"] = False
    policy["gates"]["preprocessing_parity"] = False
    policy["preprocessing_audit"] = {
        "training": "Pillow thumbnail max416 BILINEAR, crop, Pillow BILINEAR224",
        "runtime_required": "viewport_native416_halfpixel_round_crop_rgb224_halfpixel_round_v2",
        "current_embedding_training": "independently encoded thumbnail416 current view",
        "current_embedding_old_runtime": "retrieval embedding from original viewport to224",
        "passed": False, "old_acceptance_reexecuted": False,
        "note": "V2 preprocessing fixtures do not validate or relabel the V1 trained model."}
    policy["enabled_scenes"] = [scene for scene in policy["enabled_scenes"]
                                if policy["per_scene"][scene]["pairwise_agreement"] > policy["per_scene"][scene]["keep_current_pairwise_agreement"]]
    policy["enabled"] = False
    policy["enabled_scenes"] = []
    policy["reasons"] = [key for key, passed in policy["gates"].items() if not passed]
    policy["supplemental_protocol_sha256"] = sha256(directory / "supplemental-protocol.json")
    policy["metric_semantics"] = acceptance["metric_semantics"]
    policy["teacher_stable_crop_coverage"] = acceptance["teacher_stable_crop_coverage"]
    write_json(directory / "framing_preference_policy.json", policy)
    result = {"schema_version": 1, "acceptance_unchanged_sha256": acceptance_hash,
              "acceptance_reexecuted": False, "supplemental_protocol_sha256": policy["supplemental_protocol_sha256"],
              "keep_current_pairwise_agreement": policy["metrics"]["keep_current_pairwise_agreement"],
              "beats_keep_current_baseline": keep_pass, "preprocessing_parity_passed": False, "enabled": policy["enabled"],
              "final_policy_sha256": sha256(directory / "framing_preference_policy.json")}
    if sha256(acceptance_path) != acceptance_hash:
        raise ValueError("audit modified original acceptance artifact")
    write_json(directory / "deployment-audit.json", result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment-dir", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(audit(args.experiment_dir)))
