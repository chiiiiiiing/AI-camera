#!/usr/bin/env python3
"""Collect authorized social-media portrait candidates.

This collector is for content where the project has explicit permission from
the platform/account/creator. It uses official APIs or user-provided exports;
it does not automate browsers, bypass login walls, solve captchas, reuse
personal cookies, or scrape private/social feeds.
"""

from __future__ import annotations

import argparse
import hashlib
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
from typing import Any, Iterable

from collect_openverse import CollectionError, download_image, load_json, matches_mobile_aspect_ratio
from collect_public_photos import contains_excluded_term


USER_AGENT = "ai-camera-authorized-social-portrait-collector/0.1"
DEFAULT_GRAPH_VERSION = "v25.0"
INSTAGRAM_GRAPH_URL = "https://graph.facebook.com"
TIKTOK_VIDEO_LIST_URL = "https://open.tiktokapis.com/v2/video/list/"
WEIBO_TIMELINE_URL = "https://api.weibo.com/2/statuses/user_timeline.json"
SUPPORTED_PLATFORMS = {"instagram", "tiktok", "weibo", "xiaohongshu_export", "export"}


@dataclass(frozen=True)
class SocialAsset:
    platform: str
    provider_id: str
    asset_url: str
    landing_url: str
    creator: str | None
    title: str | None
    caption: str | None
    timestamp: str | int | None
    media_kind: str
    authorization_id: str
    authorization_note: str
    account_id: str | None = None
    username: str | None = None


@dataclass(frozen=True)
class ResumeState:
    provider_keys: frozenset[str]
    hashes: frozenset[str]


def http_json(
    url: str,
    *,
    params: dict[str, Any] | None = None,
    body: dict[str, Any] | None = None,
    bearer_token: str | None = None,
    timeout: int = 45,
) -> dict[str, Any]:
    data = None
    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    request_url = url
    if params:
        request_url = f"{url}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(request_url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
    except urllib.error.HTTPError as exc:
        error_body = exc.read(500).decode("utf-8", errors="replace")
        raise CollectionError(f"HTTP {exc.code} for {url}: {error_body}") from exc
    except urllib.error.URLError as exc:
        raise CollectionError(f"network error for {url}: {exc.reason}") from exc
    try:
        value = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise CollectionError(f"invalid JSON from {url}") from exc
    if not isinstance(value, dict):
        raise CollectionError(f"unexpected JSON payload from {url}")
    return value


def normalize_platform(value: str) -> str:
    platform = value.strip().lower()
    if platform not in SUPPORTED_PLATFORMS:
        raise ValueError(f"unsupported platform: {value}")
    return platform


def safe_id(value: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip())
    return sanitized.strip("._")[:140] or "unknown"


def load_resume_state(manifest_path: Path) -> ResumeState:
    provider_keys: set[str] = set()
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
                provider_keys.add(f"{source['platform']}:{source['provider_id']}")
                hashes.add(str(record["sha256"]))
            except (KeyError, TypeError, json.JSONDecodeError) as exc:
                raise ValueError(f"invalid manifest line {line_number}: {exc}") from exc
    return ResumeState(frozenset(provider_keys), frozenset(hashes))


def load_authorizations(path: Path) -> list[dict[str, Any]]:
    value = load_json(path)
    if value.get("schema_version") != 1:
        raise ValueError("authorization manifest schema_version must be 1")
    authorizations = value.get("authorizations")
    if not isinstance(authorizations, list) or not authorizations:
        raise ValueError("authorization manifest requires a non-empty authorizations list")
    normalized: list[dict[str, Any]] = []
    for index, authorization in enumerate(authorizations, 1):
        if not isinstance(authorization, dict):
            raise ValueError(f"authorization #{index} must be an object")
        platform = normalize_platform(str(authorization.get("platform") or ""))
        authorization_id = str(authorization.get("authorization_id") or "").strip()
        if not authorization_id:
            raise ValueError(f"authorization #{index} requires authorization_id")
        authorization_note = str(authorization.get("authorization_note") or authorization.get("proof") or "").strip()
        if not authorization_note:
            raise ValueError(f"authorization #{index} requires authorization_note or proof")
        normalized_item = dict(authorization)
        normalized_item["platform"] = platform
        normalized_item["authorization_id"] = authorization_id
        normalized_item["authorization_note"] = authorization_note
        normalized.append(normalized_item)
    return normalized


def read_token(auth: dict[str, Any], default_env: str) -> str:
    token_env = str(auth.get("access_token_env") or default_env)
    token = os.environ.get(token_env)
    if not token:
        raise CollectionError(f"{token_env} is required for {auth['platform']} authorization {auth['authorization_id']}")
    return token


