"""The game's sprite animations (sprites-final/<pet>/), played by time the way the app plays them."""
from __future__ import annotations

import json
from functools import cache

from PIL import Image

from .cast import ROOT


@cache
def manifest(pet: str) -> dict:
    return json.loads((ROOT / f"sprites-final/{pet}/manifest.json").read_text())


@cache
def _strip(pet: str, anim: str) -> Image.Image:
    return Image.open(ROOT / f"sprites-final/{pet}/{manifest(pet)['animations'][anim]['file']}").convert("RGBA")


@cache
def cell(pet: str, anim: str, k: int, height: int) -> Image.Image:
    """Frame [k] of [pet]'s [anim], scaled so the whole cell is [height] px tall."""
    m = manifest(pet)
    cw, ch = m["cell"]
    im = _strip(pet, anim).crop((k * cw, 0, (k + 1) * cw, ch))
    s = height / ch
    return im.resize((round(cw * s), height), Image.LANCZOS)


def frame_at(pet: str, anim: str, t: float, speed: float = 1.0) -> int:
    """Which frame of [anim] shows [t] seconds in: looping ones wrap, the rest hold their last frame."""
    a = manifest(pet)["animations"][anim]
    ms, total = t * 1000 * speed, sum(a["durationsMs"])
    if a["loop"]:
        ms %= total
    acc = 0
    for k, d in enumerate(a["durationsMs"]):
        acc += d
        if ms < acc:
            return k
    return a["frames"] - 1


def length(pet: str, anim: str) -> float:
    return sum(manifest(pet)["animations"][anim]["durationsMs"]) / 1000


def anchor(pet: str, height: int) -> tuple[float, float]:
    """Where in a cell scaled to [height] the feet are: (anchor x, baseline y)."""
    m = manifest(pet)
    s = height / m["cell"][1]
    return m["anchorX"] * s, m["baselineY"] * s
