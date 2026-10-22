"""Generate the fight's healing potion with OpenAI's image model, and finalize the pick into sprites-final/fight/.

Candidates go to art-work/potion/potion-<k>.png (gitignored), in the same glossy collectible-toy style as the food
(tools/generate_food.py). `--pick K` trims candidate K and writes sprites-final/fight/potion.webp. The API key is
read from the OPENAI_API_KEY environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_potion.py [--n 3]
       tools/.venv/bin/python -I tools/generate_potion.py --pick 2
"""
from __future__ import annotations

import argparse
import base64
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from finalize_food import ALPHA_FLOOR, MARGIN  # noqa: E402
from generate_food import API, MODEL, STYLE, redact  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-work/potion"
FINAL = ROOT / "sprites-final/fight/potion.webp"
SIZE = 384
POTION = (
    "a chunky round-bottomed glass potion flask with a short neck and a cork stopper, full of glowing, bubbling "
    "bright green healing potion, a few bubbles rising inside, a little white skull-and-crossbones drawn on a tan "
    "paper label tied around the neck with string, a soft green glow; upright and slightly tilted."
)


def generate(key: str, k: int) -> Path:
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": STYLE.format(item=POTION), "size": "1024x1024", "quality": "high",
                            "background": "transparent", "output_format": "png", "n": 1}, timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    dest = OUT / f"potion-{k}.png"
    dest.write_bytes(base64.b64decode(r.json()["data"][0]["b64_json"]))
    return dest


def finalize(k: int) -> Path:
    im = Image.open(OUT / f"potion-{k}.png").convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(a)
    im = im.crop(a.getbbox())
    pad = round(max(im.size) * MARGIN)
    framed = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    framed.paste(im, (pad, pad))
    scale = SIZE / max(framed.size)
    framed = framed.resize((round(framed.width * scale), round(framed.height * scale)), Image.LANCZOS)
    FINAL.parent.mkdir(parents=True, exist_ok=True)
    framed.save(FINAL, "WEBP", quality=90, alpha_quality=100, method=6)
    return FINAL


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=3, help="candidates to generate")
    ap.add_argument("--pick", type=int, help="finalize this candidate instead of generating")
    args = ap.parse_args()
    if args.pick is not None:
        print(f"wrote {finalize(args.pick).relative_to(ROOT)}")
        return 0
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    failed = 0
    with ThreadPoolExecutor(max_workers=3) as pool:
        for k, fut in [(k, pool.submit(generate, key, k)) for k in range(1, args.n + 1)]:
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL potion-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
