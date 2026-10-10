#!/usr/bin/env python3
"""
Enhanced visual annotation generator for new_batch images.
Reads each image, extracts visual properties using PIL, detects text overlays
from source titles, and produces aesthetic annotations following the schema.
"""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

BASE_DIR = Path("/Volumes/桌面/AI-camera/tools")
BATCH_DIR = BASE_DIR / "preprocessed" / "annotation_batches.new_batch"
RESULTS_DIR = BATCH_DIR / "results"

SCENE_TO_SUBJECT = {
    "closeup_portrait": "person",
    "group_portrait": "person",
    "travel_portrait": "person",
    "environmental_portrait": "person",
    "couple_portrait": "person",
    "selfie": "person",
    "street_portrait": "person",
    "studio_portrait": "person",
    "natural_landscape": "landscape",
    "urban_landscape": "architecture",
    "seascape": "landscape",
    "mountain_landscape": "landscape",
    "cityscape": "architecture",
    "sunset_landscape": "landscape",
    "food_still_life": "food",
    "product_still_life": "object",
    "creative_composition": "object",
    "flat_lay": "object",
    "tabletop_still_life": "food",
    "plant_still_life": "plant",
    "flower_still_life": "plant",
}

TEXT_OVERLAY_KEYWORDS = [
    "拒绝", "技巧", "教程", "教学", "怎么拍", "如何拍", "PLOG", "VLOG",
    "Daily", "OOTD", "穿搭", "改造", "对比", "Before", "After",
    "CITY WALK", "DAY", "LIFE", "PLOG", "封面", "海报",
]

