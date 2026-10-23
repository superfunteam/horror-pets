"""Generate the training mini games' props with OpenAI's image model, on transparent backgrounds, in the same glossy
toy style as the food: the tombstones a stable mate hides behind (Hide and Seek), and the body parts that rain down
in Organ Drop.

Every candidate is written to art-work/minigames/<id>-<k>.png (gitignored); the picked ones are trimmed and encoded
into sprites-final/minigames/ by tools/finalize_minigame_art.py. The API key is read from the OPENAI_API_KEY
environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_minigame_art.py [--n 2] [--start 1] [--only ID ...]
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
OUT = ROOT / "art-work/minigames"

STYLE = (
    "A single prop for a cute horror video game, rendered in a glossy, high-end 3D collectible-toy style: soft studio "
    "key light, a subtle purple rim light, rich saturated color, smooth stylized shapes and slightly exaggerated, chunky "
    "cartoon proportions, appealing, spooky and funny rather than gory. {view} The whole item is fully in frame, "
    "centered, with a little margin, isolated on a fully transparent background with no ground and no shadow. No text, "
    "letters, numbers, names, dates or logos anywhere, no people, no characters. The item: {item}"
)
FRONT = "Seen straight on from the front, at eye level."
TUMBLE = "Three-quarter view from slightly above."
PROPS = {  # id: (view, the item, size)
    # Tombstones: tall and wide enough for a chibi killer to hide behind, front-on so they line up in a row.
    "tomb-rounded": (FRONT, "a chunky, rounded-top gray stone tombstone, cracked down one side, green moss on its "
                            "shoulders, a carved skull in a little arch near the top, a few cobwebs, set in a small "
                            "mound of dark earth with tufts of dead grass.", "1024x1536"),
    "tomb-cross": (FRONT, "a weathered stone Celtic cross grave marker on a thick square stone base, chipped and "
                          "mossy, a crow-shaped notch broken out of one arm, set in a small mound of dark earth with "
                          "tufts of dead grass.", "1024x1536"),
    "tomb-slab": (FRONT, "a wide, crooked, square-topped dark stone headstone leaning slightly to one side, a jagged "
                         "crack across it, ivy creeping up from the bottom, a carved bat over a blank panel, set in a "
                         "small mound of dark earth with tufts of dead grass.", "1024x1536"),
    # Organ Drop's body parts: chunky, rubbery-toy cute-gross, each instantly readable at a glance.
    "heart": (TUMBLE, "a plump, glossy cartoon human heart, rich red with a few blue veins and stubby pipe-like "
                      "arteries on top.", "1024x1024"),
    "brain": (TUMBLE, "a squishy, glossy pink cartoon brain with deep rounded folds.", "1024x1024"),
    "eyeball": (TUMBLE, "a big round glossy cartoon eyeball with a bright green iris, a few squiggly red veins and a "
                        "short pink optic nerve trailing behind it.", "1024x1024"),
    "bone": (TUMBLE, "a big white cartoon bone with knobby ends, like a dog bone, slightly chipped.", "1024x1024"),
    "hand": (TUMBLE, "a severed, pale gray-green cartoon zombie hand, fingers curled, stitches across the wrist.",
             "1024x1024"),
    "kidney": (TUMBLE, "a glossy, bean-shaped, dark red cartoon kidney with a short tube coming off its side.",
               "1024x1024"),
}


def redact(text: str, key: str) -> str:
    return text.replace(key, "[redacted]") if key else text


def generate(key: str, prop: str, k: int) -> Path:
    view, item, size = PROPS[prop]
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": STYLE.format(view=view, item=item), "size": size,
                            "quality": "high", "background": "transparent", "output_format": "png", "n": 1},
                      timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    dest = OUT / f"{prop}-{k}.png"
    dest.write_bytes(base64.b64decode(r.json()["data"][0]["b64_json"]))
    return dest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2, help="candidates per prop")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=sorted(PROPS))
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [(p, k) for p in PROPS if not args.only or p in args.only for k in range(args.start, args.start + args.n)]
    failed = 0
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = {pool.submit(generate, key, p, k): (p, k) for p, k in jobs}
        for fut, (p, k) in futures.items():
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {p}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
