"""Scenes to video: frames piped to ffmpeg as raw RGB, their sound cues mixed into one track beside them."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable, Protocol

from PIL import Image

W, H, FPS = 1920, 1080, 60
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
RATE = 48000


@dataclass(frozen=True)
class Cue:
    """A sound starting [t] seconds into a scene, at [gain]: from [skip] seconds into the file, for at most [length]
    seconds, sped up by [tempo] (pitch kept), faded in and out. A [music] cue ducks under the others."""
    t: float
    path: Path
    gain: float = 1.0
    skip: float = 0.0
    length: float | None = None
    tempo: float = 1.0
    fade_in: float = 0.0
    fade_out: float = 0.0
    music: bool = False


class Scene(Protocol):
    duration: float

    def frame(self, t: float) -> Image.Image:
        """The RGB frame W x H at [t] seconds."""

    def cues(self) -> list[Cue]: ...


def prepare(scene) -> None:
    """Runs [scene]'s one-time work (cutting footage, rendering 3D phones), if it has any, before frames are drawn."""
    if hasattr(scene, "prepare"):
        scene.prepare()


@dataclass
class Sequence:
    """Scenes back to back, each starting where the one before it ends, plus [extra] cues over the whole run."""
    scenes: list
    extra: tuple[Cue, ...] = ()

    def __post_init__(self) -> None:
        self.starts, t = [], 0.0
        for s in self.scenes:
            self.starts.append(t)
            t += s.duration
        self.duration = t

    def frame(self, t: float) -> Image.Image:
        i = max(i for i, s in enumerate(self.starts) if s <= t + 1e-9)
        return self.scenes[i].frame(max(0.0, t - self.starts[i]))  # never a hair before a scene's start

    def cues(self) -> list[Cue]:
        return [replace(c, t=c.t + s) for s, scene in zip(self.starts, self.scenes) for c in scene.cues()] + list(self.extra)

    def prepare(self) -> None:
        for scene in self.scenes:
            prepare(scene)


def _chain(i: int, c: Cue) -> str:
    """The filters that place cue [c] (ffmpeg input [i]) on the timeline."""
    f = [f"aresample={RATE}", "aformat=channel_layouts=stereo"]
    if c.skip or c.length:
        f.append(f"atrim=start={c.skip:.4f}" + (f":duration={c.length * c.tempo:.4f}" if c.length else ""))
        f.append("asetpts=PTS-STARTPTS")
    if c.tempo != 1.0:
        f.append(f"atempo={c.tempo:.4f}")
    if c.fade_in:
        f.append(f"afade=t=in:d={c.fade_in:.3f}")
    if c.fade_out and c.length:
        f.append(f"afade=t=out:st={c.length - c.fade_out:.4f}:d={c.fade_out:.3f}")
    f += [f"volume={c.gain:.3f}", f"adelay={round(c.t * 1000)}:all=1"]
    return f"[{i}:a]{','.join(f)}"


def mix(cues: list[Cue], duration: float, dest: Path) -> Path:
    """[cues] mixed into a stereo WAV exactly [duration] long: the music ducked under everything else (the announcer),
    and a limiter so stacked hits do not clip."""
    args = [FFMPEG, "-v", "error", "-y", "-f", "lavfi", "-t", f"{duration:.4f}", "-i", f"anullsrc=r={RATE}:cl=stereo"]
    chains, music, rest = [], [], []
    for i, c in enumerate(cues, start=1):
        args += ["-i", str(c.path)]
        chains.append(f"{_chain(i, c)}[c{i}]")
        (music if c.music else rest).append(f"[c{i}]")

    def bus(base: str, labels: list[str], name: str) -> str:
        # Mixed over the silent bed, so each bus is exactly [duration] long.
        return f"{base}{''.join(labels)}amix=inputs={len(labels) + 1}:normalize=0:duration=first[{name}]"

    if music and rest:
        chains += ["[0:a]asplit[bm][bv]", bus("[bm]", music, "m"), bus("[bv]", rest, "v"), "[v]asplit[vk][vo]",
                   "[m][vk]sidechaincompress=threshold=0.1:ratio=3:attack=8:release=200[md]",
                   "[md][vo]amix=inputs=2:normalize=0,alimiter=limit=0.8:level=disabled[out]"]
    else:
        chains += [bus("[0:a]", music + rest, "a"), "[a]alimiter=limit=0.8:level=disabled[out]"]
    subprocess.run(args + ["-filter_complex", ";".join(chains), "-map", "[out]", "-c:a", "pcm_s16le", str(dest)], check=True)
    return dest


def _encode(make: Callable[[], Scene], first: int, end: int, dest: Path, fps: int, crf: int) -> Path:
    """Frames [first, end) of the scene [make] builds, encoded as silent H.264 to [dest]."""
    scene = make()
    enc = subprocess.Popen(
        [FFMPEG, "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(fps), "-i", "-",
         "-c:v", "libx264", "-preset", "slow", "-crf", str(crf), "-pix_fmt", "yuv420p", str(dest)],
        stdin=subprocess.PIPE,
    )
    assert enc.stdin
    try:
        for i in range(first, end):
            im = scene.frame(i / fps)
            assert im.size == (W, H) and im.mode == "RGB", (im.size, im.mode)
            enc.stdin.write(im.tobytes())
    finally:
        enc.stdin.close()
        if enc.wait() != 0:
            raise RuntimeError(f"ffmpeg failed on {dest}")
    return dest


def render(make: Callable[[], Scene], dest: Path, fps: int = FPS, crf: int = 16, workers: int = 8) -> Path:
    """The scene [make] builds as an H.264 MP4 with its sound, at [fps]. Its frames are drawn in [workers] processes,
    each building its own copy of the scene (so [make] must be a top-level function) and encoding one stretch."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    scene = make()
    prepare(scene)
    frames = round(scene.duration * fps)
    with tempfile.TemporaryDirectory() as tmp:
        audio = mix(scene.cues(), frames / fps, Path(tmp) / "mix.wav")
        bounds = [frames * i // workers for i in range(workers + 1)]
        parts = [Path(tmp) / f"part{i}.mp4" for i in range(workers)]
        with ProcessPoolExecutor(workers) as pool:
            list(pool.map(_encode, [make] * workers, bounds[:-1], bounds[1:], parts, [fps] * workers, [crf] * workers))
        listing = Path(tmp) / "parts.txt"
        listing.write_text("".join(f"file '{p}'\n" for p in parts))
        subprocess.run([FFMPEG, "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing), "-i", str(audio),
                        "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
                        "-movflags", "+faststart", str(dest)], check=True)
    return dest


def stills(scene: Scene, times: list[float], dest_dir: Path, stem: str) -> list[Path]:
    """Frames of [scene] at [times], as JPEGs, for checking a scene without rendering it."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    prepare(scene)
    out = []
    for t in times:
        p = dest_dir / f"{stem}-{t:05.2f}.jpg"
        scene.frame(t).save(p, quality=88)
        out.append(p)
    return out
