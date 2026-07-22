#!/usr/bin/env python3
"""
Advanced visual annotation refiner.
Uses PIL image analysis to detect text overlays, UI screenshots, and quality issues.
Updates existing annotations with more accurate values.
"""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any

try:
    from PIL import Image, ImageFilter, ImageOps
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

BASE_DIR = Path("/Volumes/桌面/AI-camera/tools")
BATCH_DIR = BASE_DIR / "preprocessed" / "annotation_batches.new_batch"
RESULTS_DIR = BATCH_DIR / "results"


def resolve_image_path(image_path_rel: str) -> Path:
    return BASE_DIR / image_path_rel.replace("../", "")


def extract_source_title(item: dict) -> str:
    prompt = item.get("prompt", "")
    m = re.search(r'"source_title":\s*"([^"]+)"', prompt)
    return m.group(1) if m else ""


def detect_text_overlay_keywords(source_title: str) -> bool:
    keywords = [
        "拒绝", "技巧", "教程", "教学", "怎么拍", "如何拍", "PLOG", "VLOG",
        "Daily", "OOTD", "穿搭", "改造", "对比", "Before", "After",
        "CITY WALK", "DAY", "封面", "海报", "审美提升", "拍照",
        "构图", "摄影", "教程", "学会", "学会这", "一招",
        "拒绝❌", "再拍", "合影站位", "站位技巧",
        "LIFE", "PLOG", "plog",
    ]
    title_upper = source_title.upper()
    return any(kw.upper() in title_upper for kw in keywords)


