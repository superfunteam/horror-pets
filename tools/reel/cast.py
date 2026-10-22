"""The seven killers as the video shows them, and where their art, stages and sounds are."""
from __future__ import annotations

import json
import re
import subprocess
import wave
from dataclasses import dataclass
from functools import cache
from pathlib import Path

import numpy as np
from PIL import Image

from . import fx

ROOT = Path(__file__).resolve().parents[2]

# The landing page's picks (tools/finalize_site_art.py), but for Chucky a redo with his arms clear of his chin and a
# whole knife (tools/generate_site_art.py): the full-size render when it is here, else the site's copy.
POSE_PICKS = {"jason": 1, "freddy": 15, "pinhead": 3, "ghostface": 3, "chucky": 2, "michael": 1, "leatherface": 1}


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


# How loud the game's fight sounds play over the song, which they are not mastered for: about 6 dB over it where
# they land (specials are fuller, and need less).
SFX_GAIN = {"hit": 2.0, "heavy_hit": 1.4, "crit": 1.9, "miss": 2.2, "ko": 1.8}
SPECIAL_GAIN = 1.2


def sfx_gain(name: str) -> float:
    return SPECIAL_GAIN if name.startswith("special_") else SFX_GAIN[name]


@cache
def punch(path: Path) -> float:
    """When the WAV at [path] is loudest: seconds to the peak of its 10 ms envelope."""
    with wave.open(str(path)) as w:
        rate, channels = w.getframerate(), w.getnchannels()
        x = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, channels).astype(float).mean(1)
    n = max(1, rate // 100)
    return float(np.argmax(np.convolve(np.abs(x), np.ones(n) / n, "same"))) / rate


# The killers' sounds in their intros: the trailer's own clips in audio/trailer/<pet>.mp3 (supplied for the trailer;
# not in the repo, not in the game): a line or a sound bite each, their leading silence trimmed, the second half of
# Pinhead's ("has a face") twice as loud, and all matched in loudness. Michael has none; he is silent.
TRAILER_AUDIO = ROOT / "audio/trailer"
INTRO_LUFS = -14.0
INTRO_BOOST = {"pinhead": (1.3, 2.0)}  # from this many seconds into the clip, this many times louder
# Freddy's and Chucky's lines start under room tone (so trimming silence takes nothing) and run a little long for six
# beats: their lead-in is cut and they are played a touch faster, pitch kept.
INTRO_FIT = {"freddy": (0.25, 1.06), "chucky": (0.4, 1.06)}  # (seconds cut from the start, tempo)
SOUND_CACHE = ROOT / "art-work/video/cache/sfx"


def _lufs(path: Path) -> float:
    err = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(path), "-af", "ebur128", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    return float(re.findall(r"I:\s+(-?[\d.]+) LUFS", err)[-1])


def intro_sound(pet: str) -> Path | None:
    """[pet]'s clip for its intro (see TRAILER_AUDIO), built into the cache the first time; None for Michael."""
    src = TRAILER_AUDIO / f"{pet}.mp3"
    if pet == "michael":
        return None
    if not src.exists():
        raise FileNotFoundError(f"missing the trailer clip {src.relative_to(ROOT)}")
    out = SOUND_CACHE / f"trailer-{pet}.wav"
    if out.exists() and out.stat().st_mtime > src.stat().st_mtime:
        return out
    SOUND_CACHE.mkdir(parents=True, exist_ok=True)
    shaped = SOUND_CACHE / f"trailer-{pet}-shaped.wav"
    at, gain = INTRO_BOOST.get(pet, (0.0, 1.0))
    cut, tempo = INTRO_FIT.get(pet, (0.0, 1.0))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-af",
                    f"volume={gain}:enable='gte(t,{at})',atrim=start={cut},asetpts=PTS-STARTPTS,atempo={tempo},"
                    "afade=t=in:d=0.03,silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.03",
                    "-c:a", "pcm_f32le", str(shaped)], check=True)
    db = INTRO_LUFS - _lufs(shaped)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(shaped), "-af",
                    f"volume={db:.2f}dB,alimiter=limit=0.95:level=disabled", str(out)], check=True)
    shaped.unlink()
    return out
