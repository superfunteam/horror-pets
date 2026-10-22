"""Render the launch video's scenes (tools/reel/) to art-work/video/out/ (gitignored).

  intros: each killer's intro as its own MP4, and all seven back to back as intros-reel.mp4.
  --stills 0.3,0.7,1.5 writes those frames as JPEGs instead, for checking a scene quickly.

Usage: tools/.venv/bin/python -I tools/render_video.py intros [--only jason] [--stills 0.3,0.7]
"""
from __future__ import annotations

import argparse
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from reel import cast, render  # noqa: E402
from reel.intro import CharacterIntro  # noqa: E402

OUT = cast.ROOT / "art-work/video/out"


def intro(pet: str, stills: list[float] | None) -> list[Path]:
    scene = CharacterIntro(cast.BY_ID[pet])
    if stills:
        return render.stills(scene, stills, OUT / "stills", f"intro-{pet}")
    return [render.render(scene, OUT / f"intro-{pet}.mp4")]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("scene", choices=["intros"])
    ap.add_argument("--only", action="append", choices=list(cast.BY_ID))
    ap.add_argument("--stills", type=lambda s: [float(x) for x in s.split(",")])
    args = ap.parse_args()
    pets = [k.id for k in cast.KILLERS if not args.only or k.id in args.only]
    start = time.time()
    with ProcessPoolExecutor(max_workers=min(len(pets), 7)) as pool:
        results = dict(zip(pets, pool.map(intro, pets, [args.stills] * len(pets))))
    for pet in pets:
        for p in results[pet]:
            print(f"wrote {p.relative_to(cast.ROOT)}")
    if not args.stills and not args.only:
        reel = render.concat([results[p][0] for p in pets], OUT / "intros-reel.mp4")
        print(f"wrote {reel.relative_to(cast.ROOT)}")
    print(f"took {time.time() - start:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
