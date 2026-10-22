"""Retouch a picked killer pose (art-work/site/<pet>-<k>.png) without redrawing it: the image model repaints the
pose with only the hands fixed, told where they are by a mask. It keeps the pose, framing and details close enough to
use the repaint whole (pasting just the hands back into the original leaves ghosted seams, as the hands it draws never
sit exactly where the old ones did).

Candidates go to art-work/site/<pet>-<n>.png from --start (gitignored); pick one in tools/finalize_site_art.py and
tools/reel/cast.py. The API key is read from the OPENAI_API_KEY environment variable only.

Where a take gets one hand right and the other worse than the original, --merge pastes just that hand's part of the
take back onto the original (MERGE), which the repaint stays close enough to for the seam to vanish.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/retouch_site_art.py --only freddy [--n 2] [--start 3]
       tools/.venv/bin/python -I tools/retouch_site_art.py --merge freddy 22 glove --start 28
"""
from __future__ import annotations

import argparse
import base64
import io
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_cover import API, MODEL, redact  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "art-work/site"

KEEP = (
    "Retouch this exact image. Keep everything else perfectly identical: the same character, pose, face, outfit, "
    "weapon, colors, lighting, rim light, glossy 3D chibi collectible-figure style, framing and fully transparent "
    "background. Change only the hands: {fix} Anatomically correct, clean and natural, the same size and position as "
    "now, nothing else added."
)
# pet: (the pick to retouch, boxes around the hands as (left, top, right, bottom) px, what to fix)
RETOUCH = {
    # 15 has the bare hand fixed (merged from take 12), so only the glove's missing thumb is left, masked to just the
    # gap under its palm (masking the whole glove has it redraw the caps and blades)
    "freddy": (15, [(450, 590, 610, 770)],
               "his bare, burned hand on the right side of the image stays exactly as it is. His bladed glove on the "
               "left side of the image keeps every brass finger cap and every long, sweeping, glowing curved steel "
               "blade exactly as it is now (same place, length, curve, spacing and shine), still exactly four caps "
               "and four blades. Add only his missing thumb: a plain brown leather glove thumb with a rounded leather "
               "tip, no blade and no cap, coming out from under the bottom of the leather palm and curling down and "
               "inward between the two lower blades, in front of the red sweater fringe, clearly visible."),
    "ghostface": (1, [(1000, 730, 1460, 1070)],
                  "his open, reaching black-gloved hand on the right side of the image must have exactly five digits, "
                  "a thumb and four fingers, splayed and grasping toward the viewer."),
    "pinhead": (2, [(1090, 560, 1330, 840), (255, 425, 470, 690)],
                "both of his pale open hands must have exactly five digits each, a thumb and four fingers, elegant and "
                "relaxed with the palms open toward the viewer, the same pose as now."),
}
# pet: {part: the take's part to keep, as (left, top, right, bottom) px, cut where take and original look alike}
MERGE = {
    "freddy": {"hand": (1150, 540, 1536, 920),  # his bare hand, keeping the original's bladed glove (1 + 12 = 15)
               "glove": (425, 565, 630, 825)},  # the thumb under his glove, keeping 15's caps and blades (15 + 22 = 28)
}
FEATHER = 12  # px the merged part blends into the original over


def mask(size: tuple[int, int], boxes: list[tuple[int, int, int, int]]) -> Image.Image:
    """The model's mask: transparent inside [boxes], where it may paint, opaque elsewhere."""
    m = Image.new("L", size, 255)
    d = ImageDraw.Draw(m)
    for l, t, r, b in boxes:
        d.rectangle([l, t, r, b], fill=0)
    hint = Image.new("RGBA", size, (0, 0, 0, 255))
    hint.putalpha(m)
    return hint


def retouch(key: str, pet: str, k: int) -> Path:
    pick, boxes, fix = RETOUCH[pet]
    original = Image.open(SRC / f"{pet}-{pick}.png").convert("RGBA")
    files = []
    for name, im in (("image[]", original), ("mask", mask(original.size, boxes))):
        buf = io.BytesIO()
        im.save(buf, "PNG")
        files.append((name, (f"{name.strip('[]')}.png", buf.getvalue(), "image/png")))
    r = requests.post(f"{API}/images/edits", headers={"Authorization": f"Bearer {key}"}, files=files,
                      data={"model": MODEL, "prompt": KEEP.format(fix=fix), "size": f"{original.width}x{original.height}",
                            "quality": "high", "background": "transparent", "output_format": "png", "n": "1"},
                      timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    painted = Image.open(io.BytesIO(base64.b64decode(r.json()["data"][0]["b64_json"]))).convert("RGBA")
    dest = SRC / f"{pet}-{k}.png"
    painted.resize(original.size, Image.LANCZOS).save(dest)
    return dest


def merge(pet: str, take: int, part: str, k: int) -> Path:
    """[pet]'s original pick with [take]'s MERGE [part] laid over it, feathered in, as candidate [k]."""
    original = Image.open(SRC / f"{pet}-{RETOUCH[pet][0]}.png").convert("RGBA")
    painted = Image.open(SRC / f"{pet}-{take}.png").convert("RGBA")
    m = Image.new("L", original.size, 0)
    l, t, r, b = MERGE[pet][part]
    ImageDraw.Draw(m).rectangle([l, t, r, b], fill=255)
    out = Image.composite(painted, original, m.filter(ImageFilter.GaussianBlur(FEATHER)))
    dest = SRC / f"{pet}-{k}.png"
    out.save(dest)
    return dest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", choices=list(RETOUCH))
    ap.add_argument("--n", type=int, default=2, help="candidates of each")
    ap.add_argument("--start", type=int, default=3, help="first candidate number (past the picks, so none is overwritten)")
    ap.add_argument("--merge", nargs=3, metavar=("PET", "TAKE", "PART"), help="lay TAKE's MERGE PART over PET's original")
    args = ap.parse_args()
    if args.merge:
        pet, take, part = args.merge[0], int(args.merge[1]), args.merge[2]
        if (SRC / f"{pet}-{args.start}.png").exists():
            print(f"{pet}-{args.start}.png already exists; pass a later --start", file=sys.stderr)
            return 2
        print(f"wrote {merge(pet, take, part, args.start).relative_to(ROOT)}")
        return 0
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    jobs = [(p, k) for p in (args.only or RETOUCH) for k in range(args.start, args.start + args.n)]
    for p, k in jobs:
        if (SRC / f"{p}-{k}.png").exists():
            print(f"{p}-{k}.png already exists; pass a later --start", file=sys.stderr)
            return 2
    failed = 0
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [((p, k), pool.submit(retouch, key, p, k)) for p, k in jobs]
        for (p, k), fut in futures:
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {p}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
