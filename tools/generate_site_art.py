"""Generate the landing page's hero art with OpenAI's image model: a backdrop with no one in it, and each killer on
its own, in an action pose on a transparent background, so the page can fly them in from the edges.

Each killer is drawn from its own base render (the same references as tools/generate_cover.py), so it stays on
model. Candidates go to art-work/site/ (gitignored); tools/finalize_site_art.py trims the picks into site/art/.
The API key is read from the OPENAI_API_KEY environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_site_art.py [--n 2] [--only backdrop|jason|...]
"""
from __future__ import annotations

import argparse
import base64
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_cover import API, MODEL, REFERENCES, redact, reference_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-work/site"

BACKDROP = (
    "Epic backdrop for a horror fighting-game splash screen, rendered in the glossy, high-end 3D style of a collectible "
    "figure diorama: a stormy red-and-purple night sky churning with clouds and forked lightning, a dark ruined cemetery "
    "below with crooked tombstones, broken iron fences and dead trees silhouetted toward the left and right edges, glowing "
    "embers and flying debris in the air, a hot red glow on the horizon at the center, wet ground catching the light in "
    "the foreground. Cinematic, wide, dramatic depth. The middle of the frame is open sky and open ground. Absolutely no "
    "characters, people, figures, creatures or silhouettes of people, no text, letters, logos or watermark. Landscape 16:9."
)
POSE = (
    "Render exactly this character from the reference image, perfectly on-model (same proportions, outfit, mask or face, "
    "colors and weapon), in the same glossy 3D chibi collectible-figure style, now in a dynamic action pose for a "
    "fighting-game splash screen: {pose} Dramatic foreshortening, a little motion blur on the weapon, strong red and "
    "purple storm rim light with a hot key light from the front, as if lit by lightning. The whole character fully in "
    "frame with a margin, isolated on a fully transparent background, no ground, no shadow, no text, no other characters."
)
POSES = {
    "jason": "leaping toward the viewer with his machete raised high over his head, ready to strike.",
    "freddy": "lunging forward and slashing at the viewer with the bladed glove on his right hand, his bare left "
              "hand open and clawing, cackling. Get both hands exactly right: the glove is brown leather with four "
              "brass-capped fingers, each ending in one long curved steel blade, so exactly four blades fanned out, "
              "and a plain leather thumb with no blade, clearly visible wrapped beside them; his bare, burned left "
              "hand has exactly a thumb and four fingers.",
    "pinhead": "floating upright with arms spread wide, pale hands open, long chains with hooks flying out around him.",
    "ghostface": "diving forward in mid-air with his knife drawn back to stab, black robe billowing behind him.",
    "chucky": "leaping at the viewer with a big kitchen knife raised high overhead in his right hand and his left arm "
              "flung out to the side, both arms well clear of his face and chin, grinning madly; the knife whole and "
              "solid, one straight steel blade set firmly in a single unbroken dark handle in his fist.",
    "michael": "striding toward the viewer with his kitchen knife raised high in one fist, head tilted.",
    "leatherface": "charging forward revving his chainsaw over his head, apron flapping, mid-swing.",
}


def save(r: requests.Response, dest: Path) -> Path:
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    dest.write_bytes(base64.b64decode(r.json()["data"][0]["b64_json"]))
    return dest


def backdrop(key: str, k: int) -> Path:
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": BACKDROP, "size": "2560x1440", "quality": "high", "n": 1},
                      timeout=600)
    return save(r, OUT / f"backdrop-{k}.png")


def killer(key: str, pet: str, k: int) -> Path:
    ref = next(path for p, path, _ in REFERENCES if p == pet)
    r = requests.post(f"{API}/images/edits", headers={"Authorization": f"Bearer {key}"},
                      files=[("image[]", (f"{pet}.png", reference_png(ROOT / ref), "image/png"))],
                      data={"model": MODEL, "prompt": POSE.format(pose=POSES[pet]), "size": "1536x1536",
                            "quality": "high", "background": "transparent", "output_format": "png", "n": "1"},
                      timeout=600)
    return save(r, OUT / f"{pet}-{k}.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2, help="candidates of each")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=["backdrop", *POSES])
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    ids = [i for i in ["backdrop", *POSES] if not args.only or i in args.only]
    jobs = [(i, k) for i in ids for k in range(args.start, args.start + args.n)]
    failed = 0
    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = [((i, k), pool.submit(backdrop, key, k) if i == "backdrop" else pool.submit(killer, key, i, k)) for i, k in jobs]
        for (i, k), fut in futures:
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {i}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
