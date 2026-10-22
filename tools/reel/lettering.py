"""Title lettering that slams down out of the camera, holds with a glint, and zips off: the move every feature
title and the outro line make, and the killers' names before them."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from PIL import Image

from . import fx
from .motion import in_cubic, in_out_sine, lerp, pulse, ramp

SLAM = 0.14  # how long the lettering takes to come down
GHOSTS = [(0.018, 0.5), (0.036, 0.3)]


@dataclass
class Slam:
    """[image] centered at [at], [width] wide, landing at [land] and zipping off toward [exit_dx] (px) from [leave],
    or settling: moving to the place and scale [settle] gives (from, to, center, scale)."""
    image: Image.Image
    at: tuple[float, float]
    width: float
    land: float
    leave: float | None = None
    exit_dx: float = -2200
    glint_at: float | None = None
    settle: tuple[float, float, tuple[float, float], float] | None = None

    def __post_init__(self):
        self.src = self.image
        self.small = fx.fit(self.image, self.width, 10_000)
        self.fit_scale = self.small.width / self.src.width
        self.white = fx.silhouette(self.small, (255, 255, 255))
        ys, xs = np.mgrid[0:self.small.height, 0:self.small.width].astype(np.float32)
        self.glint_x = xs + 0.45 * ys
        self.alpha = np.asarray(self.small.getchannel("A"), dtype=np.float32) / 255

    def pose(self, t: float) -> tuple[float, float, float, float]:
        u = ramp(t, self.land - SLAM, self.land, in_cubic)
        scale = lerp(3.0, 1, u) * (1 + 0.07 * pulse(t, self.land, 0.2))
        x, y = self.at
        if self.leave is not None:
            x += self.exit_dx * ramp(t, self.leave, self.leave + 0.22, in_cubic)
        if self.settle is not None:
            start, end, (sx, sy), to = self.settle
            v = ramp(t, start, end, in_out_sine)
            x, y, scale = lerp(x, sx, v), lerp(y, sy, v), scale * lerp(1, to, v)
        return x, y, scale, lerp(-8, 0, u)

    def image_at(self, scale: float, angle: float) -> Image.Image:
        if scale > 1.05:  # from the full-size art, so it stays sharp while it is big
            return fx.transformed(self.src, self.fit_scale * scale, angle)
        return fx.transformed(self.small, scale, angle)

    def visible(self, t: float) -> bool:
        return t >= self.land - SLAM and (self.leave is None or t < self.leave + 0.24)

    def draw(self, canvas: Image.Image, t: float, dx: float = 0, dy: float = 0) -> None:
        if not self.visible(t):
            return
        x, y, s, a = self.pose(t)
        alpha = ramp(t, self.land - SLAM, self.land - SLAM + 0.05)
        moving = t < self.land or (self.leave is not None and t > self.leave)
        if moving:
            for back, op in GHOSTS:
                gx, gy, gs, ga = self.pose(t - back)
                if t - back >= self.land - SLAM:
                    fx.place(canvas, fx.faded(self.image_at(gs, ga), op * alpha), gx + dx, gy + dy)
        fx.place(canvas, fx.faded(self.image_at(s, a), alpha), x + dx, y + dy)
        white = 0.8 * pulse(t, self.land, 0.12)
        if white > 0.02:
            fx.place(canvas, fx.faded(fx.transformed(self.white, s, a), white), x + dx, y + dy)
        if self.glint_at is not None and abs(s - 1) < 0.02:
            g = ramp(t, self.glint_at, self.glint_at + 0.4)
            if 0 < g < 1:
                pos = lerp(-200, self.small.width + self.small.height * 0.45 + 200, g)
                band = np.exp(-(((self.glint_x - pos) / 36) ** 2)) * self.alpha * 0.75
                light = np.zeros(band.shape + (4,), dtype=np.uint8)
                light[..., :3] = 255
                light[..., 3] = (band * 255).astype(np.uint8)
                fx.add_light(canvas, Image.fromarray(light, "RGBA"), round(x + dx - self.small.width / 2),
                             round(y + dy - self.small.height / 2))
