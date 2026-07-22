#!/usr/bin/env python3
"""Create a short-ID thumbnail review pack for human photo screening."""

from __future__ import annotations

import argparse
import html
import json
import math
import sys
from pathlib import Path
from typing import Any

TYPE_PREFIX = {
    "portrait": "P",
    "landscape": "L",
    "still_life": "S",
}


def load_records(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
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
            records.append(value)
    return records


def selected_records(
    records: list[dict[str, Any]],
    photo_type: str | None,
    quality_status: str,
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for record in records:
        if photo_type and record.get("photo_type") != photo_type:
            continue
        if record.get("review", {}).get("quality_status") != quality_status:
            continue
        result.append(record)
    return sorted(result, key=lambda r: (r.get("photo_type", ""), r.get("scene_category", ""), r.get("photo_id", "")))


def assign_short_ids(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    counters: dict[str, int] = {}
    rows: list[dict[str, Any]] = []
    for record in records:
        photo_type = str(record.get("photo_type"))
        prefix = TYPE_PREFIX.get(photo_type, "X")
        counters[prefix] = counters.get(prefix, 0) + 1
        short_id = f"{prefix}{counters[prefix]:03d}"
        rows.append({"short_id": short_id, "record": record})
    return rows


def make_thumbnail(image_path: Path, thumb_path: Path, size: int) -> tuple[int, int]:
    from PIL import Image, ImageOps

    with Image.open(image_path) as image:
        image = ImageOps.exif_transpose(image).convert("RGB")
        image.thumbnail((size, size), Image.Resampling.LANCZOS)
        thumb_path.parent.mkdir(parents=True, exist_ok=True)
        image.save(thumb_path, quality=88)
        return image.size


def write_index_jsonl(path: Path, rows: list[dict[str, Any]], manifest_dir: Path) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            record = row["record"]
            source = record.get("source", {})
            handle.write(
                json.dumps(
                    {
                        "short_id": row["short_id"],
                        "photo_id": record.get("photo_id"),
                        "photo_type": record.get("photo_type"),
                        "scene_category": record.get("scene_category"),
                        "file_path": record.get("file_path"),
                        "absolute_path": str(manifest_dir / record.get("file_path", "")),
                        "title": source.get("title"),
                        "landing_url": source.get("landing_url"),
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
                + "\n"
            )


def write_html(path: Path, rows: list[dict[str, Any]], thumb_dir: Path, manifest_dir: Path) -> None:
    cards: list[str] = []
    for row in rows:
        record = row["record"]
        short_id = row["short_id"]
        source = record.get("source", {})
        title = source.get("title") or ""
        category = record.get("scene_category", "")
        image_path = manifest_dir / record.get("file_path", "")
        thumb_name = f"{short_id}.jpg"
        cards.append(
            f"""
<article class="card">
  <a href="{html.escape(image_path.as_posix())}"><img src="{html.escape((thumb_dir / thumb_name).relative_to(path.parent).as_posix())}" alt="{html.escape(short_id)}"></a>
  <div class="id">{html.escape(short_id)}</div>
  <div class="meta">{html.escape(str(category))}</div>
  <div class="title">{html.escape(str(title))}</div>
</article>"""
        )
    path.write_text(
        f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AI Camera Review Pack</title>
  <style>
    body {{ margin: 24px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f6f7f9; color: #111; }}
    h1 {{ font-size: 22px; margin: 0 0 8px; }}
    p {{ margin: 0 0 18px; color: #555; }}
    .grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 14px; }}
    .card {{ background: white; border: 1px solid #ddd; border-radius: 8px; padding: 10px; }}
    img {{ width: 100%; height: 190px; object-fit: contain; background: #eee; border-radius: 6px; }}
    .id {{ font-size: 22px; font-weight: 800; margin-top: 8px; }}
    .meta {{ font-size: 12px; color: #666; margin-top: 2px; }}
    .title {{ font-size: 12px; color: #333; margin-top: 6px; line-height: 1.35; word-break: break-word; }}
  </style>
</head>
<body>
  <h1>AI Camera Review Pack</h1>
  <p>用短编号沟通：例如“拒绝 P001 P004 P009”。点击缩略图可打开原图。</p>
  <section class="grid">
    {''.join(cards)}
  </section>
</body>
</html>
""",
        encoding="utf-8",
    )


def write_contact_sheet(path: Path, rows: list[dict[str, Any]], thumb_dir: Path) -> None:
    from PIL import Image, ImageDraw, ImageFont

    if not rows:
        return
    cell_w, cell_h = 260, 320
    cols = min(4, max(1, math.ceil(math.sqrt(len(rows)))))
    rows_count = math.ceil(len(rows) / cols)
    sheet = Image.new("RGB", (cell_w * cols, cell_h * rows_count), "white")
    draw = ImageDraw.Draw(sheet)
    try:
        font_large = ImageFont.truetype("Arial.ttf", 24)
        font_small = ImageFont.truetype("Arial.ttf", 13)
    except OSError:
        font_large = ImageFont.load_default()
        font_small = ImageFont.load_default()
    for index, row in enumerate(rows):
        x = (index % cols) * cell_w
        y = (index // cols) * cell_h
        thumb_path = thumb_dir / f"{row['short_id']}.jpg"
        with Image.open(thumb_path) as thumb:
            thumb = thumb.convert("RGB")
            tx = x + (cell_w - thumb.width) // 2
            sheet.paste(thumb, (tx, y + 10))
        record = row["record"]
        draw.text((x + 12, y + 220), row["short_id"], fill="black", font=font_large)
        draw.text((x + 12, y + 252), str(record.get("scene_category", ""))[:34], fill="#444", font=font_small)
        title = str(record.get("source", {}).get("title") or "")[:54]
        draw.text((x + 12, y + 274), title, fill="#222", font=font_small)
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path, quality=92)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--photo-type", choices=("portrait", "landscape", "still_life"))
    parser.add_argument("--quality-status", default="accepted", choices=("pending", "accepted", "rejected"))
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--thumb-size", type=int, default=220)
    args = parser.parse_args()
    output_dir = args.output_dir or args.manifest.parent / "review_pack"
    thumb_dir = output_dir / "thumbs"
    try:
        records = selected_records(load_records(args.manifest), args.photo_type, args.quality_status)
        rows = assign_short_ids(records)
        output_dir.mkdir(parents=True, exist_ok=True)
        for row in rows:
            record = row["record"]
            make_thumbnail(args.manifest.parent / record["file_path"], thumb_dir / f"{row['short_id']}.jpg", args.thumb_size)
        write_index_jsonl(output_dir / "review_index.jsonl", rows, args.manifest.parent)
        write_html(output_dir / "index.html", rows, thumb_dir, args.manifest.parent)
        write_contact_sheet(output_dir / "contact_sheet.jpg", rows, thumb_dir)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"Review pack: {output_dir / 'index.html'}")
    print(f"Contact sheet: {output_dir / 'contact_sheet.jpg'}")
    print(f"Index: {output_dir / 'review_index.jsonl'}")
    print(f"Items: {len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
