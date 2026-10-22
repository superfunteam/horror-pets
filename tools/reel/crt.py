"""The opening: an old CRT TV in a dark living room, starting the game.

It fades up close on the tube showing faint static. A channel change crackles onto the game's title screen (recorded
off the TV emulator, logo and all), seen through scanlines, bloom, color fringes, flicker and a rolling bar; a beat
later the camera rushes into the screen and the CRT look falls away until the title fills the frame, crystal clear.
On the next bar PRESS START flashes with a bling, and as the announcer shouts the game's name the camera pushes into
the logo, punching on TURBO, until it flashes over into whatever comes next.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image, ImageChops, ImageFilter

from . import cast, fx
from .footage import Clip
from .motion import in_cubic, in_out_sine, lerp, out_cubic, pulse, ramp
from .render import FPS, H, W, Cue
from .title import SHOUT, SHOUT_FIRST, SHOUT_TURBO

ROOM = cast.ROOT / "video/art/room-crt.webp"
MASK = cast.ROOT / "video/art/room-crt-screen.png"
SFX = cast.ROOT / "video/sfx"
TITLE = Clip("tv-title.mp4", 3.0, 9.0)  # the title screen, PRESS START blinking, before it was pressed
PRESS_BOX = (0.36, 0.76, 0.64, 0.86)  # where PRESS START sits on the title screen, as fractions of it
LOGO_AT = (0.5, 0.3)  # the logo's middle, as a fraction of the title screen
Z0 = 1.55  # how close the camera starts to the TV
SHOUT_DELAY = 0.12  # the shout's first word lands this long after start is pressed


class CrtIntro:
    """[duration] long: the channel changes at [channel], the camera rushes in over [rush], start is pressed at
    [press] and the name is shouted from it."""

    def __init__(self, duration: float, channel: float, rush: tuple[float, float], press: float):
        self.duration, self.channel, self.rush, self.press = duration, channel, rush, press
        self.turbo = press + SHOUT_DELAY + SHOUT_TURBO - SHOUT_FIRST
        self.room = Image.open(ROOM).convert("RGB")
        self.mask = Image.open(MASK).convert("L").filter(ImageFilter.GaussianBlur(1.5))
        x0, y0, x1, y1 = self.mask.getbbox()
        # The picture is 16:9, letterboxed in the 4:3 tube; the rush ends with it filling the frame exactly.
        pw = x1 - x0
        cy = (y0 + y1) / 2
        self.pic = (x0, cy - pw * 9 / 32, x1, cy + pw * 9 / 32)
        self.base = W / self.room.width
        self.z_full = W / (pw * self.base)
        rng = random.Random(3)
        self.snow = [Image.fromarray(np.array([[rng.randrange(256) for _ in range(160)] for _ in range(90)], np.uint8), "L")
                     for _ in range(6)]

    def prepare(self) -> None:
        TITLE.extract()

    def zoom(self, t: float) -> float:
        z = lerp(Z0, Z0 + 0.12, ramp(t, 0, self.rush[0], in_out_sine))
        return lerp(z, self.z_full, ramp(t, *self.rush, in_cubic))

    def view(self, z: float) -> tuple[float, float, float, float]:
        """The part of the room the camera sees at zoom [z], closing on the picture as it zooms."""
        s = self.base * z
        vw, vh = W / s, H / s
        u = min(1.0, (z - 1) / (self.z_full - 1) * 1.6)
        cx = lerp(self.room.width / 2, (self.pic[0] + self.pic[2]) / 2, u)
        cy = lerp(self.room.height / 2, (self.pic[1] + self.pic[3]) / 2, u)
        cx = min(max(cx, vw / 2), self.room.width - vw / 2)
        cy = min(max(cy, vh / 2), self.room.height - vh / 2)
        return cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2

    def static(self, t: float, w: int, h: int, level: float) -> Image.Image:
        """Faint snow: dark, low contrast, [level] (0..1) of full strength."""
        snow = self.snow[round(t * 30) % len(self.snow)].resize((w, h), Image.NEAREST)
        grey = Image.merge("RGB", (snow, snow, snow))
        return Image.blend(Image.new("RGB", (w, h), (12, 14, 18)), grey, level)

    def title_screen(self, t: float, w: int, h: int) -> Image.Image:
        """The title screen at [t], [w] x [h]: from the press on, pushing in on the logo, with PRESS START lit."""
        im = TITLE.frame(t - self.channel)
        if t >= self.press:
            z = 1 + 0.16 * ramp(t, self.press, self.duration, out_cubic) + 0.05 * pulse(t, self.turbo, 0.3)
            z *= 1 + 0.04 * pulse(t, self.press, 0.25)
            cw, ch = im.width / z, im.height / z
            cx = lerp(im.width / 2, LOGO_AT[0] * im.width, ramp(t, self.press, self.duration))
            cy = lerp(im.height / 2, LOGO_AT[1] * im.height, ramp(t, self.press, self.duration))
            cy = min(max(cy, ch / 2), im.height - ch / 2)
            im = im.resize(im.size, Image.BILINEAR, box=(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2))
        im = im.resize((w, h), Image.BILINEAR) if (w, h) != im.size else im
        lit = sum(pulse(t, self.press + k * 0.12, 0.1) for k in range(3))
        if lit > 0.01:  # PRESS START blinks bright as it is pressed
            x0, y0, x1, y1 = (round(v) for v in (PRESS_BOX[0] * w, PRESS_BOX[1] * h, PRESS_BOX[2] * w, PRESS_BOX[3] * h))
            glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            glow.paste((255, 240, 200, round(170 * min(1.0, lit))), (x0, y0, x1, y1))
            im = Image.alpha_composite(im.convert("RGBA"), glow.filter(ImageFilter.GaussianBlur(h / 60))).convert("RGB")
        return im

    def picture(self, t: float, w: int, h: int, crt: float) -> Image.Image:
        """What the TV shows, [w] x [h], with [crt] (0..1) of the old tube's look on it."""
        if t < self.channel:
            im = self.static(t, w, h, 0.22)
        else:
            im = self.title_screen(t, w, h)
            burst = ramp(t, self.channel, self.channel + 0.14)
            if burst < 1:  # the channel change: a bright crackle of snow over the new picture
                im = Image.blend(self.static(t, w, h, 1.0), im, burst)
        if crt <= 0.02:
            return im
        a = np.asarray(im, np.float32)
        rng = random.Random(round(t * FPS))
        a *= 1 - crt * (0.06 * rng.random())  # flicker
        roll = (t * 0.45) % 1.3 - 0.15  # a brighter band rolling down
        rows = np.arange(h, dtype=np.float32) / h
        a *= (1 + crt * 0.14 * np.exp(-((rows - roll) / 0.06) ** 2))[:, None, None]
        period = max(2.0, h / 150)
        a *= (1 - crt * 0.5 * (np.sin(np.arange(h) * 2 * math.pi / period) > 0.3))[:, None, None].astype(np.float32)
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2))
        a *= (1 - crt * 0.45 * np.clip(r - 0.55, 0, 1))[:, :, None]
        im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB")
        im = fx.chroma(im, max(1, w / 300) * crt)
        glow = im.filter(ImageFilter.GaussianBlur(max(1.5, w / 90)))
        return ImageChops.add(im, Image.blend(Image.new("RGB", im.size), glow, 0.4 * crt))

    def frame(self, t: float) -> Image.Image:
        z = self.zoom(t)
        crt = 1 - ramp(t, self.rush[0], self.rush[1] - 0.05, out_cubic)
        if z >= self.z_full - 1e-6:  # the picture fills the frame: no room left to draw
            out = self.picture(t, W, H, crt)
        else:
            box = self.view(z)
            s = W / (box[2] - box[0])
            out = self.room.resize((W, H), Image.BILINEAR, box=box)
            tube = self.mask.resize((W, H), Image.BILINEAR, box=box)
            px0, py0 = (self.pic[0] - box[0]) * s, (self.pic[1] - box[1]) * s
            pw, ph = (self.pic[2] - self.pic[0]) * s, (self.pic[3] - self.pic[1]) * s
            pic = self.picture(t, max(2, round(pw)), max(2, round(ph)), crt)
            screen = Image.new("RGB", (W, H))
            screen.paste(pic, (round(px0), round(py0)))
            screen = ImageChops.add(screen, Image.new("RGB", (W, H), (14, 16, 22)))  # the tube's own dark glow
            out.paste(screen, (0, 0), tube)
            spill = 0.16 * crt * (0.85 + 0.15 * random.Random(round(t * FPS) + 9).random())
            out = Image.blend(out, ImageChops.screen(out, tube.filter(ImageFilter.GaussianBlur(60)).convert("RGB")), spill)
        out = fx.flash(out, (0, 0, 0), 1 - ramp(t, 0, 0.5, in_out_sine))
        flash = max(0.7 * pulse(t, self.press, 0.18), 0.45 * pulse(t, self.turbo, 0.16),
                    0.8 * ramp(t, self.duration - 0.15, self.duration))
        return fx.flash(out, (255, 255, 255), flash)

    def cues(self) -> list[Cue]:
        return [
            Cue(0, SFX / "static.wav", 0.12, length=self.channel, fade_in=0.4, fade_out=0.1),
            Cue(self.channel - 0.04, SFX / "channel.wav", 0.8),
            Cue(self.press, SFX / "start.wav", 0.9),
            Cue(self.press + SHOUT_DELAY - SHOUT_FIRST, SHOUT, 1.2),
        ]
