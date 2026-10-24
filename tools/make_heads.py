"""Cut each killer's head out of its first idle frame into sprites-final/<pet>/head.webp (256x256, with alpha).

The stable badge, the stable menu and the Unlocked screen show killers by their heads. A head is the top of the
idle frame's visible pixels: a square centred on the head band, sized to the pet's height. Chucky the doll is
almost all head, so his square is wider and sits lower. Jaws swims side-on with his head at the left end, so his
square is bigger, halfway down, and over the front of him, with room around his nose, his jaw and his dorsal fin; his
body runs on past the square's right side, so it fades out toward that side instead of stopping at a straight edge
(the super cut-in shows the head big and square).

Usage: tools/.venv/bin/python -I tools/make_heads.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SIZE = 256
# (side of the square, centre's depth below the top), as fractions of the pet's visible height, and optionally the
# centre's distance from the left, as a fraction of the visible width (otherwise over the head band's middle).
FRAMING = {"chucky": (0.66, 0.30), "jaws": (1.10, 0.50, 0.36)}
DEFAULT_FRAMING = (0.50, 0.21)
# Where, across the head's square, a pet whose body runs on past its right side starts to fade out (to nothing at the
# right edge).
FADE_RIGHT = {"jaws": 0.62}


def head(pet: str) -> Image.Image:
    m = json.loads((ROOT / f"sprites-final/{pet}/manifest.json").read_text())
    cw, ch = m["cell"]
    cell = Image.open(ROOT / f"sprites-final/{pet}/idle.png").convert("RGBA").crop((0, 0, cw, ch))
    alpha = np.asarray(cell)[..., 3]
    rows = np.where(alpha.max(axis=1) > 40)[0]
    top, height = rows.min(), rows.max() - rows.min()
    side_frac, centre_frac, *across = FRAMING.get(pet, DEFAULT_FRAMING)
    if across:
        cols = np.where(alpha.max(axis=0) > 40)[0]
        cx = int(cols.min() + (cols.max() - cols.min()) * across[0])
    else:
        band = alpha[top:top + int(height * 0.5)]
        cx = int(np.median(np.where(band > 40)[1]))
    side = int(height * side_frac)
    cy = top + int(height * centre_frac)
    y0 = max(0, cy - side // 2)
    square = cell.crop((cx - side // 2, y0, cx + side // 2, y0 + side)).resize((SIZE, SIZE), Image.LANCZOS)
    return fade_right(square, FADE_RIGHT[pet]) if pet in FADE_RIGHT else square


def fade_right(img: Image.Image, start: float) -> Image.Image:
    """Fade `img` out toward its right edge: opaque as it was up to `start` of the way across, then smoothly to
    nothing at the edge."""
    rgba = np.asarray(img).astype(np.float32)
    x = (np.arange(img.width) + 0.5) / img.width
    t = np.clip((x - start) / (1 - start), 0, 1)
    rgba[..., 3] *= 1 - t * t * (3 - 2 * t)
    return Image.fromarray(rgba.round().astype(np.uint8), "RGBA")


def main() -> int:
    for pet in sorted(p.name for p in (ROOT / "sprites-final").iterdir() if (p / "manifest.json").is_file()):
        dest = ROOT / f"sprites-final/{pet}/head.webp"
        head(pet).save(dest, "WEBP", quality=90, alpha_quality=100, method=6)
        print(f"{dest.relative_to(ROOT)} {dest.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
