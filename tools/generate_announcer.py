"""Generate the fight announcer lines with ElevenLabs into audio-final/voice/ (16-bit PCM mono .wav, 44.1 kHz).

The voice is "David - Sports Arena Announcer" on Eleven v4, pushed to shout: every line carries a [shouting]
tag, capitals and exclamation marks, at the lowest stability (the most expressive delivery). Each line is
generated --takes times; a take only counts if ElevenLabs speech-to-text hears the line's words back (no flubs,
repeats or read-out tags), and the loudest such take wins, since a harder shout comes out hotter before the
loudness normalization below.

WAV rather than Opus-in-Ogg because the app plays the lines through SoundPool, which cannot decode
Opus below Android 10. The download is MP3 (lossless formats need a Pro plan) and is converted here.

The API key is read from the ELEVENLABS_API_KEY environment variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_announcer.py [--only NAME ...] [--takes 3]
"""
from __future__ import annotations

import argparse
import difflib
import os
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
import requests

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.elevenlabs.io/v1"
VOICE_ID = "DduhIyyKkOosbP8VefhP"  # David - Sports Arena Announcer
MODEL = "eleven_v4"
STT_MODEL = "scribe_v2"
OUTPUT_FORMAT = "mp3_44100_192"
VOICE_SETTINGS = {"stability": 0.0, "similarity_boost": 0.75}
MIN_MATCH = 0.8  # transcript similarity a take needs to count

LINES = {
    "jason": "[shouting] JASON!!!",
    "freddy": "[shouting] FREDDY!!!",
    "pinhead": "[shouting] PINHEAD!!!",
    "ghostface": "[shouting] GHOSTFACE!!!",
    "chucky": "[shouting] CHUCKY!!!",
    "michael": "[shouting] MICHAEL!!!",
    "leatherface": "[shouting] LEATHERFACE!!!",
    "versus": "[shouting] VERSUS!!!",
    "fight": "[shouting] FIGHT!!!",
    "ko": "[shouting] KNOCKOUT!!!",
    "critical": "[shouting] CRITICAL!!!",
    "gotcha": "[shouting] GOTCHA!!!",
    "jason_never_dies": "[shouting] JASON... NEVER DIES!!!",
    "freddy_never_dies": "[shouting] FREDDY... NEVER DIES!!!",
    "pinhead_never_dies": "[shouting] PINHEAD... NEVER DIES!!!",
    "ghostface_never_dies": "[shouting] GHOSTFACE... NEVER DIES!!!",
    "chucky_never_dies": "[shouting] CHUCKY... NEVER DIES!!!",
    "michael_never_dies": "[shouting] MICHAEL... NEVER DIES!!!",
    "leatherface_never_dies": "[shouting] LEATHERFACE... NEVER DIES!!!",
    "round_2": "[shouting] ROUND TWO!!!",
    "final_round": "[shouting] FINAL ROUND!!!",
    "you_win": "[shouting] YOU WIN!!!",
    "you_lose": "[shouting] YOU LOSE!!!",
    "perfect": "[shouting] PERFECT!!!",
    "choose_your_killer": "[shouting] CHOOSE YOUR KILLER!!!",
}
NUMBERS = {"2": "two"}

# trim the breath before the shout and the quiet end of the tail (the voice keeps its own arena reverb), add a
# short slap-back, normalize. Tight trims keep "[name]! Versus! [name]!" inside the VS card.
FILTER = ("silenceremove=start_periods=1:start_threshold=-30dB:start_silence=0.03:stop_periods=-1:"
          "stop_threshold=-36dB:stop_duration=0.08:stop_silence=0.05,aecho=0.8:0.6:70:0.22,loudnorm=I=-14:TP=-1.0:LRA=7")


def redact(text: str, key: str) -> str:
    """Strip the API key from text that is about to be printed."""
    return text.replace(key, "[redacted]")


def words(text: str) -> str:
    """Lowercase words only, with [tags] and punctuation removed and digits spelled out."""
    text = re.sub(r"\[[^\]]*\]", " ", text.lower())
    return " ".join(NUMBERS.get(w, w) for w in re.findall(r"[a-z0-9]+", text))


def match(expected: str, heard: str) -> float:
    return difflib.SequenceMatcher(None, words(expected), words(heard)).ratio()


def loudness_db(path: Path) -> float:
    """RMS level of the voiced samples, in dBFS: how hard the take was shouted before normalization."""
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", "44100", "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(pcm, np.int16).astype(np.float32) / 32768
    voiced = x[np.abs(x) > 0.02]
    return float(20 * np.log10(np.sqrt(np.mean(voiced ** 2)))) if voiced.size else -99.0


def duration_s(path: Path) -> float:
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                                capture_output=True, text=True, check=True).stdout)


def encode(src: Path, out: Path) -> None:
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(src), "-af", FILTER, "-map_metadata", "-1",
                    "-fflags", "+bitexact", "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le", str(out)], check=True)


def speak(key: str, text: str) -> bytes:
    r = requests.post(f"{API}/text-to-speech/{VOICE_ID}", params={"output_format": OUTPUT_FORMAT},
                      headers={"xi-api-key": key},
                      json={"text": text, "model_id": MODEL, "voice_settings": VOICE_SETTINGS}, timeout=120)
    if r.status_code != 200:
        raise RuntimeError(f"text-to-speech {r.status_code}: {r.text[:300]}")
    return r.content


def transcribe(key: str, path: Path) -> str:
    with path.open("rb") as f:
        r = requests.post(f"{API}/speech-to-text", headers={"xi-api-key": key}, files={"file": f},
                          data={"model_id": STT_MODEL, "tag_audio_events": "false"}, timeout=120)
    if r.status_code != 200:
        raise RuntimeError(f"speech-to-text {r.status_code}: {r.text[:300]}")
    return r.json().get("text", "")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append")
    ap.add_argument("--takes", type=int, default=3, help="takes per line; the loudest one that reads back right wins")
    args = ap.parse_args()
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    print(f"model: {MODEL}  voice: {VOICE_ID}")
    raw_dir = ROOT / "art-work/voice"
    out_dir = ROOT / "audio-final/voice"
    raw_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for name, text in LINES.items():
        if args.only and name not in args.only:
            continue
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
            print(f"FAIL {name}: no take read back as {words(text)!r}; kept the old line", file=sys.stderr)
            failed += 1
            continue
        out = out_dir / f"{name}.wav"
        encode(best[1], out)
        print(f"voice/{name}.wav  {duration_s(out):.2f}s")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