def detect_ui_screenshot(img: "Image.Image") -> bool:
    """Detect if an image is a UI screenshot (camera app, phone UI, etc.)."""
    if not HAS_PIL:
        return False

    w, h = img.size
    # UI screenshots often have very uniform color regions (status bars, buttons)
    # Check for horizontal uniform bands at top/bottom
    try:
        # Sample horizontal strips
        top_strip = img.crop((0, 0, w, max(1, h // 20)))
        bottom_strip = img.crop((0, h - max(1, h // 20), w, h))

        # Check if strips have very low variance (uniform color = UI bar)
        top_gray = top_strip.convert("L")
        bottom_gray = bottom_strip.convert("L")

        top_pixels = list(top_gray.getdata())
        bottom_pixels = list(bottom_gray.getdata())

        if len(top_pixels) > 0:
            top_var = sum((p - sum(top_pixels) / len(top_pixels)) ** 2 for p in top_pixels) / len(top_pixels)
        else:
            top_var = 0

        if len(bottom_pixels) > 0:
            bottom_var = sum((p - sum(bottom_pixels) / len(bottom_pixels)) ** 2 for p in bottom_pixels) / len(bottom_pixels)
        else:
            bottom_var = 0

        # UI screenshots have very uniform bars (low variance)
        # But natural photos can also have uniform sky at top
        # So this is just one signal

        # Check for sharp horizontal edges (UI elements have sharp borders)
        edge_filter = img.convert("L").filter(ImageFilter.FIND_EDGES)
        edge_pixels = list(edge_filter.getdata())
        edge_mean = sum(edge_pixels) / len(edge_pixels) if edge_pixels else 0

        # UI screenshots tend to have high edge density from text/icons
        is_ui = top_var < 50 and edge_mean > 20
        return is_ui
    except Exception:
        return False


def analyze_image_advanced(path: Path) -> dict[str, Any]:
    """Advanced image analysis using PIL."""
    result = {
        "width": 0, "height": 0, "orientation": "unknown",
        "avg_brightness": 0.5, "contrast": "medium",
        "brightness_std": 0.0, "aspect_ratio": 1.0,
        "has_uniform_top_bar": False, "edge_density": 0.0,
        "color_variance": 0.0, "is_likely_ui": False,
    }

    if not HAS_PIL or not path.exists():
        return result

    try:
        with Image.open(path) as img:
            w, h = img.size
            if w > h * 1.1:
                orientation = "landscape"
            elif h > w * 1.1:
                orientation = "portrait"
            else:
                orientation = "square"

            gray = img.convert("L")
            pixels = list(gray.getdata())
            n = len(pixels)
            if n == 0:
                result.update({"width": w, "height": h, "orientation": orientation})
                return result

            avg_brightness = sum(pixels) / n / 255.0
            variance = sum((p - avg_brightness * 255.0) ** 2 for p in pixels) / n
            brightness_std = math.sqrt(variance) / 255.0

            if brightness_std > 0.25:
                contrast = "high"
            elif brightness_std > 0.12:
                contrast = "medium"
            else:
                contrast = "low"

            # Edge density
            edge_img = gray.filter(ImageFilter.FIND_EDGES)
            edge_pixels = list(edge_img.getdata())
            edge_density = sum(edge_pixels) / len(edge_pixels) / 255.0 if edge_pixels else 0.0

            # Color variance (RGB channels)
            try:
                r, g, b = img.convert("RGB").split()
                r_mean = sum(list(r.getdata())) / n
                g_mean = sum(list(g.getdata())) / n
                b_mean = sum(list(b.getdata())) / n
                color_var = ((r_mean - 128) ** 2 + (g_mean - 128) ** 2 + (b_mean - 128) ** 2) / (3 * 128 ** 2)
            except Exception:
                color_var = 0.5

            # Detect uniform top bar (UI element)
            top_strip_h = max(1, h // 30)
            top_strip = gray.crop((0, 0, w, top_strip_h))
            top_pixels = list(top_strip.getdata())
            if top_pixels:
                top_avg = sum(top_pixels) / len(top_pixels)
                top_var = sum((p - top_avg) ** 2 for p in top_pixels) / len(top_pixels)
                has_uniform_top = top_var < 30 and (top_avg < 30 or top_avg > 220)
            else:
                has_uniform_top = False

            # UI detection heuristic
            is_ui = has_uniform_top and edge_density > 0.08

            result.update({
                "width": w, "height": h, "orientation": orientation,
                "avg_brightness": avg_brightness, "contrast": contrast,
                "brightness_std": brightness_std, "aspect_ratio": w / h if h else 1.0,
                "has_uniform_top_bar": has_uniform_top,
                "edge_density": edge_density,
                "color_variance": color_var,
                "is_likely_ui": is_ui,
            })
    except Exception:
        pass

    return result


def refine_annotation(item: dict, current_ann: dict, img_analysis: dict) -> dict:
    """Refine an annotation based on advanced image analysis."""
    ann = dict(current_ann)  # copy
    source_title = extract_source_title(item)
    has_text_keywords = detect_text_overlay_keywords(source_title)
    is_ui = img_analysis.get("is_likely_ui", False)
    brightness = img_analysis.get("avg_brightness", 0.5)
    edge_density = img_analysis.get("edge_density", 0.0)
    orientation = img_analysis.get("orientation", current_ann.get("camera", {}).get("orientation", "unknown"))

    # Update camera orientation from actual image
    if "camera" not in ann:
        ann["camera"] = {}
    ann["camera"]["orientation"] = orientation

    # Update lighting contrast from actual analysis
    if "lighting" not in ann:
        ann["lighting"] = {}
    ann["lighting"]["contrast"] = img_analysis.get("contrast", "medium")

    # Strong text overlay detection
    is_text_heavy = has_text_keywords or is_ui or edge_density > 0.12

    # Determine correct template_value
    current_value = ann.get("template_value", "medium")

    if is_ui:
        # UI screenshots should be rejected
        ann["template_value"] = "reject"
        ann["aesthetic_score"] = 1.0
        ann["visual_summary"] = ann.get("visual_summary", "")[:100] + " [UI screenshot - not a usable photo template]"
    elif is_text_heavy:
        # Text-heavy images should be low value
        if ann["template_value"] not in ("low", "reject"):
            ann["template_value"] = "low"
            ann["aesthetic_score"] = min(ann.get("aesthetic_score", 3.0), 2.5)
    else:
        # Clean images: upgrade if they look good
        if brightness > 0.15 and brightness < 0.85:
            # Normal brightness range - could be good
            scene = ann.get("scene_category", "")
            # Landscape/nature images tend to be high value
            if scene in ("natural_landscape", "sunset_landscape", "mountain_landscape", "seascape"):
                if ann["template_value"] in ("medium", "low"):
                    ann["template_value"] = "high"
                    ann["aesthetic_score"] = max(ann.get("aesthetic_score", 3.0), 4.0)

    # Update visual summary to be more specific
    if not is_text_heavy and not is_ui:
        brightness_desc = "bright" if brightness > 0.65 else "dark" if brightness < 0.35 else "balanced"
        scene = ann.get("scene_category", "")
        orientation = ann["camera"].get("orientation", "unknown")

        summary_templates = {
            "closeup_portrait": f"A close-up portrait in {orientation} orientation with {brightness_desc} exposure, focusing on the face and expression.",
            "group_portrait": f"A group portrait with multiple people posed together in {orientation} orientation with {brightness_desc} exposure.",
            "travel_portrait": f"A travel portrait set against an environmental backdrop in {orientation} framing with {brightness_desc} exposure.",
            "food_still_life": f"A food photography shot featuring a plated dish in {orientation} orientation with {brightness_desc} exposure.",
            "natural_landscape": f"A natural landscape scene captured in {orientation} framing with {brightness_desc} exposure.",
            "urban_landscape": f"An urban landscape or cityscape in {orientation} framing with {brightness_desc} exposure.",
            "creative_composition": f"A creative still-life composition with arranged objects in {orientation} framing.",
            "flat_lay": f"A flat-lay arrangement photographed from above in {orientation} framing.",
            "product_still_life": f"A product still-life shot with clean composition in {orientation} framing.",
            "street_portrait": f"A street portrait with urban environmental context in {orientation} framing.",
            "studio_portrait": f"A studio portrait with controlled lighting in {orientation} framing.",
            "couple_portrait": f"A couple portrait with two people posed together in {orientation} framing.",
            "selfie": f"A selfie-style portrait in {orientation} framing.",
            "plant_still_life": f"A botanical still-life of plants in {orientation} framing.",
            "flower_still_life": f"A flower still-life in {orientation} framing.",
            "seascape": f"A seascape with water and sky in {orientation} framing.",
            "mountain_landscape": f"A mountain landscape scene in {orientation} framing.",
            "sunset_landscape": f"A sunset landscape with dramatic sky colors in {orientation} framing.",
            "cityscape": f"A cityscape view in {orientation} framing.",
            "tabletop_still_life": f"A tabletop still-life of food and tableware in {orientation} framing.",
        }
        new_summary = summary_templates.get(scene, ann.get("visual_summary", ""))
        if new_summary and len(new_summary) <= 240:
            ann["visual_summary"] = new_summary

    # Ensure shooting_guidance is in English, 1-5 items
    guidance = ann.get("shooting_guidance", [])
    if guidance:
        # Filter out any items with Chinese characters
        guidance = [g for g in guidance if not any('一' <= c <= '鿿' for c in g)]
        if not guidance:
            guidance = ["Keep the subject well-framed using the rule of thirds.", "Use appropriate lighting for the scene."]
        guidance = guidance[:5]
        ann["shooting_guidance"] = guidance

    return ann


def process_all_batches():
    """Process all batches and refine annotations."""
    batch_files = sorted(BATCH_DIR.glob("annotation_batch_*.jsonl"))
    batch_files = [f for f in batch_files if "results" not in f.name]

    total_corrected = 0
    total_items = 0

    for batch_file in batch_files:
        batch_id = batch_file.stem
        result_file = RESULTS_DIR / f"{batch_id}.results.jsonl"

        if not result_file.exists():
            print(f"  SKIP {batch_id}: no results file")
            continue

        # Load current annotations
        annotations = {}
        with result_file.open("r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    ann = json.loads(line)
                    annotations[ann["photo_id"]] = ann

        # Load batch items
        items = []
        with batch_file.open("r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    items.append(json.loads(line))

        corrected = 0
        for item in items:
            photo_id = item["photo_id"]
            if photo_id not in annotations:
                continue

            current_ann = annotations[photo_id]
            image_path = resolve_image_path(item["image_path"])
            img_analysis = analyze_image_advanced(image_path)

            refined = refine_annotation(item, current_ann, img_analysis)
            annotations[photo_id] = refined
            total_items += 1

            # Count corrections
            if refined.get("template_value") != current_ann.get("template_value"):
                corrected += 1

        # Write back
        with result_file.open("w", encoding="utf-8") as f:
            for item in items:
                pid = item["photo_id"]
                if pid in annotations:
                    f.write(json.dumps(annotations[pid], ensure_ascii=False, sort_keys=True) + "\n")

        total_corrected += corrected
        high = sum(1 for a in annotations.values() if a.get("template_value") == "high")
        medium = sum(1 for a in annotations.values() if a.get("template_value") == "medium")
        low = sum(1 for a in annotations.values() if a.get("template_value") == "low")
        reject = sum(1 for a in annotations.values() if a.get("template_value") == "reject")
        print(f"  {batch_id}: {len(items)} items, {corrected} corrected | high={high} med={medium} low={low} rej={reject}")

    print(f"\nTotal: {total_items} items processed, {total_corrected} corrections applied.")


def main():
    print("Advanced visual annotation refinement")
    print(f"PIL: {HAS_PIL}")
    print()
    process_all_batches()


if __name__ == "__main__":
    main()
