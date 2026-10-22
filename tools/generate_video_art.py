"""Generate the launch video's feature-scene art with OpenAI's image model: the feature titles as lettering in the
game logo's style (drawn from the logo itself, so they match), and the props the scenes are built on: the living room
the video opens in, the phones the PvP scene taps together, and the TV of the outro. Every screen is flat chroma-key
green so gameplay can be laid into it.

Candidates go to art-work/video/art/ (gitignored); tools/finalize_video_art.py trims the picks into video/art/.
The API key is read from the OPENAI_API_KEY environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_video_art.py [--n 2] [--start 1] [--only room]
"""
from __future__ import annotations

import argparse
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_cover import API, MODEL, redact  # noqa: E402
from generate_site_art import save  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-work/video/art"
LOGO = ROOT / "branding/logo.png"

LETTERING = (
    "Using exactly the lettering style of the reference logo (its chrome-blue metallic block capitals with a dark navy "
    "outline and a sky-blue-to-white gradient, and its energetic slanted hot-pink-to-yellow brush script with a thick "
    "dark purple outline and speed strokes), make a new title lockup that reads: {text} Exact spelling, every letter "
    "correct, no other words, letters or symbols, nothing from the original logo's words. Crisp clean edges, a subtle "
    "tight drop shadow, centered, a generous transparent margin, isolated on a fully transparent background."
)
TITLES = {
    "raise": "\"RAISE & TRAIN\" on the first line in the chrome block capitals, and \"your killer\" under it in the "
             "big pink-to-yellow brush script.",
    "fight": "\"FIGHT\" huge on the first line in the chrome block capitals, and \"to evolve your skills\" under it in "
             "the pink-to-yellow brush script.",
    "pvp": "\"ONLINE\" on the first line in the chrome block capitals, and \"PvP\" under it, huge, in the "
           "pink-to-yellow brush script.",
    "outro": "\"SLASHING ON ANDROID PHONES & ANDROID TV\" on the first line in the chrome block capitals, and "
             "\"this Halloween\" under it, big, in the pink-to-yellow brush script.",
}
GREEN = "a flat, perfectly uniform pure chroma-key green (#00FF00) with no reflections, glare, text or image on it"
PROPS = {  # (prompt, size, transparent background)
    "room": (
        "A dark, cozy living room at night in late October, seen from behind the couch: a large flat TV on a low "
        f"wooden stand is the room's main light, and its screen is {GREEN}. A rain-streaked window beside it shows a "
        "lightning storm outside, carved jack-o'-lanterns glow on the TV stand and the windowsill, a few cobwebs, a "
        "game controller on the coffee table. Cinematic and moody, deep purple and orange light, glossy high-end 3D "
        "render. No people, no characters, no text or logos. Landscape 16:9.",
        "2560x1440", False),
    "phone": (
        "A modern Android smartphone lying sideways in landscape orientation, seen straight on from the front, thin "
        f"black bezels, a small punch-hole front camera, a glossy dark frame; its screen is {GREEN}. Isolated on a "
        "fully transparent background, no shadow, no hands, no text or logos.",
        "1536x1024", True),
    "phone-back": (
        "A modern Android smartphone standing upright in portrait orientation, seen at a three-quarter angle from "
        "behind so its matte black back and its right edge show, a horizontal camera bar across the top of the back. "
        "Isolated on a fully transparent background, no shadow, no hands, no text or logos.",
        "1024x1536", True),
    "tv": (
        "A modern slim flat-screen TV on a small two-foot stand, seen straight on from the front, thin black bezel; "
        f"its screen is {GREEN}. Isolated on a fully transparent background, no shadow, no text or logos.",
        "1536x1024", True),
}


def lettering(key: str, name: str, k: int) -> Path:
    r = requests.post(f"{API}/images/edits", headers={"Authorization": f"Bearer {key}"},
                      files=[("image[]", ("logo.png", LOGO.read_bytes(), "image/png"))],
                      data={"model": MODEL, "prompt": LETTERING.format(text=TITLES[name]), "size": "1536x1024",
                            "quality": "high", "background": "transparent", "output_format": "png", "n": "1"},
                      timeout=600)
    return save(r, OUT / f"title-{name}-{k}.png")


def prop(key: str, name: str, k: int) -> Path:
    prompt, size, clear = PROPS[name]
    body = {"model": MODEL, "prompt": prompt, "size": size, "quality": "high", "output_format": "png", "n": 1}
    if clear:
        body["background"] = "transparent"
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"}, json=body, timeout=600)
    return save(r, OUT / f"{name}-{k}.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2, help="candidates of each")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=[*TITLES, *PROPS])
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    ids = [i for i in [*TITLES, *PROPS] if not args.only or i in args.only]
    jobs = [(i, k) for i in ids for k in range(args.start, args.start + args.n)]
    failed = 0
    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = [((i, k), pool.submit(lettering if i in TITLES else prop, key, i, k)) for i, k in jobs]
        for (i, k), fut in futures:
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {i}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
