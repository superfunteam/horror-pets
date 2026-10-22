"""Generate the launch video's sound effects with ElevenLabs sound generation into video/sfx/ (16-bit PCM WAV):
the CRT's static, the channel change onto the title screen, the bling of pressing start, the projector, and the
fights' beat-'em-up blows (the game's own fight sounds ring like bells under the song).

Each is generated --takes times into art-work/video/sfx/ (gitignored) and the loudest take is kept, normalized; a
blow's take must not ring (see tonality), and has its leading silence cut so it lands on the frame it is cued to.
--pick name=take keeps a take chosen by ear instead.
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
DRY = "dry and close, no reverb, no bells, no chimes, no ringing tones, no music, no voices"
BLOWS = {  # (prompt, seconds): the fights' hits, played on the frame each lands
    "punch": ("A single fast punch landing in a 90s arcade beat 'em up: a tight, meaty thwap of a fist smacking a "
              f"body, a punchy low thud, {DRY}", 0.5),
    "punch_heavy": ("A single heavy hit landing in a 90s arcade fighting game: a deep bassy body thud with a sharp "
                    f"leathery smack on top, like a hard kick to the gut, {DRY}", 0.6),
    "crit": ("A brutal critical hit in a 90s arcade fighting game: a huge bone-crunching smash, a sharp crack and a "
             f"deep booming thud, slightly crunchy and distorted, {DRY}", 0.8),
    "whiff": ("A fast swing missing in an arcade fighting game: a short breathy whoosh of air, like a jacket sleeve "
              f"swung hard past the microphone, airy noise only, no whistle, no impact, {DRY}", 0.5),
    "super": ("A devastating super move landing in a 90s arcade fighting game: a rapid flurry of hard punches ending "
              f"in one massive booming impact, {DRY}", 0.9),
    "knockout": ("The final knockout punch in a 90s arcade fighting game: one huge, loud, crunchy punch impact, a hard "
                 f"smack and a deep booming body thud, {DRY}", 1.0),
    # Each killer's weapon landing, for variety between the punches.
    "slash": ("A fast knife slash landing in a 90s arcade fighting game: a quick swish and a wet, fleshy slice-thwack, "
              f"short and punchy, {DRY}", 0.6),
    "chop": ("A heavy machete chop landing in a 90s arcade fighting game: a short whoosh and a deep, meaty chunk-thud, "
             f"{DRY}", 0.6),
    "claw": ("Razor claws raking across a body in a 90s arcade fighting game: a fast triple scrape-slash with a meaty "
             f"thwack, {DRY}", 0.6),
    "saw": ("A chainsaw biting into a body in a 90s arcade fighting game: a short, angry chainsaw snarl that rips with "
            f"a heavy thud, {DRY}", 0.9),
    "chains": ("Heavy hooked chains lashing a body in a 90s arcade fighting game: a whip-crack of rattling chain and a "
               f"hard smack, {DRY}", 0.8),
    "voodoo": ("A dark voodoo curse blasting a body in a 90s arcade fighting game: a crackling magic zap and a deep, "
               f"punchy whomp, {DRY}", 0.8),
    "stabs": ("A rapid flurry of knife stabs landing in a 90s arcade fighting game: four quick, wet stabbing thwacks in "
              f"a row, {DRY}", 0.8),
}
SOUNDS |= BLOWS
FILTER = "loudnorm=I=-16:TP=-1.0:LRA=7"
TONAL = 30.0  # a blow whose spectrum peaks this far (dB) over its median rings, like a bell


def level_db(path: Path) -> float:
    """RMS level of the whole take, in dBFS (static and crackle are too soft for the announcer's voiced level)."""
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(pcm, np.float32)
    return float(20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-9))


def tonality(path: Path) -> float:
    """How tonal the take is: its spectrum's strongest peak over its median, in dB. A thud is broadband (low); a bell
    or a chime is a few pure tones (high)."""
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", "22050", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(pcm, np.float32)
    spectrum = np.abs(np.fft.rfft(x * np.hanning(len(x)))) + 1e-12
    band = spectrum[len(spectrum) // 40:]  # above ~275 Hz, where rings and dings live (a thud's body is below)
    return float(20 * np.log10(band.max() / np.median(band)))


def blow_filter(path: Path) -> str:
    """A blow's filter: its leading silence cut, so it lands on its cue, and its peak brought to -1 dBFS."""
    out = subprocess.run(["ffmpeg", "-loglevel", "info", "-i", str(path), "-af", "volumedetect", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    peak = float(out.split("max_volume:")[1].split("dB")[0])
    return f"silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.004,volume={-1.0 - peak:.2f}dB"


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
    ap.add_argument("--pick", action="append", default=[], help="name=take: keep that take, without generating")
    args = ap.parse_args()
    picks = dict(p.split("=") for p in args.pick)
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
        if name in picks:
            best = (0.0, raw_dir / f"{name}-{picks[name]}.mp3")
        takes = []
        for take in range(0 if name in picks else args.takes):
            raw = raw_dir / f"{name}-{take}.mp3"
            try:
                raw.write_bytes(generate(key, text, seconds))
            except (requests.RequestException, RuntimeError) as e:
                print(f"  {name} take {take}: {redact(str(e), key)}", file=sys.stderr)
                continue
            level = level_db(raw)
            tone = tonality(raw) if name in BLOWS else 0.0
            print(f"  {name} take {take}: {level:.1f} dBFS" + (f", tonality {tone:.0f} dB" if name in BLOWS else ""))
            takes.append((tone >= TONAL, -level, tone, raw))
        if takes:  # the loudest that doesn't ring, or failing that the one that rings least
            ringing, _, tone, raw = min(takes, key=lambda k: (k[0], k[2] if k[0] else k[1]))
            best = (0.0, raw)
        if best is None:
            failed += 1
            continue
        out = out_dir / f"{name}.wav"
        encode(best[1], out, blow_filter(best[1]) if name in BLOWS else FILTER)
        print(f"video/sfx/{name}.wav  {duration_s(out):.2f}s")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
