"""The title card, as on the landing page (site/index.html): the storm, the seven killers flying in to stand around its
edges, and the logo slamming down into the middle as the announcer shouts the game's name.

The killers come in a few at a time ([arrivals]), each from their side. The logo lands at [logo_at] with a flash and
focus lines as the shout starts, and hits again at [turbo_at], where the shout is sped up just enough for TURBO to land.
With no arrivals and no logo it is only the storm, fading up from black with lightning, which opens the video.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageEnhance

from . import cast, fx
from .motion import in_cubic, jitter, lerp, out_back, pulse, ramp
from .render import FPS, H, W, Cue

BACKDROP = cast.ROOT / "site/art/backdrop.webp"
LOGO = cast.ROOT / "branding/logo.png"
SHOUT = cast.ROOT / "video/voice/title.wav"
SHOUT_FIRST, SHOUT_TURBO = 0.08, 1.76  # where "Virtual" and "Turbo" start in SHOUT (by ElevenLabs speech-to-text)

STORM, GLOW, LIGHTNING, WHITE = (20, 11, 28), (255, 77, 87), (205, 215, 255), (255, 255, 255)
ZOOM_MAX = 1.2
FOCUS = (W / 2, H * 0.4)  # what the camera pushes in on
ARRIVE = 0.3  # how long a killer takes to fly in
LOGO_AT, LOGO_WIDTH, LOGO_SLAM = (W / 2, H * 0.36), 1120, 0.14


@dataclass(frozen=True)
class Spot:
    """Where a killer stands on the title card, from the landing page's .k-* rules (site/style.css): their center as a
    fraction of the frame, their height as a fraction of its height, their lean (degrees clockwise, as in CSS), the
    side they fly in from, and how near they are (nearer ones move more as the camera pushes in)."""
    id: str
    x: float
    y: float
    h: float
    tilt: float
    side: str
    depth: float


SPOTS = [  # back to front
    Spot("ghostface", 0.14, 0.27, 0.44, -6, "left", 0.6),
    Spot("pinhead", 0.86, 0.26, 0.46, 3, "right", 0.6),
    Spot("jason", 0.16, 0.67, 0.58, 3, "left", 1.0),
    Spot("freddy", 0.84, 0.65, 0.54, -3, "right", 1.0),
    Spot("michael", 0.32, 0.94, 0.46, 2, "left", 1.3),
    Spot("leatherface", 0.69, 0.93, 0.48, -2, "right", 1.3),
    Spot("chucky", 0.50, 0.90, 0.30, 0, "bottom", 1.6),  # higher than on the page, so he is not cut off
]
FROM = {"left": (-0.7 * W, 0.06 * H), "right": (0.7 * W, 0.06 * H), "bottom": (0, 0.7 * H)}
GHOSTS = [(0.018, 0.45), (0.036, 0.28), (0.054, 0.14)]  # (how far back in time, opacity)


def _shade() -> Image.Image:
    """The landing page's darkening over the storm: its edges, then its floor fading into the page color."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot((xx - 0.5 * W) / (0.9 * W), (yy - 0.35 * H) / (0.7 * H))
    edge = Image.new("RGBA", (W, H), (10, 4, 14, 0))
    edge.putalpha(Image.fromarray((np.clip((r - 0.4) / 0.6, 0, 1) * 0.75 * 255).astype(np.uint8), "L"))
    floor = Image.new("RGBA", (W, H), STORM + (0,))
    floor.putalpha(Image.fromarray((np.clip((yy / H - 0.6) / 0.4, 0, 1) * 255).astype(np.uint8), "L"))
    return Image.alpha_composite(edge, floor)


