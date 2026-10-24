"""Generate the VS card's art with OpenAI's image model, on transparent backgrounds: a VS logo per killer in that
killer's film style, the eighth killer's name lettering (the other seven are the launch video's wordmarks), the
"new challenger" banner, and the oxygen tank the eighth killer spits in a fight.

Candidates go to art-work/vs/<id>-<k>.png (gitignored); tools/finalize_vs_art.py trims the picks into sprites-final/.
The API key is read from the OPENAI_API_KEY environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_vs_art.py [--n 2] [--start 1] [--only vs-jason ...]
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
from generate_cover import API, MODEL, redact  # noqa: E402
from generate_food import STYLE as PROP_STYLE  # noqa: E402
from generate_wordmarks import PROMPT as NAME_PROMPT  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-work/vs"

VS_PROMPT = (
    "A 1990s arcade fighting game's VS logo, isolated on a fully transparent background. It reads exactly \"VS\" in two "
    "huge capital letters and nothing else: no other words, letters, numbers or symbols. {style} Bold, chunky and "
    "slanted forward, with a thick dark outline around both letters and a hard drop shadow tight to them. Crisp clean "
    "edges, centered, a generous transparent margin all around, no background plate, frame, banner or scenery."
)
VS_STYLES = {
    "jason": "Summer-camp slasher style: blood-red chrome letters with a hot white bevel, cracked like a smashed window "
             "with glass shards flying off, a scratched hockey-mask white trim, a machete slash between the V and the S.",
    "freddy": "Nightmare style: scorched letters banded inside with red and moss-green sweater stripes, burnt crispy "
              "edges, four razor-claw slashes raking diagonally through both letters, a wisp of boiler-room steam.",
    "pinhead": "Gothic occult style: polished antique gold letters engraved with filigree, studded with rows of tiny "
               "steel pins, hooked chains hanging off them, a cold glow of blue hellish light.",
    "ghostface": "Teen slasher-movie style: stark white brush-stroke letters edged in jet black like the screaming "
                 "mask, a tattered black cloak hem trailing under them, one bloody knife streak slashing across.",
    "chucky": "Toy-box style: chunky glossy plastic letters, the V in primary red and the S in primary blue with yellow "
              "trim, stitched together with black thread like a doll's scars, a few drips of red.",
    "michael": "Halloween night style: tall letters carved from a glowing orange jack-o'-lantern, candlelight burning "
               "inside them, a kitchen-knife glint across the S, a few dark autumn leaves swirling around.",
    "leatherface": "Texas chainsaw style: rusty riveted sheet-metal letters patched with stitched leather, a revving "
                   "chainsaw blade tearing between the V and the S, sparks and sawdust spraying off.",
    "jaws": "Shark-attack movie style: deep ocean-blue letters with white foam crests, rows of jagged shark teeth along "
            "their edges, seawater spraying off them, a great white's dorsal fin cutting up between the V and the S.",
}

JAWS_NAME_STYLE = (
    "Seaside summer horror style: chunky ocean-blue capitals with white sea-foam crests and rows of jagged, shark-tooth "
    "notches along their top edges, sea spray flying off them, and a cartoon dorsal fin poking up from a little splash "
    "beside the last letter."
)

CHALLENGER = (
    "A 1990s arcade fighting game's 'new challenger' announcement banner, isolated on a fully transparent background. "
    "It reads exactly \"HERE COMES A NEW CHALLENGER!\" in capital letters on two lines, \"HERE COMES A\" small on the "
    "first line and \"NEW CHALLENGER!\" huge on the second, every letter spelled correctly, no other words or symbols. "
    "Molten gold-and-fire chrome letters with hot white highlights and a forward slant, crackling with electric sparks, "
    "a thick dark outline and a hard drop shadow. Crisp clean edges, centered, a generous transparent margin all "
    "around, no background plate, frame or scenery."
)

TANK = (
    "a silver scuba diving oxygen tank, a tall polished steel cylinder with a black rubber boot, a chrome valve and "
    "regulator on top and a short coiled black hose, a couple of dents, tipped at a jaunty angle as if flying through "
    "the air."
)

ITEMS = {
    **{f"vs-{pet}": VS_PROMPT.format(style=style) for pet, style in VS_STYLES.items()},
    "name-jaws": NAME_PROMPT.format(name="JAWS", style=JAWS_NAME_STYLE),
    "challenger": CHALLENGER,
    "oxygen-tank": PROP_STYLE.format(item=TANK),
}


def generate(key: str, item: str, k: int) -> Path:
    size = "1536x1024" if item in ("challenger", "name-jaws") else "1024x1024"
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": ITEMS[item], "size": size, "quality": "high",
                            "background": "transparent", "output_format": "png", "n": 1},
                      timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    dest = OUT / f"{item}-{k}.png"
    dest.write_bytes(base64.b64decode(r.json()["data"][0]["b64_json"]))
    return dest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2, help="candidates per item")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=sorted(ITEMS))
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [(i, k) for i in ITEMS if not args.only or i in args.only for k in range(args.start, args.start + args.n)]
    failed = 0
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = {pool.submit(generate, key, i, k): (i, k) for i, k in jobs}
        for fut, (i, k) in futures.items():
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {i}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
