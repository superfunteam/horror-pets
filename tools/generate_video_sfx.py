"""Generate the launch video's sound effects with ElevenLabs sound generation into video/sfx/ (16-bit PCM WAV):
the CRT's static, the channel change onto the title screen, and the bling of pressing start.

Each is generated --takes times into art-work/video/sfx/ (gitignored) and the loudest take is kept, normalized.
The API key is read from the ELEVENLABS_API_KEY environment variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_video_sfx.py [--only start] [--takes 2]
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_announcer import API, duration_s, encode, redact  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SOUNDS = {  # (prompt, seconds)
    "static": ("Soft, quiet analog TV static hiss from an old CRT television left on in a dark room, gentle white "
               "noise with a faint electrical hum, no music, no voices", 3.0),
    "channel": ("An old CRT television changing channel: a hard mechanical knob click and a short sharp crackling "
                "burst of static that cuts off, no music, no voices", 1.0),
    "projector": ("An old 16mm film projector starting up: a mechanical clattering whir and the film rattling through the gate, "
                  "speeding up faster and faster, no music, no voices", 3.0),
    "start": ("Retro arcade video game press start sound: a bright, sparkling, punchy bling chime with a quick rising "
              "arpeggio, 16-bit game console, no voices", 1.2),
}
FILTER = "loudnorm=I=-16:TP=-1.0:LRA=7"


def level_db(path: Path) -> float:
    """RMS level of the whole take, in dBFS (static and crackle are too soft for the announcer's voiced level)."""
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(pcm, np.float32)
    return float(20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-9))


def generate(key: str, text: str, seconds: float) -> bytes:
    r = requests.post(f"{API}/sound-generation", headers={"xi-api-key": key},
                      json={"text": text, "duration_seconds": seconds, "prompt_influence": 0.6}, timeout=120)
    if r.status_code != 200:
        raise RuntimeError(f"sound-generation {r.status_code}: {r.text[:300]}")
    return r.content


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", choices=list(SOUNDS))
    ap.add_argument("--takes", type=int, default=2)
    args = ap.parse_args()
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    raw_dir, out_dir = ROOT / "art-work/video/sfx", ROOT / "video/sfx"
    raw_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for name, (text, seconds) in SOUNDS.items():
        if args.only and name not in args.only:
            continue
        best = None
        for take in range(args.takes):
            raw = raw_dir / f"{name}-{take}.mp3"
            try:
                raw.write_bytes(generate(key, text, seconds))
            except (requests.RequestException, RuntimeError) as e:
                print(f"  {name} take {take}: {redact(str(e), key)}", file=sys.stderr)
                continue
            level = level_db(raw)
            print(f"  {name} take {take}: {level:.1f} dBFS")
            if best is None or level > best[0]:
                best = (level, raw)
        if best is None:
            failed += 1
            continue
        out = out_dir / f"{name}.wav"
        encode(best[1], out, FILTER)
        print(f"video/sfx/{name}.wav  {duration_s(out):.2f}s")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
