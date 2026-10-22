"""Generate the launch video's announcer lines into video/voice/, the same way tools/generate_announcer.py makes the
game's: the same voice shouting, --takes takes, and the loudest take that ElevenLabs speech-to-text hears back right.

These are only for the video, so they stay out of audio-final/ (which the app ships).
The API key is read from the ELEVENLABS_API_KEY environment variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_video_voice.py [--takes 3]
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_announcer import (  # noqa: E402
    MIN_MATCH, duration_s, encode, loudness_db, match, redact, speak, transcribe, words,
)

ROOT = Path(__file__).resolve().parents[1]
LINES = {
    "title": "[shouting] VIRTUAL PET SLASHER 2 TURBO!!!",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--takes", type=int, default=3, help="takes per line; the loudest one that reads back right wins")
    args = ap.parse_args()
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    raw_dir, out_dir = ROOT / "art-work/video/voice", ROOT / "video/voice"
    raw_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for name, text in LINES.items():
        best = None
        for take in range(args.takes):
            raw = raw_dir / f"{name}-{take}.mp3"
            try:
                raw.write_bytes(speak(key, text))
                heard = transcribe(key, raw)
            except (requests.RequestException, RuntimeError) as e:
                print(f"  {name} take {take}: {redact(str(e), key)}", file=sys.stderr)
                continue
            score, level = match(text, heard), loudness_db(raw)
            print(f"  {name} take {take}: heard {heard!r} (match {score:.2f}), {level:.1f} dBFS")
            if score >= MIN_MATCH and (best is None or level > best[0]):
                best = (level, raw)
        if best is None:
            print(f"FAIL {name}: no take read back as {words(text)!r}", file=sys.stderr)
            failed += 1
            continue
        out = out_dir / f"{name}.wav"
        encode(best[1], out)
        print(f"video/voice/{name}.wav  {duration_s(out):.2f}s")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
