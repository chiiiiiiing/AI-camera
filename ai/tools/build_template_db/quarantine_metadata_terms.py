#!/usr/bin/env python3
"""Reject accepted records whose title or tags contain excluded terms."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_EXCLUDED_TERMS = {
    "painting", "illustration", "drawing", "sketch", "artwork",
    "poster", "sculpture", "render", "cgi",
    "sexy", "sensual", "erotic", "nude", "naked", "lingerie",
    "boudoir", "pinup", "glamour", "steamy", "sheer", "topless",
    "shirtless", "cleavage",
}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"line {line_number} is not an object")
            rows.append(value)
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def load_terms(config_path: Path | None) -> set[str]:
    if not config_path:
        return set(DEFAULT_EXCLUDED_TERMS)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    terms = config.get("defaults", {}).get("excluded_media_terms", [])
    if not isinstance(terms, list):
        raise ValueError("defaults.excluded_media_terms must be a list")
    return {str(term).lower() for term in terms}


def source_terms(record: dict[str, Any]) -> set[str]:
    source = record.get("source", {})
    values = [str(source.get("title") or "")]
    values.extend(str(tag) for tag in source.get("tags", []) if tag)
    words: set[str] = set()
    for value in values:
        normalized = "".join(character.lower() if character.isalnum() else " " for character in value)
        words.update(normalized.split())
    return words


def quarantine_records(
    records: list[dict[str, Any]],
    excluded_terms: set[str],
    reviewed_at: str | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    reviewed_at = reviewed_at or datetime.now(timezone.utc).isoformat()
    summary = {
        "records": len(records),
        "metadata_failures": 0,
        "newly_rejected": 0,
        "matched_terms": {},
    }
    for record in records:
        review = record.setdefault("review", {})
        if review.get("quality_status") != "accepted":
            continue
        matched = sorted(source_terms(record) & excluded_terms)
        if not matched:
            continue
        summary["metadata_failures"] += 1
        summary["newly_rejected"] += 1
        for term in matched:
            matched_terms = summary["matched_terms"]
            matched_terms[term] = matched_terms.get(term, 0) + 1
        review["quality_status"] = "rejected"
        review["rejection_reason"] = f"metadata_excluded_terms:{','.join(matched)}"
        review["reviewed_at"] = reviewed_at
    return records, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        records, summary = quarantine_records(load_jsonl(args.manifest), load_terms(args.config))
        write_jsonl(args.output, records)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    print(f"Wrote: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
