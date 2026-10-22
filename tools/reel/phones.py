"""Real 3D phones in scenes, rendered by tools/phones3d (three.js in headless Chrome) with gameplay on their screens.

A scene says, for each of its frames, where its phones are and what their screens show (a Shot). Each Shot renders to
its own transparent PNG under art-work/video/cache/phones/, named by its content, so a frame is only ever rendered
once: render_all() draws every missing one in a single Chrome session, and frames are then just loaded.

Coordinates are the renderer's (three.js): roughly centimeters, +X right, +Y up, +Z toward the camera; a phone is
16 x 7.5 x 0.96, landscape, its screen facing +Z. See tools/phones3d/render.mjs.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path

from PIL import Image

from .cast import ROOT
from .render import H, W

CACHE = ROOT / "art-work/video/cache/phones"
NODE = shutil.which("node") or str(Path.home() / ".nvm/versions/node/v22.22.2/bin/node")
SCRIPT = ROOT / "tools/phones3d/render.mjs"


@dataclass(frozen=True)
class Phone:
    position: tuple[float, float, float]
    rotation: tuple[float, float, float] = (0.0, 0.0, 0.0)  # XYZ Euler, radians
    screen: str | None = None  # absolute path of the image on its screen
    glow: float = 1.0
    scale: float = 1.0


@dataclass(frozen=True)
class Shot:
    phones: tuple[Phone, ...]
    camera: tuple[float, float, float] = (0.0, 0.0, 60.0)
    look: tuple[float, float, float] = (0.0, 0.0, 0.0)
    fov: float = 30.0
    rim: tuple[int, int, int] = (180, 40, 110)

    @property
    def path(self) -> Path:
        key = hashlib.sha1(json.dumps(asdict(self), sort_keys=True).encode()).hexdigest()[:20]
        return CACHE / f"{key}.png"

    def spec(self) -> dict:
        return {
            "out": str(self.path),
            "camera": {"fov": self.fov, "position": list(self.camera), "lookAt": list(self.look)},
            "rim": list(self.rim),
            "phones": [{"position": list(p.position), "rotation": list(p.rotation), "scale": p.scale,
                        "screen": p.screen, "glow": p.glow} for p in self.phones],
        }


def render_all(shots: list[Shot]) -> None:
    """Renders every shot not already cached, in one run of the phones3d renderer."""
    todo = list({s.path: s for s in shots if not s.path.exists()}.values())
    if not todo:
        return
    CACHE.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump({"width": W, "height": H, "frames": [s.spec() for s in todo]}, f)
    try:
        env = dict(os.environ, PATH=f"{Path(NODE).parent}:{os.environ.get('PATH', '')}")
        subprocess.run([NODE, str(SCRIPT), f.name], check=True, env=env)
    finally:
        os.unlink(f.name)


def load(shot: Shot) -> Image.Image:
    return Image.open(shot.path).convert("RGBA")
