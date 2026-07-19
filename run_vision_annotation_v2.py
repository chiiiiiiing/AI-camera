#!/usr/bin/env python3
"""Process annotation batches through vision model API with schema v1 compliance."""

import base64
import json
import os
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path
from typing import Any


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    """Load JSONL file into list of dicts."""
    rows = []
    with path.open("r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                print(f"ERROR: {path} line {line_num}: {e}", file=sys.stderr)
    return rows


def load_existing_results(path: Path) -> dict[str, dict[str, Any]]:
    """Load existing results keyed by photo_id. Only keeps successful results."""
    results = {}
    if not path.exists():
        return results
    for record in load_jsonl(path):
        photo_id = record.get("photo_id")
        if photo_id and "error" not in record:
            results[photo_id] = record
    return results


def encode_image(image_path: Path) -> str:
    """Read and base64 encode an image file."""
    with image_path.open("rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def enhance_prompt_with_schema(original_prompt: str, input_record: dict[str, Any]) -> str:
    """Add explicit schema requirements to the prompt."""
    schema_addition = f"""

IMPORTANT: Your response MUST be a single JSON object (no Markdown, no code blocks, no explanation) matching this exact schema:

{{
  "schema_version": 1,
  "photo_id": "{input_record.get('photo_id')}",
  "photo_type": "{input_record.get('photo_type')}",
  "scene_category": "{input_record.get('scene_category')}",
  "visual_summary": "string (1-240 chars, describe what you see)",
  "composition": {{
    "type": "string (describe composition style)",
    "subject_position": "left|center|right|top|bottom|none|unknown",
    "horizon_position": "upper_third|middle|lower_third|not_visible|unknown",
    "leading_lines": true/false,
    "negative_space": "low|medium|high|unknown"
  }},
  "lighting": {{
    "quality": "soft|hard|mixed|unknown",
    "direction": "front|side|back|top|ambient|unknown",
    "time_of_day": "day|golden_hour|blue_hour|night|indoor|unknown",
    "contrast": "low|medium|high|unknown"
  }},
  "camera": {{
    "angle": "eye_level|low|high|top_down|unknown",
    "distance": "close|medium|wide|unknown",
    "orientation": "landscape|portrait|square|unknown"
  }},
  "subject": {{
    "primary_type": "person|landscape|object|food|plant|architecture|unknown",
    "count": number (0 or more),
    "pose_description": string or null (null for non-portrait photos)
  }},
  "aesthetic_score": number (0-5),
  "template_value": "reject|low|medium|high",
  "shooting_guidance": ["string (1-5 concrete tips, each max 120 chars)"]
}}

CRITICAL RULES:
- photo_id MUST be exactly: {input_record.get('photo_id')}
- photo_type MUST be exactly: {input_record.get('photo_type')}
- scene_category MUST be exactly: {input_record.get('scene_category')}
- aesthetic_score: 0-1=reject, 2=low, 3=medium, 4-5=high
- shooting_guidance MUST be an array of 1-5 strings
- Non-portrait photos: subject.pose_description MUST be null
- Return ONLY the JSON object, nothing else
"""
    return original_prompt + schema_addition


def call_vision_api(prompt: str, image_base64: str, api_key: str, base_url: str, model: str, retries: int = 3) -> dict[str, Any]:
    """Call vision model API and return response with retries."""
    payload = {
        "model": model,
        "max_tokens": 4096,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "image",
                        "source": {
                            "type": "base64",
                            "media_type": "image/jpeg",
                            "data": image_base64
                        }
                    },
                    {
                        "type": "text",
                        "text": prompt
                    }
                ]
            }
        ]
    }

    data = json.dumps(payload).encode("utf-8")

    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                f"{base_url}/v1/messages",
                data=data,
                headers={
                    "Content-Type": "application/json",
                    "x-api-key": api_key,
                    "anthropic-version": "2023-06-01"
                },
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=180) as response:
                result = json.loads(response.read().decode("utf-8"))
                for block in result.get("content", []):
                    if block.get("type") == "text":
                        return {"text": block["text"], "raw_response": result}
                return {"text": "", "raw_response": result}
        except urllib.error.HTTPError as e:
            error_body = e.read().decode("utf-8", errors="replace")
            if e.code >= 500 and attempt < retries - 1:
                print(f"[retry {attempt+1}/{retries}]", end=" ", flush=True)
                time.sleep(2 ** attempt)
                continue
            return {"error": f"HTTP {e.code}: {error_body}"}
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            if attempt < retries - 1:
                print(f"[retry {attempt+1}/{retries}]", end=" ", flush=True)
                time.sleep(2 ** attempt)
                continue
            return {"error": f"Connection error: {e}"}
        except Exception as e:
            return {"error": str(e)}

    return {"error": "Max retries exceeded"}


