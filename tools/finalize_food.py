"""Trim the picked food candidates and encode them into sprites-final/food/<id>.webp (with alpha).

Each pick (see PICKS, chosen by eye from tools/generate_food.py's art-work/food/<id>-<k>.png) is cropped to its
visible pixels plus a small margin and scaled so its longer side is SIZE px, about twice what a TV shows.

Usage: tools/.venv/bin/python -I tools/finalize_food.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/food"
OUT = ROOT / "sprites-final/food"
PICKS = {"meat": 1, "drumstick": 2, "spinach": 1, "cake": 1, "popcorn": 1, "brain": 2}
SIZE = 384
MARGIN = 0.03  # of the longer side, so a bite mark or the bounce never clips a hard edge
ALPHA_FLOOR = 8  # specks fainter than this are the model's haze, not food


def finalize(food: str, k: int) -> Path:
    im = Image.open(SRC / f"{food}-{k}.png").convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    box = a.getbbox()
    if box is None:
        raise ValueError(f"{food}-{k} is empty")
    im = im.crop(box)
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    scale = SIZE / max(framed.size)
    framed = framed.resize((round(framed.width * scale), round(framed.height * scale)), Image.LANCZOS)
    dest = OUT / f"{food}.webp"
    framed.save(dest, "WEBP", quality=90, alpha_quality=100, method=6)
    return dest


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for food, k in PICKS.items():
        dest = finalize(food, k)
        print(f"{dest.relative_to(ROOT)} {Image.open(dest).size} {dest.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
