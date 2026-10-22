"""The opening: a dark living room on a stormy Halloween night, an old CRT TV glowing with the game.

The room fades up out of the dark with lightning in the window. The TV clicks on (a white line, a moment of snow) to
the seven killers, through scanlines, bloom, color fringes, flicker and a rolling bar. The camera creeps toward it,
then rushes into the screen, and the CRT look falls away as it does: by the time the picture fills the frame it is
crystal clear, ready to cut to the first killer on the drop.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image, ImageChops, ImageFilter

from . import cast, fx
from .motion import in_cubic, in_out_sine, lerp, out_cubic, pulse, ramp
from .render import FPS, H, W, Cue

ROOM = cast.ROOT / "video/art/room-crt.webp"
MASK = cast.ROOT / "video/art/room-crt-screen.png"
ART = cast.ROOT / "sprites-final/title/cover.webp"
LIGHTNING = (200, 210, 255)
ON, SNOW = 0.8, 1.05  # when the TV clicks on, and when the snow gives way to the picture


class CrtIntro:
    """[duration] long, with lightning on the [bolts] (seconds into it)."""

    def __init__(self, duration: float, bolts: tuple[float, ...]):
        self.duration, self.bolts = duration, bolts
        self.room = Image.open(ROOM).convert("RGB")
        self.mask = Image.open(MASK).convert("L").filter(ImageFilter.GaussianBlur(1.5))
        x0, y0, x1, y1 = self.mask.getbbox()
        self.tube = (x0, y0, x1, y1)
        # The picture is 16:9, letterboxed in the 4:3 tube; the push ends with it filling the frame exactly.
        pw = x1 - x0
        ph = pw * 9 / 16
        cy = (y0 + y1) / 2
        self.pic = (x0, cy - ph / 2, x1, cy + ph / 2)
        self.base = W / self.room.width
        self.z_full = W / (pw * self.base)
        self.art = Image.open(ART).convert("RGB")
        rng = random.Random(3)
        self.snow = [Image.fromarray(np.array([[rng.randrange(256) for _ in range(160)] for _ in range(90)], np.uint8), "L")
                     for _ in range(4)]

    def zoom(self, t: float) -> float:
        z = 1 + 0.04 * ramp(t, 0, 1.6)
        z += 1.2 * ramp(t, 1.6, self.duration - 1.6, in_out_sine)
        return z + (self.z_full - 2.24) * ramp(t, self.duration - 1.6, self.duration - 0.2, in_cubic)

    def clarity(self, z: float) -> float:
        """How far the CRT look has fallen away: none in the room, all of it once the picture fills the frame."""
        return ramp(z, 2.3, self.z_full * 0.95, out_cubic)

    def view(self, z: float) -> tuple[float, float, float, float]:
        """The part of the room the camera sees at zoom [z], moving from the room's middle to the picture's."""
        s = self.base * z
        vw, vh = W / s, H / s
        u = (z - 1) / (self.z_full - 1)
        cx = lerp(self.room.width / 2, (self.pic[0] + self.pic[2]) / 2, min(1, u * 1.6))
        cy = lerp(self.room.height / 2, (self.pic[1] + self.pic[3]) / 2, min(1, u * 1.6))
        cx = min(max(cx, vw / 2), self.room.width - vw / 2)
        cy = min(max(cy, vh / 2), self.room.height - vh / 2)
        return cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2

    def picture(self, t: float, w: int, h: int, crt: float) -> Image.Image:
        """What the TV shows, [w] x [h], with [crt] (0..1) of the old tube's look on it."""
        if t < SNOW:
            snow = self.snow[round(t * 30) % len(self.snow)].resize((w, h), Image.NEAREST)
            im = Image.merge("RGB", (snow, snow, snow))
            on = ramp(t, ON, ON + 0.12, out_cubic)  # the white line opening up into the picture
            band = Image.new("L", (w, h), 0)
            hb = max(2, round(h * on))
            band.paste(255, (0, (h - hb) // 2, w, (h + hb) // 2))
            lit = Image.blend(Image.new("RGB", (w, h), (255, 255, 255)), im, on)
            return Image.composite(lit, Image.new("RGB", (w, h)), band)
        im = fx.cover(self.art, w, h)
        if crt <= 0.02:
            return im
        a = np.asarray(im, np.float32)
        rng = random.Random(round(t * FPS))
        a *= 1 - crt * (0.06 * rng.random())  # flicker
        roll = (t * 0.45) % 1.3 - 0.15  # a brighter band rolling down
        rows = np.arange(h, dtype=np.float32) / h
        a *= (1 + crt * 0.14 * np.exp(-((rows - roll) / 0.06) ** 2))[:, None, None]
        period = max(2.0, h / 150)
        lines = 1 - crt * 0.5 * (np.sin(np.arange(h) * 2 * math.pi / period) > 0.3)
        a *= lines[:, None, None].astype(np.float32)
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2))
        a *= (1 - crt * 0.45 * np.clip(r - 0.55, 0, 1))[:, :, None]
        im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB")
        im = fx.chroma(im, max(1, w / 300) * crt)
        glow = im.filter(ImageFilter.GaussianBlur(max(1.5, w / 90)))
        return ImageChops.add(im, Image.blend(Image.new("RGB", im.size), glow, 0.4 * crt))

    def frame(self, t: float) -> Image.Image:
        z = self.zoom(t)
        box = self.view(z)
        s = W / (box[2] - box[0])
        out = self.room.resize((W, H), Image.BILINEAR, box=box)
        tube = self.mask.resize((W, H), Image.BILINEAR, box=box)
        crt = 1 - self.clarity(z)
        if t >= ON:
            px0, py0 = (self.pic[0] - box[0]) * s, (self.pic[1] - box[1]) * s
            pw, ph = (self.pic[2] - self.pic[0]) * s, (self.pic[3] - self.pic[1]) * s
            pic = self.picture(t, max(2, round(pw)), max(2, round(ph)), crt)
            screen = Image.new("RGB", (W, H))
            screen.paste(pic, (round(px0), round(py0)))
            # The tube's own glow: the dark glass around a letterboxed picture lifts a little.
            screen = ImageChops.add(screen, Image.new("RGB", (W, H), (14, 16, 22)))
            out.paste(screen, (0, 0), tube)
            # Light from the screen spills onto the room, flickering with it.
            spill = 0.18 * crt * (0.85 + 0.15 * random.Random(round(t * FPS) + 9).random())
            out = Image.blend(out, ImageChops.screen(out, tube.filter(ImageFilter.GaussianBlur(60)).convert("RGB")), spill)
        bolt = max((pulse(t, b, 0.18) + 0.6 * pulse(t, b + 0.12, 0.2) for b in self.bolts), default=0.0)
        out = fx.flash(out, LIGHTNING, 0.35 * min(1.0, bolt) * (1 - ramp(z, 1.6, 3.0)))
        out = fx.flash(out, (0, 0, 0), 1 - ramp(t, 0, 1.2, in_out_sine))
        return fx.flash(out, (255, 255, 255), 0.6 * ramp(t, self.duration - 0.1, self.duration))

    def cues(self) -> list[Cue]:
        return []
