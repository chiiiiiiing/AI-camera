#!/usr/bin/env python3
"""Collect rights-traceable CC0/PDM photo candidates from Openverse.

This collector intentionally excludes sources whose current terms require
separate authorization for machine-learning or bulk-download use.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


API_URL = "https://api.openverse.org/v1/images/"
USER_AGENT = "ai-camera-template-builder/0.1 (research dataset collector)"
ALLOWED_PHOTO_TYPES = {"portrait", "landscape", "still_life"}
DEFAULT_EXCLUDED_MEDIA_TERMS = {
    "painting", "illustration", "drawing", "sketch", "artwork",
    "poster", "sculpture", "render", "cgi",
}
PIL_FORMAT_EXTENSIONS = {
    "JPEG": ".jpg",
    "PNG": ".png",
    "WEBP": ".webp",
    "TIFF": ".tif",
}


class CollectionError(RuntimeError):
    """Raised when a collection request or downloaded asset is invalid."""


@dataclass(frozen=True)
class ResumeState:
    provider_ids: frozenset[str]
    hashes: frozenset[str]


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def validate_config(config: dict[str, Any]) -> None:
    if config.get("version") != 1:
        raise ValueError("search config version must be 1")
    defaults = config.get("defaults")
    categories = config.get("categories")
    if not isinstance(defaults, dict) or not isinstance(categories, list) or not categories:
        raise ValueError("config requires defaults and at least one category")
    licenses = defaults.get("licenses")
    if not isinstance(licenses, list) or not licenses:
        raise ValueError("defaults.licenses must be a non-empty list")
    if not set(licenses).issubset({"cc0", "pdm"}):
        raise ValueError("the initial collector only permits CC0 and PDM candidates")
    for field in ("min_long_edge", "min_short_edge", "preferred_long_edge", "preferred_short_edge"):
        if not isinstance(defaults.get(field), int) or defaults[field] < 1:
            raise ValueError(f"defaults.{field} must be a positive integer")
    if not isinstance(defaults.get("mobile_aspect_ratio"), (int, float)):
        raise ValueError("defaults.mobile_aspect_ratio must be numeric")
    if not isinstance(defaults.get("aspect_ratio_tolerance"), (int, float)):
        raise ValueError("defaults.aspect_ratio_tolerance must be numeric")

    category_names: set[str] = set()
    for category in categories:
        photo_type = category.get("photo_type")
        name = category.get("scene_category")
        queries = category.get("queries")
        if photo_type not in ALLOWED_PHOTO_TYPES:
            raise ValueError(f"unsupported photo_type: {photo_type}")
        if not isinstance(name, str) or not name:
            raise ValueError("every category needs scene_category")
        if name in category_names:
            raise ValueError(f"duplicate scene_category: {name}")
        category_names.add(name)
        if not isinstance(queries, list) or not queries or not all(isinstance(q, str) and q for q in queries):
            raise ValueError(f"{name} requires at least one query")
        if not isinstance(category.get("target_count"), int) or category["target_count"] < 1:
            raise ValueError(f"{name}.target_count must be positive")


def load_resume_state(manifest_path: Path) -> ResumeState:
    provider_ids: set[str] = set()
    hashes: set[str] = set()
    if not manifest_path.exists():
        return ResumeState(frozenset(), frozenset())
    with manifest_path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                provider_ids.add(str(record["source"]["provider_id"]))
                hashes.add(str(record["sha256"]))
            except (KeyError, TypeError, json.JSONDecodeError) as exc:
                raise ValueError(f"invalid manifest line {line_number}: {exc}") from exc
    return ResumeState(frozenset(provider_ids), frozenset(hashes))


def http_get(url: str, token: str | None = None, *, timeout: int = 45) -> tuple[bytes, dict[str, str]]:
    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read(), {key.lower(): value for key, value in response.headers.items()}
    except urllib.error.HTTPError as exc:
        if exc.code == 429:
            retry_after = exc.headers.get("Retry-After", "60")
            raise CollectionError(f"Openverse rate limit reached; retry after {retry_after}s") from exc
        body = exc.read(500).decode("utf-8", errors="replace")
        raise CollectionError(f"HTTP {exc.code} for {url}: {body}") from exc
    except urllib.error.URLError as exc:
        raise CollectionError(f"network error for {url}: {exc.reason}") from exc


def search_page(
    query: str,
    licenses: list[str],
    page: int,
    page_size: int,
    token: str | None,
) -> dict[str, Any]:
    params = urllib.parse.urlencode(
        {
            "q": query,
            "license": ",".join(licenses),
            "category": "photograph",
            "mature": "false",
            "page": page,
            "page_size": page_size,
        }
    )
    payload, _ = http_get(f"{API_URL}?{params}", token)
    try:
        result = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise CollectionError("Openverse returned invalid JSON") from exc
    if not isinstance(result, dict) or not isinstance(result.get("results"), list):
        raise CollectionError("Openverse response is missing results")
    return result


def candidate_is_eligible(
    candidate: dict[str, Any],
    allowed_licenses: set[str],
    min_long_edge: int,
    min_short_edge: int,
    target_aspect_ratio: float = 4.0 / 3.0,
    aspect_ratio_tolerance: float = 0.04,
    excluded_media_terms: set[str] | None = None,
) -> bool:
    license_id = str(candidate.get("license", "")).lower()
    width = candidate.get("width")
    height = candidate.get("height")
    if not (
        license_id in allowed_licenses
        and isinstance(width, int)
        and isinstance(height, int)
        and max(width, height) >= min_long_edge
        and min(width, height) >= min_short_edge
        and isinstance(candidate.get("url"), str)
        and candidate["url"].startswith(("https://", "http://"))
        and isinstance(candidate.get("foreign_landing_url"), str)
    ):
        return False
    if not matches_mobile_aspect_ratio(width, height, target_aspect_ratio, aspect_ratio_tolerance):
        return False
    return looks_like_photo(candidate, excluded_media_terms or DEFAULT_EXCLUDED_MEDIA_TERMS)


def matches_mobile_aspect_ratio(
    width: int, height: int, target_ratio: float = 4.0 / 3.0, tolerance: float = 0.04
) -> bool:
    short_edge = min(width, height)
    if short_edge <= 0:
        return False
    return abs(max(width, height) / short_edge - target_ratio) <= tolerance


def candidate_terms(candidate: dict[str, Any]) -> set[str]:
    values = [str(candidate.get("title") or "")]
    tags = candidate.get("tags") or []
    for tag in tags:
        values.append(str(tag.get("name", "")) if isinstance(tag, dict) else str(tag))
    words: set[str] = set()
    for value in values:
        normalized = "".join(character.lower() if character.isalnum() else " " for character in value)
        words.update(normalized.split())
    return words


def looks_like_photo(candidate: dict[str, Any], excluded_terms: set[str]) -> bool:
    if candidate.get("category") not in {None, "photograph"}:
        return False
    return not bool(candidate_terms(candidate) & {term.lower() for term in excluded_terms})


def resolve_asset_urls(candidate: dict[str, Any]) -> list[str]:
    """Return full-size proxy first, then the provider URL as fallback."""
    urls: list[str] = []
    thumbnail = candidate.get("thumbnail")
    if isinstance(thumbnail, str) and thumbnail.startswith(("https://", "http://")):
        separator = "&" if "?" in thumbnail else "?"
        urls.append(f"{thumbnail}{separator}full_size=true&compressed=false")
    provider_url = str(candidate["url"])
    if provider_url not in urls:
        urls.append(provider_url)
    return urls


def download_image(url: str, max_bytes: int) -> tuple[bytes, int, int, str]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "image/*"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            length = response.headers.get("Content-Length")
            if length and int(length) > max_bytes:
                raise CollectionError(f"asset exceeds {max_bytes} bytes")
            payload = response.read(max_bytes + 1)
    except (urllib.error.HTTPError, urllib.error.URLError) as exc:
        raise CollectionError(f"image download failed for {url}: {exc}") from exc
    if len(payload) > max_bytes:
        raise CollectionError(f"asset exceeds {max_bytes} bytes")

    try:
        from PIL import Image
    except ImportError as exc:
        raise CollectionError("Pillow is required; install requirements.txt") from exc

    try:
        with Image.open(io.BytesIO(payload)) as image:
            image.verify()
        with Image.open(io.BytesIO(payload)) as image:
            width, height = image.size
            image_format = image.format or ""
    except Exception as exc:  # Pillow raises several format-specific errors.
        raise CollectionError(f"invalid image from {url}: {exc}") from exc
    extension = PIL_FORMAT_EXTENSIONS.get(image_format.upper())
    if not extension:
        raise CollectionError(f"unsupported image format: {image_format}")
    return payload, width, height, extension


def make_record(
    candidate: dict[str, Any],
    category: dict[str, Any],
    query: str,
    relative_path: str,
    digest: str,
    width: int,
    height: int,
    asset_url: str | None = None,
) -> dict[str, Any]:
    provider_id = str(candidate["id"])
    is_portrait = category["photo_type"] == "portrait"
    return {
        "schema_version": 1,
        "photo_id": f"openverse_{provider_id}",
        "photo_type": category["photo_type"],
        "scene_category": category["scene_category"],
        "query": query,
        "file_path": relative_path,
        "sha256": digest,
        "width": width,
        "height": height,
        "reported_width": candidate["width"],
        "reported_height": candidate["height"],
        "source": {
            "provider": "openverse",
            "provider_id": provider_id,
            "landing_url": candidate["foreign_landing_url"],
            "asset_url": asset_url or candidate["url"],
            "creator": candidate.get("creator"),
            "creator_url": candidate.get("creator_url"),
            "title": candidate.get("title"),
            "tags": [
                str(tag.get("name", "")) if isinstance(tag, dict) else str(tag)
                for tag in (candidate.get("tags") or [])
                if (tag.get("name") if isinstance(tag, dict) else tag)
            ],
        },
        "rights": {
            "license": str(candidate["license"]).lower(),
            "license_url": candidate.get("license_url") or "https://creativecommons.org/publicdomain/",
            "attribution": candidate.get("attribution") or "",
            "verification_status": "provider_declared_pending_review",
        },
        "review": {
            "quality_status": "pending",
            "rights_status": "pending",
            "portrait_release_status": "pending" if is_portrait else "not_applicable",
        },
    }


def append_jsonl(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def iter_categories(config: dict[str, Any], selected: set[str] | None) -> Iterable[dict[str, Any]]:
    for category in config["categories"]:
        if selected is None or category["scene_category"] in selected:
            yield category


def collect(args: argparse.Namespace) -> int:
    config = load_json(args.config)
    validate_config(config)
    if args.dry_run:
        total = sum(category["target_count"] for category in config["categories"])
        print(f"Config OK: {len(config['categories'])} categories, target={total}")
        return 0

    token = os.environ.get("OPENVERSE_API_TOKEN")
    output_root: Path = args.output_dir
    manifest_path: Path = args.manifest
    resume = load_resume_state(manifest_path)
    seen_ids = set(resume.provider_ids)
    seen_hashes = set(resume.hashes)
    selected = set(args.category) if args.category else None
    accepted_total = 0
    errors_path = manifest_path.with_name("collection_errors.jsonl")
    defaults = config["defaults"]
    allowed_licenses = set(defaults["licenses"])
    excluded_media_terms = set(defaults.get("excluded_media_terms", DEFAULT_EXCLUDED_MEDIA_TERMS))
    attempted_ids = set(seen_ids)

    for category in iter_categories(config, selected):
        for query in category["queries"]:
            accepted_query = 0
            examined_query = 0
            page = 1
            while accepted_query < args.limit_per_query and examined_query < args.max_candidates_per_query:
                try:
                    result = search_page(query, sorted(allowed_licenses), page, args.page_size, token)
                except CollectionError as exc:
                    append_jsonl(
                        errors_path,
                        {
                            "provider": "openverse",
                            "stage": "search",
                            "query": query,
                            "page": page,
                            "error": str(exc),
                        },
                    )
                    print(f"search skipped for {query!r}: {exc}", file=sys.stderr, flush=True)
                    if args.error_delay:
                        time.sleep(args.error_delay)
                    break
                candidates = result["results"]
                if not candidates:
                    break
                for candidate in candidates:
                    if accepted_query >= args.limit_per_query:
                        break
                    provider_id = str(candidate.get("id", ""))
                    if not provider_id or provider_id in attempted_ids:
                        continue
                    if not candidate_is_eligible(
                        candidate,
                        allowed_licenses,
                        defaults["min_long_edge"],
                        defaults["min_short_edge"],
                        defaults["mobile_aspect_ratio"],
                        defaults["aspect_ratio_tolerance"],
                        excluded_media_terms,
                    ):
                        continue
                    attempted_ids.add(provider_id)
                    examined_query += 1
                    try:
                        download_errors: list[str] = []
                        downloaded = None
                        asset_url = ""
                        for candidate_url in resolve_asset_urls(candidate):
                            try:
                                downloaded = download_image(candidate_url, args.max_bytes)
                                asset_url = candidate_url
                                break
                            except CollectionError as exc:
                                download_errors.append(str(exc))
                        if downloaded is None:
                            raise CollectionError("; ".join(download_errors))
                        payload, width, height, extension = downloaded
                        if (
                            max(width, height) < defaults["min_long_edge"]
                            or min(width, height) < defaults["min_short_edge"]
                        ):
                            raise CollectionError(f"decoded image is too small: {width}x{height}")
                        if not matches_mobile_aspect_ratio(
                            width,
                            height,
                            defaults["mobile_aspect_ratio"],
                            defaults["aspect_ratio_tolerance"],
                        ):
                            raise CollectionError(f"decoded image is not 4:3 or 3:4: {width}x{height}")
                        digest = hashlib.sha256(payload).hexdigest()
                        if digest in seen_hashes:
                            seen_ids.add(provider_id)
                            continue
                        destination = (
                            output_root
                            / category["photo_type"]
                            / category["scene_category"]
                            / f"openverse_{provider_id}{extension}"
                        )
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        destination.write_bytes(payload)
                        relative_path = destination.relative_to(manifest_path.parent).as_posix()
                        record = make_record(
                            candidate,
                            category,
                            query,
                            relative_path,
                            digest,
                            width,
                            height,
                            asset_url,
                        )
                        append_jsonl(manifest_path, record)
                        seen_ids.add(provider_id)
                        seen_hashes.add(digest)
                        accepted_query += 1
                        accepted_total += 1
                        print(f"accepted {record['photo_id']} -> {relative_path}")
                    except (CollectionError, OSError) as exc:
                        append_jsonl(
                            errors_path,
                            {
                                "provider": "openverse",
                                "provider_id": provider_id,
                                "query": query,
                                "error": str(exc),
                            },
                        )
                page += 1
                page_count = int(result.get("page_count") or page - 1)
                if page > page_count:
                    break
                if args.request_delay:
                    time.sleep(args.request_delay)

    print(f"Collection complete: accepted={accepted_total}, manifest={manifest_path}", flush=True)
    return 0


def build_parser() -> argparse.ArgumentParser:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=script_dir / "config" / "search_queries.json")
    parser.add_argument("--output-dir", type=Path, default=script_dir.parent / "raw_photos")
    parser.add_argument("--manifest", type=Path, default=script_dir.parent / "raw_photos" / "manifest.jsonl")
    parser.add_argument("--category", action="append", help="Collect only this scene_category; repeatable")
    parser.add_argument("--limit-per-query", type=int, default=3, help="Accepted photos per query (default: pilot size 3)")
    parser.add_argument("--page-size", type=int, default=20)
    parser.add_argument("--max-candidates-per-query", type=int, default=100)
    parser.add_argument("--max-bytes", type=int, default=40 * 1024 * 1024)
    parser.add_argument("--request-delay", type=float, default=0.25)
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
