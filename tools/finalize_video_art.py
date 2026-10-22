"""Trim the picked feature-scene art from tools/generate_video_art.py into video/art/.

Titles are cropped to their visible pixels plus a margin and saved WIDTH wide as WebP with alpha. The basement with
the CRT TV is saved with its chroma-key screen cut out as a mask (basement-screen.png, white where the screen is), and
the green light the key threw on the room turned neutral, so a scene can lay anything into the screen. The TV keeps its green
screen (scenes key it).

Usage: tools/.venv/bin/python -I tools/finalize_video_art.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
from finalize_food import ALPHA_FLOOR, MARGIN  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/video/art"
OUT = ROOT / "video/art"
TITLES = {"raise": 1, "fight": 1, "pvp-local": 2, "environments-25": 1, "outro-onto": 3}
ROOM, TV = ("basement", 2), 1
WIDTH = 1600


def title(name: str, k: int) -> Path:
    im = Image.open(SRC / f"title-{name}-{k}.png").convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    im = im.crop(a.getbbox())
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    framed = framed.resize((WIDTH, round(framed.height * WIDTH / framed.width)), Image.LANCZOS)
    dest = OUT / f"title-{name}.webp"
    framed.save(dest, "WEBP", quality=92, alpha_quality=100, method=6)
    return dest


def green(a: np.ndarray) -> np.ndarray:
    return (a[..., 1] > 150) & (a[..., 1] - np.maximum(a[..., 0], a[..., 2]) > 60)


def room(name: str, k: int) -> list[Path]:
    a = np.asarray(Image.open(SRC / f"{name}-{k}.png").convert("RGB")).astype(np.float32)
    labels, n = ndimage.label(green(a))
    sizes = ndimage.sum(np.ones_like(labels), labels, range(1, n + 1))
    screen = ndimage.binary_fill_holes(labels == 1 + int(np.argmax(sizes)))
    screen = ndimage.binary_dilation(screen, iterations=2)  # cover the key's soft edge
    # The rest of the room: whatever green the key cast (the tube's inner lip most of all) becomes plain, neutral light,
    # as if from whatever is on the screen.
    r, g, b = a[..., 0].copy(), a[..., 1].copy(), a[..., 2].copy()
    spill = np.clip(g - np.maximum(r, b), 0, None) * ~screen
    a[..., 0], a[..., 1], a[..., 2] = r + spill * 0.25, g - spill * 0.75, b + spill * 0.25
    a[screen] = 0
    room_path, mask_path = OUT / f"{name}.webp", OUT / f"{name}-screen.png"
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(room_path, "WEBP", quality=90, method=6)
    Image.fromarray((screen * 255).astype(np.uint8), "L").save(mask_path, optimize=True)
    return [room_path, mask_path]


def tv(k: int) -> Path:
    im = Image.open(SRC / f"tv-{k}.png").convert("RGBA")
    im = im.crop(im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v).getbbox())
    dest = OUT / "tv.webp"
    im.save(dest, "WEBP", quality=92, alpha_quality=100, method=6)
    return dest


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    paths = [title(n, k) for n, k in TITLES.items()] + room(*ROOM) + [tv(TV)]
    for p in paths:
        print(f"{p.relative_to(ROOT)} {Image.open(p).size} {p.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
