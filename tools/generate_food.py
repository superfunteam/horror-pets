"""Generate the food that drops in when a pet is fed, with OpenAI's image model, on transparent backgrounds.

Every candidate is written to art-work/food/<id>-<k>.png (gitignored); the picked ones are trimmed and
encoded into sprites-final/food/ by tools/finalize_food.py. The API key is read from the OPENAI_API_KEY
environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_food.py [--n 2] [--start 1] [--only ID ...]
"""
from __future__ import annotations

import argparse
import base64
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.openai.com/v1"
MODEL = "gpt-image-2.5-sunburst"
OUT = ROOT / "art-work/food"

STYLE = (
    "A single food item for a cute horror video game, rendered in a glossy, high-end 3D collectible-toy style: soft studio "
    "key light, a subtle colored rim light, rich saturated color, smooth stylized shapes and slightly exaggerated, chunky "
    "cartoon proportions, appealing and funny. Three-quarter view from slightly above. The whole item is fully in frame, "
    "centered, with a little margin, isolated on a fully transparent background with no ground shadow. No text, letters, "
    "numbers or logos anywhere, no people, no hands, no characters. The item: {item}"
)
FOOD = {
    "meat": "a gigantic cartoon hunk of roasted meat on a big white bone, the classic caveman meat-on-the-bone, a glistening "
            "brown crust with a few grill marks, the bone sticking out of both ends.",
    "drumstick": "a huge roasted turkey drumstick with golden-brown, crackly, juicy skin and a big white bone knob at the end.",
    "spinach": "an opened tin can of spinach, its jagged lid bent back, a big heap of dark green leafy spinach bursting out of "
               "the top and flopping over the rim; the can has a plain green label with a simple leaf drawing and no text.",
    "cake": "a round birthday cake with thick white frosting, glossy cherry-red icing dripping down the sides, one lit candle "
            "on top, and a big kitchen knife stuck into it at a jaunty angle.",
    "popcorn": "an overflowing red-and-white striped movie-theater popcorn bucket heaped high with fluffy buttery popcorn, a "
               "few kernels tumbling off the top.",
    "brain": "a wobbly, glossy, translucent lime-green gelatin dessert molded in the shape of a brain, sitting on a small white "
             "dessert plate, jiggly and shiny with a few bubbles inside.",
}


def redact(text: str, key: str) -> str:
    return text.replace(key, "[redacted]") if key else text


def generate(key: str, food: str, k: int) -> Path:
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": STYLE.format(item=FOOD[food]), "size": "1024x1024",
                            "quality": "high", "background": "transparent", "output_format": "png", "n": 1},
                      timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    dest = OUT / f"{food}-{k}.png"
    dest.write_bytes(base64.b64decode(r.json()["data"][0]["b64_json"]))
    return dest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2, help="candidates per food")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=sorted(FOOD))
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [(f, k) for f in FOOD if not args.only or f in args.only for k in range(args.start, args.start + args.n)]
    failed = 0
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(generate, key, f, k): (f, k) for f, k in jobs}
        for fut, (f, k) in futures.items():
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {f}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
