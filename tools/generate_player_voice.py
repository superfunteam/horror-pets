"""Generate the player's voice for Play (the cartoon glove's baby talk) with ElevenLabs into audio-final/voice/.

The same pipeline as tools/generate_announcer.py (Eleven v4, --takes takes a line, only takes whose speech-to-text
read-back matches count, the loudest wins), with ElevenLabs' playful "Jessica" voice and no arena echo: the player
is in the room, not on the PA. The app picks one of each gag's lines at random (ui/home/PlayHand.kt).

The API key is read from the ELEVENLABS_API_KEY environment variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_player_voice.py [--only NAME ...] [--takes 3]
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_announcer import MIN_MATCH, duration_s, encode, loudness_db, match, redact, speak, transcribe, words  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
VOICE_ID = "cgSgspJ2msm6clMCkdW9"  # Jessica - Playful, Bright, Warm
# Trim the silence either side and normalize a little under the announcer, who should stay the loudest voice.
FILTER = ("silenceremove=start_periods=1:start_threshold=-35dB:start_silence=0.03:stop_periods=-1:"
          "stop_threshold=-40dB:stop_duration=0.1:stop_silence=0.05,loudnorm=I=-16:TP=-1.0:LRA=7")

LINES = {
    "play_tickle_1": "[giggles] Tickle tickle tickle!",
    "play_tickle_2": "[playful] Goochie goochie goo!",
    "play_poke_1": "[playful] Boop!",
    "play_poke_2": "[playful] Poke! Poke! Poke!",
    "play_keys_1": "[excited] Ooh! Who wants the shiny keys?",
    "play_keys_2": "[playful] Jingle jingle jingle!",
    "play_shake_1": "[excited] Up you go! Wheee!",
    "play_shake_2": "[playful] Who's a good little killer? You are!",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append")
    ap.add_argument("--takes", type=int, default=3, help="takes per line; the loudest one that reads back right wins")
    args = ap.parse_args()
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    raw_dir = ROOT / "art-work/voice"
    out_dir = ROOT / "audio-final/voice"
    raw_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for name, text in LINES.items():
        if args.only and name not in args.only:
            continue
        best = None
        for take in range(args.takes):
            raw = raw_dir / f"{name}-{take}.mp3"
            try:
                raw.write_bytes(speak(key, text, VOICE_ID))
                heard = transcribe(key, raw)
            except (requests.RequestException, RuntimeError) as e:
                print(f"  {name} take {take}: {redact(str(e), key)}", file=sys.stderr)
                continue
            score, level = match(text, heard), loudness_db(raw)
            print(f"  {name} take {take}: heard {heard!r} (match {score:.2f}), {level:.1f} dBFS", flush=True)
            if score >= MIN_MATCH and (best is None or level > best[0]):
                best = (level, raw)
        if best is None:
            print(f"FAIL {name}: no take read back as {words(text)!r}", file=sys.stderr)
            failed += 1
            continue
        out = out_dir / f"{name}.wav"
        encode(best[1], out, FILTER)
        print(f"voice/{name}.wav  {duration_s(out):.2f}s", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
