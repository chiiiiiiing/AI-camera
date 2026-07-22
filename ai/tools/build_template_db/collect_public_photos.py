#!/usr/bin/env python3
"""Collect rights-traceable photo candidates from approved public APIs.

This script intentionally avoids scraping login-gated social feeds, bypassing
anti-bot protections, or downloading sources blocked by project policy. It
collects only provider-declared CC0/Public Domain Mark candidates, then leaves
quality, rights, model/property release, and portrait-safety review as pending.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from collect_openverse import (
    CollectionError,
    append_jsonl,
    download_image,
    excluded_terms_for_category,
    iter_categories,
    load_json,
    matches_mobile_aspect_ratio,
    search_page as openverse_search_page,
    validate_config,
)


USER_AGENT = "ai-camera-template-builder/0.2 (rights-traceable photo collector)"
COMMONS_API_URL = "https://commons.wikimedia.org/w/api.php"
FLICKR_API_URL = "https://www.flickr.com/services/rest/"
CC0_URL = "https://creativecommons.org/publicdomain/zero/1.0/"
PDM_URL = "https://creativecommons.org/publicdomain/mark/1.0/"
DEFAULT_PROVIDERS = ("openverse", "wikimedia_commons")
AVAILABLE_PROVIDERS = {"openverse", "wikimedia_commons", "flickr"}
ALLOWED_SOURCE_STATUSES = {"allowed", "candidate_only"}
PHOTOGRAPH_MIME_TYPES = {"image/jpeg", "image/png", "image/webp", "image/tiff"}


@dataclass(frozen=True)
class ResumeState:
    provider_keys: frozenset[str]
    hashes: frozenset[str]


@dataclass(frozen=True)
class ProviderCandidate:
    provider: str
    provider_id: str
    license: str
    license_url: str
    landing_url: str
    asset_urls: tuple[str, ...]
    creator: str | None
    creator_url: str | None
    title: str | None
    tags: tuple[str, ...]
    reported_width: int
    reported_height: int
    attribution: str


class ProviderAdapter:
    name: str

    def search(
        self,
        query: str,
        allowed_licenses: set[str],
        page: int,
        page_size: int,
    ) -> tuple[list[ProviderCandidate], bool]:
        raise NotImplementedError


def http_get_json(url: str, params: dict[str, Any], *, timeout: int = 45) -> dict[str, Any]:
    request_url = f"{url}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(
        request_url,
        headers={"Accept": "application/json", "User-Agent": USER_AGENT},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
    except urllib.error.HTTPError as exc:
        body = exc.read(500).decode("utf-8", errors="replace")
        raise CollectionError(f"HTTP {exc.code} for {url}: {body}") from exc
    except urllib.error.URLError as exc:
        raise CollectionError(f"network error for {url}: {exc.reason}") from exc
    try:
        value = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise CollectionError(f"invalid JSON from {url}") from exc
    if not isinstance(value, dict):
        raise CollectionError(f"unexpected JSON payload from {url}")
    return value


def safe_id(value: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip())
    return sanitized.strip("._")[:120] or "unknown"


def provider_key(candidate: ProviderCandidate) -> str:
    return f"{candidate.provider}:{candidate.provider_id}"


def load_resume_state(manifest_path: Path) -> ResumeState:
    keys: set[str] = set()
    hashes: set[str] = set()
    if not manifest_path.exists():
        return ResumeState(frozenset(), frozenset())
    with manifest_path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                source = record["source"]
                provider = str(source["provider"])
                provider_id = str(source["provider_id"])
                keys.add(f"{provider}:{provider_id}")
                hashes.add(str(record["sha256"]))
            except (KeyError, TypeError, json.JSONDecodeError) as exc:
                raise ValueError(f"invalid manifest line {line_number}: {exc}") from exc
    return ResumeState(frozenset(keys), frozenset(hashes))


def normalize_text(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", value.lower()).strip()


def contains_excluded_term(values: list[str], excluded_terms: set[str]) -> bool:
    text = f" {' '.join(normalize_text(value) for value in values if value)} "
    words = set(text.split())
    for term in excluded_terms:
        normalized = normalize_text(term)
        if not normalized:
            continue
        if " " in normalized:
            if f" {normalized} " in text:
                return True
        elif normalized in words:
            return True
    return False


def candidate_text_values(candidate: ProviderCandidate) -> list[str]:
    values = [candidate.title or "", candidate.creator or ""]
    values.extend(candidate.tags)
    return values


def metadata_is_eligible(
    candidate: ProviderCandidate,
    allowed_licenses: set[str],
    min_long_edge: int,
    min_short_edge: int,
    aspect_ratio: float,
    aspect_ratio_tolerance: float,
    excluded_terms: set[str],
) -> bool:
    if candidate.license not in allowed_licenses:
        return False
    if not (
        candidate.asset_urls
        and candidate.landing_url.startswith(("https://", "http://"))
        and candidate.reported_width >= 1
        and candidate.reported_height >= 1
    ):
        return False
    if max(candidate.reported_width, candidate.reported_height) < min_long_edge:
        return False
    if min(candidate.reported_width, candidate.reported_height) < min_short_edge:
        return False
    if not matches_mobile_aspect_ratio(
        candidate.reported_width,
        candidate.reported_height,
        aspect_ratio,
        aspect_ratio_tolerance,
    ):
        return False
    return not contains_excluded_term(candidate_text_values(candidate), excluded_terms)


def optional_url(value: Any) -> str | None:
    if isinstance(value, str) and value.startswith(("https://", "http://")):
        return value
    return None


def strip_html(value: Any) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", str(value)))).strip()


def extmetadata_value(metadata: dict[str, Any], key: str) -> str:
    entry = metadata.get(key)
    if isinstance(entry, dict):
        return strip_html(entry.get("value"))
    return ""


def commons_license(metadata: dict[str, Any]) -> tuple[str, str] | None:
    license_short = extmetadata_value(metadata, "LicenseShortName")
    license_url = extmetadata_value(metadata, "LicenseUrl")
    usage_terms = extmetadata_value(metadata, "UsageTerms")
    joined = " ".join([license_short, license_url, usage_terms]).lower()
    if "creativecommons.org/publicdomain/zero" in joined or "cc0" in joined:
        return "cc0", license_url or CC0_URL
    if (
        "creativecommons.org/publicdomain/mark" in joined
        or "public domain mark" in joined
        or "public domain" in joined
    ):
        return "pdm", license_url or PDM_URL
    return None


class OpenverseAdapter(ProviderAdapter):
    name = "openverse"

    def search(
        self,
        query: str,
        allowed_licenses: set[str],
        page: int,
        page_size: int,
    ) -> tuple[list[ProviderCandidate], bool]:
        token = os.environ.get("OPENVERSE_API_TOKEN")
        result = openverse_search_page(query, sorted(allowed_licenses), page, page_size, token)
        raw_candidates = result.get("results")
        if not isinstance(raw_candidates, list):
            raise CollectionError("Openverse response is missing results")
        candidates: list[ProviderCandidate] = []
        for item in raw_candidates:
            if not isinstance(item, dict):
                continue
            license_id = str(item.get("license", "")).lower()
            width = item.get("width")
            height = item.get("height")
            provider_id = str(item.get("id") or "")
            asset_url = optional_url(item.get("url"))
            landing_url = optional_url(item.get("foreign_landing_url"))
            if (
                license_id not in allowed_licenses
                or not provider_id
                or not isinstance(width, int)
                or not isinstance(height, int)
                or asset_url is None
                or landing_url is None
                or item.get("category") not in {None, "photograph"}
            ):
                continue
            urls: list[str] = []
            thumbnail = optional_url(item.get("thumbnail"))
            if thumbnail:
                separator = "&" if "?" in thumbnail else "?"
                urls.append(f"{thumbnail}{separator}full_size=true&compressed=false")
            if asset_url not in urls:
                urls.append(asset_url)
            candidates.append(
                ProviderCandidate(
                    provider=self.name,
                    provider_id=provider_id,
                    license=license_id,
                    license_url=str(item.get("license_url") or (CC0_URL if license_id == "cc0" else PDM_URL)),
                    landing_url=landing_url,
                    asset_urls=tuple(urls),
                    creator=item.get("creator") if isinstance(item.get("creator"), str) else None,
                    creator_url=optional_url(item.get("creator_url")),
                    title=item.get("title") if isinstance(item.get("title"), str) else None,
                    tags=tuple(
                        str(tag.get("name", "")) if isinstance(tag, dict) else str(tag)
                        for tag in (item.get("tags") or [])
                        if (tag.get("name") if isinstance(tag, dict) else tag)
                    ),
                    reported_width=width,
                    reported_height=height,
                    attribution=str(item.get("attribution") or ""),
                )
            )
        page_count = int(result.get("page_count") or page)
        return candidates, page < page_count


class WikimediaCommonsAdapter(ProviderAdapter):
    name = "wikimedia_commons"

    def search(
        self,
        query: str,
        allowed_licenses: set[str],
        page: int,
        page_size: int,
    ) -> tuple[list[ProviderCandidate], bool]:
        offset = (page - 1) * page_size
        search_payload = http_get_json(
            COMMONS_API_URL,
            {
                "action": "query",
                "format": "json",
                "list": "search",
                "srnamespace": 6,
                "srsearch": f"{query} filetype:bitmap",
                "srlimit": page_size,
                "sroffset": offset,
                "srprop": "size|timestamp",
                "formatversion": 2,
            },
        )
        search_results = search_payload.get("query", {}).get("search", [])
        if not isinstance(search_results, list) or not search_results:
            return [], False
        titles = [
            result["title"]
            for result in search_results
            if isinstance(result, dict) and isinstance(result.get("title"), str)
        ]
        if not titles:
            return [], bool(search_payload.get("continue"))
        image_payload = http_get_json(
            COMMONS_API_URL,
            {
                "action": "query",
                "format": "json",
                "prop": "imageinfo",
                "titles": "|".join(titles),
                "iiprop": "url|size|mime|extmetadata",
                "iiextmetadatafilter": "LicenseShortName|LicenseUrl|Artist|ObjectName|ImageDescription|Credit|UsageTerms",
                "redirects": 1,
                "formatversion": 2,
            },
        )
        pages = image_payload.get("query", {}).get("pages", [])
        if not isinstance(pages, list):
            raise CollectionError("Wikimedia Commons response is missing pages")
        candidates: list[ProviderCandidate] = []
        for page_item in pages:
            if not isinstance(page_item, dict):
                continue
            imageinfo = page_item.get("imageinfo") or []
            if not imageinfo or not isinstance(imageinfo[0], dict):
                continue
            info = imageinfo[0]
            mime = str(info.get("mime") or "").lower()
            width = info.get("width")
            height = info.get("height")
            asset_url = optional_url(info.get("url"))
            metadata = info.get("extmetadata") if isinstance(info.get("extmetadata"), dict) else {}
            license_pair = commons_license(metadata)
            if (
                license_pair is None
                or license_pair[0] not in allowed_licenses
                or mime not in PHOTOGRAPH_MIME_TYPES
                or not isinstance(width, int)
                or not isinstance(height, int)
                or asset_url is None
            ):
                continue
            title = str(page_item.get("title") or "")
            landing_title = urllib.parse.quote(title.replace(" ", "_"), safe="/:")
            object_name = extmetadata_value(metadata, "ObjectName")
            description = extmetadata_value(metadata, "ImageDescription")
            creator = extmetadata_value(metadata, "Artist") or None
            credit = extmetadata_value(metadata, "Credit")
            candidates.append(
                ProviderCandidate(
                    provider=self.name,
                    provider_id=str(page_item.get("pageid") or safe_id(title)),
                    license=license_pair[0],
                    license_url=license_pair[1],
                    landing_url=f"https://commons.wikimedia.org/wiki/{landing_title}",
                    asset_urls=(asset_url,),
                    creator=creator,
                    creator_url=None,
                    title=object_name or description or title,
                    tags=(title,),
                    reported_width=width,
                    reported_height=height,
                    attribution=credit or creator or "",
                )
            )
        return candidates, bool(search_payload.get("continue"))


class FlickrAdapter(ProviderAdapter):
    name = "flickr"
    license_map = {
        "9": ("cc0", CC0_URL),
        "10": ("pdm", PDM_URL),
    }

    def __init__(self, api_key: str | None) -> None:
        if not api_key:
            raise CollectionError("FLICKR_API_KEY is required when --provider flickr is selected")
        self.api_key = api_key

    def search(
        self,
        query: str,
        allowed_licenses: set[str],
        page: int,
        page_size: int,
    ) -> tuple[list[ProviderCandidate], bool]:
        flickr_license_ids = [
            flickr_id
            for flickr_id, (license_id, _) in self.license_map.items()
            if license_id in allowed_licenses
        ]
        if not flickr_license_ids:
            return [], False
        payload = http_get_json(
            FLICKR_API_URL,
            {
                "method": "flickr.photos.search",
                "api_key": self.api_key,
                "format": "json",
                "nojsoncallback": 1,
                "text": query,
                "license": ",".join(flickr_license_ids),
                "media": "photos",
                "content_types": 0,
                "safe_search": 1,
                "sort": "relevance",
                "extras": "license,owner_name,tags,o_dims,url_o,url_l,url_c,url_z,path_alias",
                "per_page": page_size,
                "page": page,
            },
        )
        if payload.get("stat") != "ok":
            raise CollectionError(f"Flickr API error: {payload.get('message') or payload}")
        photo_page = payload.get("photos")
        if not isinstance(photo_page, dict):
            raise CollectionError("Flickr response is missing photos")
        raw_photos = photo_page.get("photo")
        if not isinstance(raw_photos, list):
            raise CollectionError("Flickr response is missing photo results")
        candidates: list[ProviderCandidate] = []
        for item in raw_photos:
            if not isinstance(item, dict):
                continue
            license_pair = self.license_map.get(str(item.get("license")))
            provider_id = str(item.get("id") or "")
            if license_pair is None or not provider_id:
                continue
            asset_urls, width, height = self._asset_urls_and_size(item)
            if not asset_urls or width is None or height is None:
                continue
            owner = str(item.get("owner") or "")
            owner_path = str(item.get("pathalias") or owner)
            landing_url = f"https://www.flickr.com/photos/{urllib.parse.quote(owner_path)}/{provider_id}/"
            candidates.append(
                ProviderCandidate(
                    provider=self.name,
                    provider_id=provider_id,
                    license=license_pair[0],
                    license_url=license_pair[1],
                    landing_url=landing_url,
                    asset_urls=tuple(asset_urls),
                    creator=item.get("ownername") if isinstance(item.get("ownername"), str) else None,
                    creator_url=f"https://www.flickr.com/photos/{urllib.parse.quote(owner_path)}/" if owner_path else None,
                    title=item.get("title") if isinstance(item.get("title"), str) else None,
                    tags=tuple(str(item.get("tags") or "").split()),
                    reported_width=width,
                    reported_height=height,
                    attribution=str(item.get("ownername") or ""),
                )
            )
        current_page = int(photo_page.get("page") or page)
        pages = int(photo_page.get("pages") or current_page)
        return candidates, current_page < pages

    @staticmethod
    def _asset_urls_and_size(item: dict[str, Any]) -> tuple[list[str], int | None, int | None]:
        variants = [
            ("url_o", "o_width", "o_height"),
            ("url_l", "width_l", "height_l"),
            ("url_c", "width_c", "height_c"),
            ("url_z", "width_z", "height_z"),
        ]
        urls: list[str] = []
        selected_width: int | None = None
        selected_height: int | None = None
        for url_key, width_key, height_key in variants:
            url = optional_url(item.get(url_key))
            width = parse_int(item.get(width_key))
            height = parse_int(item.get(height_key))
            if url and width and height:
                if selected_width is None:
                    selected_width = width
                    selected_height = height
                urls.append(url)
        return urls, selected_width, selected_height


def parse_int(value: Any) -> int | None:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def build_adapters(provider_names: tuple[str, ...]) -> list[ProviderAdapter]:
    adapters: list[ProviderAdapter] = []
    for name in provider_names:
        if name == "openverse":
            adapters.append(OpenverseAdapter())
        elif name == "wikimedia_commons":
            adapters.append(WikimediaCommonsAdapter())
        elif name == "flickr":
            adapters.append(FlickrAdapter(os.environ.get("FLICKR_API_KEY")))
        else:
            raise CollectionError(f"unsupported provider: {name}")
    return adapters


def resolve_provider_names(selected: list[str] | None) -> tuple[str, ...]:
    if not selected:
        return DEFAULT_PROVIDERS
    provider_names: list[str] = []
    for name in selected:
        if name == "all":
            provider_names.extend(sorted(AVAILABLE_PROVIDERS))
            continue
        if name not in AVAILABLE_PROVIDERS:
            raise ValueError(f"unsupported provider: {name}")
        provider_names.append(name)
    deduped = tuple(dict.fromkeys(provider_names))
    if not deduped:
        raise ValueError("at least one provider is required")
    return deduped


def validate_source_policy(provider_names: tuple[str, ...], policy_path: Path) -> None:
    policy = load_json(policy_path)
    sources = policy.get("sources")
    if not isinstance(sources, dict):
        raise ValueError("source policy requires a sources object")
    blocked: list[str] = []
    missing: list[str] = []
    for provider in provider_names:
        source = sources.get(provider)
        if not isinstance(source, dict):
            missing.append(provider)
            continue
        if source.get("status") not in ALLOWED_SOURCE_STATUSES:
            blocked.append(f"{provider} ({source.get('status')})")
    if missing:
        raise ValueError(f"source policy is missing providers: {', '.join(missing)}")
    if blocked:
        raise ValueError(f"source policy blocks providers: {', '.join(blocked)}")


def make_record(
    candidate: ProviderCandidate,
    category: dict[str, Any],
    query: str,
    relative_path: str,
    digest: str,
    width: int,
    height: int,
    asset_url: str,
) -> dict[str, Any]:
    is_portrait = category["photo_type"] == "portrait"
    return {
        "schema_version": 1,
        "photo_id": f"{candidate.provider}_{safe_id(candidate.provider_id)}",
        "photo_type": category["photo_type"],
        "scene_category": category["scene_category"],
        "query": query,
        "file_path": relative_path,
        "sha256": digest,
        "width": width,
        "height": height,
        "reported_width": candidate.reported_width,
        "reported_height": candidate.reported_height,
        "source": {
            "provider": candidate.provider,
            "provider_id": candidate.provider_id,
            "landing_url": candidate.landing_url,
            "asset_url": asset_url,
            "creator": candidate.creator,
            "creator_url": candidate.creator_url,
            "title": candidate.title,
            "tags": list(candidate.tags),
        },
        "rights": {
            "license": candidate.license,
            "license_url": candidate.license_url,
            "attribution": candidate.attribution,
            "verification_status": "provider_declared_pending_review",
        },
        "review": {
            "quality_status": "pending",
            "rights_status": "pending",
            "portrait_release_status": "pending" if is_portrait else "not_applicable",
        },
    }


def relative_file_path(destination: Path, manifest_path: Path) -> str:
    try:
        return destination.relative_to(manifest_path.parent).as_posix()
    except ValueError:
        return destination.resolve().as_posix()


def collect(args: argparse.Namespace) -> int:
    config = load_json(args.config)
    validate_config(config)
    provider_names = resolve_provider_names(args.provider)
    validate_source_policy(provider_names, args.source_policy)
    defaults = config["defaults"]
    allowed_licenses = set(defaults["licenses"])
    if args.dry_run:
        total = sum(category["target_count"] for category in config["categories"])
        print(
            "Config OK: "
            f"{len(config['categories'])} categories, target={total}, "
            f"providers={','.join(provider_names)}"
        )
        if "flickr" in provider_names and not os.environ.get("FLICKR_API_KEY"):
            print("Note: --provider flickr requires FLICKR_API_KEY at collection time")
        return 0

    adapters = build_adapters(provider_names)
    output_root: Path = args.output_dir
    manifest_path: Path = args.manifest
    resume = load_resume_state(manifest_path)
    seen_keys = set(resume.provider_keys)
    seen_hashes = set(resume.hashes)
    attempted_keys = set(seen_keys)
    selected_categories = set(args.category) if args.category else None
    accepted_total = 0
    errors_path = manifest_path.with_name("collection_errors.jsonl")

    for category in iter_categories(config, selected_categories):
        excluded_terms = excluded_terms_for_category(defaults, category)
        for adapter in adapters:
            for query in category["queries"]:
                accepted_query = 0
                examined_query = 0
                page = 1
                while accepted_query < args.limit_per_query and examined_query < args.max_candidates_per_query:
                    try:
                        candidates, has_more = adapter.search(query, allowed_licenses, page, args.page_size)
                    except CollectionError as exc:
                        append_jsonl(
                            errors_path,
                            {
                                "provider": adapter.name,
                                "stage": "search",
                                "query": query,
                                "page": page,
                                "error": str(exc),
                            },
                        )
                        print(f"search skipped for {adapter.name}:{query!r}: {exc}", file=sys.stderr, flush=True)
                        if args.error_delay:
                            time.sleep(args.error_delay)
                        break
                    if not candidates:
                        break
                    for candidate in candidates:
                        if accepted_query >= args.limit_per_query:
                            break
                        key = provider_key(candidate)
                        if key in attempted_keys:
                            continue
                        attempted_keys.add(key)
                        examined_query += 1
                        if not metadata_is_eligible(
                            candidate,
                            allowed_licenses,
                            defaults["min_long_edge"],
                            defaults["min_short_edge"],
                            defaults["mobile_aspect_ratio"],
                            defaults["aspect_ratio_tolerance"],
                            excluded_terms,
                        ):
                            continue
                        try:
                            downloaded = None
                            asset_url = ""
                            download_errors: list[str] = []
                            for candidate_url in candidate.asset_urls:
                                try:
                                    downloaded = download_image(candidate_url, args.max_bytes)
                                    asset_url = candidate_url
                                    break
                                except CollectionError as exc:
                                    download_errors.append(str(exc))
                            if downloaded is None:
                                raise CollectionError("; ".join(download_errors))
                            payload, width, height, extension = downloaded
                            if max(width, height) < defaults["min_long_edge"] or min(width, height) < defaults["min_short_edge"]:
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
                                seen_keys.add(key)
                                continue
                            destination = (
                                output_root
                                / category["photo_type"]
                                / category["scene_category"]
                                / f"{candidate.provider}_{safe_id(candidate.provider_id)}{extension}"
                            )
                            destination.parent.mkdir(parents=True, exist_ok=True)
                            destination.write_bytes(payload)
                            relative_path = relative_file_path(destination, manifest_path)
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
                            seen_keys.add(key)
                            seen_hashes.add(digest)
                            accepted_query += 1
                            accepted_total += 1
                            print(f"accepted {record['photo_id']} -> {relative_path}", flush=True)
                        except (CollectionError, OSError) as exc:
                            append_jsonl(
                                errors_path,
                                {
                                    "provider": candidate.provider,
                                    "provider_id": candidate.provider_id,
                                    "query": query,
                                    "error": str(exc),
                                },
                            )
                    page += 1
                    if not has_more:
                        break
                    if args.request_delay:
                        time.sleep(args.request_delay)

    print(f"Collection complete: accepted={accepted_total}, manifest={manifest_path}", flush=True)
    return 0


def build_parser() -> argparse.ArgumentParser:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=script_dir / "config" / "search_queries.json")
    parser.add_argument("--source-policy", type=Path, default=script_dir / "config" / "source_policy.json")
    parser.add_argument("--output-dir", type=Path, default=script_dir.parent / "raw_photos")
    parser.add_argument("--manifest", type=Path, default=script_dir.parent / "raw_photos" / "manifest.jsonl")
    parser.add_argument("--provider", action="append", help="Provider: openverse, wikimedia_commons, flickr, or all")
    parser.add_argument("--category", action="append", help="Collect only this scene_category; repeatable")
    parser.add_argument("--limit-per-query", type=int, default=3, help="Accepted photos per query per provider")
    parser.add_argument("--page-size", type=int, default=20)
    parser.add_argument("--max-candidates-per-query", type=int, default=120)
    parser.add_argument("--max-bytes", type=int, default=40 * 1024 * 1024)
    parser.add_argument("--request-delay", type=float, default=0.35)
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
