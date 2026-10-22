"""Trim the picked landing-page art from tools/generate_site_art.py into site/art/ as WebP (killers with alpha).

PICKS names the candidate chosen by eye for each. Killers are cropped to their visible pixels plus a margin and
scaled so the longer side is KILLER_SIZE; the backdrop is saved at BACKDROP_WIDTH.

Usage: tools/.venv/bin/python -I tools/finalize_site_art.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from finalize_food import ALPHA_FLOOR, MARGIN  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/site"
OUT = ROOT / "site/art"
PICKS = {"backdrop": 2, "jason": 1, "freddy": 32, "pinhead": 3, "ghostface": 3, "chucky": 2, "michael": 1, "leatherface": 1}
KILLER_SIZE = 1100
BACKDROP_WIDTH = 2560


def killer(name: str, k: int) -> Path:
    im = Image.open(SRC / f"{name}-{k}.png").convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    im = im.crop(a.getbbox())
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    scale = KILLER_SIZE / max(framed.size)
    framed = framed.resize((round(framed.width * scale), round(framed.height * scale)), Image.LANCZOS)
    dest = OUT / f"{name}.webp"
    framed.save(dest, "WEBP", quality=86, alpha_quality=95, method=6)
    return dest


def backdrop(k: int) -> Path:
    im = Image.open(SRC / f"backdrop-{k}.png").convert("RGB")
    im = im.resize((BACKDROP_WIDTH, round(im.height * BACKDROP_WIDTH / im.width)), Image.LANCZOS)
    dest = OUT / "backdrop.webp"
    im.save(dest, "WEBP", quality=80, method=6)
    return dest


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, k in PICKS.items():
        dest = backdrop(k) if name == "backdrop" else killer(name, k)
        print(f"{dest.relative_to(ROOT)} {Image.open(dest).size} {dest.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
