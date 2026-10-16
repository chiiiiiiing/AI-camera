#!/usr/bin/env python3
"""Collect portrait candidates from approved public photo APIs.

This is not a social-feed scraper. It intentionally avoids login-gated pages,
anti-bot bypasses, private accounts, profile scraping, and platforms blocked by
`config/source_policy.json`. The output is a candidate manifest only: every
portrait keeps rights and portrait-release review pending until a human verifies
the source page, model/property rights, and commercial-use boundary.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from collect_public_photos import CollectionError, collect


PORTRAIT_CATEGORIES = (
    "closeup_portrait",
    "indoor_portrait",
    "outdoor_nature_portrait",
    "urban_street_portrait",
    "golden_hour_portrait",
    "couple_portrait",
    "group_portrait",
    "travel_portrait",
)


def build_parser() -> argparse.ArgumentParser:
    script_dir = Path(__file__).resolve().parent
    default_output_dir = script_dir.parent / "raw_photos" / "portrait_candidates"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=script_dir / "config" / "portrait_search_queries.json")
    parser.add_argument("--source-policy", type=Path, default=script_dir / "config" / "source_policy.json")
    parser.add_argument("--output-dir", type=Path, default=default_output_dir)
    parser.add_argument("--manifest", type=Path, default=default_output_dir / "manifest.jsonl")
    parser.add_argument(
        "--provider",
        action="append",
        help=(
            "Approved provider: openverse, wikimedia_commons, flickr, or all. "
            "Flickr uses the official API and requires FLICKR_API_KEY."
        ),
    )
    parser.add_argument(
        "--category",
        action="append",
        choices=PORTRAIT_CATEGORIES,
        help="Collect only this portrait scene_category; repeatable. Defaults to all portrait categories.",
    )
    parser.add_argument("--limit-per-query", type=int, default=5, help="Accepted photos per query per provider")
    parser.add_argument("--page-size", type=int, default=30)
    parser.add_argument("--max-candidates-per-query", type=int, default=180)
    parser.add_argument("--max-bytes", type=int, default=40 * 1024 * 1024)
    parser.add_argument("--request-delay", type=float, default=0.5)
    parser.add_argument("--error-delay", type=float, default=5.0)
    parser.add_argument("--dry-run", action="store_true", help="Validate configuration without network access")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.limit_per_query < 1 or args.max_candidates_per_query < 1 or not 1 <= args.page_size <= 100:
        parser.error("limits must be positive and page-size must be 1..100")
    try:
        return collect(args)
    except (CollectionError, OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
