"""A killer's intro cut, in the spirit of Super Street Fighter II Turbo: a few beats of the music, just them and their name.

It starts on a blast in the music with a white flash and the camera punching in on their stage. The killer flies in
from their side (Killer.entry) with afterimages and speed lines and lands with a shake, then their name slams down out
of the camera on the next beat, with a flash, focus lines and an RGB split, as their own line plays. They hold, with
embers drifting and a glint across the name, until the camera rushes in for the cut to whatever comes next.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

from . import cast, fx
from .motion import in_cubic, jitter, lerp, out_back, out_expo, pulse, ramp
from .render import FPS, H, W, Cue

CHAR_IN = (0.0, 0.24)
NAME_SLAM = 0.14  # how long the name takes to come down, landing on the beat
OUT = 0.12  # the rush into the next cut, at the very end

CHAR_AT, CHAR_BOX = (W * 0.715, H * 0.55), (1000, 1010)
NAME_AT, NAME_BOX = (W * 0.28, H * 0.45), (900, 360)
ZOOM_MAX = 1.3  # the stage is prepared at this zoom, the most the camera pushes in
WHITE = (255, 255, 255)

# Where each entry starts relative to where the killer lands: offset, scale and tilt (degrees counterclockwise).
ENTRIES = {
    "right": (1300, 0, 1.0, 12),
    "left": (-1900, 0, 1.0, -12),
    "top": (0, -1250, 1.15, 0),
    "bottom": (0, 1150, 1.0, 0),
    "depth": (0, 0, 0.12, -30),
}
GHOSTS = [(0.018, 0.5), (0.036, 0.34), (0.054, 0.22), (0.072, 0.12)]  # (how far back in time, opacity)


class CharacterIntro:
    """[killer]'s cut, [duration] long, with the name landing [beat] seconds in."""

    def __init__(self, killer: cast.Killer, duration: float, beat: float, seed: int = 13):
        self.k, self.duration, self.beat = killer, duration, beat
        self.name_in = (beat - NAME_SLAM, beat)
        self.glint = (2 * beat, 2 * beat + 0.4)
        self.accent = cast.accent(killer)

        bg = fx.cover(cast.backdrop(killer), round(W * ZOOM_MAX), round(H * ZOOM_MAX))
        bg = ImageEnhance.Brightness(bg).enhance(0.6)
        bg = Image.blend(bg, Image.new("RGB", bg.size, self.accent), 0.12)
        self.bg = bg.filter(ImageFilter.GaussianBlur(2))

        pose = fx.fit(cast.pose(killer), *CHAR_BOX)
        self.char = fx.glow(pose, self.accent, 22, 0.9)
        ghost = fx.tinted(self.char, self.accent, 0.55)
        self.char_ghosts = [fx.faded(ghost, a) for _, a in GHOSTS]
        self.backlight = fx.radial(1400, 1250, self.accent, 0.5)

        self.name_src = cast.wordmark(killer)
        self.name = fx.fit(self.name_src, *NAME_BOX)
        self.name_fit = self.name.width / self.name_src.width
        self.name_white = fx.silhouette(self.name, WHITE)
        ys, xs = np.mgrid[0:self.name.height, 0:self.name.width].astype(np.float32)
        self.glint_x = xs + 0.45 * ys
        self.name_alpha = np.asarray(self.name.getchannel("A"), dtype=np.float32) / 255

        vertical = killer.entry in ("top", "bottom")
        lines = fx.speed_lines(H if vertical else W, W if vertical else H, 90, seed)
        self.lines = lines.rotate(90, expand=True) if vertical else lines
        self.name_focus = [fx.focus_lines(W, H, NAME_AT[0], NAME_AT[1], 360, 140, seed + i) for i in range(3)]
        self.char_focus = [fx.focus_lines(W, H, CHAR_AT[0], CHAR_AT[1], 380, 120, seed + 10 + i) for i in range(3)]
        self.vignette = fx.vignette(W, H, 0.75)

        self.embers = fx.Embers(70, seed, [self.accent, (255, 170, 80), (255, 235, 200)], W, H)

    # Where things are at time t ------------------------------------------------------------------------------------

    def rush(self, t: float) -> float:
        """0 until the last moments, then up to 1 as the camera rushes in for the cut."""
        return ramp(t, self.duration - OUT, self.duration, in_cubic)

    def char_pose(self, t: float) -> tuple[float, float, float, float]:
        """The killer's center, scale and tilt at [t]."""
        dx0, dy0, s0, a0 = ENTRIES[self.k.entry]
        u = ramp(t, *CHAR_IN, out_back)
        x, y = CHAR_AT[0] + dx0 * (1 - u), CHAR_AT[1] + dy0 * (1 - u)
        scale, angle = lerp(s0, 1, u), a0 * (1 - u)
        held = t - CHAR_IN[1]
        if held > 0:
            y += 8 * math.sin(2 * math.pi * held / 1.5)
            scale *= 1 + 0.012 * math.sin(2 * math.pi * held / 1.5 + 1)
        r = self.rush(t)
        return lerp(x, W / 2 + (x - W / 2) * 1.25, r), y, scale * (1 + 0.25 * r), angle

    def name_pose(self, t: float) -> tuple[float, float, float, float]:
        """The name's center, scale and tilt at [t]."""
        u = ramp(t, *self.name_in, in_cubic)
        scale = lerp(3.2, 1, u) * (1 + 0.08 * pulse(t, self.beat, 0.2))
        x = NAME_AT[0] + 14 * ramp(t, self.beat, self.duration)
        r = self.rush(t)
        return lerp(x, W / 2 + (x - W / 2) * 1.25, r), NAME_AT[1], scale * (1 + 0.25 * r), lerp(-9, 0, u)

    def shake(self, t: float) -> tuple[float, float]:
        land = 34 if self.k.entry in ("top", "bottom", "depth") else 26
        amp = land * pulse(t, CHAR_IN[1], 0.3) + 34 * pulse(t, self.beat, 0.4)
        jx, jy = jitter(t, FPS, 5)
        return jx * amp, jy * amp

    # Drawing ---------------------------------------------------------------------------------------------------------

    def stage(self, t: float, dx: float, dy: float) -> Image.Image:
        """The stage as the camera sees it: a punch in on the flash, a slow push, then the rush into the cut."""
        z = lerp(ZOOM_MAX, 1.08, ramp(t, 0, 0.35, out_expo)) - 0.05 * ramp(t, 0.35, self.duration)
        z += 0.3 * self.rush(t)
        bw, bh = self.bg.size
        vw, vh = W * ZOOM_MAX / z, H * ZOOM_MAX / z
        cx = min(max(bw / 2 - dx * ZOOM_MAX / z, vw / 2), bw - vw / 2)
        cy = min(max(bh / 2 - dy * ZOOM_MAX / z, vh / 2), bh - vh / 2)
        box = (cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2)
        return self.bg.resize((W, H), Image.BILINEAR, box=box).convert("RGBA")

    def draw_lines(self, canvas: Image.Image, alpha: float, offset: float) -> None:
        if alpha <= 0.01:
            return
        layer = fx.faded(self.lines, alpha)
        # Scrolled by [offset] and wrapped around, so the streaks never run out.
        if self.k.entry in ("top", "bottom"):
            o = round(offset) % H
            canvas.alpha_composite(layer.crop((0, 0, W, H - o)), (0, o))
            if o:
                canvas.alpha_composite(layer.crop((0, H - o, W, H)), (0, 0))
        else:
            o = round(offset) % W
            canvas.alpha_composite(layer.crop((0, 0, W - o, H)), (o, 0))
            if o:
                canvas.alpha_composite(layer.crop((W - o, 0, W, H)), (0, 0))

    def draw_char(self, canvas: Image.Image, t: float, dx: float, dy: float) -> None:
        x, y, s, a = self.char_pose(t)
        for (back, _), ghost in zip(GHOSTS, self.char_ghosts):
            gx, gy, gs, ga = self.char_pose(t - back)
            if math.hypot(gx - x, gy - y) > 24 or abs(gs - s) > 0.04:
                fx.place(canvas, fx.transformed(ghost, gs, ga), gx + dx, gy + dy)
        fx.place(canvas, fx.transformed(self.char, s, a), x + dx, y + dy)

    def name_image(self, scale: float, angle: float) -> Image.Image:
        if scale > 1.05:  # drawn from the full-size logo, so it stays sharp while it is big
            return fx.transformed(self.name_src, self.name_fit * scale, angle)
        return fx.transformed(self.name, scale, angle)

    def draw_name(self, canvas: Image.Image, t: float, dx: float, dy: float) -> None:
        if t < self.name_in[0]:
            return
        x, y, s, a = self.name_pose(t)
        alpha = ramp(t, self.name_in[0], self.name_in[0] + 0.06)
        if t < self.beat:
            for back, op in GHOSTS[:2]:
                if t - back >= self.name_in[0]:
                    gx, gy, gs, ga = self.name_pose(t - back)
                    fx.place(canvas, fx.faded(self.name_image(gs, ga), op * alpha), gx + dx, gy + dy)
        fx.place(canvas, fx.faded(self.name_image(s, a), alpha), x + dx, y + dy)
        white = 0.8 * pulse(t, self.beat, 0.12)
        if white > 0.02:
            fx.place(canvas, fx.faded(fx.transformed(self.name_white, s, a), white), x + dx, y + dy)
        g = ramp(t, *self.glint)
        if 0 < g < 1 and abs(s - 1) < 0.02:
            pos = lerp(-200, self.name.width + self.name.height * 0.45 + 200, g)
            band = np.exp(-(((self.glint_x - pos) / 34) ** 2)) * self.name_alpha * 0.8
            light = np.zeros(band.shape + (4,), dtype=np.uint8)
            light[..., :3] = 255
            light[..., 3] = (band * 255).astype(np.uint8)
            fx.add_light(canvas, Image.fromarray(light, "RGBA"), round(x + dx - self.name.width / 2), round(y + dy - self.name.height / 2))

    def frame(self, t: float) -> Image.Image:
        dx, dy = self.shake(t)
        canvas = self.stage(t, dx, dy)

        light = ramp(t, CHAR_IN[1] - 0.1, CHAR_IN[1] + 0.25)
        if light > 0.01:
            fx.place(canvas, fx.faded(self.backlight, light), CHAR_AT[0] + dx, CHAR_AT[1] + dy)
        self.embers.draw(canvas, t, False, dx, dy)

        entering = 1 - ramp(t, CHAR_IN[1], CHAR_IN[1] + 0.3)
        if self.k.entry == "depth":
            fx.place(canvas, fx.faded(self.char_focus[round(t * 30) % 3], 0.7 * entering), W / 2 + dx, H / 2 + dy)
        else:
            sign = {"right": -1, "left": 1, "top": 1, "bottom": -1}[self.k.entry]
            self.draw_lines(canvas, 0.85 * entering, sign * t * 5200)

        self.draw_char(canvas, t, dx, dy)
        focus = 0.9 * pulse(t, self.beat, 0.45)
        if focus > 0.01:
            fx.place(canvas, fx.faded(self.name_focus[round(t * 30) % 3], focus), W / 2 + dx, H / 2 + dy)
        self.draw_name(canvas, t, dx, dy)
        self.embers.draw(canvas, t, True, dx, dy)
        canvas.alpha_composite(self.vignette)

        out = canvas.convert("RGB")
        out = fx.chroma(out, 10 * pulse(t, CHAR_IN[1], 0.18) + 16 * pulse(t, self.beat, 0.22))
        out = fx.smear(out, 80 * self.rush(t))
        return fx.flash(out, WHITE, max(0.8 * pulse(t, 0, 0.14), 0.5 * pulse(t, CHAR_IN[1], 0.1),
                                        0.6 * pulse(t, self.beat, 0.1), 0.3 * self.rush(t)))

    def cues(self) -> list[Cue]:
        # The killer's clip from the moment they appear, faded out as the next one comes in.
        sound = cast.intro_sound(self.k.id)
        return [Cue(0.05, sound, 1.0, length=self.duration - 0.05, fade_out=0.12)] if sound else []
