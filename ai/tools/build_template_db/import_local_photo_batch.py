#!/usr/bin/env python3
"""Import a local photo folder into a raw photo JSONL manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from collections import Counter
from pathlib import Path
from typing import Any
from urllib.parse import quote


IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff", ".heic"}
MIN_LONG_EDGE = 960
MIN_SHORT_EDGE = 640
TARGET_RATIO = 4.0 / 3.0
ASPECT_TOLERANCE = 0.04
MAX_BYTES = 40 * 1024 * 1024

EXCLUDED_TERMS = {
    "ai generated",
    "generated ai",
    "artificial intelligence",
    "midjourney",
    "stable diffusion",
    "dall e",
    "dall-e",
    "illustration",
    "drawing",
    "painting",
    "render",
    "cgi",
    "screenshot",
    "poster",
    "watermark",
    "minor",
    "child",
    "children",
    "kid",
    "baby",
    "teen",
    "teenager",
    "\u672a\u6210\u5e74",
    "\u513f\u7ae5",
    "\u5b69\u5b50",
    "\u5b9d\u5b9d",
    "sexy",
    "sensual",
    "erotic",
    "nude",
    "naked",
    "lingerie",
    "boudoir",
    "topless",
    "\u6027\u611f",
    "\u5185\u8863",
    "\u79c1\u623f",
    "\u5077\u62cd",
    "hospital",
    "clinic",
    "medical",
    "religion",
    "religious",
    "politics",
    "political",
    "protest",
    "military",
    "police",
}


def safe_id(value: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip())
    return sanitized.strip("._")[:120] or "local"


def local_uri(*parts: str) -> str:
    quoted = "/".join(quote(str(part).strip("/")) for part in parts if str(part))
    return f"local://ai-camera/{quoted or 'local'}"


def http_or_local(value: str | None, *fallback_parts: str) -> str:
    if value and value.startswith(("http://", "https://", "file://", "local://")):
        return value
    return local_uri(*fallback_parts)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def matches_mobile_aspect_ratio(width: int, height: int) -> bool:
    short_edge = min(width, height)
    if short_edge <= 0:
        return False
    return abs(max(width, height) / short_edge - TARGET_RATIO) <= ASPECT_TOLERANCE


def decode_dimensions(path: Path) -> tuple[int, int]:
    from PIL import Image, ImageOps

    with Image.open(path) as image:
        image.verify()
    with Image.open(path) as image:
        image = ImageOps.exif_transpose(image)
        return image.size


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("_", " ").lower()).strip()


def keyword_matches(text: str) -> list[str]:
    normalized = f" {normalize_text(text)} "
    matches: list[str] = []
    for term in sorted(EXCLUDED_TERMS):
        if " " in term:
            if term in normalized:
                matches.append(term)
        elif re.search(rf"(?<![a-z0-9]){re.escape(term)}(?![a-z0-9])", normalized):
            matches.append(term)
    return matches


def broad_category(value: str) -> str:
    category = value.strip().lower()
    if category in {"portrait", "landscape", "food", "still"}:
        return category
    if "portrait" in category or "\u4eba\u50cf" in category:
        return "portrait"
    if "food" in category or "\u7f8e\u98df" in category:
        return "food"
    if "landscape" in category or "\u98ce\u666f" in category:
        return "landscape"
    if "still" in category or "\u9759\u7269" in category:
        return "still"
    if category in {"natural_landscape", "urban_landscape"}:
        return "landscape"
    if category == "food_still_life":
        return "food"
    if category in {"plant_still_life", "lifestyle_still_life", "creative_composition"}:
        return "still"
    return "still"


def photo_type_for(category: str) -> str:
    if category == "portrait":
        return "portrait"
    if category == "landscape":
        return "landscape"
    return "still_life"


def infer_scene_category(category: str, text: str) -> str:
    normalized = normalize_text(text)
    if category == "food":
        return "food_still_life"
    if category == "landscape":
        urban_terms = (
            "city",
            "cityscape",
            "skyline",
            "urban",
            "street",
            "architecture",
            "building",
            "bridge",
            "downtown",
            "\u57ce\u5e02",
            "\u57ce\u5e02\u98ce\u5149",
            "\u5efa\u7b51",
            "\u8857",
            "\u676d\u5dde",
            "\u6df1\u5733",
            "\u9752\u5c9b",
        )
        return "urban_landscape" if any(term in normalized for term in urban_terms) else "natural_landscape"
    if category == "still":
        if any(term in normalized for term in ("flower", "plant", "vase", "bouquet", "rose", "tulip", "\u82b1", "\u7eff\u690d", "\u690d\u7269", "\u5411\u65e5\u8475")):
            return "plant_still_life"
        if any(term in normalized for term in ("composition", "negative space", "symmetry", "\u6784\u56fe", "\u5149\u5f71", "\u5f20\u529b", "\u56db\u8c61\u9650")):
            return "creative_composition"
        return "lifestyle_still_life"
    if category == "portrait":
        if any(term in normalized for term in ("couple", "girlfriend", "boyfriend", "\u60c5\u4fa3", "\u5973\u670b\u53cb", "\u4e24\u4e2a\u4eba", "\u6211\u4eec")):
            return "couple_portrait"
        if any(term in normalized for term in ("group", "friends", "\u5408\u5f71", "\u7fa4\u50cf", "\u6bd5\u4e1a\u7167", "\u670b\u53cb")):
            return "group_portrait"
        if any(term in normalized for term in ("golden hour", "sunset", "\u9ec4\u91d1", "\u65e5\u843d", "\u5915\u9633", "\u5929\u5149", "\u795e\u4ed9\u65f6\u523b")):
            return "golden_hour_portrait"
        if any(term in normalized for term in ("street", "city", "urban", "\u8857", "\u57ce\u5e02", "\u591c\u666f", "\u5efa\u7b51")):
            return "urban_street_portrait"
        if any(term in normalized for term in ("travel", "landmark", "\u65c5\u884c", "\u65c5\u6e38", "\u673a\u4f4d")):
            return "travel_portrait"
        if any(term in normalized for term in ("park", "grass", "flower", "mountain", "beach", "forest", "\u516c\u56ed", "\u8349\u5730", "\u8349\u539f", "\u82b1", "\u5c71", "\u6d77", "\u6cb9\u83dc\u82b1")):
            return "outdoor_nature_portrait"
        if any(term in normalized for term in ("indoor", "cafe", "coffee", "\u5496\u5561", "\u5ba4\u5185", "\u5e97", "\u751c\u54c1\u5e97")):
            return "indoor_portrait"
        return "closeup_portrait"
    return "lifestyle_still_life"


def normalize_license(raw_license: Any, raw_url: Any, provider: str) -> tuple[str, str]:
    if provider.startswith("local_xhs"):
        return "local_authorized", local_uri("rights", "local_authorized")
    text = normalize_text(str(raw_license or ""))
    license_url = str(raw_url or "")
    if "zero" in text or text == "cc0" or "/zero/" in license_url:
        return "cc0", license_url or "https://creativecommons.org/publicdomain/zero/1.0/"
    if "public domain" in text or text == "pdm" or "publicdomain/mark" in license_url:
        return "pdm", license_url or "https://creativecommons.org/publicdomain/mark/1.0/"
    if "by-sa" in text or "by sa" in text:
        return "cc-by-sa", license_url or "https://creativecommons.org/licenses/by-sa/4.0/"
    if text in {"by", "cc by"} or "by/" in license_url:
        return "cc-by", license_url or "https://creativecommons.org/licenses/by/4.0/"
    return "unknown", local_uri("rights", "unknown")


def resolve_path(value: Any, source_root: Path) -> Path | None:
    if not value:
        return None
    path = Path(str(value))
    candidates = [path]
    parts = path.parts
    if "\u56fe\u7247\u7d20\u6750" in parts:
        index = parts.index("\u56fe\u7247\u7d20\u6750")
        candidates.append(source_root.joinpath(*parts[index + 1 :]))
    if not path.is_absolute():
        candidates.append(source_root / path)
    for candidate in candidates:
        if candidate.exists():
            return candidate.resolve()
    return None


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def load_combined_items(source_dir: Path, combined_manifest: Path | None, source_root: Path) -> list[dict[str, Any]]:
    if combined_manifest and combined_manifest.exists():
        value = load_json(combined_manifest)
        items = value.get("items", []) if isinstance(value, dict) else value
        if not isinstance(items, list):
            raise ValueError(f"combined manifest has no list of items: {combined_manifest}")
        return [dict(item) for item in items if isinstance(item, dict)]
    return [
        {"file": str(path), "category": "", "source": "local_file", "source_file": str(path), "title": ""}
        for path in sorted(source_dir.iterdir())
        if path.is_file() and path.suffix.lower() in IMAGE_EXTS
    ]


def load_source_metadata(source_root: Path) -> dict[str, dict[str, Any]]:
    lookup: dict[str, dict[str, Any]] = {}
    for manifest in source_root.rglob("manifest.json"):
        try:
            value = load_json(manifest)
        except (OSError, json.JSONDecodeError):
            continue
        items = value.get("items", []) if isinstance(value, dict) else value
        if not isinstance(items, list):
            continue
        for item in items:
            if not isinstance(item, dict):
                continue
            for field in ("file", "file_path", "source"):
                path = resolve_path(item.get(field), source_root)
                if path is not None:
                    lookup[path.as_posix()] = item
    return lookup


def metadata_text(item: dict[str, Any], source_meta: dict[str, Any]) -> str:
    values: list[str] = []
    for field in ("title", "source_title", "description", "creator", "artist", "file_name"):
        values.append(str(item.get(field) or ""))
        values.append(str(source_meta.get(field) or ""))
    source_file = Path(str(item.get("source_file") or "")).name
    output_file = Path(str(item.get("file") or "")).name
    values.extend([source_file, output_file])
    return " ".join(value for value in values if value)


def provider_for(item: dict[str, Any], source_path: Path | None) -> str:
    source = normalize_text(str(item.get("source") or ""))
    source_text = normalize_text(str(source_path or ""))
    if "xhs" in source or "xhs" in source_text:
        return "local_xhs_export"
    if "openverse" in source or "commons" in source or "photo_candidates" in source_text:
        return "local_openverse_import"
    return "local_file"


def provider_id_for(item: dict[str, Any], image_path: Path, source_meta: dict[str, Any]) -> str:
    note_id = source_meta.get("note_id")
    image_index = source_meta.get("image_index")
    if note_id and image_index:
        return safe_id(f"{note_id}_{image_index}")
    for field in ("provider_id", "id", "source_url", "landing_url"):
        if source_meta.get(field):
            return safe_id(str(source_meta[field]))
    return safe_id(image_path.stem)


def place_image(source: Path, destination: Path, mode: str) -> str:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if sha256_file(destination) == sha256_file(source):
            return "existing"
        destination.unlink()
    if mode == "copy":
        shutil.copy2(source, destination)
        return "copied"
    try:
        os.link(source, destination)
        return "linked"
    except OSError:
        shutil.copy2(source, destination)
        return "copied"


def build_record(
    *,
    photo_id: str,
    item: dict[str, Any],
    source_meta: dict[str, Any],
    image_path: Path,
    relative_path: str,
    digest: str,
    width: int,
    height: int,
    category: str,
    scene_category: str,
    provider: str,
    provider_id: str,
    keyword_flags: list[str],
    geometry_flags: list[str],
) -> dict[str, Any]:
    photo_type = photo_type_for(category)
    text = metadata_text(item, source_meta)
    source_url = str(source_meta.get("source_url") or item.get("source_url") or "")
    landing_url = str(source_meta.get("landing_url") or item.get("landing_url") or "")
    asset_url = str(source_meta.get("download_url") or source_meta.get("asset_url") or source_url)
    creator = source_meta.get("creator") or source_meta.get("artist")
    license_id, license_url = normalize_license(source_meta.get("license") or item.get("license"), source_meta.get("license_url") or item.get("license_url"), provider)
    verification = "user_declared_pending_review" if provider.startswith("local_xhs") else "provider_declared_pending_review"
    rejection_reasons = []
    if geometry_flags:
        rejection_reasons.extend(geometry_flags)
    if keyword_flags:
        rejection_reasons.append(f"metadata_excluded_terms:{','.join(keyword_flags)}")
    quality_status = "rejected" if rejection_reasons else "pending"
    return {
        "schema_version": 1,
        "photo_id": photo_id,
        "photo_type": photo_type,
        "scene_category": scene_category,
        "query": f"local_import:{category}:{scene_category}",
        "file_path": relative_path,
        "sha256": digest,
        "width": width,
        "height": height,
        "reported_width": int(item.get("width") or source_meta.get("width") or width),
        "reported_height": int(item.get("height") or source_meta.get("height") or height),
        "source": {
            "provider": provider,
            "provider_id": provider_id,
            "landing_url": http_or_local(landing_url or source_url, provider, provider_id, "landing"),
            "asset_url": http_or_local(asset_url or source_url, provider, provider_id, "asset"),
            "creator": str(creator) if creator else None,
            "creator_url": None,
            "title": text[:300] or image_path.stem,
            "tags": [category, scene_category, provider],
        },
        "rights": {
            "license": license_id,
            "license_url": license_url,
            "attribution": str(creator or source_meta.get("attribution") or item.get("source") or provider),
            "verification_status": verification,
        },
        "review": {
            "quality_status": quality_status,
            "rights_status": "pending",
            "portrait_release_status": "pending" if photo_type == "portrait" else "not_applicable",
            "rejection_reason": ";".join(rejection_reasons) if rejection_reasons else None,
            "cv_failure_reason": None,
            "reviewed_at": None,
        },
    }


def import_batch(
    source_dir: Path,
    output_dir: Path,
    *,
    combined_manifest: Path | None,
    source_root: Path,
    batch_id: str,
    mode: str = "link",
    overwrite: bool = False,
) -> dict[str, Any]:
    if overwrite and output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / "manifest.jsonl"
    report_path = output_dir / "import_report.json"
    source_lookup = load_source_metadata(source_root)
    items = load_combined_items(source_dir, combined_manifest, source_root)

    records: list[dict[str, Any]] = []
    seen_hashes: set[str] = set()
    report: dict[str, Any] = {
        "schema_version": 1,
        "batch_id": batch_id,
        "source_dir": str(source_dir),
        "combined_manifest": str(combined_manifest) if combined_manifest else None,
        "output_dir": str(output_dir),
        "records": 0,
        "input_items": len(items),
        "skipped_missing_file": 0,
        "decode_errors": 0,
        "duplicate_hashes": 0,
        "placed": Counter(),
        "geometry_flags": Counter(),
        "keyword_flags": Counter(),
        "photo_types": Counter(),
        "scene_categories": Counter(),
        "source_providers": Counter(),
        "rights_licenses": Counter(),
        "review_quality_status": Counter(),
        "examples": [],
    }

    for item in sorted(items, key=lambda row: int(row.get("index") or 0)):
        image_path = resolve_path(item.get("file"), source_root)
        if image_path is None or not image_path.exists():
            report["skipped_missing_file"] += 1
            continue
        if image_path.suffix.lower() not in IMAGE_EXTS:
            continue
        try:
            width, height = decode_dimensions(image_path)
        except Exception as exc:
            report["decode_errors"] += 1
            if len(report["examples"]) < 20:
                report["examples"].append({"file": str(image_path), "reason": f"decode_error:{exc}"})
            continue
        digest = sha256_file(image_path)
        if digest in seen_hashes:
            report["duplicate_hashes"] += 1
            continue
        seen_hashes.add(digest)

        source_path = resolve_path(item.get("source_file"), source_root)
        source_meta = source_lookup.get(source_path.as_posix() if source_path else "", {})
        category = broad_category(str(item.get("category") or source_meta.get("category") or image_path.name))
        text = metadata_text(item, source_meta)
        scene_category = infer_scene_category(category, text)
        provider = provider_for(item, source_path)
        provider_id = provider_id_for(item, image_path, source_meta)
        geometry_flags: list[str] = []
        if max(width, height) < MIN_LONG_EDGE or min(width, height) < MIN_SHORT_EDGE:
            geometry_flags.append("too_small")
        if not matches_mobile_aspect_ratio(width, height):
            geometry_flags.append("not_mobile_4_3")
        if image_path.stat().st_size > MAX_BYTES:
            geometry_flags.append("too_large")
        keyword_flags = keyword_matches(text)

        photo_id = f"{batch_id}_{len(records) + 1:05d}"
        destination = output_dir / "images" / photo_type_for(category) / scene_category / image_path.name
        placed = place_image(image_path, destination, mode)
        relative_path = destination.relative_to(output_dir).as_posix()
        record = build_record(
            photo_id=photo_id,
            item=item,
            source_meta=source_meta,
            image_path=image_path,
            relative_path=relative_path,
            digest=digest,
            width=width,
            height=height,
            category=category,
            scene_category=scene_category,
            provider=provider,
            provider_id=provider_id,
            keyword_flags=keyword_flags,
            geometry_flags=geometry_flags,
        )
        records.append(record)
        report["placed"][placed] += 1
        report["photo_types"][record["photo_type"]] += 1
        report["scene_categories"][record["scene_category"]] += 1
        report["source_providers"][provider] += 1
        report["rights_licenses"][record["rights"]["license"]] += 1
        report["review_quality_status"][record["review"]["quality_status"]] += 1
        for flag in geometry_flags:
            report["geometry_flags"][flag] += 1
        for flag in keyword_flags:
            report["keyword_flags"][flag] += 1

    with manifest_path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    report["records"] = len(records)
    report["manifest"] = str(manifest_path)
    for key in (
        "placed",
        "geometry_flags",
        "keyword_flags",
        "photo_types",
        "scene_categories",
        "source_providers",
        "rights_licenses",
        "review_quality_status",
    ):
        report[key] = dict(sorted(report[key].items()))
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    return report


def build_parser() -> argparse.ArgumentParser:
    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parents[1]
    default_source = repo_root / "\u56fe\u7247\u7d20\u6750" / "\u5168\u90e8\u7167\u7247_\u5df2\u7b5b\u9009"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_dir", nargs="?", type=Path, default=default_source)
    parser.add_argument("--combined-manifest", type=Path)
    parser.add_argument("--source-root", type=Path, default=repo_root / "\u56fe\u7247\u7d20\u6750")
    parser.add_argument("--output-dir", type=Path, default=script_dir.parent / "raw_photos" / "new_batch")
    parser.add_argument("--batch-id", default="new_batch")
    parser.add_argument("--mode", choices=("link", "copy"), default="link")
    parser.add_argument("--overwrite", action="store_true")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    source_dir = args.source_dir
    combined_manifest = args.combined_manifest
    if combined_manifest is None:
        candidate = source_dir.with_name(f"{source_dir.name}_manifest.json")
        combined_manifest = candidate if candidate.exists() else None
    try:
        report = import_batch(
            source_dir.resolve(),
            args.output_dir.resolve(),
            combined_manifest=combined_manifest.resolve() if combined_manifest else None,
            source_root=args.source_root.resolve(),
            batch_id=safe_id(args.batch_id),
            mode=args.mode,
            overwrite=args.overwrite,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    if report["records"] == 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
