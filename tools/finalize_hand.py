"""Trim the picked glove poses and key ring and encode them into sprites-final/hand/<name>.webp (with alpha).

PICKS maps each file the game loads to the candidate chosen by eye from tools/generate_hand.py's
art-work/hand/<pose>-<k>.png. The two tickle frames are alternated in the game, so the fingers wiggle.

Usage: tools/.venv/bin/python -I tools/finalize_hand.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from finalize_food import ALPHA_FLOOR, MARGIN  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/hand"
OUT = ROOT / "sprites-final/hand"
PICKS = {"point": "point_side-1", "tickle_a": "tickle_side-1", "tickle_b": "tickle_side-2", "pinch": "pinch_down-2",
         "keys": "keys-1"}
SIZE = 512  # the longer side, about twice what a TV shows


def finalize(name: str, source: str) -> Path:
    im = Image.open(SRC / f"{source}.png").convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    box = a.getbbox()
    if box is None:
        raise ValueError(f"{source} is empty")
    im = im.crop(box)
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    scale = SIZE / max(framed.size)
    framed = framed.resize((round(framed.width * scale), round(framed.height * scale)), Image.LANCZOS)
    dest = OUT / f"{name}.webp"
    framed.save(dest, "WEBP", quality=90, alpha_quality=100, method=6)
    return dest


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, source in PICKS.items():
        dest = finalize(name, source)
        print(f"{dest.relative_to(ROOT)} {Image.open(dest).size} {dest.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
