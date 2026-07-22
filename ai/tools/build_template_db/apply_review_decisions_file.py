#!/usr/bin/env python3
"""Apply human review decisions written in a Markdown review file."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REVIEW_TYPES = {
    "portrait": ("P", "portrait"),
    "landscape": ("L", "landscape"),
    "still_life": ("S", "still_life"),
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
                raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: line is not an object")
            rows.append(value)
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def short_id(prefix: str, number: int) -> str:
    return f"{prefix.upper()}{number:03d}"


def section_text(markdown: str, section_name: str) -> str:
    pattern = rf"^##\s+{re.escape(section_name)}\s+审核结果\s*$"
    match = re.search(pattern, markdown, flags=re.MULTILINE)
    if not match:
        return ""
    next_section = re.search(r"^##\s+", markdown[match.end():], flags=re.MULTILINE)
    end = match.end() + next_section.start() if next_section else len(markdown)
    return markdown[match.end():end]


def rejection_block(section: str) -> str:
    match = re.search(r"^\s*-\s*拒绝[:：](.*?)(?=^\s*-\s*备注[:：]|\Z)", section, flags=re.S | re.M)
    return match.group(1).strip() if match else ""


def ids_from_segment(segment: str, default_prefix: str) -> list[str]:
    ids: list[str] = []
    pattern = re.compile(
        r"(?i)([pls])?\s*(\d{1,3})(?:\s*[-–—~]\s*([pls])?\s*(\d{1,3}))?"
    )
    for match in pattern.finditer(segment):
        start_prefix = (match.group(1) or default_prefix).upper()
        start = int(match.group(2))
        end_prefix = (match.group(3) or start_prefix).upper()
        end = int(match.group(4) or start)
        if start_prefix != end_prefix:
            raise ValueError(f"mixed-prefix range is not supported: {match.group(0)}")
        if end < start:
            start, end = end, start
        ids.extend(short_id(start_prefix, number) for number in range(start, end + 1))
    return ids


def split_decision_segments(block: str) -> list[str]:
    cleaned = block.replace("；", ",").replace(";", ",").replace("\n", ",")
    return [segment.strip(" ,，") for segment in re.split(r"[，,]", cleaned) if segment.strip(" ,，")]


def is_crop_segment(segment: str) -> bool:
    return any(marker in segment for marker in ("裁剪", "抠", "拼", "组合", "文字教学"))


def has_explicit_reason(segment: str) -> bool:
    return is_crop_segment(segment) or any(
        marker in segment for marker in ("人像", "封面", "不对", "不好看", "不行", "删")
    )


def rejection_reason(segment: str) -> str:
    if "文字教学" in segment:
        return "needs_crop_instructional_text"
    if any(marker in segment for marker in ("裁剪", "抠", "拼", "组合")):
        return "needs_crop_multi_image"
    if "人像" in segment:
        return "wrong_category_person"
    if "封面" in segment:
        return "cover_or_text_graphic"
    if "不对" in segment:
        return "wrong_category"
    if "不好看" in segment:
        return "manual_not_selected"
    if "不行" in segment or "删" in segment:
        return "manual_quality_reject"
    return "manual_quality_reject"


def target_photo_type(segment: str, default_photo_type: str) -> str:
    if "放到人像" in segment or ("人像" in segment and is_crop_segment(segment)):
        return "portrait"
    return default_photo_type


def parse_decisions(markdown: str) -> dict[str, dict[str, str]]:
    decisions: dict[str, dict[str, str]] = {}

    def add_decision(item: str, action: str, reason: str, note: str, photo_type: str) -> None:
        if item in decisions:
            raise ValueError(f"duplicate review decision for {item}")
        decisions[item] = {
            "action": action,
            "reason": reason,
            "note": note,
            "source_photo_type": photo_type,
            "target_photo_type": target_photo_type(note, photo_type),
        }

    for section_name, (prefix, photo_type) in REVIEW_TYPES.items():
        block = rejection_block(section_text(markdown, section_name))
        pending: list[tuple[str, str]] = []
        for segment in split_decision_segments(block):
            ids = ids_from_segment(segment, prefix)
            if not ids:
                continue
            if not has_explicit_reason(segment):
                pending.extend((item, segment) for item in ids)
                continue
            action = "needs_crop" if is_crop_segment(segment) else "reject"
            reason = rejection_reason(segment)
            if pending:
                pending_action = action if action == "needs_crop" else "reject"
                pending_reason = reason if action == "needs_crop" else "manual_quality_reject"
                for item, note in pending:
                    add_decision(item, pending_action, pending_reason, note, photo_type)
                pending = []
            for item in ids:
                add_decision(item, action, reason, segment, photo_type)
        for item, note in pending:
            add_decision(item, "reject", "manual_quality_reject", note, photo_type)
    return decisions


def load_review_index(review_pack_root: Path) -> tuple[dict[str, dict[str, Any]], dict[str, str]]:
    short_to_index: dict[str, dict[str, Any]] = {}
    photo_to_short: dict[str, str] = {}
    for section_name in REVIEW_TYPES:
        index_path = review_pack_root / section_name / "review_index.jsonl"
        for row in load_jsonl(index_path):
            sid = str(row.get("short_id", "")).upper()
            photo_id = row.get("photo_id")
            if not sid or not isinstance(photo_id, str):
                raise ValueError(f"{index_path}: missing short_id or photo_id")
            if sid in short_to_index:
                raise ValueError(f"duplicate short_id in review packs: {sid}")
            short_to_index[sid] = row
            photo_to_short[photo_id] = sid
    return short_to_index, photo_to_short


def apply_review(
    records: list[dict[str, Any]],
    short_to_index: dict[str, dict[str, Any]],
    photo_to_short: dict[str, str],
    decisions: dict[str, dict[str, str]],
    reviewed_at: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    unknown = sorted(short_id for short_id in decisions if short_id not in short_to_index)
    if unknown:
        raise ValueError(f"unknown short IDs in review decisions: {', '.join(unknown)}")

    crop_candidates: list[dict[str, Any]] = []
    counts_by_type: dict[str, Counter[str]] = defaultdict(Counter)
    counts = Counter()

    for record in records:
        photo_id = str(record.get("photo_id", ""))
        photo_type = str(record.get("photo_type", ""))
        rights = record.setdefault("rights", {})
        rights["license"] = "local_authorized"
        rights["license_url"] = "local://ai-camera/rights/local_authorized"
        rights["attribution"] = rights.get("attribution") or "user_confirmed_complete_authorization"
        rights["verification_status"] = "user_declared_pending_review"
        review = record.setdefault("review", {})
        review["rights_status"] = "accepted"
        review["portrait_release_status"] = "accepted" if photo_type == "portrait" else "not_applicable"

        sid = photo_to_short.get(photo_id)
        if sid is None:
            status = str(review.get("quality_status"))
            counts[status] += 1
            counts_by_type[photo_type][status] += 1
            continue

        decision = decisions.get(sid)
        if decision is None:
            review["quality_status"] = "accepted"
            review["rejection_reason"] = None
            review["reviewed_at"] = reviewed_at
            counts["accepted"] += 1
            counts_by_type[photo_type]["accepted"] += 1
            continue

        review["quality_status"] = "rejected"
        review["rejection_reason"] = decision["reason"]
        review["reviewed_at"] = reviewed_at
        counts[decision["action"]] += 1
        counts["rejected"] += 1
        counts_by_type[photo_type]["rejected"] += 1
        counts_by_type[photo_type][decision["action"]] += 1
        if decision["action"] == "needs_crop":
            index_row = short_to_index[sid]
            crop_candidates.append(
                {
                    "short_id": sid,
                    "photo_id": photo_id,
                    "source_photo_type": photo_type,
                    "target_photo_type": decision["target_photo_type"],
                    "scene_category": record.get("scene_category"),
                    "file_path": record.get("file_path"),
                    "absolute_path": index_row.get("absolute_path"),
                    "reason": decision["reason"],
                    "review_note": decision["note"],
                    "instruction": "Create a clean single-photo crop before reconsidering this sample.",
                    "can_use_visible_tip_text_as_training_hint": decision["reason"] == "needs_crop_instructional_text",
                }
            )

    summary = {
        "schema_version": 1,
        "reviewed_at": reviewed_at,
        "total_records": len(records),
        "review_pack_records": len(photo_to_short),
        "decision_ids": len(decisions),
        "counts": dict(sorted(counts.items())),
        "counts_by_type": {
            key: dict(sorted(value.items())) for key, value in sorted(counts_by_type.items())
        },
        "crop_candidates": len(crop_candidates),
    }
    return records, crop_candidates, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("review_decisions", type=Path)
    parser.add_argument("--review-pack-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="Full reviewed manifest")
    parser.add_argument("--accepted-output", type=Path, required=True)
    parser.add_argument("--crop-output", type=Path, required=True)
    parser.add_argument("--summary-output", type=Path, required=True)
    parser.add_argument("--reviewed-at", default=None)
    args = parser.parse_args()

    try:
        reviewed_at = args.reviewed_at or datetime.now(timezone.utc).isoformat()
        records = load_jsonl(args.manifest)
        decisions = parse_decisions(args.review_decisions.read_text(encoding="utf-8"))
        short_to_index, photo_to_short = load_review_index(args.review_pack_root)
        reviewed, crop_candidates, summary = apply_review(
            records,
            short_to_index,
            photo_to_short,
            decisions,
            reviewed_at,
        )
        accepted = [
            record
            for record in reviewed
            if record.get("review", {}).get("quality_status") == "accepted"
        ]
        write_jsonl(args.output, reviewed)
        write_jsonl(args.accepted_output, accepted)
        write_jsonl(args.crop_output, crop_candidates)
        args.summary_output.parent.mkdir(parents=True, exist_ok=True)
        args.summary_output.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    print(f"Reviewed manifest: {args.output}")
    print(f"Accepted manifest: {args.accepted_output}")
    print(f"Crop candidates: {args.crop_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