def parse_model_response(text: str) -> dict[str, Any]:
    """Parse JSON from model response text."""
    text = text.strip()

    # Remove markdown code blocks if present
    if text.startswith("```"):
        lines = text.split("\n")
        if lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # Try to find JSON object in text
        start = text.find("{")
        end = text.rfind("}")
        if start != -1 and end != -1 and end > start:
            try:
                return json.loads(text[start:end+1])
            except json.JSONDecodeError:
                pass
        return {"parse_error": "Could not extract JSON", "raw_text": text}


def validate_schema(record: dict[str, Any], input_record: dict[str, Any]) -> list[str]:
    """Validate record against schema v1. Returns list of errors."""
    errors = []

    # Required fields
    required = ["schema_version", "photo_id", "photo_type", "scene_category",
                "visual_summary", "composition", "lighting", "camera", "subject",
                "aesthetic_score", "template_value", "shooting_guidance"]

    for field in required:
        if field not in record:
            errors.append(f"Missing required field: {field}")

    if errors:
        return errors

    # schema_version must be 1
    if record.get("schema_version") != 1:
        errors.append("schema_version must be 1")

    # photo_id, photo_type, scene_category must match input
    if record.get("photo_id") != input_record.get("photo_id"):
        errors.append(f"photo_id mismatch: {record.get('photo_id')} != {input_record.get('photo_id')}")

    if record.get("photo_type") != input_record.get("photo_type"):
        errors.append(f"photo_type mismatch")

    if record.get("scene_category") != input_record.get("scene_category"):
        errors.append(f"scene_category mismatch")

    # aesthetic_score 0-5
    score = record.get("aesthetic_score")
    if not isinstance(score, (int, float)) or score < 0 or score > 5:
        errors.append(f"aesthetic_score must be 0-5, got {score}")

    # template_value enum
    if record.get("template_value") not in ["reject", "low", "medium", "high"]:
        errors.append(f"template_value must be reject/low/medium/high")

    # shooting_guidance array with 1-5 items
    guidance = record.get("shooting_guidance")
    if not isinstance(guidance, list) or len(guidance) < 1 or len(guidance) > 5:
        errors.append(f"shooting_guidance must be array with 1-5 items")

    # Composition validation
    comp = record.get("composition", {})
    if not isinstance(comp, dict):
        errors.append("composition must be object")
    else:
        if comp.get("subject_position") not in ["left", "center", "right", "top", "bottom", "none", "unknown"]:
            errors.append("composition.subject_position invalid")
        if comp.get("horizon_position") not in ["upper_third", "middle", "lower_third", "not_visible", "unknown"]:
            errors.append("composition.horizon_position invalid")
        if not isinstance(comp.get("leading_lines"), bool):
            errors.append("composition.leading_lines must be boolean")
        if comp.get("negative_space") not in ["low", "medium", "high", "unknown"]:
            errors.append("composition.negative_space invalid")

    # Lighting validation
    light = record.get("lighting", {})
    if not isinstance(light, dict):
        errors.append("lighting must be object")
    else:
        if light.get("quality") not in ["soft", "hard", "mixed", "unknown"]:
            errors.append("lighting.quality invalid")
        if light.get("direction") not in ["front", "side", "back", "top", "ambient", "unknown"]:
            errors.append("lighting.direction invalid")
        if light.get("time_of_day") not in ["day", "golden_hour", "blue_hour", "night", "indoor", "unknown"]:
            errors.append("lighting.time_of_day invalid")
        if light.get("contrast") not in ["low", "medium", "high", "unknown"]:
            errors.append("lighting.contrast invalid")

    # Camera validation
    cam = record.get("camera", {})
    if not isinstance(cam, dict):
        errors.append("camera must be object")
    else:
        if cam.get("angle") not in ["eye_level", "low", "high", "top_down", "unknown"]:
            errors.append("camera.angle invalid")
        if cam.get("distance") not in ["close", "medium", "wide", "unknown"]:
            errors.append("camera.distance invalid")
        if cam.get("orientation") not in ["landscape", "portrait", "square", "unknown"]:
            errors.append("camera.orientation invalid")

    # Subject validation
    subj = record.get("subject", {})
    if not isinstance(subj, dict):
        errors.append("subject must be object")
    else:
        if subj.get("primary_type") not in ["person", "landscape", "object", "food", "plant", "architecture", "unknown"]:
            errors.append("subject.primary_type invalid")
        if not isinstance(subj.get("count"), int) or subj.get("count") < 0:
            errors.append("subject.count must be non-negative integer")

        # Non-portrait photos must have pose_description as null
        if record.get("photo_type") != "portrait":
            if subj.get("pose_description") is not None:
                errors.append("Non-portrait photos must have subject.pose_description as null")

    return errors


