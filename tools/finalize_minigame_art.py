"""Trim the picked training mini-game candidates and encode them into sprites-final/minigames/<id>.webp (with alpha).

Each pick (see PICKS, chosen by eye from tools/generate_minigame_art.py's art-work/minigames/<id>-<k>.png) is
cropped to its visible pixels plus a small margin and scaled so its longer side is the tombstone SIZE or the organ
SIZE: about twice what a TV shows.

Usage: tools/.venv/bin/python -I tools/finalize_minigame_art.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/minigames"
OUT = ROOT / "sprites-final/minigames"
PICKS = {"tomb-rounded": 1, "tomb-cross": 1, "tomb-slab": 1,
         "heart": 1, "brain": 1, "eyeball": 1, "bone": 1, "hand": 1, "kidney": 1}
SIZE = 384  # an organ's longer side
TOMB_SIZE = 512  # a tombstone's: the shuffle's pieces are drawn larger than the falling organs
MARGIN = 0.03  # of the longer side, so a bounce or a squash never clips a hard edge
ALPHA_FLOOR = 8  # specks fainter than this are the model's haze, not art


def finalize(art: str, k: int) -> Path:
    im = Image.open(SRC / f"{art}-{k}.png").convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    box = a.getbbox()
    if box is None:
        raise ValueError(f"{art}-{k} is empty")
    im = im.crop(box)
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    scale = (TOMB_SIZE if art.startswith("tomb-") else SIZE) / max(framed.size)
    framed = framed.resize((round(framed.width * scale), round(framed.height * scale)), Image.LANCZOS)
    dest = OUT / f"{art}.webp"
    framed.save(dest, "WEBP", quality=90, alpha_quality=100, method=6)
    return dest


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for art, k in PICKS.items():
        dest = finalize(art, k)
        print(f"{dest.relative_to(ROOT)} {Image.open(dest).size} {dest.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
