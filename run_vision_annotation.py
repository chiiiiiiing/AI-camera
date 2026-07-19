#!/usr/bin/env python3
"""Process annotation batches through vision model API."""

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
        start = text.find("{")
        end = text.rfind("}")
        if start != -1 and end != -1 and end > start:
            try:
                return json.loads(text[start:end+1])
            except json.JSONDecodeError:
                pass
        return {"parse_error": "Could not extract JSON", "raw_text": text}


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
            prompt = record.get("prompt", "")

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
    output_dir = Path("results")
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