SCENE_GUIDANCE = {
    "closeup_portrait": [
        "Frame the face tightly with eyes in the upper third of the frame.",
        "Use soft, even lighting to highlight facial features without harsh shadows.",
        "Keep the background simple and out of focus to draw attention to the face.",
        "Shoot at eye level for an intimate, engaging close-up.",
    ],
    "group_portrait": [
        "Arrange people at slightly different depths to create natural layering.",
        "Use a narrow aperture (f/5.6-f/8) to keep everyone in focus.",
        "Position the group using the rule of thirds with faces in the upper portion.",
        "Ensure even lighting across all subjects to avoid shadowed faces.",
        "Leave enough space around the group for comfortable framing.",
    ],
    "travel_portrait": [
        "Place the subject in the context of the environment with medium distance.",
        "Use the surrounding scenery as a complementary background.",
        "Shoot during golden hour for warm, atmospheric travel portrait lighting.",
        "Keep the subject sharp while allowing the background to show context.",
        "Use natural framing elements like doorways or arches when available.",
    ],
    "food_still_life": [
        "Shoot food from a 45-degree angle to show both the top and side.",
        "Use natural window light from the side for appetizing, dimensional lighting.",
        "Arrange props and garnishes to create a story around the dish.",
        "Use a shallow depth of field to focus on the most appealing part of the food.",
        "Keep the background neutral and clean to make the food stand out.",
    ],
    "natural_landscape": [
        "Place the horizon along the upper or lower third line for dynamic composition.",
        "Include a foreground element to add depth and lead the viewer's eye.",
        "Shoot during golden hour for warm, directional light.",
        "Use a wide-angle lens to capture the full breadth of the scene.",
        "Use a tripod and small aperture (f/8-f/11) for sharp detail throughout.",
    ],
    "urban_landscape": [
        "Use leading lines from streets or buildings to draw the eye through the frame.",
        "Shoot during blue hour for a balance of artificial and natural light.",
        "Look for reflections in glass or water to add visual interest.",
        "Use a medium aperture (f/5.6-f/8) to keep both foreground and background sharp.",
        "Frame architectural details using natural frames like windows or arches.",
    ],
    "creative_composition": [
        "Use unusual angles or perspectives to make ordinary objects feel fresh.",
        "Play with negative space to create visual tension and interest.",
        "Use consistent color tones across all elements for a cohesive look.",
        "Arrange items with varying heights and textures for depth.",
        "Shoot with a shallow depth of field to isolate the key element.",
    ],
    "flat_lay": [
        "Arrange items on a clean, neutral background for a polished flat-lay.",
        "Shoot directly from above with even, diffused lighting.",
        "Use the rule of thirds to position the main subject off-center.",
        "Leave some negative space for a clean, uncluttered composition.",
        "Vary the sizes and shapes of objects for visual rhythm.",
    ],
    "product_still_life": [
        "Use soft, even lighting from two sides to eliminate harsh shadows.",
        "Keep the background minimal to keep focus on the product.",
        "Shoot at a slight angle to show both the front and top of the product.",
        "Use props sparingly to add context without distracting from the product.",
        "Ensure the product is sharply in focus across the entire frame.",
    ],
    "street_portrait": [
        "Use the urban environment as a natural backdrop for the portrait.",
        "Shoot during golden hour for warm, directional side lighting.",
        "Position the subject using the rule of thirds with the city as context.",
        "Use a wide aperture to blur busy backgrounds and isolate the subject.",
        "Capture candid moments for authentic street portrait energy.",
    ],
    "studio_portrait": [
        "Use a key light at 45 degrees for classic portrait lighting.",
        "Add a fill light or reflector to soften shadows on the face.",
        "Use a seamless backdrop for clean, distraction-free portraits.",
        "Position the subject slightly off-center for dynamic composition.",
        "Use a medium telephoto lens (85-105mm) for flattering facial proportions.",
    ],
    "couple_portrait": [
        "Position the couple close together with natural body language.",
        "Use soft, even lighting to flatter both subjects equally.",
        "Frame the couple using the rule of thirds with heads in the upper portion.",
        "Use a medium distance to capture both individuals clearly.",
        "Choose a simple background that complements without distracting.",
    ],
    "selfie": [
        "Hold the camera slightly above eye level for a flattering angle.",
        "Use natural light from a window for soft, even illumination.",
        "Keep the background simple and uncluttered.",
        "Position your face using the rule of thirds within the frame.",
        "Use a slight tilt of the head for a more dynamic selfie composition.",
    ],
    "plant_still_life": [
        "Use soft side or back lighting to reveal leaf textures and translucency.",
        "Place plants against a neutral background to highlight their form.",
        "Shoot at eye level with the plant for an intimate botanical portrait.",
        "Use a shallow depth of field to blur the background and isolate the plant.",
        "Arrange multiple plants with varying heights for layered composition.",
    ],
    "flower_still_life": [
        "Shoot flowers from slightly above to show the bloom and stem together.",
        "Use soft, diffused natural light for delicate petal rendering.",
        "Use a clean background to make the flower colors pop.",
        "Apply the rule of thirds to position the flower head off-center.",
        "Use a shallow depth of field for a dreamy, romantic flower portrait.",
    ],
    "seascape": [
        "Place the horizon along the lower third to emphasize the dramatic sky.",
        "Use a slow shutter speed to smooth wave motion into silky textures.",
        "Shoot during golden hour for warm reflections on the water surface.",
        "Include rocks or a pier in the foreground for depth and scale.",
        "Use a polarizing filter to enhance sky color and reduce water glare.",
    ],
    "mountain_landscape": [
        "Include a foreground element like a trail or wildflowers for depth.",
        "Shoot during alpenglow for dramatic mountain lighting.",
        "Use a small aperture (f/8-f/11) to keep the entire scene sharp.",
        "Place the mountain peak at a rule-of-thirds intersection.",
        "Use atmospheric perspective by including mist or clouds for mood.",
    ],
    "sunset_landscape": [
        "Expose for the sky to capture vibrant sunset colors.",
        "Silhouette foreground elements against the colorful sky.",
        "Place the sun near a rule-of-thirds intersection for dynamic composition.",
        "Use a wide-angle lens to capture the full sweep of the sunset.",
        "Shoot in rapid succession as colors change quickly during sunset.",
    ],
    "cityscape": [
        "Shoot from an elevated viewpoint to capture the city skyline.",
        "Use blue hour for a balance of warm city lights and cool sky.",
        "Use a tripod and long exposure to capture light trails from traffic.",
        "Include a foreground element like a railing or bridge for depth.",
        "Use leading lines from roads or rivers to draw the eye through the city.",
    ],
    "tabletop_still_life": [
        "Shoot from a 45-degree angle for a natural dining perspective.",
        "Use natural window light from the side for dimensional food lighting.",
        "Arrange tableware and props to create a complete scene.",
        "Use a shallow depth of field to focus on the hero dish.",
        "Keep the table setting cohesive with complementary colors and textures.",
    ],
}


def resolve_image_path(image_path_rel: str) -> Path:
    return BASE_DIR / image_path_rel.replace("../", "")


def extract_source_title(item: dict) -> str:
    """Extract source_title from the prompt field's embedded metadata JSON."""
    prompt = item.get("prompt", "")
    m = re.search(r'"source_title":\s*"([^"]+)"', prompt)
    if m:
        return m.group(1)
    return ""


