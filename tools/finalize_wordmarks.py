"""Trim the picked wordmarks from tools/generate_wordmarks.py into video/wordmarks/ as WebP with alpha.

PICKS names the candidate chosen by eye for each killer. Each is cropped to its visible pixels plus a margin and
scaled so it is WIDTH wide, which is more than the video ever shows it at.

Usage: tools/.venv/bin/python -I tools/finalize_wordmarks.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from finalize_food import ALPHA_FLOOR, MARGIN  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/video/wordmarks"
OUT = ROOT / "video/wordmarks"
PICKS = {"jason": 1, "freddy": 1, "pinhead": 2, "ghostface": 1, "chucky": 1, "michael": 2, "leatherface": 2}
WIDTH = 1600


def wordmark(name: str, k: int) -> Path:
    im = Image.open(SRC / f"{name}-{k}.png").convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    im = im.crop(a.getbbox())
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    framed = framed.resize((WIDTH, round(framed.height * WIDTH / framed.width)), Image.LANCZOS)
    dest = OUT / f"{name}.webp"
    framed.save(dest, "WEBP", quality=92, alpha_quality=100, method=6)
    return dest


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, k in PICKS.items():
        dest = wordmark(name, k)
        print(f"{dest.relative_to(ROOT)} {Image.open(dest).size} {dest.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