def parse_export_jsonl(path: Path, auth: dict[str, Any]) -> Iterable[SocialAsset]:
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid export JSON on line {line_number}: {exc}") from exc
            if not isinstance(item, dict):
                raise ValueError(f"export line {line_number} is not an object")
            platform = normalize_platform(str(item.get("platform") or auth["platform"]))
            asset_url = str(item.get("image_url") or item.get("asset_url") or "")
            provider_id = str(item.get("media_id") or item.get("provider_id") or "")
            landing_url = str(item.get("landing_url") or item.get("permalink") or "")
            if not asset_url.startswith(("http://", "https://")) or not provider_id or not landing_url:
                raise ValueError(f"export line {line_number} requires image_url, media_id, and landing_url")
            yield SocialAsset(
                platform=platform,
                provider_id=provider_id,
                asset_url=asset_url,
                landing_url=landing_url,
                creator=item.get("creator") or item.get("username"),
                title=item.get("title"),
                caption=item.get("caption"),
                timestamp=item.get("timestamp"),
                media_kind=str(item.get("media_kind") or "image"),
                authorization_id=auth["authorization_id"],
                authorization_note=auth["authorization_note"],
                account_id=item.get("account_id") or auth.get("account_id"),
                username=item.get("username") or auth.get("username"),
            )


def iter_instagram_assets(auth: dict[str, Any], graph_version: str, max_pages: int) -> Iterable[SocialAsset]:
    token = read_token(auth, "INSTAGRAM_ACCESS_TOKEN")
    account_id = str(auth.get("account_id") or auth.get("ig_user_id") or "")
    if not account_id:
        raise CollectionError(f"instagram authorization {auth['authorization_id']} requires account_id")
    allowed_ids = set(str(value) for value in auth.get("allowed_media_ids", []) if value)
    fields = "id,caption,media_type,media_url,permalink,timestamp,username,children{media_type,media_url,id,permalink}"
    url = f"{INSTAGRAM_GRAPH_URL}/{graph_version}/{account_id}/media"
    params: dict[str, Any] | None = {"fields": fields, "limit": int(auth.get("page_size") or 50), "access_token": token}
    pages = 0
    while url and pages < max_pages:
        payload = http_json(url, params=params)
        for item in payload.get("data", []):
            if not isinstance(item, dict):
                continue
            media_id = str(item.get("id") or "")
            if allowed_ids and media_id not in allowed_ids:
                continue
            media_type = str(item.get("media_type") or "")
            if media_type == "IMAGE" and item.get("media_url"):
                yield SocialAsset(
                    platform="instagram",
                    provider_id=media_id,
                    asset_url=str(item["media_url"]),
                    landing_url=str(item.get("permalink") or ""),
                    creator=item.get("username") or auth.get("username"),
                    title=None,
                    caption=item.get("caption"),
                    timestamp=item.get("timestamp"),
                    media_kind="image",
                    authorization_id=auth["authorization_id"],
                    authorization_note=auth["authorization_note"],
                    account_id=account_id,
                    username=item.get("username") or auth.get("username"),
                )
            if media_type == "CAROUSEL_ALBUM":
                children = item.get("children", {}).get("data", []) if isinstance(item.get("children"), dict) else []
                for child in children:
                    if not isinstance(child, dict) or child.get("media_type") != "IMAGE" or not child.get("media_url"):
                        continue
                    yield SocialAsset(
                        platform="instagram",
                        provider_id=f"{media_id}_{child.get('id')}",
                        asset_url=str(child["media_url"]),
                        landing_url=str(child.get("permalink") or item.get("permalink") or ""),
                        creator=item.get("username") or auth.get("username"),
                        title=None,
                        caption=item.get("caption"),
                        timestamp=item.get("timestamp"),
                        media_kind="carousel_image",
                        authorization_id=auth["authorization_id"],
                        authorization_note=auth["authorization_note"],
                        account_id=account_id,
                        username=item.get("username") or auth.get("username"),
                    )
        paging = payload.get("paging") if isinstance(payload.get("paging"), dict) else {}
        url = paging.get("next")
        params = None
        pages += 1