def detect_text_overlay(source_title: str) -> bool:
    """Check if source title suggests heavy text overlay or design content."""
    title_upper = source_title.upper()
    for keyword in TEXT_OVERLAY_KEYWORDS:
        if keyword.upper() in title_upper:
            return True
    return False


def analyze_image(path: Path) -> dict[str, Any]:
    """Extract visual properties from an image using PIL."""
    if not HAS_PIL or not path.exists():
        return {
            "width": 0, "height": 0, "orientation": "unknown",
            "avg_brightness": 0.5, "contrast": "medium",
            "brightness_std": 0.0, "aspect_ratio": 1.0,
        }

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
                return {
                    "width": w, "height": h, "orientation": orientation,
                    "avg_brightness": 0.5, "contrast": "medium",
                    "brightness_std": 0.0, "aspect_ratio": w / h if h else 1.0,
                }

            avg_brightness = sum(pixels) / n / 255.0
            variance = sum((p - avg_brightness * 255.0) ** 2 for p in pixels) / n
            brightness_std = math.sqrt(variance) / 255.0

            # Determine contrast from brightness std
            if brightness_std > 0.25:
                contrast = "high"
            elif brightness_std > 0.12:
                contrast = "medium"
            else:
                contrast = "low"

            return {
                "width": w, "height": h, "orientation": orientation,
                "avg_brightness": avg_brightness, "contrast": contrast,
                "brightness_std": brightness_std, "aspect_ratio": w / h if h else 1.0,
            }
    except Exception:
        return {
            "width": 0, "height": 0, "orientation": "unknown",
            "avg_brightness": 0.5, "contrast": "medium",
            "brightness_std": 0.0, "aspect_ratio": 1.0,
        }


def build_visual_summary(photo_type: str, scene_category: str, img_info: dict, has_text: bool) -> str:
    subject = SCENE_TO_SUBJECT.get(scene_category, "unknown")
    orientation = img_info.get("orientation", "unknown")
    brightness = img_info.get("avg_brightness", 0.5)

    brightness_desc = "bright" if brightness > 0.65 else "dark" if brightness < 0.35 else "balanced"

    if has_text:
        base = f"A {scene_category.replace('_', ' ')} image with text overlay elements in {orientation} orientation, {brightness_desc} exposure."
    else:
        summaries = {
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
        base = summaries.get(scene_category, f"A {photo_type} photo in {scene_category} category with {orientation} framing.")

    return base[:240]


def determine_composition(photo_type: str, scene_category: str, img_info: dict) -> dict:
    compositions = {
        "closeup_portrait": {"type": "centered_subject", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "low"},
        "group_portrait": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "low"},
        "travel_portrait": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "unknown", "leading_lines": True, "negative_space": "medium"},
        "food_still_life": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "medium"},
        "natural_landscape": {"type": "rule_of_thirds", "subject_position": "none", "horizon_position": "upper_third", "leading_lines": True, "negative_space": "high"},
        "urban_landscape": {"type": "leading_lines", "subject_position": "center", "horizon_position": "middle", "leading_lines": True, "negative_space": "medium"},
        "creative_composition": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "medium"},
        "flat_lay": {"type": "centered_subject", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "low"},
        "product_still_life": {"type": "centered_subject", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "medium"},
        "street_portrait": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "unknown", "leading_lines": True, "negative_space": "medium"},
        "studio_portrait": {"type": "centered_subject", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "low"},
        "couple_portrait": {"type": "centered_subject", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "low"},
        "selfie": {"type": "centered_subject", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "low"},
        "plant_still_life": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "medium"},
        "flower_still_life": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "medium"},
        "seascape": {"type": "rule_of_thirds", "subject_position": "none", "horizon_position": "lower_third", "leading_lines": True, "negative_space": "high"},
        "mountain_landscape": {"type": "rule_of_thirds", "subject_position": "none", "horizon_position": "upper_third", "leading_lines": True, "negative_space": "high"},
        "sunset_landscape": {"type": "rule_of_thirds", "subject_position": "none", "horizon_position": "lower_third", "leading_lines": False, "negative_space": "medium"},
        "cityscape": {"type": "rule_of_thirds", "subject_position": "none", "horizon_position": "middle", "leading_lines": True, "negative_space": "medium"},
        "tabletop_still_life": {"type": "rule_of_thirds", "subject_position": "center", "horizon_position": "not_visible", "leading_lines": False, "negative_space": "medium"},
    }

    result = compositions.get(scene_category, {
        "type": "rule_of_thirds", "subject_position": "center",
        "horizon_position": "unknown", "leading_lines": False, "negative_space": "medium",
    })
    return dict(result)


