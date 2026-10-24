"""Trim the VS card's art into the game's assets.

- `sprites-final/vs/<pet>-vs.webp`: each killer's VS logo (tools/generate_vs_art.py candidates, PICKS).
- `sprites-final/vs/<pet>-name.webp`: each killer's name lettering: the launch video's wordmarks (`video/wordmarks/`)
  for the seven, the generated one for the eighth.
- `sprites-final/vs/challenger.webp`: the "new challenger" banner.
- `sprites-final/fight/oxygen_tank.webp`: the tank the eighth killer spits in a fight.

Usage: tools/.venv/bin/python -I tools/finalize_vs_art.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/vs"
VS = ROOT / "sprites-final/vs"
FIGHT = ROOT / "sprites-final/fight"
MARGIN = 0.03
ALPHA_FLOOR = 8

PICKS = {
    "vs-jason": 1, "vs-freddy": 2, "vs-pinhead": 1, "vs-ghostface": 2, "vs-chucky": 1, "vs-michael": 2,
    "vs-leatherface": 1, "vs-jaws": 1, "name-jaws": 2, "challenger": 2, "oxygen-tank": 1,
}
VIDEO_WORDMARKS = ["jason", "freddy", "pinhead", "ghostface", "chucky", "michael", "leatherface"]
VS_SIZE = 640  # longer side, about twice what a TV shows
NAME_SIZE = 900
BANNER_SIZE = 1400
TANK_SIZE = 384


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
    scale = min(1.0, size / max(framed.size))
    framed = framed.resize((round(framed.width * scale), round(framed.height * scale)), Image.LANCZOS)
    framed.save(dest, "WEBP", quality=90, alpha_quality=100, method=6)
    return dest


def main() -> int:
    VS.mkdir(parents=True, exist_ok=True)
    FIGHT.mkdir(parents=True, exist_ok=True)
    out = []
    for item, k in PICKS.items():
        src = SRC / f"{item}-{k}.png"
        if item.startswith("vs-"):
            out.append(trim(src, VS / f"{item.removeprefix('vs-')}-vs.webp", VS_SIZE))
        elif item == "name-jaws":
            out.append(trim(src, VS / "jaws-name.webp", NAME_SIZE))
        elif item == "challenger":
            out.append(trim(src, VS / "challenger.webp", BANNER_SIZE))
        elif item == "oxygen-tank":
            out.append(trim(src, FIGHT / "oxygen_tank.webp", TANK_SIZE))
    for pet in VIDEO_WORDMARKS:
        out.append(trim(ROOT / f"video/wordmarks/{pet}.webp", VS / f"{pet}-name.webp", NAME_SIZE))
    for p in out:
        print(f"{p.relative_to(ROOT)} {Image.open(p).size} {p.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
