"""Generate the player's cartoon glove (several poses) and the key ring for Play, on transparent backgrounds.

The glove follows a reference photo of plush cartoon gloves (art-work/hand/reference.png, not committed) for its
design only; every pose is rendered in the game's glossy collectible-figure style. Candidates are written to
art-work/hand/<id>-<k>.png (gitignored); the picked ones are trimmed into sprites-final/hand/ by
tools/finalize_hand.py. The API key is read from the OPENAI_API_KEY environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_hand.py [--n 2] [--start 1] [--only ID ...]
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
OUT = ROOT / "art-work/hand"
REFERENCE = OUT / "reference.png"

GLOVE = (
    "A single cartoon glove for a cute horror video game, rendered in a glossy, high-end 3D collectible-toy style: soft "
    "studio key light, a subtle rim light, smooth stylized shapes, chunky and funny. Match the reference image's glove "
    "design only: a puffy bright-white cartoon glove with three fat fingers and a thumb, three dark stitched lines on the "
    "back of the hand, and a thick rolled cuff. Only the glove: no arm, no wrist, no body, nothing coming out of the cuff "
    "(its opening is a dark hole). One glove, isolated on a fully transparent background, no shadow, no text. "
    "The pose: {pose}"
)
POSES = {
    "open_side": "an open right hand seen from the side, palm down, fingers together, reaching straight to the LEFT: the "
                 "fingertips point left and the cuff is at the right.",
    "point_side": "a right hand pointing its index finger straight to the LEFT, the other two fingers curled into the palm "
                  "and the thumb tucked, like about to poke something; the cuff is at the right.",
    "tickle_side": "a right hand doing a tickling gesture toward the LEFT: the fingers bent and spread, wiggling, "
                   "fingertips toward the left; the cuff is at the right.",
    "open_down": "an open right hand reaching straight DOWN from above, palm down, fingers spread, about to grab something "
                 "below; the cuff is at the top.",
    "pinch_down": "a right hand pointing DOWN from above with the thumb and index finger pinched together, as if dangling "
                  "something small from a string, the other fingers curled; the cuff is at the top.",
}
KEYS = (
    "A ring of four old keys for a cute horror video game, rendered in a glossy, high-end 3D collectible-toy style: one "
    "big rusty skeleton key, a brass house key, a silver key and a little car key, all hanging from one round metal key "
    "ring at the top, seen from the front. Chunky and appealing. Isolated on a fully transparent background, no shadow, "
    "no hands, no text."
)


def redact(text: str, key: str) -> str:
    return text.replace(key, "[redacted]") if key else text


def save(r: requests.Response, dest: Path) -> Path:
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    dest.write_bytes(base64.b64decode(r.json()["data"][0]["b64_json"]))
    return dest


def glove(key: str, pose: str, k: int) -> Path:
    with REFERENCE.open("rb") as ref:
        r = requests.post(f"{API}/images/edits", headers={"Authorization": f"Bearer {key}"},
                          files=[("image[]", ("reference.png", ref, "image/png"))],
                          data={"model": MODEL, "prompt": GLOVE.format(pose=POSES[pose]), "size": "1024x1024",
                                "quality": "high", "background": "transparent", "output_format": "png", "n": "1"},
                          timeout=600)
    return save(r, OUT / f"{pose}-{k}.png")


def keys(key: str, k: int) -> Path:
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": KEYS, "size": "1024x1024", "quality": "high",
                            "background": "transparent", "output_format": "png", "n": 1}, timeout=600)
    return save(r, OUT / f"keys-{k}.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2, help="candidates per pose")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=sorted([*POSES, "keys"]))
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    if not REFERENCE.is_file():
        print(f"missing {REFERENCE.relative_to(ROOT)}", file=sys.stderr)
        return 2
    ids = [i for i in [*POSES, "keys"] if not args.only or i in args.only]
    jobs = [(i, k) for i in ids for k in range(args.start, args.start + args.n)]
    failed = 0
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(keys, key, k) if i == "keys" else pool.submit(glove, key, i, k): (i, k) for i, k in jobs}
        for fut, (i, k) in futures.items():
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {i}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
