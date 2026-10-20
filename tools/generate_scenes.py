"""Generate candidate scene backgrounds with OpenAI's image model, plus sprite composites for review.

The API key is read from the OPENAI_API_KEY environment variable only.

Usage:
  OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_scenes.py --n 2
  OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_scenes.py --only jason/dock --n 3 --tag v2
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import requests
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.openai.com/v1"
GUESS_FLOOR_Y = 0.80
PET_HEIGHT_FRACTION = 0.46


def newest_image_model(key: str) -> str:
    r = requests.get(f"{API}/models", headers={"Authorization": f"Bearer {key}"}, timeout=60)
    r.raise_for_status()
    models = [m for m in r.json()["data"] if m["id"].startswith("gpt-image")]
    return max(models, key=lambda m: m["created"])["id"]


def generate(key: str, model: str, prompt: str, size: str, quality: str) -> Image.Image:
    r = requests.post(
        f"{API}/images/generations",
        headers={"Authorization": f"Bearer {key}"},
        json={"model": model, "prompt": prompt, "size": size, "quality": quality, "n": 1},
        timeout=600,
    )
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:500]}")
    data = r.json()["data"][0]
    return Image.open(io.BytesIO(base64.b64decode(data["b64_json"]))).convert("RGB")


def crop_16x9(img: Image.Image) -> Image.Image:
    w, h = img.size
    ch = round(w * 9 / 16)
    if ch <= h:
        top = (h - ch) // 2
        return img.crop((0, top, w, top + ch))
    cw = round(h * 16 / 9)
    left = (w - cw) // 2
    return img.crop((left, 0, left + cw, h))


def composite(scene: Image.Image, pet: str, floor_y: float = GUESS_FLOOR_Y) -> Image.Image:
    base = crop_16x9(scene).convert("RGBA")
    W, H = base.size
    manifest = json.loads((ROOT / f"sprites-final/{pet}/manifest.json").read_text())
    cw, chh = manifest["cell"]
    strip = Image.open(ROOT / f"sprites-final/{pet}/idle.png").convert("RGBA")
    cell = strip.crop((0, 0, cw, chh))
    scale = H * PET_HEIGHT_FRACTION / chh
    cell = cell.resize((round(cw * scale), round(chh * scale)), Image.LANCZOS)
    feet_y = round(H * floor_y)
    x = W // 2 - cell.width // 2
    y = feet_y - round(manifest["baselineY"] * scale)

    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    sw = round(cell.width * 0.45)
    ImageDraw.Draw(shadow).ellipse((W // 2 - sw // 2, feet_y - sw // 10, W // 2 + sw // 2, feet_y + sw // 10),
                                   fill=(0, 0, 0, 150))
    base = Image.alpha_composite(base, shadow.filter(ImageFilter.GaussianBlur(6)))
    base.alpha_composite(cell, (x, y))

    draw = ImageDraw.Draw(base)
    safe_w = round(H * 4 / 3)
    draw.rectangle(((W - safe_w) // 2, 0, (W + safe_w) // 2, H - 1), outline=(0, 255, 160, 255), width=3)
    draw.line((0, feet_y, W, feet_y), fill=(255, 255, 0, 120), width=1)
    return base.convert("RGB")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", help="pet/id, repeatable")
    ap.add_argument("--n", type=int, default=2)
    ap.add_argument("--model")
    ap.add_argument("--size", default="1536x1024")
    ap.add_argument("--quality", default="high")
    ap.add_argument("--tag", default="")
    ap.add_argument("--out", default="art-work/scenes")
    args = ap.parse_args()

    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    model = args.model or newest_image_model(key)
    print(f"model: {model}  size: {args.size}  quality: {args.quality}")

    cfg = json.loads((ROOT / "tools/scene_prompts.json").read_text())
    scenes = [s for s in cfg["scenes"] if not args.only or f"{s['pet']}/{s['id']}" in args.only]
    jobs = [(s, k) for s in scenes for k in range(1, args.n + 1)]
    out = ROOT / args.out
    tag = f"-{args.tag}" if args.tag else ""

    def run(job):
        s, k = job
        prompt = f"{cfg['style']}\n\nScene: {s['prompt']}"
        try:
            img = generate(key, model, prompt, args.size, args.quality)
        except Exception as e:  # noqa: BLE001 - report and keep the batch going
            return f"FAIL {s['pet']}/{s['id']} #{k}: {e}"
        d = out / s["pet"]
        d.mkdir(parents=True, exist_ok=True)
        stem = f"{s['id']}{tag}-{k}"
        img.save(d / f"{stem}.png")
        composite(img, s["pet"]).save(d / f"{stem}-composite.jpg", quality=88)
        return f"ok {s['pet']}/{stem} {img.size[0]}x{img.size[1]}"

    with ThreadPoolExecutor(max_workers=6) as pool:
        for line in pool.map(run, jobs):
            print(line, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
