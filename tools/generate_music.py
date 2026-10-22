"""Generate candidate songs for the launch video with ElevenLabs Music: one-minute horror trap instrumentals, each a
different flavor, laid out so the video can cut on their hits (an intro, a drop, a breakdown, a bigger drop, a hard end).

They go to art-work/video/music/ (gitignored) as trap-<name>.mp3. The API key is read from the ELEVENLABS_API_KEY
environment variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_music.py [--only music-box] [--seconds 60]
"""
from __future__ import annotations

import argparse
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_announcer import API, redact  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-work/video/music"
MODEL = "music_v1"
OUTPUT_FORMAT = "mp3_44100_192"

TRACKS = {
    "music-box": (
        "Dark horror trap instrumental, 140 BPM, F minor. A detuned, warped wind-up music box plays a creepy "
        "nursery-rhyme melody over thick eerie pads, toy piano stabs and vinyl crackle. Booming distorted 808 bass "
        "with pitch slides, crisp snares, fast rolling triplet hi-hats, reversed cymbals. Structure: the music box "
        "alone for the first 8 seconds, a short tension riser, then a hard drop with the full beat; a stripped "
        "breakdown at about 0:32 with just the music box and a heartbeat kick; a second, heavier drop at 0:40; a "
        "sudden hard stop on one last 808 hit at 1:00."
    ),
    "slasher-synth": (
        "Retro 1980s slasher-movie synth trap instrumental, 145 BPM, A minor. A cold, relentless analog synth "
        "arpeggio and a sparse, repeating high piano motif in an unsettling rhythm, glassy VHS-warped pads, over hard "
        "trap drums: distorted 808s, big gated-reverb snares, rapid hi-hat rolls and stutters, metallic knife-scrape "
        "risers. Structure: only the piano motif and the synth pulse for the first 8 seconds, then a drop with the "
        "full beat; a filter-sweep breakdown in the middle; a final drop with a screeching lead synth added; a hard "
        "cut to silence at 1:00."
    ),
    "hell-organ": (
        "Epic gothic horror trap instrumental, 150 BPM, D minor. Massive cathedral pipe organ chords, a dark choir "
        "pad, tolling church bells, rattling chains used as percussion, staccato low strings, with crushing distorted "
        "808s, hard-hitting trap snares, aggressive hi-hat rolls, big impacts and braams. Structure: an ominous organ "
        "and bells intro for the first 8 seconds, then a hard drop; a breakdown led by the choir pad and the chains "
        "around the middle; a final huge drop with everything; ending on one giant hit and a last bell toll at 1:00."
    ),
}


def compose(key: str, name: str, seconds: int) -> Path:
    r = requests.post(f"{API}/music", params={"output_format": OUTPUT_FORMAT}, headers={"xi-api-key": key},
                      json={"prompt": TRACKS[name], "music_length_ms": seconds * 1000, "model_id": MODEL,
                            "force_instrumental": True},
                      timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"music {r.status_code}: {r.text[:400]}")
    dest = OUT / f"trap-{name}.mp3"
    dest.write_bytes(r.content)
    return dest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", choices=list(TRACKS))
    ap.add_argument("--seconds", type=int, default=60)
    args = ap.parse_args()
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    names = [n for n in TRACKS if not args.only or n in args.only]
    failed = 0
    with ThreadPoolExecutor(max_workers=2) as pool:  # the account allows two music requests at once
        futures = [(n, pool.submit(compose, key, n, args.seconds)) for n in names]
        for n, fut in futures:
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {n}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
