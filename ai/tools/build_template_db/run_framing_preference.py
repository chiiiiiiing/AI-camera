#!/usr/bin/env python3
"""Reproduce V1: freeze, encode, select, accept once, audit and fail closed.

All expensive model and image work stays local. Existing acceptance results are
verified and reused only when requested; a failed experiment is never tuned on
its consumed acceptance set.
The V1 Pillow preprocessing has no Android parity grant. This wrapper reproduces
that limitation; V2 pixel fixtures cannot be substituted for new V2 acceptance.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from audit_framing_preference import audit
from prepare_quality_teacher import sha256
from train_framing_preference import accept, build_pairs, extract, prepare, train, write_json


def run(args):
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name in ("HF_HOME", "TORCH_HOME", "XDG_CACHE_HOME", "CUDA_CACHE_PATH", "TRITON_CACHE_DIR", "TEMP", "TMP"):
        target = args.output_dir / "cache" / name.lower()
        target.mkdir(parents=True, exist_ok=True)
        os.environ[name] = str(target.resolve())
    if (args.output_dir / "acceptance.json").exists():
        if not args.reuse_completed:
            raise ValueError("acceptance is immutable; explicitly reuse completed artifacts")
        policy = json.loads((args.output_dir / "framing_preference_policy.json").read_text(encoding="utf-8"))
        for field, path in (("acceptance_report_sha256", args.output_dir / "acceptance.json"),
                            ("model_sha256", args.output_dir / "framing_preference_head.json"),
                            ("training_protocol_sha256", args.output_dir / "frozen-protocol.json"),
                            ("encoder_sha256", args.encoder)):
            if policy.get(field) != sha256(path):
                raise ValueError(f"completed experiment changed: {field}")
        return audit(args.output_dir)
    rows, _ = prepare(args)
    supplemental = {"schema_version": 1, "frozen_before_acceptance": True,
                    "protocol_sha256": sha256(args.output_dir / "frozen-protocol.json"),
                    "additional_gate": "crop_cluster_macro_agreement must strictly exceed keep_current_cluster_macro_agreement",
                    "keep_current_definition": "predict current preferred for every stable crop pair; sign<0 is correct",
                    "acceptance_may_not_be_rerun": True}
    supplemental_path = args.output_dir / "supplemental-protocol.json"
    if supplemental_path.exists() and json.loads(supplemental_path.read_text(encoding="utf-8")) != supplemental:
        raise ValueError("supplemental protocol changed")
    write_json(supplemental_path, supplemental)
    vectors, teacher, records = extract(args, rows)
    pairs, attempted = build_pairs(records, teacher)
    write_json(args.output_dir / "pairs.json", pairs)
    head, _ = train(args, vectors, pairs, records)
    accept(args, head, vectors, pairs, records, attempted)
    return audit(args.output_dir)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-manifest", "previous-protocol", "previous-views", "object-structures", "encoder", "vendor-dir", "deps-dir", "output-dir"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--reuse-completed", action="store_true")
    print(json.dumps(run(parser.parse_args())))
