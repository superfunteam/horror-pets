"""Generate candidate songs for the launch video with ElevenLabs Music: one-minute horror trap/dubstep instrumentals,
each with its own horror hook, all built the same hard way so the video can cut on them: a short intro, then three
wobble-bass drops with quick builds between them, and one last hit.

Each track is sent to music_v2_5 as a composition plan (STRUCTURE, one chunk per section with its exact length), which
that model always keeps to; music_v1 drifted from timings both in a prompt and in a plan. Even so, what happens inside
a section varies take to take (a drop can come out sparse), so each track is generated --takes times and the take
whose loudness best follows the plan's intensity wins. Takes go to art-work/video/music/takes/ and the winners to
art-work/video/music/wub-<name>.mp3 (all gitignored). The API key is read from the ELEVENLABS_API_KEY environment
variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_music.py [--only music-box] [--takes 2]
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_announcer import API, redact  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-work/video/music"
MODEL = "music_v2_5"
OUTPUT_FORMAT = "mp3_48000_192"

GENRE = ["horror trap", "dubstep", "riddim", "heavy wobble bass", "wub wub bass", "aggressive", "hard-hitting",
         "half-time trap drums", "distorted 808s", "150 BPM", "dark", "instrumental"]
CORE = ["dubstep", "horror trap", "150 BPM", "dark"]  # carried into every section after the first, which sets the tone
NEGATIVE = ["vocals", "singing", "lyrics", "rap", "soft", "chill", "lo-fi", "acoustic", "fade out"]
QUIET = ["full drums", "bass drop", "wobble bass"]  # kept out of the intro, builds and breakdown, so the drops hit
FULL = ["sparse", "breakdown", "ambient", "quiet", "outro"]  # kept out of the drops

# (section, seconds, how hard it should hit from 0 to 1, what happens in it); {hook} is each track's horror sound.
# Drops land at 0:08, 0:24 and 0:46.
STRUCTURE = [
    ("Intro", 4, 0.15, ["{hook} alone", "eerie", "tense"]),
    ("Build", 4, 0.5, ["snare roll speeding up", "rising siren riser", "{hook}"]),
    ("Drop 1", 12, 1.0, ["massive wobble bass drop", "wub wub bass", "half-time trap drums", "distorted 808", "{hook}"]),
    ("Build 2", 4, 0.5, ["fast snare roll", "pitch riser", "reverse cymbal"]),
    ("Drop 2", 12, 1.0, ["filthy riddim wobbles", "fast wobble bass", "triplet hi-hat rolls", "growling bass", "{hook}"]),
    ("Breakdown", 6, 0.25, ["{hook} alone", "sub bass rumble", "heartbeat kick"]),
    ("Build 3", 4, 0.5, ["accelerating snare roll", "screaming riser", "a beat of silence right before the drop"]),
    ("Drop 3", 11, 1.0, ["the biggest, loudest drop", "screeching wobble bass", "everything at once", "{hook}"]),
    ("Final hit", 3, 0.8, ["one last massive bass hit", "{hook} ringing out"]),
]
TRACKS = {  # each track's hook, and what else colors it
    "music-box": ("a detuned, creepy wind-up music box melody", ["toy piano", "nursery rhyme horror", "F minor"]),
    "slasher-synth": ("a cold 1980s analog synth arpeggio and a high piano motif",
                      ["retro slasher movie synths", "gated reverb snares", "A minor"]),
    "hell-organ": ("cathedral pipe organ chords and tolling church bells", ["dark choir pad", "rattling chains", "D minor"]),
}


def plan(name: str) -> dict:
    """[name]'s composition plan: one chunk per section, the first carrying the whole track's styles."""
    hook, extra = TRACKS[name]
    chunks = []
    for i, (section, seconds, intensity, styles) in enumerate(STRUCTURE):
        local = [s.format(hook=hook) for s in styles]
        chunks.append({
            "text": f"[{section}]\n{{{', '.join(local)}}}",
            "duration_ms": seconds * 1000,
            "positive_styles": (GENRE + extra if i == 0 else CORE) + local,
            "negative_styles": NEGATIVE + (FULL if intensity >= 0.8 else QUIET if intensity <= 0.5 else []),
        })
    return {"chunks": chunks}


def follows_plan(path: Path) -> float:
    """How closely [path]'s loudness, section by section, rises and falls with the plan's intensity (-1 to 1)."""
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", "22050", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(pcm, np.float32)
    levels, t = [], 0
    for _, seconds, _, _ in STRUCTURE:
        seg = x[int(t * 22050):int((t + seconds) * 22050)]
        levels.append(10 * np.log10(np.mean(seg ** 2) + 1e-9))
        t += seconds
    return float(np.corrcoef([s[2] for s in STRUCTURE], levels)[0, 1])


def compose(key: str, name: str, take: int) -> Path:
    r = requests.post(f"{API}/music", params={"output_format": OUTPUT_FORMAT}, headers={"xi-api-key": key},
                      json={"composition_plan": plan(name), "model_id": MODEL}, timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"music {r.status_code}: {r.text[:600]}")
    dest = OUT / "takes" / f"wub-{name}-{take}.mp3"
    dest.write_bytes(r.content)
    return dest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", choices=list(TRACKS))
    ap.add_argument("--takes", type=int, default=2, help="takes per track; the one that best follows the plan wins")
    args = ap.parse_args()
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    (OUT / "takes").mkdir(parents=True, exist_ok=True)
    jobs = [(n, k) for n in TRACKS if not args.only or n in args.only for k in range(args.takes)]
    takes: dict[str, list[tuple[float, Path]]] = {}
    with ThreadPoolExecutor(max_workers=2) as pool:  # the account allows two music requests at once
        futures = [(n, k, pool.submit(compose, key, n, k)) for n, k in jobs]
        for n, k, fut in futures:
            try:
                path = fut.result()
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {n} take {k}: {redact(str(e), key)}", flush=True)
                continue
            score = follows_plan(path)
            print(f"  {n} take {k}: follows the plan {score:+.2f}", flush=True)
            takes.setdefault(n, []).append((score, path))
    failed = 0
    for n in dict.fromkeys(n for n, _ in jobs):
        if n not in takes:
            failed += 1
            continue
        score, best = max(takes[n])
        shutil.copyfile(best, OUT / f"wub-{n}.mp3")
        print(f"wrote art-work/video/music/wub-{n}.mp3 (take {best.stem.rsplit('-', 1)[1]}, {score:+.2f})")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
