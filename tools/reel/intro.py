"""A killer's intro, in the spirit of Super Street Fighter II Turbo: fast, loud and flashing, about three seconds.

The stage flashes in and the camera punches in; the killer flies in from their side (Killer.entry) with afterimages
and speed lines, and lands with a shake; a dark slab sweeps in and their name slams down onto it out of the camera,
with a white flash, focus lines and an RGB split, as the announcer calls it; their passive slides in under it and the
stage's name fades up in the corner. They hold for a beat with embers drifting and a glint across the name, then
everything whips off and a slanted wipe closes to black, ready to cut to the next one.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

from . import cast, fx
from .motion import in_cubic, in_out_sine, jitter, lerp, out_back, out_expo, pulse, ramp
from .render import FPS, H, W, Cue

DURATION = 3.4
CHAR_IN, CHAR_LAND, CHAR_OUT = (0.04, 0.36), 0.36, (2.86, 3.12)
SLAB_IN = (0.42, 0.58)
NAME_IN, NAME_LAND, NAME_OUT = (0.50, 0.66), 0.66, (2.78, 3.04)
TAG_IN, STAGE_IN, GLINT = (0.80, 0.98), (1.00, 1.30), (1.45, 1.85)
WIPE = (3.02, 3.36)

CHAR_AT, CHAR_BOX = (W * 0.715, H * 0.55), (1000, 1010)
NAME_AT, NAME_BOX = (W * 0.28, H * 0.40), (860, 330)
ZOOM_MAX = 1.3  # the stage is prepared at this zoom, the most the camera ever pushes in before the outro
INK = (14, 10, 16)
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
    duration = DURATION

    def __init__(self, killer: cast.Killer, seed: int = 13):
        self.k = killer
        self.accent = cast.accent(killer)
        self.rng = random.Random(seed)

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

        self.slab = fx.slab(1190, 230, 70, (10, 6, 12, 205), self.accent + (255,), 7)
        self.slab_at = (-40 + self.slab.width / 2, NAME_AT[1] + 8)

        word = fx.text(killer.epithet.upper(), cast.FONT_BLACK, 76, INK, tracking=0.06, skew=0.18)
        tag = fx.slab(word.width + 90, 92, 28, self.accent + (255,))
        tag.alpha_composite(word, ((tag.width - word.width) // 2, (tag.height - word.height) // 2))
        self.tag, self.tag_white = tag, fx.silhouette(tag, WHITE)
        self.tag_at = (NAME_AT[0] - self.name.width / 2 + 30 + tag.width / 2, NAME_AT[1] + self.name.height / 2 + 26 + tag.height / 2)

        title = fx.text(cast.stage(killer)["title"].upper(), cast.FONT_BOLD, 38, (255, 255, 255, 225), tracking=0.32)
        caption = Image.new("RGBA", (title.width + 66, max(title.height, 8)), (0, 0, 0, 0))
        ImageDraw.Draw(caption).rectangle([0, caption.height // 2 - 3, 44, caption.height // 2 + 3], fill=self.accent + (255,))
        caption.alpha_composite(title, (66, 0))
        self.caption = caption

        vertical = killer.entry in ("top", "bottom")
        lines = fx.speed_lines(H if vertical else W, W if vertical else H, 90, seed)
        self.entry_lines = lines.rotate(90, expand=True) if vertical else lines
        self.exit_lines = fx.speed_lines(W, H, 90, seed + 1)
        self.name_focus = [fx.focus_lines(W, H, NAME_AT[0], NAME_AT[1], 360, 140, seed + i) for i in range(3)]
        self.char_focus = [fx.focus_lines(W, H, CHAR_AT[0], CHAR_AT[1], 380, 120, seed + 10 + i) for i in range(3)]
        self.vignette = fx.vignette(W, H, 0.75)

        tones = [self.accent, (255, 170, 80), (255, 235, 200)]
        self.ember_sprites = [[fx.soft_dot(r, c) for c in tones] for r in (2, 3, 5)]
        self.embers = [
            dict(x=self.rng.uniform(0, W), y=self.rng.uniform(0, H), vy=self.rng.uniform(50, 190),
                 sway=self.rng.uniform(8, 40), freq=self.rng.uniform(0.8, 2.4), phase=self.rng.uniform(0, 6.3),
                 size=self.rng.choices([0, 1, 2], [5, 3, 1])[0], tone=self.rng.randrange(3), front=self.rng.random() < 0.3)
            for _ in range(70)
        ]

    # Where things are at time t ------------------------------------------------------------------------------------

    def char_pose(self, t: float) -> tuple[float, float, float, float]:
        """The killer's center, scale and tilt at [t]."""
        dx0, dy0, s0, a0 = ENTRIES[self.k.entry]
        u = ramp(t, *CHAR_IN, out_back)
        x, y = CHAR_AT[0] + dx0 * (1 - u), CHAR_AT[1] + dy0 * (1 - u)
        scale, angle = lerp(s0, 1, u), a0 * (1 - u)
        held = t - CHAR_LAND
        if held > 0:
            y += 8 * math.sin(2 * math.pi * held / 1.5)
            scale *= 1 + 0.012 * math.sin(2 * math.pi * held / 1.5 + 1)
        v = ramp(t, *CHAR_OUT, in_cubic)
        return x + 1500 * v, y, scale, angle - 10 * v

    def name_pose(self, t: float) -> tuple[float, float, float, float]:
        """The name's center, scale and tilt at [t]."""
        u = ramp(t, *NAME_IN, in_cubic)
        scale = lerp(3.2, 1, u) * (1 + 0.08 * pulse(t, NAME_LAND, 0.2))
        x = NAME_AT[0] + 14 * ramp(t, NAME_LAND, NAME_OUT[0])
        v = ramp(t, *NAME_OUT, in_cubic)
        return x - 1500 * v, NAME_AT[1], scale, lerp(-9, 0, u) + 6 * v

    def shake(self, t: float) -> tuple[float, float]:
        land = 34 if self.k.entry in ("top", "bottom", "depth") else 26
        amp = land * pulse(t, CHAR_LAND, 0.3) + 34 * pulse(t, NAME_LAND, 0.4)
        jx, jy = jitter(t, FPS, 5)
        return jx * amp, jy * amp

    # Drawing ---------------------------------------------------------------------------------------------------------

    def stage(self, t: float, dx: float, dy: float) -> Image.Image:
        """The stage as the camera sees it: a punch in on the flash, a slow push, then a rush in for the outro."""
        z = lerp(ZOOM_MAX, 1.08, ramp(t, 0, 0.5, out_expo)) - 0.04 * ramp(t, 0.5, DURATION)
        z += 0.3 * ramp(t, CHAR_OUT[0] - 0.1, DURATION, in_cubic)
        bw, bh = self.bg.size
        vw, vh = W * ZOOM_MAX / z, H * ZOOM_MAX / z
        cx = min(max(bw / 2 - dx * ZOOM_MAX / z, vw / 2), bw - vw / 2)
        cy = min(max(bh / 2 - dy * ZOOM_MAX / z, vh / 2), bh - vh / 2)
        box = (cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2)
        return self.bg.resize((W, H), Image.BILINEAR, box=box).convert("RGBA")

    def draw_embers(self, canvas: Image.Image, t: float, front: bool, dx: float, dy: float) -> None:
        for e in self.embers:
            if e["front"] != front:
                continue
            y = (e["y"] - e["vy"] * t) % (H + 120) - 60
            x = e["x"] + e["sway"] * math.sin(e["freq"] * t + e["phase"])
            fx.place(canvas, self.ember_sprites[e["size"] + (1 if front and e["size"] < 2 else 0)][e["tone"]], x + dx, y + dy)

    def draw_lines(self, canvas: Image.Image, lines: Image.Image, alpha: float, offset: float, vertical: bool) -> None:
        if alpha <= 0.01:
            return
        layer = fx.faded(lines, alpha)
        # Scrolled by [offset] and wrapped around, so the streaks never run out.
        if vertical:
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
        if scale > 1.05:  # drawn from the full-size logo, so the slam is sharp while it is big
            return fx.transformed(self.name_src, self.name_fit * scale, angle)
        return fx.transformed(self.name, scale, angle)

    def draw_name(self, canvas: Image.Image, t: float, dx: float, dy: float) -> None:
        if t < NAME_IN[0]:
            return
        x, y, s, a = self.name_pose(t)
        alpha = ramp(t, NAME_IN[0], NAME_IN[0] + 0.06)
        if t < NAME_LAND or t > NAME_OUT[0]:
            for back, op in GHOSTS[:2]:
                gx, gy, gs, ga = self.name_pose(t - back)
                if t - back >= NAME_IN[0]:
                    fx.place(canvas, fx.faded(self.name_image(gs, ga), op * alpha), gx + dx, gy + dy)
        fx.place(canvas, fx.faded(self.name_image(s, a), alpha), x + dx, y + dy)
        white = 0.8 * pulse(t, NAME_LAND, 0.12)
        if white > 0.02:
            fx.place(canvas, fx.faded(fx.transformed(self.name_white, s, a), white), x + dx, y + dy)
        g = ramp(t, *GLINT)
        if 0 < g < 1:
            pos = lerp(-200, self.name.width + self.name.height * 0.45 + 200, g)
            band = np.exp(-(((self.glint_x - pos) / 34) ** 2)) * self.name_alpha * 0.8
            light = np.zeros(band.shape + (4,), dtype=np.uint8)
            light[..., :3] = 255
            light[..., 3] = (band * 255).astype(np.uint8)
            fx.add_light(canvas, Image.fromarray(light, "RGBA"), round(x + dx - self.name.width / 2), round(y + dy - self.name.height / 2))

    def frame(self, t: float) -> Image.Image:
        dx, dy = self.shake(t)
        canvas = self.stage(t, dx, dy)

        light = ramp(t, CHAR_LAND - 0.1, CHAR_LAND + 0.25) * (1 - ramp(t, *CHAR_OUT))
        if light > 0.01:
            fx.place(canvas, fx.faded(self.backlight, light), CHAR_AT[0] + dx, CHAR_AT[1] + dy)
        self.draw_embers(canvas, t, False, dx, dy)

        entering = ramp(t, 0, 0.08) * (1 - ramp(t, CHAR_LAND, CHAR_LAND + 0.3))
        if self.k.entry == "depth":
            fx.place(canvas, fx.faded(self.char_focus[round(t * 30) % 3], 0.7 * entering), W / 2 + dx, H / 2 + dy)
        else:
            sign = {"right": -1, "left": 1, "top": 1, "bottom": -1}[self.k.entry]
            self.draw_lines(canvas, self.entry_lines, 0.85 * entering, sign * t * 5200, self.k.entry in ("top", "bottom"))
        self.draw_lines(canvas, self.exit_lines, 0.85 * ramp(t, CHAR_OUT[0] - 0.1, CHAR_OUT[0] + 0.04), t * 5200, False)

        if t >= SLAB_IN[0]:
            sx = lerp(-self.slab.width / 2, self.slab_at[0], ramp(t, *SLAB_IN, out_expo))
            sx -= 1700 * ramp(t, NAME_OUT[0] - 0.04, NAME_OUT[1], in_cubic)
            fx.place(canvas, self.slab, sx + dx, self.slab_at[1] + dy)

        self.draw_char(canvas, t, dx, dy)

        focus = 0.9 * pulse(t, NAME_LAND, 0.45)
        if focus > 0.01:
            fx.place(canvas, fx.faded(self.name_focus[round(t * 30) % 3], focus), W / 2 + dx, H / 2 + dy)
        self.draw_name(canvas, t, dx, dy)

        if t >= TAG_IN[0]:
            tx = lerp(-self.tag.width, self.tag_at[0], ramp(t, *TAG_IN, out_expo))
            tx -= 1700 * ramp(t, *NAME_OUT, in_cubic)
            fx.place(canvas, self.tag, tx + dx, self.tag_at[1] + dy)
            white = pulse(t, TAG_IN[1] - 0.08, 0.2)
            if white > 0.02:
                fx.place(canvas, fx.faded(self.tag_white, white), tx + dx, self.tag_at[1] + dy)
        cap = ramp(t, *STAGE_IN) * (1 - ramp(t, *NAME_OUT))
        if cap > 0.01:
            fx.place(canvas, fx.faded(self.caption, cap), 96 + self.caption.width / 2 - 30 * (1 - cap) + dx, H - 84 + dy)

        self.draw_embers(canvas, t, True, dx, dy)
        canvas.alpha_composite(self.vignette)

        out = canvas.convert("RGB")
        out = fx.chroma(out, 10 * pulse(t, CHAR_LAND, 0.18) + 16 * pulse(t, NAME_LAND, 0.22))
        out = fx.smear(out, 110 * ramp(t, CHAR_OUT[0] - 0.04, WIPE[1], in_cubic))
        out = fx.flash(out, WHITE, max(pulse(t, 0, 0.14), 0.5 * pulse(t, CHAR_LAND, 0.1), 0.6 * pulse(t, NAME_LAND, 0.1)))
        self.wipe(out, t)
        return out

    def wipe(self, frame: Image.Image, t: float) -> None:
        """The slanted band that closes the intro: a white edge, then the accent, then black, right to left."""
        u = ramp(t, *WIPE, in_out_sine)
        if u <= 0:
            return
        lead, slant = lerp(W + 140, -700, u), 320
        d = ImageDraw.Draw(frame)
        for off, color in ((0, WHITE), (14, self.accent), (70, INK)):
            x = lead + off
            d.polygon([(x + slant, 0), (W + 1000, 0), (W + 1000, H), (x, H)], fill=color)

    def cues(self) -> list[Cue]:
        k = self.k
        return [
            Cue(0.0, cast.sfx("hand_whoosh"), 1.0),
            Cue(CHAR_LAND - 0.03, cast.sfx("heavy_hit"), 0.9),
            Cue(NAME_IN[0] - 0.04, cast.sfx("hand_whoosh"), 0.7),
            Cue(NAME_LAND - 0.01, cast.sfx("crit"), 0.8),
            Cue(NAME_LAND + 0.02, cast.voice(k.id), 1.3),
            Cue(TAG_IN[0], cast.sfx("hand_whoosh"), 0.4),
            Cue(1.78, cast.sfx(f"special_{k.id}"), 0.55),
            Cue(CHAR_OUT[0] - 0.08, cast.sfx("hand_whoosh"), 0.9),
            Cue(WIPE[0], cast.sfx("hand_whoosh"), 0.6),
        ]
