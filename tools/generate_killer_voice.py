"""Generate the killers' own voices with ElevenLabs into audio-final/voice/ (16-bit PCM mono .wav, 44.1 kHz).

Chucky is ElevenLabs' "Angry Kid" on Eleven v4, in character and foul-mouthed: his lines at Home (idle, FEED, PLAY) and
in a fight (attack, heavy, hit taken, block, special, win, lose, miss) are the design's, verbatim. The voice is found by
its name in the account's voice list at every run (its id is printed, never stored). Unlike the announcer, a killer is
in the room, not on the PA: no [shouting] tag, a looser stability for a snottier delivery, and no slap-back echo.

The same pipeline as tools/generate_announcer.py: each line is generated --takes times, a take only counts if
ElevenLabs speech-to-text hears the line's words back (no flubs, repeats or read-out tags), and the loudest such take
wins. The download is MP3 and is converted to WAV here, since the app plays the lines through SoundPool.

The API key is read from the ELEVENLABS_API_KEY environment variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_killer_voice.py [--only NAME ...] [--takes 3]
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_announcer import (  # noqa: E402
    API, MIN_MATCH, MODEL, duration_s, encode, loudness_db, match, redact, speak, transcribe, words,
)

ROOT = Path(__file__).resolve().parents[1]
VOICE_SETTINGS = {"stability": 0.35, "similarity_boost": 0.8}
# Trim the breath before the line and bring it to -16 LUFS, a little under the announcer, who stays the loudest voice.
# No echo: the killer is in the room.
FILTER = ("silenceremove=start_periods=1:start_threshold=-35dB:start_silence=0.03,"
          "loudnorm=I=-16:TP=-1.0:LRA=7")

VOICES = {  # killer -> (the voice's name in ElevenLabs' library, {file name: line})
    "chucky": ("Angry Kid", {
        "chucky_idle_1": "Hi, I'm Chucky. Wanna play?",
        "chucky_idle_2": "What the fuck are you lookin' at?",
        "chucky_idle_3": "Hey. HEY. I'm bored over here.",
        "chucky_feed_1": "About fuckin' time.",
        "chucky_feed_2": "You call this food, bitch?",
        "chucky_play_1": "Quit it, asshole!",
        "chucky_play_2": "Ha! Do that again!",
        "chucky_attack_1": "Eat this!",
        "chucky_attack_2": "Say hi to the knife!",
        "chucky_heavy": "Chucky's gonna play!",
        "chucky_hurt_1": "Son of a bitch!",
        "chucky_hurt_2": "That all you got?",
        "chucky_block": "Nice try, bitch.",
        "chucky_special": "Friends till the end... bitch!",
        "chucky_win": "Ha ha ha! Nobody fucks with the Chuck.",
        "chucky_lose": "This isn't over, bitch!",
        "chucky_miss": "Hold still, dammit!",
    }),
}


def find_voice(key: str, name: str) -> str:
    """The id of the voice called exactly `name` among the account's voices."""
    r = requests.get(f"{API}/voices", headers={"xi-api-key": key}, timeout=60)
    if r.status_code != 200:
        raise RuntimeError(f"voices {r.status_code}: {r.text[:300]}")
    ids = [v["voice_id"] for v in r.json().get("voices", []) if v.get("name") == name]
    if not ids:
        raise RuntimeError(f"no voice named {name!r} in the account; add it from the Voice Library first")
    return ids[0]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", metavar="NAME", help="a line to generate, e.g. chucky_idle_1 (repeatable)")
    ap.add_argument("--takes", type=int, default=3, help="takes per line; the loudest one that reads back right wins")
    args = ap.parse_args()
    unknown = set(args.only or []) - {name for _, lines in VOICES.values() for name in lines}
    if unknown:
        ap.error(f"no such line: {', '.join(sorted(unknown))}")  # a typo should not be a quiet no-op
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    raw_dir = ROOT / "art-work/voice"
    out_dir = ROOT / "audio-final/voice"
    raw_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for killer, (voice_name, lines) in VOICES.items():
        todo = {name: text for name, text in lines.items() if not args.only or name in args.only}
        if not todo:
            continue
        try:
            voice_id = find_voice(key, voice_name)
        except (requests.RequestException, RuntimeError) as e:
            print(f"FAIL {killer}: {redact(str(e), key)}", file=sys.stderr)
            failed += len(todo)
            continue
        print(f"model: {MODEL}  {killer}: {voice_name} ({voice_id})")
        for name, text in todo.items():
            best = None
            for take in range(args.takes):
                raw = raw_dir / f"{name}-{take}.mp3"
                try:
                    raw.write_bytes(speak(key, text, voice_id, VOICE_SETTINGS))
                    heard = transcribe(key, raw)
                except (requests.RequestException, RuntimeError) as e:
                    print(f"  {name} take {take}: {redact(str(e), key)}", file=sys.stderr)
                    continue
                score, level = match(text, heard), loudness_db(raw)
                print(f"  {name} take {take}: heard {heard!r} (match {score:.2f}), {level:.1f} dBFS", flush=True)
                if score >= MIN_MATCH and (best is None or level > best[0]):
                    best = (level, raw)
            if best is None:
                print(f"FAIL {name}: no take read back as {words(text)!r}; kept the old line", file=sys.stderr)
                failed += 1
                continue
            out = out_dir / f"{name}.wav"
            encode(best[1], out, FILTER)
            print(f"voice/{name}.wav  {duration_s(out):.2f}s", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