class TitleCard:
    """[duration] long: killers flying in at their [arrivals] times, the logo landing at [logo_at] and hitting again at
    [turbo_at], lightning at the [lightning] times, faded in from and out to black over [fade_in] and [fade_out]."""

    def __init__(self, duration: float, arrivals: dict[str, float] | None = None, logo_at: float | None = None,
                 turbo_at: float | None = None, lightning: tuple[float, ...] = (), fade_in: float = 0.0,
                 fade_out: float = 0.0, seed: int = 29):
        self.duration, self.arrivals = duration, arrivals or {}
        self.logo_at, self.turbo_at, self.fade_in, self.fade_out = logo_at, turbo_at, fade_in, fade_out
        hits = [*self.arrivals.values(), *(x for x in (logo_at, turbo_at) if x is not None)]
        self.lightning = sorted({*lightning, *hits})

        bg = fx.cover(Image.open(BACKDROP).convert("RGB"), round(W * ZOOM_MAX), round(H * ZOOM_MAX))
        self.bg = ImageEnhance.Brightness(bg).enhance(0.85)
        self.shade = _shade()
        self.spots = [s for s in SPOTS if s.id in self.arrivals]
        self.cards, self.ghosts = {}, {}
        for s in self.spots:
            pose = fx.fit(cast.pose(cast.BY_ID[s.id]), W, s.h * 0.94 * H)
            card = fx.shadowed(pose, 18, 12, 0.55)
            self.cards[s.id] = card
            ghost = fx.tinted(card, GLOW, 0.55)
            self.ghosts[s.id] = [fx.faded(ghost, a) for _, a in GHOSTS]

        logo = Image.open(LOGO).convert("RGBA")
        self.logo_src = fx.shadowed(logo, 10, 15, 0.7)
        self.logo = fx.fit(self.logo_src, LOGO_WIDTH * self.logo_src.width / logo.width, H)
        self.logo_fit = self.logo.width / self.logo_src.width
        self.logo_white = fx.silhouette(self.logo, WHITE)
        ys, xs = np.mgrid[0:self.logo.height, 0:self.logo.width].astype(np.float32)
        self.glint_x = xs + 0.45 * ys
        self.logo_alpha = np.asarray(self.logo.getchannel("A"), dtype=np.float32) / 255
        self.focus = [fx.focus_lines(W, H, LOGO_AT[0], LOGO_AT[1], 470, 160, seed + i) for i in range(3)]
        self.embers = fx.Embers(60, seed, [GLOW, (255, 170, 80), (255, 235, 200)], W, H)

    # Where things are at time t ------------------------------------------------------------------------------------

    def hit(self, t: float, at: float | None, dur: float) -> float:
        return 0.0 if at is None else pulse(t, at, dur)

    def zoom(self, t: float) -> float:
        z = 1 + 0.05 * ramp(t, 0, self.duration)
        z += sum(0.03 * pulse(t, a, 0.3) for a in self.arrivals.values())
        return z + 0.06 * self.hit(t, self.turbo_at, 0.5)

    def shake(self, t: float) -> tuple[float, float]:
        amp = sum(18 * pulse(t, a, 0.25) for a in self.arrivals.values())
        amp += 30 * self.hit(t, self.logo_at, 0.35) + 44 * self.hit(t, self.turbo_at, 0.5)
        jx, jy = jitter(t, FPS, 11)
        return jx * amp, jy * amp

    def spot_pose(self, s: Spot, t: float, z: float) -> tuple[float, float, float, float]:
        """Killer [s]'s center, scale and tilt at [t] under camera zoom [z]."""
        u = ramp(t, self.arrivals[s.id], self.arrivals[s.id] + ARRIVE, out_back)
        ox, oy = FROM[s.side]
        x, y = s.x * W + ox * (1 - u), s.y * H + oy * (1 - u)
        k = 1 + (z - 1) * s.depth
        scale = k * (1 + 0.06 * self.hit(t, self.turbo_at, 0.3))
        return FOCUS[0] + (x - FOCUS[0]) * k, FOCUS[1] + (y - FOCUS[1]) * k, scale, -s.tilt

    def logo_pose(self, t: float) -> tuple[float, float]:
        """The logo's scale and tilt at [t]."""
        u = ramp(t, self.logo_at - LOGO_SLAM, self.logo_at, in_cubic)
        scale = lerp(3.0, 1, u) * (1 + 0.06 * self.hit(t, self.logo_at, 0.2) + 0.12 * self.hit(t, self.turbo_at, 0.25))
        return scale, lerp(-6, 0, u)

    # Drawing ---------------------------------------------------------------------------------------------------------

    def stage(self, t: float, z: float, dx: float, dy: float) -> Image.Image:
        bw, bh = self.bg.size
        vw, vh = W * ZOOM_MAX / z, H * ZOOM_MAX / z
        cx = min(max(bw / 2 - dx * ZOOM_MAX / z, vw / 2), bw - vw / 2)
        cy = min(max(bh / 2 - dy * ZOOM_MAX / z, vh / 2), bh - vh / 2)
        frame = self.bg.resize((W, H), Image.BILINEAR, box=(cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2))
        bolt = max((pulse(t, a, 0.25) for a in self.lightning), default=0.0)
        return fx.flash(frame, LIGHTNING, 0.45 * bolt).convert("RGBA")

    def draw_logo(self, canvas: Image.Image, t: float, dx: float, dy: float) -> None:
        if self.logo_at is None or t < self.logo_at - LOGO_SLAM:
            return
        s, a = self.logo_pose(t)
        alpha = ramp(t, self.logo_at - LOGO_SLAM, self.logo_at - LOGO_SLAM + 0.05)
        if t < self.logo_at:
            for back, op in GHOSTS[:2]:
                if t - back >= self.logo_at - LOGO_SLAM:
                    gs, ga = self.logo_pose(t - back)
                    fx.place(canvas, fx.faded(self.image(gs, ga), op * alpha), LOGO_AT[0] + dx, LOGO_AT[1] + dy)
        fx.place(canvas, fx.faded(self.image(s, a), alpha), LOGO_AT[0] + dx, LOGO_AT[1] + dy)
        white = max(0.8 * pulse(t, self.logo_at, 0.12), 0.6 * self.hit(t, self.turbo_at, 0.16))
        if white > 0.02:
            fx.place(canvas, fx.faded(fx.transformed(self.logo_white, s, a), white), LOGO_AT[0] + dx, LOGO_AT[1] + dy)
        if self.turbo_at is not None and abs(s - 1) < 0.02:
            for start in (self.turbo_at + 0.45, self.turbo_at + 2.4):
                g = ramp(t, start, start + 0.5)
                if 0 < g < 1:
                    pos = lerp(-200, self.logo.width + self.logo.height * 0.45 + 200, g)
                    band = np.exp(-(((self.glint_x - pos) / 40) ** 2)) * self.logo_alpha * 0.7
                    light = np.zeros(band.shape + (4,), dtype=np.uint8)
                    light[..., :3] = 255
                    light[..., 3] = (band * 255).astype(np.uint8)
                    fx.add_light(canvas, Image.fromarray(light, "RGBA"), round(LOGO_AT[0] + dx - self.logo.width / 2),
                                 round(LOGO_AT[1] + dy - self.logo.height / 2))

    def image(self, scale: float, angle: float) -> Image.Image:
        if scale > 1.05:  # drawn from the full-size logo, so it stays sharp while it is big
            return fx.transformed(self.logo_src, self.logo_fit * scale, angle)
        return fx.transformed(self.logo, scale, angle)

    def frame(self, t: float) -> Image.Image:
        dx, dy = self.shake(t)
        z = self.zoom(t)
        canvas = self.stage(t, z, dx, dy)
        canvas.alpha_composite(self.shade)
        self.embers.draw(canvas, t, False, dx, dy)
        for s in self.spots:
            if t < self.arrivals[s.id]:
                continue
            x, y, sc, a = self.spot_pose(s, t, z)
            for (back, _), ghost in zip(GHOSTS, self.ghosts[s.id]):
                if self.arrivals[s.id] <= t - back < self.arrivals[s.id] + ARRIVE:
                    gx, gy, gs, ga = self.spot_pose(s, t - back, z)
                    fx.place(canvas, fx.transformed(ghost, gs, ga), gx + dx, gy + dy)
            fx.place(canvas, fx.transformed(self.cards[s.id], sc, a), x + dx, y + dy)
        focus = max(0.9 * self.hit(t, self.logo_at, 0.4), self.hit(t, self.turbo_at, 0.55))
        if focus > 0.01:
            fx.place(canvas, fx.faded(self.focus[round(t * 30) % 3], focus), W / 2 + dx, H / 2 + dy)
        self.draw_logo(canvas, t, dx, dy)
        self.embers.draw(canvas, t, True, dx, dy)

        out = canvas.convert("RGB")
        out = fx.chroma(out, 14 * self.hit(t, self.logo_at, 0.2) + 20 * self.hit(t, self.turbo_at, 0.25))
        out = fx.flash(out, WHITE, max(0.0, *(0.2 * pulse(t, a, 0.1) for a in set(self.arrivals.values())),
                                       0.7 * self.hit(t, self.logo_at, 0.12), 0.85 * self.hit(t, self.turbo_at, 0.16)))
        dark = max(1 - ramp(t, 0, self.fade_in) if self.fade_in else 0.0,
                   ramp(t, self.duration - self.fade_out, self.duration) if self.fade_out else 0.0)
        return fx.flash(out, (0, 0, 0), dark)

    def cues(self) -> list[Cue]:
        if self.logo_at is None or self.turbo_at is None:
            return []
        # Sped up so that "Virtual" lands with the logo and "Turbo" on the second hit.
        tempo = (SHOUT_TURBO - SHOUT_FIRST) / (self.turbo_at - self.logo_at)
        return [Cue(self.logo_at - SHOUT_FIRST / tempo, SHOUT, 1.1, tempo=tempo)]
