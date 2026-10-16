#!/usr/bin/env python3
"""Fetch pinned, public local-only quality teachers; never upload source images."""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.request
from pathlib import Path

CLIP_COMMIT = "d05afc436d78f1c48dc0dbf8e5980a9d471f35f6"
AESTHETIC_COMMIT = "6934dd81792f086e613a121dbce43082cb8be85e"
CLIP_SHA = "b8cca3fd41ae0c99ba7e8951adf17d267cdb84cd88be6f7c2e0eca1737a03836"
AESTHETIC_SHA = "21dd590f3ccdc646f0d53120778b296013b096a035a2718c9cb0d511bff0f1e0"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def download(url: str, path: Path, expected: str | None = None) -> dict:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and (expected is None or sha256(path) == expected):
        return {"url": url, "file": path.name, "sha256": sha256(path), "bytes": path.stat().st_size}
    partial = path.with_suffix(path.suffix + ".partial")
    failures = []
    for attempt in range(2):
        try:
            with urllib.request.urlopen(url, timeout=90) as response, partial.open("wb") as stream:
                while block := response.read(1024 * 1024):
                    stream.write(block)
            digest = sha256(partial)
            if expected is not None and digest != expected:
                raise ValueError(f"SHA256 mismatch for {path.name}: {digest}")
            partial.replace(path)
            return {"url": url, "file": path.name, "sha256": digest, "bytes": path.stat().st_size}
        except Exception as exc:
            failures.append(f"attempt {attempt + 1}: {type(exc).__name__}: {exc}")
            if attempt == 0:
                time.sleep(2)
    raise RuntimeError("; ".join(failures))


def prepare(root: Path) -> dict:
    root.mkdir(parents=True, exist_ok=True)
    records = []
    clip_base = f"https://raw.githubusercontent.com/openai/CLIP/{CLIP_COMMIT}"
    for name in ("__init__.py", "clip.py", "model.py", "simple_tokenizer.py", "bpe_simple_vocab_16e6.txt.gz"):
        records.append(download(f"{clip_base}/clip/{name}", root / "clip" / name))
    records.append(download(f"{clip_base}/LICENSE", root / "CLIP-LICENSE"))
    author_base = f"https://raw.githubusercontent.com/christophschuhmann/improved-aesthetic-predictor/{AESTHETIC_COMMIT}"
    for name in ("LICENSE", "simple_inference.py", "sac+logos+ava1-l14-linearMSE.pth"):
        records.append(download(f"{author_base}/{name}", root / ("aesthetic-LICENSE" if name == "LICENSE" else name),
                                AESTHETIC_SHA if name.endswith(".pth") else None))
    print("Downloading pinned OpenAI CLIP ViT-L/14 weights (local cache)", flush=True)
    records.append(download(f"https://openaipublic.azureedge.net/clip/models/{CLIP_SHA}/ViT-L-14.pt", root / "ViT-L-14.pt", CLIP_SHA))
    result = {"schema_version": 1, "clip_commit": CLIP_COMMIT, "aesthetic_commit": AESTHETIC_COMMIT,
              "clip_license": "MIT", "aesthetic_license": "Apache-2.0", "local_inference_only": True,
              "training_image_licenses_not_granted_by_model_license": True, "files": records}
    (root / "teacher-provenance.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def verify_teacher(root: Path) -> dict:
    """Reject changed executable code or weights before importing the local teacher."""
    result = json.loads((root / "teacher-provenance.json").read_text(encoding="utf-8"))
    if result.get("clip_commit") != CLIP_COMMIT or result.get("aesthetic_commit") != AESTHETIC_COMMIT:
        raise ValueError("teacher revision differs from the pinned experiment")
    required = {"__init__.py", "clip.py", "model.py", "simple_tokenizer.py", "bpe_simple_vocab_16e6.txt.gz",
                "CLIP-LICENSE", "aesthetic-LICENSE", "simple_inference.py", "sac+logos+ava1-l14-linearMSE.pth", "ViT-L-14.pt"}
    names = [record.get("file") for record in result.get("files", [])]
    if len(names) != len(required) or set(names) != required:
        raise ValueError("teacher artifact manifest is incomplete or duplicated")
    for record in result.get("files", []):
        name = record["file"]
        if Path(name).name != name or name in (".", ".."):
            raise ValueError("invalid teacher artifact filename")
        path = root / name
        if not path.is_file():
            path = root / "clip" / name
        if not path.is_file() or path.stat().st_size != record["bytes"] or sha256(path) != record["sha256"]:
            raise ValueError(f"teacher artifact changed: {name}")
    if sha256(root / "ViT-L-14.pt") != CLIP_SHA or sha256(root / "sac+logos+ava1-l14-linearMSE.pth") != AESTHETIC_SHA:
        raise ValueError("teacher weight hash differs from official pinned weights")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = prepare(args.output_dir)
        print(json.dumps({"ready": True, "files": len(result["files"])}))
    except Exception as exc:
        failure = {"ready": False, "error": str(exc), "retry_limit": 2}
        (args.output_dir / "download-failure.json").write_text(json.dumps(failure, indent=2), encoding="utf-8")
        raise


if __name__ == "__main__":
    main()