def iter_tiktok_assets(auth: dict[str, Any], max_pages: int) -> Iterable[SocialAsset]:
    token = read_token(auth, "TIKTOK_ACCESS_TOKEN")
    cursor = int(auth.get("cursor") or 0)
    max_count = int(auth.get("page_size") or 20)
    pages = 0
    while pages < max_pages:
        payload = http_json(
            TIKTOK_VIDEO_LIST_URL,
            params={"fields": "id,cover_image_url,share_url,create_time,title,video_description,width,height"},
            body={"max_count": max_count, "cursor": cursor},
            bearer_token=token,
        )
        data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
        for item in data.get("videos", []):
            if not isinstance(item, dict) or not item.get("cover_image_url") or not item.get("id"):
                continue
            yield SocialAsset(
                platform="tiktok",
                provider_id=str(item["id"]),
                asset_url=str(item["cover_image_url"]),
                landing_url=str(item.get("share_url") or ""),
                creator=auth.get("username"),
                title=item.get("title"),
                caption=item.get("video_description"),
                timestamp=item.get("create_time"),
                media_kind="video_cover_image",
                authorization_id=auth["authorization_id"],
                authorization_note=auth["authorization_note"],
                account_id=auth.get("account_id"),
                username=auth.get("username"),
            )
        if not data.get("has_more"):
            break
        cursor = int(data.get("cursor") or 0)
        pages += 1


def iter_weibo_assets(auth: dict[str, Any], max_pages: int) -> Iterable[SocialAsset]:
    token = read_token(auth, "WEIBO_ACCESS_TOKEN")
    uid = str(auth.get("uid") or auth.get("account_id") or "")
    if not uid:
        raise CollectionError(f"weibo authorization {auth['authorization_id']} requires uid")
    for page in range(1, max_pages + 1):
        payload = http_json(
            WEIBO_TIMELINE_URL,
            params={"access_token": token, "uid": uid, "count": int(auth.get("page_size") or 50), "page": page},
        )
        statuses = payload.get("statuses", [])
        if not isinstance(statuses, list) or not statuses:
            break
        for status in statuses:
            if not isinstance(status, dict):
                continue
            status_id = str(status.get("idstr") or status.get("id") or "")
            user = status.get("user") if isinstance(status.get("user"), dict) else {}
            creator = user.get("screen_name") or auth.get("username")
            candidates: list[str] = []
            if status.get("original_pic"):
                candidates.append(str(status["original_pic"]))
            for pic in status.get("pic_urls") or []:
                if isinstance(pic, dict) and pic.get("thumbnail_pic"):
                    candidates.append(str(pic["thumbnail_pic"]).replace("/thumbnail/", "/large/"))
            for index, asset_url in enumerate(dict.fromkeys(candidates)):
                if not asset_url.startswith(("http://", "https://")):
                    continue
                yield SocialAsset(
                    platform="weibo",
                    provider_id=f"{status_id}_{index}",
                    asset_url=asset_url,
                    landing_url=f"https://weibo.com/{uid}/{status_id}",
                    creator=creator,
                    title=None,
                    caption=status.get("text"),
                    timestamp=status.get("created_at"),
                    media_kind="image",
                    authorization_id=auth["authorization_id"],
                    authorization_note=auth["authorization_note"],
                    account_id=uid,
                    username=creator,
                )


def iter_assets(auth: dict[str, Any], graph_version: str, max_pages: int) -> Iterable[SocialAsset]:
    platform = auth["platform"]
    if platform == "instagram":
        yield from iter_instagram_assets(auth, graph_version, max_pages)
    elif platform == "tiktok":
        yield from iter_tiktok_assets(auth, max_pages)
    elif platform == "weibo":
        yield from iter_weibo_assets(auth, max_pages)
    elif platform in {"xiaohongshu_export", "export"}:
        export_path = Path(str(auth.get("export_path") or ""))
        if not export_path.exists():
            raise CollectionError(f"export_path does not exist for {auth['authorization_id']}: {export_path}")
        yield from parse_export_jsonl(export_path, auth)


def load_portrait_defaults(path: Path) -> dict[str, Any]:
    config = load_json(path)
    defaults = config.get("defaults")
    if not isinstance(defaults, dict):
        raise ValueError("portrait config requires defaults")
    return defaults


def asset_is_metadata_eligible(asset: SocialAsset, defaults: dict[str, Any]) -> bool:
    terms = set(defaults.get("excluded_media_terms", []))
    terms.update(defaults.get("excluded_ai_terms", []))
    terms.update(defaults.get("excluded_portrait_terms", []))
    return not contains_excluded_term(
        [asset.title or "", asset.caption or "", asset.creator or "", asset.username or ""],
        terms,
    )


