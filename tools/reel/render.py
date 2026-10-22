"""Scenes to video: frames piped to ffmpeg as raw RGB, their sound cues mixed into one track beside them."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from PIL import Image

W, H, FPS = 1920, 1080, 60
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
RATE = 48000


@dataclass(frozen=True)
class Cue:
    """A sound starting [t] seconds into a scene, at [gain]."""
    t: float
    path: Path
    gain: float = 1.0


class Scene(Protocol):
    duration: float

    def frame(self, t: float) -> Image.Image:
        """The RGB frame W x H at [t] seconds."""

    def cues(self) -> list[Cue]: ...


def mix(cues: list[Cue], duration: float, dest: Path) -> Path:
    """[cues] mixed into a stereo WAV exactly [duration] long, with a limiter so stacked hits do not clip."""
    args = [FFMPEG, "-v", "error", "-y", "-f", "lavfi", "-t", f"{duration:.4f}", "-i", f"anullsrc=r={RATE}:cl=stereo"]
    chains, labels = [], ["[0:a]"]
    for i, c in enumerate(cues, start=1):
        args += ["-i", str(c.path)]
        ms = round(c.t * 1000)
        chains.append(f"[{i}:a]aresample={RATE},aformat=channel_layouts=stereo,volume={c.gain:.3f},adelay={ms}:all=1[c{i}]")
        labels.append(f"[c{i}]")
    graph = ";".join(chains + [f"{''.join(labels)}amix=inputs={len(labels)}:normalize=0:duration=first,"
                               f"alimiter=limit=0.9[out]"])
    subprocess.run(args + ["-filter_complex", graph, "-map", "[out]", "-c:a", "pcm_s16le", str(dest)], check=True)
    return dest


def render(scene: Scene, dest: Path, fps: int = FPS, crf: int = 16) -> Path:
    """[scene] as an H.264 MP4 with its sound, at [fps]."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    frames = round(scene.duration * fps)
    with tempfile.TemporaryDirectory() as tmp:
        audio = mix(scene.cues(), frames / fps, Path(tmp) / "mix.wav")
        enc = subprocess.Popen(
            [FFMPEG, "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(fps),
             "-i", "-", "-i", str(audio), "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "slow",
             "-crf", str(crf), "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
             "-shortest", str(dest)],
            stdin=subprocess.PIPE,
        )
        assert enc.stdin
        try:
            for i in range(frames):
                im = scene.frame(i / fps)
                assert im.size == (W, H) and im.mode == "RGB", (im.size, im.mode)
                enc.stdin.write(im.tobytes())
        finally:
            enc.stdin.close()
            if enc.wait() != 0:
                raise RuntimeError(f"ffmpeg failed on {dest}")
    return dest


def stills(scene: Scene, times: list[float], dest_dir: Path, stem: str) -> list[Path]:
    """Frames of [scene] at [times], as JPEGs, for checking a scene without rendering it."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    out = []
    for t in times:
        p = dest_dir / f"{stem}-{t:05.2f}.jpg"
        scene.frame(t).save(p, quality=88)
        out.append(p)
    return out


def concat(clips: list[Path], dest: Path) -> Path:
    """[clips] (all rendered the same way) back to back in one MP4."""
    with tempfile.TemporaryDirectory() as tmp:
        listing = Path(tmp) / "clips.txt"
        listing.write_text("".join(f"file '{c.resolve()}'\n" for c in clips))
        subprocess.run([FFMPEG, "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing), "-c:v", "copy",
                        "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(dest)], check=True)
    return dest
