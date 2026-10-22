"""Gameplay recordings as frames. A Clip is a stretch of a capture in art-work/video/capture/ (recorded off the
emulators with `adb shell screenrecord`; not in the repo), cut once into JPEGs at the video's frame rate under
art-work/video/cache/, which scenes then read by time.

Clips are extracted in a scene's prepare(), which render() runs once before the frames are drawn in parallel.
"""
from __future__ import annotations

import subprocess
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from PIL import Image

from .cast import ROOT
from .render import FFMPEG, FPS

CAPTURE = ROOT / "art-work/video/capture"
CACHE = ROOT / "art-work/video/cache/footage"


@dataclass(frozen=True)
class Clip:
    """[file]'s stretch from [start] to [end] seconds, played [speed] times as fast."""
    file: str
    start: float
    end: float
    speed: float = 1.0

    @property
    def duration(self) -> float:
        return (self.end - self.start) / self.speed

    @property
    def dir(self) -> Path:
        return CACHE / f"{Path(self.file).stem}-{self.start:.2f}-{self.end:.2f}"

    def extract(self) -> None:
        done = self.dir / ".done"
        if done.exists():
            return
        self.dir.mkdir(parents=True, exist_ok=True)
        subprocess.run([FFMPEG, "-v", "error", "-y", "-ss", f"{self.start:.3f}", "-to", f"{self.end:.3f}",
                        "-i", str(CAPTURE / self.file), "-vf", f"fps={FPS}", "-q:v", "2",
                        str(self.dir / "%05d.jpg")], check=True)
        done.touch()

    def path(self, t: float) -> Path:
        """The frame showing [t] seconds into the clip (held at either end)."""
        i = min(_count(self.dir), max(1, 1 + int(t * self.speed * FPS)))
        return self.dir / f"{i:05d}.jpg"

    def frame(self, t: float) -> Image.Image:
        return _load(self.path(t))


@lru_cache(maxsize=None)
def _count(folder: Path) -> int:
    return len(list(folder.glob("*.jpg")))


@lru_cache(maxsize=64)
def _load(path: Path) -> Image.Image:
    return Image.open(path).convert("RGB")
