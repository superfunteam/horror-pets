"""Render the launch video (tools/reel/trailer.py) to art-work/video/out/launch.mp4 (gitignored).

It needs the song at art-work/video/music/spooky-gabber.mp3, which is not in the repo.
--stills 0.3,0.7,1.5 writes those frames as JPEGs instead, for checking the cut quickly.

Usage: tools/.venv/bin/python -I tools/render_video.py [--stills 0.3,0.7] [--workers 8]
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from reel import cast, render, trailer  # noqa: E402

OUT = cast.ROOT / "art-work/video/out"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stills", type=lambda s: [float(x) for x in s.split(",")])
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()
    if not trailer.SONG.exists():
        print(f"missing the song: {trailer.SONG.relative_to(cast.ROOT)}", file=sys.stderr)
        return 2
    start = time.time()
    if args.stills:
        paths = render.stills(trailer.make(), args.stills, OUT / "stills", "launch")
    else:
        paths = [render.render(trailer.make, OUT / "launch.mp4", workers=args.workers)]
    for p in paths:
        print(f"wrote {p.relative_to(cast.ROOT)}")
    print(f"took {time.time() - start:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
