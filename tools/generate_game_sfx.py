"""Generate the training mini games' sound effects with ElevenLabs sound generation into audio-final/sfx/ (16-bit PCM
mono WAV, 44.1 kHz): the slot reel's spin and the clunk it stops with, Slash Says' blade swish, the gravestone shuffle's
stone scrape, the catch's squish, the drop's splat and the game-over sting. (The eight dot tones are synthesized, by
tools/synth_audio.py.)

Each is generated --takes times into art-work/sfx/ (gitignored). Like generate_video_sfx.py, a take must not ring (see
tonality: a clunk or a swish that rings sounds like a bell under the game's music) and the loudest take that doesn't wins;
game_over is the exception, a synth wail that is meant to be tonal, so it is simply the loudest take. The kept take has
its leading silence cut, so a sound lands the moment the game cues it, and its peak brought to -1 dBFS (measured on
the mono result: the downloads are stereo and a downmix lowers the peak, and an MP3's peak can overshoot full scale).
--pick name=take keeps a take chosen by ear instead (no key needed when every sound asked for is picked).
The API key is read from the ELEVENLABS_API_KEY environment variable only.
Usage: ELEVENLABS_API_KEY=... tools/.venv/bin/python -I tools/generate_game_sfx.py [--only splat] [--takes 3]
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
from generate_announcer import duration_s, encode, redact  # noqa: E402
from generate_video_sfx import TONAL, generate, level_db, tonality  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DRY = "dry and close, no reverb, no bells, no chimes, no ringing tones, no music, no voices"
SOUNDS = {  # (prompt, seconds)
    "reel_spin": ("An old film projector reel spinning fast: a clattering mechanical whir, the film rattling through the "
                  f"gate at full speed, {DRY}", 2.0),
    "reel_stop": ("A heavy mechanical clunk as a film reel locks into place: one solid metal thunk with a short rattle, "
                  f"{DRY}", 0.6),
    "slash": (f"A quick sharp blade swish through the air: one fast airy whoosh, no impact, {DRY}", 0.4),
    "stone_slide": ("A heavy gravestone scraping across stone: a short, rough, gritty grinding slide, "
                    f"{DRY}", 0.6),
    "squish": (f"A wet cartoon squish of catching something squishy: a short slimy squelch, {DRY}", 0.4),
    "splat": (f"A cartoon gloopy splat on a floor: something wet and heavy landing with a soft slap, {DRY}", 0.6),
    "game_over": ("A short spooky arcade game-over sting: a descending synth wail that sinks and fades, 8-bit game "
                  "console sound effect, no music, no voices", 1.2),
}
TONAL_SOUNDS = {"game_over"}  # meant to ring, so never rejected for it
MIN_SECONDS = 0.5  # the API's shortest sound; a shorter ask is made this long (the kept take starts at its sound)


def sfx_filter(path: Path) -> str:
    """The filter that cuts a take's leading silence, mixes it down to mono and brings that mono peak to -1 dBFS."""
    lead = "silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.004,aformat=channel_layouts=mono"
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-af", lead, "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    peak = float(20 * np.log10(np.abs(np.frombuffer(pcm, np.float32)).max() + 1e-9))
    return f"{lead},volume={-1.0 - peak:.2f}dB"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", choices=list(SOUNDS))
    ap.add_argument("--takes", type=int, default=3)
    ap.add_argument("--pick", action="append", default=[], help="name=take: keep that take, without generating")
    args = ap.parse_args()
    picks = dict(p.split("=") for p in args.pick)
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key and any(n not in picks for n in SOUNDS if not args.only or n in args.only):
        print("ELEVENLABS_API_KEY is not set", file=sys.stderr)
        return 2
    raw_dir, out_dir = ROOT / "art-work/sfx", ROOT / "audio-final/sfx"
    raw_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for name, (text, seconds) in SOUNDS.items():
        if args.only and name not in args.only:
            continue
        tonal = name in TONAL_SOUNDS
        best = raw_dir / f"{name}-{picks[name]}.mp3" if name in picks else None
        takes = []
        for take in range(0 if name in picks else args.takes):
            raw = raw_dir / f"{name}-{take}.mp3"
            try:
                raw.write_bytes(generate(key, text, max(seconds, MIN_SECONDS)))
            except (requests.RequestException, RuntimeError) as e:
                print(f"  {name} take {take}: {redact(str(e), key)}", file=sys.stderr)
                continue
            level, tone = level_db(raw), tonality(raw)
            print(f"  {name} take {take}: {level:.1f} dBFS, tonality {tone:.0f} dB")
            takes.append((not tonal and tone >= TONAL, -level, tone, take, raw))
        if takes:  # the loudest that doesn't ring, or failing that the one that rings least
            ringing, _, _, take, best = min(takes, key=lambda k: (k[0], k[2] if k[0] else k[1]))
            print(f"  {name}: kept take {take}" + (" (every take rings)" if ringing else ""))
        if best is None:
            failed += 1
            continue
        out = out_dir / f"{name}.wav"
        encode(best, out, sfx_filter(best))
        print(f"sfx/{name}.wav  {duration_s(out):.2f}s")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