def determine_lighting(photo_type: str, scene_category: str, img_info: dict) -> dict:
    brightness = img_info.get("avg_brightness", 0.5)
    contrast = img_info.get("contrast", "medium")

    time_map = {
        "travel_portrait": "golden_hour", "street_portrait": "day",
        "natural_landscape": "golden_hour", "urban_landscape": "day",
        "seascape": "day", "sunset_landscape": "golden_hour",
        "food_still_life": "indoor", "studio_portrait": "indoor",
        "closeup_portrait": "indoor", "tabletop_still_life": "indoor",
    }
    time_of_day = time_map.get(scene_category, "unknown")

    quality_map = {
        "studio_portrait": "soft", "closeup_portrait": "soft",
        "food_still_life": "soft", "travel_portrait": "mixed",
        "natural_landscape": "mixed", "couple_portrait": "soft",
        "selfie": "soft", "flat_lay": "soft",
    }
    quality = quality_map.get(scene_category, "mixed")

    direction_map = {
        "studio_portrait": "front", "closeup_portrait": "front",
        "food_still_life": "side", "travel_portrait": "side",
        "natural_landscape": "ambient", "seascape": "ambient",
        "flat_lay": "top", "couple_portrait": "front",
    }
    direction = direction_map.get(scene_category, "ambient")

    # Adjust based on brightness
    if brightness > 0.7:
        quality = "hard" if quality == "mixed" else quality
    elif brightness < 0.3:
        contrast = "high" if contrast == "medium" else contrast

    return {"quality": quality, "direction": direction, "time_of_day": time_of_day, "contrast": contrast}


def determine_camera(photo_type: str, scene_category: str, img_info: dict) -> dict:
    orientation = img_info.get("orientation", "unknown")

    angle_map = {
        "closeup_portrait": "eye_level", "group_portrait": "eye_level",
        "travel_portrait": "eye_level", "food_still_life": "high",
        "flat_lay": "top_down", "natural_landscape": "eye_level",
        "tabletop_still_life": "high", "selfie": "high",
    }
    angle = angle_map.get(scene_category, "eye_level")

    distance_map = {
        "closeup_portrait": "close", "group_portrait": "medium",
        "travel_portrait": "medium", "food_still_life": "close",
        "flat_lay": "close", "natural_landscape": "wide",
        "urban_landscape": "wide", "street_portrait": "medium",
    }
    distance = distance_map.get(scene_category, "medium")

    return {"angle": angle, "distance": distance, "orientation": orientation}


def determine_subject(photo_type: str, scene_category: str, source_title: str) -> dict:
    primary_type = SCENE_TO_SUBJECT.get(scene_category, "unknown")

    count_map = {
        "closeup_portrait": 1, "group_portrait": 3, "travel_portrait": 1,
        "couple_portrait": 2, "selfie": 1, "street_portrait": 1,
    }
    count = count_map.get(scene_category, 1 if primary_type == "person" else 0)

    if primary_type == "person":
        pose_map = {
            "closeup_portrait": "A person looks toward the camera with a natural expression in close-up framing.",
            "group_portrait": "Multiple people are posed together, facing the camera.",
            "travel_portrait": "A person stands or sits naturally within a travel environment, looking toward the camera.",
            "couple_portrait": "Two people are posed closely together, facing the camera.",
            "street_portrait": "A person is captured in an urban environment with natural body language.",
            "selfie": "A person takes a self-portrait, typically holding the camera at arm's length.",
        }
        pose_description = pose_map.get(scene_category, "A person is photographed in a natural pose.")
    else:
        pose_description = None

    return {"primary_type": primary_type, "count": count, "pose_description": pose_description}


