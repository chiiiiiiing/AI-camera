#!/usr/bin/env python3
"""Run the current visual-clean pipeline gates and report pending artifacts."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import audit_dataset
import dataset_status
import validate_annotations
import validate_embeddings
import validate_manifest
import validate_preprocessed
import validate_template_metadata


def jsonl_records(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON in {path} on line {line_number}: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{path} line {line_number} is not an object")
            rows.append(value)
    return rows


def pass_check(name: str, summary: dict[str, Any] | None = None) -> dict[str, Any]:
    return {"name": name, "status": "pass", "summary": summary or {}}


def pending_check(name: str, reason: str) -> dict[str, Any]:
    return {"name": name, "status": "pending", "reason": reason}


def fail_check(name: str, errors: list[str]) -> dict[str, Any]:
    return {"name": name, "status": "fail", "errors": errors}


def validate_manifest_records(path: Path) -> list[str]:
    errors: list[str] = []
    for line_number, record in enumerate(jsonl_records(path), 1):
        for message in validate_manifest.validate_core(record):
            errors.append(f"line {line_number}: {message}")
    return errors


def validate_annotation_records(path: Path) -> list[str]:
    errors: list[str] = []
    for line_number, record in enumerate(jsonl_records(path), 1):
        for message in validate_annotations.validate_core(record):
            errors.append(f"line {line_number}: {message}")
    return errors


def run_checks(root: Path, allow_missing_embeddings: bool = True) -> dict[str, Any]:
    raw = root / "tools" / "raw_photos"
    preprocessed = root / "tools" / "preprocessed"
    manifest = raw / "manifest.visual-clean.jsonl"
    jobs = preprocessed / "jobs.visual-clean.jsonl"
    annotations = preprocessed / "annotations.visual-clean.jsonl"
    metadata = preprocessed / "template_metadata.visual-clean.jsonl"
    checks: list[dict[str, Any]] = []

    if not manifest.exists():
        checks.append(fail_check("manifest", [f"missing {manifest}"]))
    else:
        errors = validate_manifest_records(manifest)
        if errors:
            checks.append(fail_check("manifest_schema", errors))
        else:
            checks.append(pass_check("manifest_schema", {"records": len(jsonl_records(manifest))}))
        try:
            audit_errors, audit_summary = audit_dataset.audit_records(
                audit_dataset.load_manifest(manifest),
                manifest.parent,
            )
        except RuntimeError as exc:
            checks.append(fail_check("manifest_audit", [str(exc)]))
        else:
            checks.append(
                fail_check("manifest_audit", audit_errors)
                if audit_errors
                else pass_check("manifest_audit", audit_summary)
            )

    if not jobs.exists():
        checks.append(fail_check("preprocessed_jobs", [f"missing {jobs}"]))
    else:
        preprocessed_errors, preprocessed_summary = validate_preprocessed.validate_jobs(jobs)
        checks.append(
            fail_check("preprocessed_records", preprocessed_errors)
            if preprocessed_errors
            else pass_check("preprocessed_records", preprocessed_summary)
        )
        embedding_errors, embedding_summary = validate_embeddings.validate_jobs(
            jobs,
            allow_missing=allow_missing_embeddings,
        )
        checks.append(
            fail_check("embeddings", embedding_errors)
            if embedding_errors
            else pass_check("embeddings", embedding_summary)
        )

    annotation_rows = jsonl_records(annotations)
    if annotation_rows:
        annotation_errors = validate_annotation_records(annotations)
        checks.append(
            fail_check("annotations", annotation_errors)
            if annotation_errors
            else pass_check("annotations", {"records": len(annotation_rows)})
        )
    else:
        checks.append(pending_check("annotations", f"missing or empty {annotations}"))

    metadata_rows = jsonl_records(metadata)
    if metadata_rows:
        script_dir = Path(__file__).resolve().parent
        metadata_errors, metadata_summary = validate_template_metadata.validate_jsonl(
            metadata,
            script_dir / "schema" / "template_metadata.schema.json",
        )
        checks.append(
            fail_check("template_metadata", metadata_errors)
            if metadata_errors
            else pass_check("template_metadata", metadata_summary)
        )
    else:
        checks.append(pending_check("template_metadata", f"missing or empty {metadata}"))

    status = dataset_status.build_status(root)
    failures = sum(1 for check in checks if check["status"] == "fail")
    pending = sum(1 for check in checks if check["status"] == "pending")
    return {
        "schema_version": 1,
        "root": str(root),
        "checks": checks,
        "summary": {
            "checks": len(checks),
            "failures": failures,
            "pending": pending,
        },
        "dataset_status": status,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--require-embeddings", action="store_true")
    args = parser.parse_args()
    try:
        report = run_checks(args.root, allow_missing_embeddings=not args.require_embeddings)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 1 if report["summary"]["failures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
