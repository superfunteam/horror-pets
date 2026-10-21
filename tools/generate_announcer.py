"""Generate the fight announcer lines with OpenAI TTS into audio-final/voice/ (16-bit PCM mono .wav, 44.1 kHz).

WAV rather than Opus-in-Ogg because the app plays the lines through SoundPool, which cannot decode
Opus below Android 10.

The API key is read from the OPENAI_API_KEY environment variable only.
Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_announcer.py [--only NAME ...]
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.openai.com/v1"
VOICE = "onyx"
INSTRUCTIONS = ("Over-the-top fighting-game announcer. Booming, deep, dramatic and larger than life, "
                "like a 1990s arcade cabinet. Draw out the names. Short, punchy delivery. "
                "Say the text exactly once and nothing else: no repeats, no extra words.")

LINES = {
    "jason": "Jasonnn!",
    "freddy": "Freddy!",
    "pinhead": "Pinhead!",
    "ghostface": "Ghostface!",
    "chucky": "Chucky!",
    "michael": "Michael!",
    "leatherface": "Leatherface!",
    "versus": "Versus!",
    "fight": "Fight!",
    "ko": "Knockout!",
    "critical": "Critical!",
    "gotcha": "Gotcha!",
    "jason_never_dies": "Jason... never dies.",
    "freddy_never_dies": "Freddy... never dies.",
    "pinhead_never_dies": "Pinhead... never dies.",
    "ghostface_never_dies": "Ghostface... never dies.",
    "chucky_never_dies": "Chucky... never dies.",
    "michael_never_dies": "Michael... never dies.",
    "leatherface_never_dies": "Leatherface... never dies.",
    "round_2": "Round two!",
    "final_round": "Final round!",
    "you_win": "You win!",
    "you_lose": "You lose!",
    "perfect": "Perfect!",
    "choose_your_killer": "Choose your killer!",
}


def redact(text: str, key: str) -> str:
    """Strip the API key from text that is about to be printed."""
    return text.replace(key, "[redacted]")


def newest_tts_model(key: str) -> str:
    r = requests.get(f"{API}/models", headers={"Authorization": f"Bearer {key}"}, timeout=60)
    r.raise_for_status()
    models = [m for m in r.json()["data"] if "tts" in m["id"] and "4o" in m["id"]]
    return max(models, key=lambda m: m["created"])["id"] if models else "tts-1-hd"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append")
    ap.add_argument("--takes", type=int, default=1, help="generate N takes per line, keep the shortest")
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    try:
        model = newest_tts_model(key)
    except requests.RequestException as e:
        print(f"could not list models: {redact(str(e), key)}", file=sys.stderr)
        return 1
    print(f"model: {model}  voice: {VOICE}")
    raw_dir = ROOT / "art-work/voice"
    out_dir = ROOT / "audio-final/voice"
    raw_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, text in LINES.items():
        if args.only and name not in args.only:
            continue
        best = None
        for take in range(args.takes):
            body = {"model": model, "voice": VOICE, "input": text, "response_format": "wav"}
            if "4o" in model:
                body["instructions"] = INSTRUCTIONS
            r = requests.post(f"{API}/audio/speech", headers={"Authorization": f"Bearer {key}"}, json=body, timeout=120)
            if r.status_code != 200:
                print(f"FAIL {name}: {r.status_code} {redact(r.text[:300], key)}", file=sys.stderr)
                return 1
            raw = raw_dir / f"{name}-{take}.wav"
            raw.write_bytes(r.content)
            # trim leading/trailing silence, add a short arena slap-back, normalize, encode
            filt = ("silenceremove=start_periods=1:start_threshold=-45dB:stop_periods=-1:stop_threshold=-45dB:"
                    "stop_duration=0.15,aecho=0.8:0.6:70:0.22,loudnorm=I=-14:TP=-1.0:LRA=7")
            enc = raw_dir / f"{name}-{take}-final.wav"
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(raw), "-af", filt, "-map_metadata", "-1",
                            "-fflags", "+bitexact", "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le", str(enc)],
                           check=True)
            dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                        str(enc)], capture_output=True, text=True, check=True).stdout)
            if best is None or dur < best[0]:
                best = (dur, enc)
        best[1].replace(out_dir / f"{name}.wav")
        print(f"voice/{name}.wav")
    return 0


if __name__ == "__main__":
    sys.exit(main())
