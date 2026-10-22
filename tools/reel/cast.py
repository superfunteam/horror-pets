"""The seven killers as the video shows them, and where their art, stages and sounds are."""
from __future__ import annotations

import json
from dataclasses import dataclass
from functools import cache
from pathlib import Path

from PIL import Image

from . import fx

ROOT = Path(__file__).resolve().parents[2]

# The landing page's picks (tools/finalize_site_art.py): the full-size render when it is here, else the site's copy.
POSE_PICKS = {"jason": 1, "freddy": 1, "pinhead": 2, "ghostface": 1, "chucky": 1, "michael": 1, "leatherface": 1}


@dataclass(frozen=True)
class Killer:
    id: str
    stage: str  # which of their scenes in scenes-final/ they are shown on
    entry: str  # where their intro brings them in from: right, left, top, bottom or depth

    @property
    def name(self) -> str:
        return self.id.upper()


KILLERS = [
    Killer("jason", "dock", "top"),
    Killer("freddy", "boiler", "right"),
    Killer("pinhead", "chamber", "depth"),
    Killer("ghostface", "house", "left"),
    Killer("chucky", "toystore", "bottom"),
    Killer("michael", "haddonfield", "right"),
    Killer("leatherface", "butcher", "left"),
]
BY_ID = {k.id: k for k in KILLERS}


@cache
def _stages() -> dict:
    return json.loads((ROOT / "scenes-final/scenes.json").read_text())


def stage(k: Killer) -> dict:
    """[k]'s stage entry from scenes.json: its title, file and accent color."""
    return next(s for s in _stages()[k.id] if s["id"] == k.stage)


def accent(k: Killer) -> tuple[int, int, int]:
    h = stage(k)["accent"].lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def backdrop(k: Killer) -> Image.Image:
    return Image.open(ROOT / "scenes-final" / stage(k)["file"]).convert("RGB")


def pose(k: Killer) -> Image.Image:
    """[k]'s action pose, trimmed to its visible pixels."""
    full = ROOT / f"art-work/site/{k.id}-{POSE_PICKS[k.id]}.png"
    src = full if full.exists() else ROOT / f"site/art/{k.id}.webp"
    return fx.trim(Image.open(src).convert("RGBA"))


def wordmark(k: Killer) -> Image.Image:
    """[k]'s name as a logo (tools/finalize_wordmarks.py), trimmed."""
    return fx.trim(Image.open(ROOT / f"video/wordmarks/{k.id}.webp").convert("RGBA"))


def voice(name: str) -> Path:
    return ROOT / f"audio-final/voice/{name}.wav"


def sfx(name: str) -> Path:
    return ROOT / f"audio-final/sfx/{name}.wav"
