"""Generate each killer's name as a wordmark logo for the launch video, with OpenAI's image model: one line of
lettering styled after that killer's film world, on a transparent background, so the video can slam it in.

Candidates go to art-work/video/wordmarks/ (gitignored); tools/finalize_wordmarks.py trims the picks into
video/assets/wordmarks/. The API key is read from the OPENAI_API_KEY environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_wordmarks.py [--n 2] [--start 1] [--only jason]
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
OUT = ROOT / "art-work/video/wordmarks"

PROMPT = (
    "A character name logo for a 1990s arcade fighting game's character intro, isolated on a fully transparent "
    "background. It reads exactly \"{name}\" in capital letters on one single line, every letter spelled correctly, and "
    "no other words, letters, numbers or symbols. {style} Bold, chunky and readable from across a room, with a thick "
    "dark outline around the whole word and a hard drop shadow tight to the letters. Crisp clean edges, centered, a "
    "generous transparent margin all around, no background plate, frame, banner or scenery behind the word."
)
STYLES = {
    "jason": (
        "Summer-camp slasher style: chunky blood-red 3D block capitals with a forward slant and a bevel of hot white "
        "highlights, the letters cracked and shattering like a smashed window, a few sharp glass shards flying off "
        "the edges, a scratched hockey-mask white trim along the top."
    ),
    "freddy": (
        "Nightmare style: jagged, wiry capitals that look gouged into the surface by razor claws, in scorched dark red "
        "with sweater-stripe bands of red and moss green inside the letters, burnt crispy edges, a few drips of blood, "
        "and four parallel claw slashes raking diagonally through the word."
    ),
    "pinhead": (
        "Gothic occult style: tall ornate capitals of polished antique gold engraved with intricate filigree like an "
        "ancient puzzle box, set into black lacquer, sharp serifs ending in fine needle points, a thin glinting chain "
        "hanging between two letters, cold blue rim light on the gold."
    ),
    "ghostface": (
        "Teen-slasher style: stark bone-white hand-painted brush capitals with ragged, distressed, dry-brush edges, "
        "leaning forward, one clean knife cut slicing through the whole word, a spatter of bright red blood across a "
        "few letters, a deep black shadow."
    ),
    "chucky": (
        "Killer-toy style: chunky rounded capitals made like children's toy alphabet blocks, each letter a different "
        "bright primary color (red, blue, yellow, green), glossy plastic, cracked and split with stitched-up seams, "
        "a little red blood dripping off the bottom, playful but sinister."
    ),
    "michael": (
        "Cold 1978 autumn-night style: tall, sharp, elegant serif capitals glowing a hot pumpkin orange like lit neon "
        "against a heavy black outline, a subtle inner gradient from bright orange to deep ember red, a faint "
        "kitchen-knife glint across one letter, minimal and menacing."
    ),
    "leatherface": (
        "Grindhouse chainsaw style: rough, crude capitals spray-painted in dripping blood red over rusted, dented sheet "
        "metal letters, the edges ragged and chewed like a chainsaw tore through them, flecks of sawdust and orange "
        "sparks flying off one end, gritty 1970s texture."
    ),
}
NAMES = {pet: pet.upper() for pet in STYLES}


def wordmark(key: str, pet: str, k: int) -> Path:
    prompt = PROMPT.format(name=NAMES[pet], style=STYLES[pet])
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": prompt, "size": "2048x1152", "quality": "high",
                            "background": "transparent", "output_format": "png", "n": 1},
                      timeout=600)
    return save(r, OUT / f"{pet}-{k}.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2, help="candidates of each")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=list(STYLES))
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [(pet, k) for pet in STYLES if not args.only or pet in args.only for k in range(args.start, args.start + args.n)]
    failed = 0
    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = [((pet, k), pool.submit(wordmark, key, pet, k)) for pet, k in jobs]
        for (pet, k), fut in futures:
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {pet}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