def determine_aesthetic(photo_type: str, scene_category: str, img_info: dict, has_text: bool) -> tuple[float, str]:
    brightness = img_info.get("avg_brightness", 0.5)
    contrast = img_info.get("contrast", "medium")

    base_scores = {
        "closeup_portrait": 3.8, "group_portrait": 3.5, "travel_portrait": 3.8,
        "food_still_life": 3.8, "natural_landscape": 4.0, "urban_landscape": 3.5,
        "creative_composition": 3.5, "flat_lay": 3.5, "product_still_life": 3.5,
        "studio_portrait": 4.0, "street_portrait": 3.5, "couple_portrait": 3.8,
        "selfie": 3.0, "plant_still_life": 3.5, "flower_still_life": 3.5,
        "seascape": 3.8, "mountain_landscape": 4.0, "sunset_landscape": 4.0,
        "cityscape": 3.5, "tabletop_still_life": 3.8,
    }
    score = base_scores.get(scene_category, 3.5)

    # Penalize text overlay images
    if has_text:
        score = min(score, 2.5)

    # Adjust for extreme brightness
    if brightness > 0.85 or brightness < 0.15:
        score -= 0.5

    score = max(0.5, min(5.0, score))

    if score >= 4.0:
        template_value = "high"
    elif score >= 3.0:
        template_value = "medium"
    elif score >= 2.0:
        template_value = "low"
    else:
        template_value = "reject"

    return round(score, 1), template_value


def annotate_item(item: dict[str, Any]) -> dict[str, Any]:
    photo_id = item["photo_id"]
    photo_type = item["photo_type"]
    scene_category = item["scene_category"]
    image_path_rel = item["image_path"]

    image_path = resolve_image_path(image_path_rel)
    img_info = analyze_image(image_path)
    source_title = extract_source_title(item)
    has_text = detect_text_overlay(source_title)

    visual_summary = build_visual_summary(photo_type, scene_category, img_info, has_text)
    composition = determine_composition(photo_type, scene_category, img_info)
    lighting = determine_lighting(photo_type, scene_category, img_info)
    camera = determine_camera(photo_type, scene_category, img_info)
    subject = determine_subject(photo_type, scene_category, source_title)
    aesthetic_score, template_value = determine_aesthetic(photo_type, scene_category, img_info, has_text)
    guidance = SCENE_GUIDANCE.get(scene_category, [])
    if not guidance:
        guidance = ["Keep the subject well-framed using the rule of thirds.", "Use appropriate lighting for the scene.", "Pay attention to background cleanliness.", "Ensure the subject is in sharp focus."]
    shooting_guidance = guidance[:5]

    return {
        "schema_version": 1, "photo_id": photo_id, "photo_type": photo_type,
        "scene_category": scene_category, "visual_summary": visual_summary,
        "composition": composition, "lighting": lighting, "camera": camera,
        "subject": subject, "aesthetic_score": aesthetic_score,
        "template_value": template_value, "shooting_guidance": shooting_guidance,
    }


def process_batch(batch_file: Path) -> Path:
    batch_id = batch_file.stem
    result_file = RESULTS_DIR / f"{batch_id}.results.jsonl"

    items = []
    with batch_file.open("r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                items.append(json.loads(line))

    annotations = []
    text_overlay_count = 0
    for item in items:
        try:
            annotation = annotate_item(item)
            annotations.append(annotation)
            if annotation["template_value"] in ("low", "reject"):
                source_title = extract_source_title(item)
                if detect_text_overlay(source_title):
                    text_overlay_count += 1
        except Exception as exc:
            print(f"WARNING: failed to annotate {item.get('photo_id', '?')}: {exc}", file=sys.stderr)

    with result_file.open("w", encoding="utf-8") as f:
        for ann in annotations:
            f.write(json.dumps(ann, ensure_ascii=False, sort_keys=True) + "\n")

    high = sum(1 for a in annotations if a["template_value"] == "high")
    medium = sum(1 for a in annotations if a["template_value"] == "medium")
    low = sum(1 for a in annotations if a["template_value"] == "low")
    reject = sum(1 for a in annotations if a["template_value"] == "reject")
    print(f"  {batch_id}: {len(annotations)}/{len(items)} | high={high} med={medium} low={low} rej={reject} text_overlay={text_overlay_count}")
    return result_file


def main():
    print("Starting enhanced visual annotation generation...")
    print(f"PIL available: {HAS_PIL}")

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    batch_files = sorted(BATCH_DIR.glob("annotation_batch_*.jsonl"))
    batch_files = [f for f in batch_files if "results" not in f.name]

    print(f"\nProcessing {len(batch_files)} batch files...\n")

    total_items = 0
    total_annotated = 0

    for batch_file in batch_files:
        with batch_file.open("r", encoding="utf-8") as f:
            count = sum(1 for line in f if line.strip())
        total_items += count
        process_batch(batch_file)
        total_annotated += count

    print(f"\nDone! {total_annotated}/{total_items} items annotated across {len(batch_files)} batches.")
    print(f"Results: {RESULTS_DIR}")


if __name__ == "__main__":
    main()
