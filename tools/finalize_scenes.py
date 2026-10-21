"""Convert chosen scene candidates into scenes-final/ (WebP) and write scenes-final/scenes.json.

Per-scene metadata (floor line, tint, accent...) lives in tools/scene_meta.json. A tint of "auto"
is sampled from the image (its average ambient hue, keeping its real brightness) and written
back to scene_meta.json so the committed metadata is explicit; set it back to "auto" to re-sample.

Coordinate spaces for the floor line:
  - scene_meta.json `floorY16x9` is a fraction of the 16:9 center crop's height. That is where the
    floor was judged by eye on the generate_scenes.py composites (which are 16:9 crops).
  - scenes.json `floorY` is a fraction of the FULL image height, which is what the app's
    floorYOnScreen(crop, imgH, floorY) expects. This script converts one to the other.

Usage: tools/.venv/bin/python -I tools/finalize_scenes.py --pick jason/dock=2 --pick jason/cabin=1 ...
"""
from __future__ import annotations

import argparse
import colorsys
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
FINAL_SIZE = (1920, 1280)  # 3:2; crops to exactly 1920x1080 at 16:9 and 1707x1280 at 4:3
WEBP_QUALITY = 90


def sample_tint(img: Image.Image) -> str:
    """Average color of the upper 70% of the frame, with boosted saturation and its real brightness.

    The value is the sampled mean value clamped to [0.35, 0.80], so lerping a sprite 12-15% toward
    the tint pulls it into the scene's lighting (darker for night scenes) instead of brightening it.
    """
    a = np.asarray(img.convert("RGB").resize((96, 64)), dtype=np.float32) / 255
    mean = a[: int(64 * 0.7)].reshape(-1, 3).mean(axis=0)
    h, s, v = colorsys.rgb_to_hsv(*mean)
    r, g, b = colorsys.hsv_to_rgb(h, min(1.0, s * 1.4), min(0.80, max(0.35, v)))
    return "#{:02X}{:02X}{:02X}".format(round(r * 255), round(g * 255), round(b * 255))


def floor_y_full(floor_y_16x9: float, size: tuple[int, int]) -> float:
    """Convert a floor line measured on the 16:9 center crop to a fraction of the full image height.

    For a 3:2 image of width W and height H, the crop is ch = W*9/16 tall starting at (H-ch)/2, so
    floorY = ((H-ch)/2 + floorY16x9*ch) / H. Images at least as wide as 16:9 are cropped
    horizontally, which leaves vertical fractions unchanged.
    """
    w, h = size
    ch = w * 9 / 16
    if ch >= h:
        return round(floor_y_16x9, 4)
    return round(((h - ch) / 2 + floor_y_16x9 * ch) / h, 4)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pick", action="append", required=True, help="pet/id=k")
    ap.add_argument("--src", default="art-work/scenes")
    ap.add_argument("--out", default="scenes-final")
    args = ap.parse_args()

    prompts = json.loads((ROOT / "tools/scene_prompts.json").read_text())
    meta_path = ROOT / "tools/scene_meta.json"
    meta = json.loads(meta_path.read_text())
    picks = dict(p.split("=") for p in args.pick)

    out = ROOT / args.out
    catalog: dict[str, list[dict]] = {}
    for s in prompts["scenes"]:
        key = f"{s['pet']}/{s['id']}"
        if key not in picks:
            print(f"missing pick for {key}", file=sys.stderr)
            return 1
        src = ROOT / args.src / s["pet"] / f"{s['id']}-{picks[key]}.png"
        img = Image.open(src).convert("RGB").resize(FINAL_SIZE, Image.LANCZOS)
        m = meta[key]
        if m["tint"] == "auto":
            m["tint"] = sample_tint(img)
        dest = out / s["pet"] / f"{s['id']}.webp"
        dest.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(suffix=".png") as tmp:
            img.save(tmp.name)
            subprocess.run(["cwebp", "-quiet", "-q", str(WEBP_QUALITY), tmp.name, "-o", str(dest)], check=True)
        catalog.setdefault(s["pet"], []).append({
            "id": s["id"], "title": s["title"], "file": f"{s['pet']}/{s['id']}.webp",
            "floorY": floor_y_full(m["floorY16x9"], img.size), "tint": m["tint"], "tintStrength": m["tintStrength"],
            "shadowOpacity": m["shadowOpacity"], "accent": m["accent"],
        })
        print(f"{key} <- {src.name}  floorY {catalog[s['pet']][-1]['floorY']}  tint {m['tint']}  "
              f"{dest.stat().st_size // 1024} KB")

    (out / "scenes.json").write_text(json.dumps(catalog, indent=2) + "\n")
    meta_path.write_text(json.dumps(meta, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