def process_batch(input_path: Path, output_path: Path, api_key: str, base_url: str, model: str) -> None:
    """Process a single JSONL batch file with resume support."""
    print(f"Processing {input_path.name}...")
    records = load_jsonl(input_path)

    # Load existing successful results for resume
    existing = load_existing_results(output_path)
    print(f"  Resuming: {len(existing)} successful results already exist")

    jsonl_dir = input_path.parent

    # Open in append mode
    with output_path.open("a", encoding="utf-8") as out:
        for idx, record in enumerate(records, 1):
            photo_id = record.get("photo_id", "unknown")

            # Skip already processed successfully
            if photo_id in existing:
                print(f"  [{idx}/{len(records)}] {photo_id}... SKIP (already done)")
                continue

            image_rel_path = record.get("image_path", "")
            if image_rel_path.startswith("../../tools/"):
                image_rel_path = "../../" + image_rel_path[12:]
            image_path = (jsonl_dir / image_rel_path).resolve()
            original_prompt = record.get("prompt", "")
            prompt = enhance_prompt_with_schema(original_prompt, record)

            print(f"  [{idx}/{len(records)}] {photo_id}...", end=" ", flush=True)

            if not image_path.exists():
                print(f"ERROR: image not found: {image_path}")
                result = {
                    "photo_id": photo_id,
                    "error": f"Image not found: {image_rel_path}",
                    "model_output": None
                }
            else:
                try:
                    image_b64 = encode_image(image_path)
                    api_response = call_vision_api(prompt, image_b64, api_key, base_url, model)

                    if "error" in api_response:
                        print(f"API ERROR: {api_response['error'][:100]}")
                        result = {
                            "photo_id": photo_id,
                            "error": api_response["error"],
                            "model_output": None
                        }
                    else:
                        parsed = parse_model_response(api_response["text"])

                        # Validate schema
                        validation_errors = validate_schema(parsed, record)
                        if validation_errors:
                            print(f"VALIDATION ERROR: {'; '.join(validation_errors[:3])}")
                            result = {
                                "photo_id": photo_id,
                                "error": f"Schema validation failed: {'; '.join(validation_errors)}",
                                "model_output": parsed,
                                "raw_text": api_response["text"]
                            }
                        else:
                            result = {
                                "photo_id": photo_id,
                                "model_output": parsed,
                                "raw_text": api_response["text"]
                            }
                            print("OK")
                except Exception as e:
                    print(f"ERROR: {e}")
                    result = {
                        "photo_id": photo_id,
                        "error": str(e),
                        "model_output": None
                    }

            out.write(json.dumps(result, ensure_ascii=False) + "\n")
            out.flush()

    print(f"  Saved to {output_path}")


def main():
    api_key = os.environ.get("ANTHROPIC_AUTH_TOKEN")
    base_url = os.environ.get("ANTHROPIC_BASE_URL", "https://dashscope.aliyuncs.com/apps/anthropic")
    model = os.environ.get("ANTHROPIC_DEFAULT_SONNET_MODEL", "qwen3.7-plus")

    if not api_key:
        print("ERROR: ANTHROPIC_AUTH_TOKEN not set", file=sys.stderr)
        sys.exit(1)

    input_dir = Path("tools/preprocessed/annotation_batches.visual-clean")
    output_dir = input_dir / "results"
    output_dir.mkdir(exist_ok=True)

    batch_files = sorted(input_dir.glob("annotation_batch_*.jsonl"))
    print(f"Found {len(batch_files)} batch files\n")

    for batch_file in batch_files:
        output_file = output_dir / f"{batch_file.stem}.results.jsonl"
        process_batch(batch_file, output_file, api_key, base_url, model)
        print()

    print("Done!")


if __name__ == "__main__":
    main()
