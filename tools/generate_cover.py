"""Generate the "Virtual Pet Slasher 2 Turbo" cover art and logo with OpenAI's image model.

Key art is made from every pet's own reference render, so the characters stay on-model; the logo is
generated separately on a transparent background. tools/make_branding.py composites the chosen pair.
The API key is read from the OPENAI_API_KEY environment variable only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_cover.py [--art 2] [--logo 2]
"""
from __future__ import annotations

import argparse
import base64
import io
import os
import sys
from pathlib import Path

import numpy as np
import requests
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from spritekit import key_magenta  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.openai.com/v1"
MODEL = "gpt-image-2.5-sunburst"
OUT = ROOT / "art-work/branding"
REFERENCES = [  # (pet, base render, how the prompt describes them)
    ("jason", "sprites-source/jason/hires/base.png", "the hockey-masked killer with a machete"),
    ("freddy", "sprites-source/freddy/hires/base.png", "the burned man in a striped sweater and fedora with a bladed glove"),
    ("pinhead", "sprites-source/pinhead/decoded/base.png", "the pale pin-studded figure in a black leather cassock"),
    ("ghostface", "sprites-source/ghostface/full-resolution/ghostface-base.png", "the black-hooded figure in the white screaming mask with a knife"),
    ("chucky", "sprites-source/chucky/outputs/chucky/chucky-base.png", "the small red-haired doll in denim overalls with a knife"),
    ("michael", "sprites-source/michael/work/michael/decoded/base.png", "the pale-masked man in navy coveralls with a kitchen knife"),
]

ART_PROMPT = (
    "Epic key-art splash screen for a fighting game, rendered in the exact glossy 3D chibi collectible-figure style of the "
    "reference images. Keep every character exactly on-model (proportions, outfits, masks, colors, weapons): {cast}. "
    "All six burst toward the camera from every direction like a superhero team-up poster or a platform-fighter splash screen — "
    "leaping, lunging and swinging their weapons at each other, dramatic foreshortening and perspective, motion streaks, sparks, "
    "flying debris, under a stormy red-and-purple night sky with lightning and strong colored rim light. "
    "The small doll is clearly about half the height of the others. COMPOSITION IS CRITICAL: arrange the six around the left "
    "edge, right edge and bottom of the frame, three per side, faces and weapons angled in toward the center; the whole "
    "upper-center region (the middle 60% of the width, top 45% of the height) must be EMPTY stormy sky with no character, head, "
    "hand or weapon in it, reserved for a big title logo. No text, letters, numbers, logos or watermark anywhere. Landscape 16:9."
)
LOGO_PROMPT = (
    "A video game title logo, isolated on a fully transparent background, in a bold 1990s arcade fighting-game style. "
    "Behind everything: huge chrome-blue metallic block capitals reading \"VIRTUAL PET\" with a dark navy outline and a "
    "sky-blue-to-white vertical gradient. Overlapping in front of them: an energetic slanted brush-script title reading "
    "\"Slasher 2\" in a hot-pink-to-yellow gradient with a thick dark purple outline and slash-like speed strokes, with the "
    "\"2\" drawn big and bold. Below on the right: the word \"TURBO\" in chunky italic capitals with a red-to-gold gradient and a "
    "thick navy outline. Exact spelling: VIRTUAL PET, Slasher 2, TURBO — no other text or symbols. Crisp clean edges, "
    "subtle drop shadow, centered composition."
)


def redact(text: str, key: str) -> str:
    return text.replace(key, "[redacted]") if key else text


def reference_png(path: Path) -> bytes:
    """The base render with its magenta key removed, as PNG bytes."""
    src = np.asarray(Image.open(path).convert("RGBA"))
    keyed = key_magenta(src[..., :3])
    if src[..., 3].min() < 255:
        keyed[..., 3] = np.minimum(keyed[..., 3], src[..., 3])
    buf = io.BytesIO()
    Image.fromarray(keyed, "RGBA").save(buf, "PNG")
    return buf.getvalue()


def save_b64(data: dict, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(base64.b64decode(data["b64_json"]))
    print(f"wrote {dest.relative_to(ROOT)}")


def make_art(key: str, k: int) -> None:
    files = [("image[]", (f"{pet}.png", reference_png(ROOT / path), "image/png")) for pet, path, _ in REFERENCES]
    cast = "; ".join(desc for _, _, desc in REFERENCES)
    r = requests.post(f"{API}/images/edits", headers={"Authorization": f"Bearer {key}"}, files=files,
                      data={"model": MODEL, "prompt": ART_PROMPT.format(cast=cast), "size": "2560x1440",
                            "quality": "high", "n": "1"}, timeout=600)
    if r.status_code != 200:
        raise RuntimeError(redact(f"{r.status_code}: {r.text[:500]}", key))
    save_b64(r.json()["data"][0], OUT / f"art-{k}.png")


def make_logo(key: str, k: int) -> None:
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "prompt": LOGO_PROMPT, "size": "2048x1152", "quality": "high",
                            "background": "transparent", "output_format": "png", "n": 1}, timeout=600)
    if r.status_code != 200:
        raise RuntimeError(redact(f"{r.status_code}: {r.text[:500]}", key))
    save_b64(r.json()["data"][0], OUT / f"logo-{k}.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--art", type=int, default=2, help="number of key-art candidates")
    ap.add_argument("--logo", type=int, default=2, help="number of logo candidates")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    failed = 0
    for k in range(args.start, args.start + args.art):
        try:
            make_art(key, k)
        except Exception as e:  # noqa: BLE001 - report and keep going
            print(f"FAIL art-{k}: {redact(str(e), key)}", file=sys.stderr)
            failed += 1
    for k in range(args.start, args.start + args.logo):
        try:
            make_logo(key, k)
        except Exception as e:  # noqa: BLE001
            print(f"FAIL logo-{k}: {redact(str(e), key)}", file=sys.stderr)
            failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
