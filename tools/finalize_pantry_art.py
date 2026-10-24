"""Trim the picked pantry candidates (tools/generate_pantry_art.py) into the game's assets.

- Every item: art-work/pantry/<id>-<k>.png, cropped to its visible pixels plus a small margin and scaled so its longer
  side is ITEM_SIZE px, into sprites-final/pantry/<id>.webp (with alpha). Candidate 1 unless PICKS says otherwise.
- The cutting board: the same way at BOARD_SIZE, into sprites-final/pantry/board.webp.
- The kitchen: resized to the scenes' size into scenes-final/pantry/kitchen.webp, with its metadata (floor line, tint,
  accent: the fields of a scenes.json entry) in scenes-final/pantry/kitchen.json.

Usage: tools/.venv/bin/python -I tools/finalize_pantry_art.py
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/pantry"
SPRITES = ROOT / "sprites-final/pantry"
SCENES = ROOT / "scenes-final/pantry"
ITEM_SIZE = 384
BOARD_SIZE = 768
MARGIN = 0.03  # of the longer side, so a bite mark or the bounce never clips a hard edge
ALPHA_FLOOR = 8  # specks fainter than this are the model's haze, not food

PICKS: dict[str, int] = {"toasted_marshmallow": 2, "finger_dog": 3, "caramel": 2}  # item id -> candidate, where not 1
BOARD_PICK = 2
KITCHEN_PICK = 2
KITCHEN_META = {"floorY": 0.7531, "tintStrength": 0.12, "shadowOpacity": 0.55, "accent": "#FF7A3D"}


def _load(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"tools/{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def trim(src: Path, dest: Path, size: int) -> Path:
    im = Image.open(src).convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    box = a.getbbox()
    if box is None:
        raise ValueError(f"{src.name} is empty")
    im = im.crop(box)
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    scale = size / max(framed.size)
    framed = framed.resize((round(framed.width * scale), round(framed.height * scale)), Image.LANCZOS)
    framed.save(dest, "WEBP", quality=90, alpha_quality=100, method=6)
    return dest


def kitchen() -> Path:
    scenes = _load("finalize_scenes")
    img = Image.open(SRC / f"kitchen-{KITCHEN_PICK}.png").convert("RGB").resize(scenes.FINAL_SIZE, Image.LANCZOS)
    dest = SCENES / "kitchen.webp"
    with tempfile.NamedTemporaryFile(suffix=".png") as tmp:
        img.save(tmp.name)
        subprocess.run(["cwebp", "-quiet", "-q", str(scenes.WEBP_QUALITY), tmp.name, "-o", str(dest)], check=True)
    meta = {"id": "kitchen", "title": "The Pantry", "file": "pantry/kitchen.webp", **KITCHEN_META,
            "tint": scenes.sample_tint(img)}
    (SCENES / "kitchen.json").write_text(json.dumps(meta, indent=2) + "\n")
    return dest


def main() -> int:
    items = _load("generate_pantry_art").ITEMS
    SPRITES.mkdir(parents=True, exist_ok=True)
    SCENES.mkdir(parents=True, exist_ok=True)
    for item in items:
        trim(SRC / f"{item}-{PICKS.get(item, 1)}.png", SPRITES / f"{item}.webp", ITEM_SIZE)
    trim(SRC / f"board-{BOARD_PICK}.png", SPRITES / "board.webp", BOARD_SIZE)
    dest = kitchen()
    total = sum(p.stat().st_size for p in SPRITES.glob("*.webp")) // 1024
    print(f"{len(items)} items + board into {SPRITES.relative_to(ROOT)} ({total} KB); {dest.relative_to(ROOT)} "
          f"{dest.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