def make_record(
    asset: SocialAsset,
    relative_path: str,
    digest: str,
    width: int,
    height: int,
    reviewed_at: str | None = None,
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "candidate_id": f"{asset.platform}_{safe_id(asset.provider_id)}",
        "photo_type": "portrait",
        "scene_category": "authorized_social_portrait",
        "file_path": relative_path,
        "sha256": digest,
        "width": width,
        "height": height,
        "source": {
            "platform": asset.platform,
            "provider_id": asset.provider_id,
            "landing_url": asset.landing_url,
            "asset_url": asset.asset_url,
            "creator": asset.creator,
            "account_id": asset.account_id,
            "username": asset.username,
            "title": asset.title,
            "caption": asset.caption,
            "timestamp": asset.timestamp,
            "media_kind": asset.media_kind,
        },
        "authorization": {
            "authorization_id": asset.authorization_id,
            "authorization_note": asset.authorization_note,
            "verification_status": "user_declared_pending_review",
        },
        "review": {
            "quality_status": "pending",
            "rights_status": "pending",
            "portrait_release_status": "pending",
            "reviewed_at": reviewed_at,
        },
    }


def append_jsonl(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def collect(args: argparse.Namespace) -> int:
    defaults = load_portrait_defaults(args.portrait_config)
    authorizations = load_authorizations(args.authorization_manifest)
    if args.dry_run:
        platforms = sorted({auth["platform"] for auth in authorizations})
        print(f"Config OK: authorizations={len(authorizations)}, platforms={','.join(platforms)}")
        return 0

    output_dir: Path = args.output_dir
    manifest_path: Path = args.manifest
    errors_path = manifest_path.with_name("collection_errors.jsonl")
    resume = load_resume_state(manifest_path)
    seen_keys = set(resume.provider_keys)
    seen_hashes = set(resume.hashes)
    accepted = 0
    examined = 0

    for auth in authorizations:
        try:
            assets = iter_assets(auth, args.meta_graph_version, args.max_pages)
            for asset in assets:
                if accepted >= args.max_total:
                    break
                key = f"{asset.platform}:{asset.provider_id}"
                if key in seen_keys:
                    continue
                seen_keys.add(key)
                examined += 1
                if not asset_is_metadata_eligible(asset, defaults):
                    append_jsonl(errors_path, {"provider_key": key, "stage": "metadata_filter", "error": "excluded_terms"})
                    continue
                try:
                    payload, width, height, extension = download_image(asset.asset_url, args.max_bytes)
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
                        continue
                    destination = output_dir / asset.platform / f"{asset.platform}_{safe_id(asset.provider_id)}{extension}"
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(payload)
                    relative_path = destination.relative_to(manifest_path.parent).as_posix()
                    record = make_record(asset, relative_path, digest, width, height)
                    append_jsonl(manifest_path, record)
                    seen_hashes.add(digest)
                    accepted += 1
                    print(f"accepted {record['candidate_id']} -> {relative_path}", flush=True)
                    if args.request_delay:
                        time.sleep(args.request_delay)
                except (CollectionError, OSError) as exc:
                    append_jsonl(errors_path, {"provider_key": key, "stage": "download", "error": str(exc)})
        except (CollectionError, OSError, ValueError) as exc:
            append_jsonl(
                errors_path,
                {
                    "authorization_id": auth.get("authorization_id"),
                    "platform": auth.get("platform"),
                    "stage": "source",
                    "error": str(exc),
                },
            )
            print(f"source skipped for {auth.get('authorization_id')}: {exc}", file=sys.stderr, flush=True)
        if accepted >= args.max_total:
            break

    print(f"Collection complete: examined={examined}, accepted={accepted}, manifest={manifest_path}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    script_dir = Path(__file__).resolve().parent
    default_output = script_dir.parent / "raw_photos" / "authorized_social_portraits"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("authorization_manifest", type=Path)
    parser.add_argument("--portrait-config", type=Path, default=script_dir / "config" / "portrait_search_queries.json")
    parser.add_argument("--output-dir", type=Path, default=default_output)
    parser.add_argument("--manifest", type=Path, default=default_output / "manifest.jsonl")
    parser.add_argument("--meta-graph-version", default=DEFAULT_GRAPH_VERSION)
    parser.add_argument("--max-pages", type=int, default=5)
    parser.add_argument("--max-total", type=int, default=200)
    parser.add_argument("--max-bytes", type=int, default=40 * 1024 * 1024)
    parser.add_argument("--request-delay", type=float, default=0.5)
    parser.add_argument("--dry-run", action="store_true")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.max_pages < 1 or args.max_total < 1:
        parser.error("--max-pages and --max-total must be positive")
    try:
        return collect(args)
    except (CollectionError, OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
