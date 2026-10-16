"""Spatial features and bounded visible-frame crops for weak preference training.

Scores from this module are not probabilities of human preference. Crops never
describe scenery outside the observed frame or predict a physical camera move.
"""
from __future__ import annotations

import base64
import math
from typing import Iterable

import numpy as np
from PIL import Image

FEATURE_PROTOCOL = "embedding512_rgb64_grid4_subject_scene_v1"
SCENES = ("portrait", "landscape", "food", "still_life")
SPATIAL_DIM = 93
INPUT_DIM = 605
HIDDEN_DIM = 64
FEATURE_NAMES = [f"embedding_{i}" for i in range(512)]
FEATURE_NAMES += [f"grid_{y}_{x}_{channel}" for y in range(4) for x in range(4)
                 for channel in ("red", "green", "blue", "luma_dx", "luma_dy")]
FEATURE_NAMES += ["luma_mean", "luma_std", "dark_fraction", "light_fraction",
                  "subject_valid", "subject_left", "subject_top", "subject_right", "subject_bottom"]
FEATURE_NAMES += [f"scene_{scene}" for scene in SCENES]


def valid_box(box: Iterable[float] | None) -> bool:
    if box is None:
        return False
    values = list(box)
    return (len(values) == 4 and all(math.isfinite(v) for v in values)
            and 0 <= values[0] < values[2] <= 1 and 0 <= values[1] < values[3] <= 1)


def features(image: Image.Image, subject: list[float] | None, scene: str) -> np.ndarray:
    """Nearest pixel-center 64-square sampling, independent of bitmap resamplers."""
    rgb = np.asarray(image.convert("RGB"), dtype=np.float64)
    height, width = rgb.shape[:2]
    xs = np.minimum(width - 1, ((np.arange(64) + .5) * width / 64).astype(int))
    ys = np.minimum(height - 1, ((np.arange(64) + .5) * height / 64).astype(int))
    rgb = rgb[ys[:, None], xs[None, :]] / 255.
    luma = rgb @ np.asarray([.299, .587, .114])
    result = []
    for y in range(4):
        for x in range(4):
            cell_rgb = rgb[y * 16:(y + 1) * 16, x * 16:(x + 1) * 16]
            cell_luma = luma[y * 16:(y + 1) * 16, x * 16:(x + 1) * 16]
            result.extend(cell_rgb.mean(axis=(0, 1)))
            result.extend([np.abs(np.diff(cell_luma, axis=1)).mean(),
                           np.abs(np.diff(cell_luma, axis=0)).mean()])
    result.extend([luma.mean(), luma.std(), (luma < .04).mean(), (luma > .96).mean()])
    result.extend([1., *subject] if valid_box(subject) else [0.] * 5)
    result.extend(float(scene == name) for name in SCENES)
    return np.asarray(result, dtype=np.float32)


def crop_box(subject: list[float] | None, size: float, index: int) -> list[float]:
    # Center and a modest subject-aware shift are alternatives, not ideal rules.
    cx, cy = (.5, .5) if subject is None else ((subject[0] + subject[2]) / 2,
                                            (subject[1] + subject[3]) / 2)
    if index == 0:
        cx, cy = .5, .5
    left = min(1 - size, max(0., cx - size / 2))
    top = min(1 - size, max(0., cy - size / 2))
    return [left, top, left + size, top + size]


def map_subject(subject: list[float] | None, crop: list[float]) -> list[float] | None:
    if subject is None:
        return None
    l, t, r, b = crop
    if (subject[0] < l + .02 or subject[1] < t + .02
            or subject[2] > r - .02 or subject[3] > b - .02):
        raise ValueError("crop cuts protected subject or its safety margin")
    return [(subject[0] - l) / (r - l), (subject[1] - t) / (b - t),
            (subject[2] - l) / (r - l), (subject[3] - t) / (b - t)]


def candidates(image: Image.Image, subject: list[float] | None):
    """Current plus at most two real crops; full-subject certainty remains explicit."""
    yield "current", image, subject, [0., 0., 1., 1.]
    for index, size in enumerate((.90, .84)):
        box = crop_box(subject, size, index)
        pixels = [round(box[0] * image.width), round(box[1] * image.height),
                  round(box[2] * image.width), round(box[3] * image.height)]
        actual = [pixels[0] / image.width, pixels[1] / image.height,
                  pixels[2] / image.width, pixels[3] / image.height]
        try:
            child_subject = map_subject(subject, actual)
        except ValueError:
            continue
        yield f"crop_{index}", image.crop(pixels), child_subject, actual


def rule_baseline(values: np.ndarray) -> float:
    """Declared simple photographic baseline; no compulsory thirds or brightness."""
    values = values[-SPATIAL_DIM:]
    grid = np.asarray(values[:80]).reshape(4, 4, 5)
    energy = grid[..., 3:5].sum(axis=2)
    outer = energy.copy()
    outer[1:3, 1:3] = 0
    # Busy boundaries are only a soft cost; bright/dark style itself is not penalized.
    score = -float(outer.sum() / (energy.sum() + .01)) * .10
    if values[84] > .5:
        left, top, right, bottom = values[85:89]
        margin = min(left, top, 1 - right, 1 - bottom)
        score -= max(0., .08 - float(margin)) * 4
        cx, cy = float((left + right) / 2), float((top + bottom) / 2)
        # Both center and thirds remain valid, with only a small placement term.
        center = math.hypot(cx - .5, cy - .5)
        thirds = min(math.hypot(cx - x, cy - y) for x in (1/3, 2/3) for y in (1/3, 2/3))
        score -= min(center, thirds) * .05
    return score


def decode_head(head: dict) -> tuple[np.ndarray, ...]:
    if (head.get("schema_version") != 1 or head.get("feature_protocol") != FEATURE_PROTOCOL
            or head.get("input_dim") != INPUT_DIM or head.get("hidden_dim") != HIDDEN_DIM
            or head.get("activation") != "relu" or head.get("weight_encoding") != "float32-le-base64"):
        raise ValueError("unsupported framing preference model")
    data = np.frombuffer(base64.b64decode(head["weights_f32_base64"], validate=True), dtype="<f4")
    count = HIDDEN_DIM * INPUT_DIM + HIDDEN_DIM * 2 + 1
    mean, std = np.asarray(head["feature_mean"], dtype=np.float32), np.asarray(head["feature_std"], dtype=np.float32)
    if len(data) != count or mean.shape != (INPUT_DIM,) or std.shape != (INPUT_DIM,):
        raise ValueError("invalid preference dimensions")
    if not all(np.isfinite(x).all() for x in (data, mean, std)) or np.any(std < .05):
        raise ValueError("invalid preference parameters")
    offset = INPUT_DIM * HIDDEN_DIM
    return data[:offset].reshape(HIDDEN_DIM, INPUT_DIM), data[offset:offset + HIDDEN_DIM], data[-65:-1], data[-1], mean, std


def predict(head: dict, values: np.ndarray) -> np.ndarray:
    w1, b1, w2, b2, mean, std = decode_head(head)
    return np.maximum(((values - mean) / std) @ w1.T + b1, 0) @ w2 + b2
