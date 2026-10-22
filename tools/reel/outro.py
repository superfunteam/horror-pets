"""The outro, as the song ends: the line "Slashing onto Android phones and Android TV this Halloween" slams down big in
the middle as the announcer shouts it, then moves up as a TV and a phone running the game rise out of the storm under
it, and it all goes dark.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

from . import cast, fx, phones
from .footage import Clip
from .lettering import Slam
from .motion import in_out_sine, lerp, out_back, pulse, ramp
from .render import FPS, H, W, Cue

TITLE = cast.ROOT / "video/art/title-outro-onto.webp"
TV = cast.ROOT / "video/art/tv.webp"
STORM = cast.ROOT / "site/art/backdrop.webp"
LINE = cast.ROOT / "video/voice/outro.wav"  # the line, shouted (tools/generate_video_voice.py)
RIM = (255, 60, 110)
SETTLE = (1.7, 2.3)  # when the line moves up out of the middle
RISE = 1.9  # when the TV and the phone start to rise


class Outro:
    """[duration] long, fading out over the last [fade] seconds."""

    def __init__(self, duration: float, fade: float = 0.9):
        self.duration, self.fade = duration, fade
        storm = fx.cover(Image.open(STORM).convert("RGB"), round(W * 1.1), round(H * 1.1))
        self.storm = ImageEnhance.Brightness(storm.filter(ImageFilter.GaussianBlur(3))).enhance(0.42)
        self.vignette = fx.vignette(W, H, 0.7)
        self.embers = fx.Embers(50, 31, [RIM, (255, 170, 80), (255, 235, 200)], W, H)
        tv = Image.open(TV).convert("RGBA")
        self.tv = fx.fit(tv, 980, 640)
        a = np.asarray(self.tv).astype(int)
        key = (a[..., 1] > 150) & (a[..., 1] - np.maximum(a[..., 0], a[..., 2]) > 60)
        self.tv_screen = Image.fromarray((key * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(0.8))
        self.tv_box = self.tv_screen.getbbox()
        self.tv_feed = Clip("tv-fight-freddy.mp4", 50.0, 56.0)
        self.phone_feed = Clip("home-freddy-phone.mp4", 2.0, 8.0)
        line = Image.open(TITLE).convert("RGBA")
        big = min(1640, 0.74 * H * line.width / line.height)  # as big as the middle of the frame takes
        small = 0.28 * H * line.width / line.height  # up top, over the TV and the phone
        self.title = Slam(line, (W / 2, H * 0.47), big, land=0.14, glint_at=0.75,
                          settle=(*SETTLE, (W / 2, H * 0.17), small / big))
        self._shots: dict[int, phones.Shot] | None = None

    def shot(self, t: float) -> phones.Shot:
        rise = ramp(t, RISE + 0.05, RISE + 0.55, out_back)
        y = lerp(-26, -3.4, rise)  # out of sight below the frame until it rises
        yaw = -0.32 + 0.05 * math.sin(t * 1.2)
        phone = phones.Phone((11.5, y, 0), (0.06, yaw, -0.03), str(self.phone_feed.path(t)))
        return phones.Shot((phone,), camera=(0, 0, 46), rim=RIM)

    def shots(self) -> dict[int, phones.Shot]:
        if self._shots is None:
            self._shots = {i: self.shot(i / FPS) for i in range(round(self.duration * FPS) + 1)}
        return self._shots

    def prepare(self) -> None:
        self.tv_feed.extract()
        self.phone_feed.extract()
        phones.render_all(list(self.shots().values()))

    def draw_tv(self, canvas: Image.Image, t: float) -> None:
        rise = ramp(t, RISE, RISE + 0.5, out_back)
        tv = self.tv.copy()
        x0, y0, x1, y1 = self.tv_box
        pic = fx.cover(self.tv_feed.frame(t), x1 - x0, y1 - y0)
        layer = Image.new("RGB", tv.size)
        layer.paste(pic, (x0, y0))
        tv.paste(layer, (0, 0), self.tv_screen)
        fx.place(canvas, fx.shadowed(tv, 16, 14, 0.6), W * 0.36, lerp(H + 400, H * 0.66, rise))

    def frame(self, t: float) -> Image.Image:
        z = 1 + 0.04 * t / self.duration
        bw, bh = self.storm.size
        vw, vh = W * 1.1 / z, H * 1.1 / z
        canvas = self.storm.resize((W, H), Image.BILINEAR,
                                   box=(bw / 2 - vw / 2, bh / 2 - vh / 2, bw / 2 + vw / 2, bh / 2 + vh / 2)).convert("RGBA")
        self.embers.draw(canvas, t, False)
        self.draw_tv(canvas, t)
        shot = self.shots()[min(round(t * FPS), len(self.shots()) - 1)]
        fx.place(canvas, phones.load(shot), W / 2, H / 2)
        self.title.draw(canvas, t)
        self.embers.draw(canvas, t, True)
        canvas.alpha_composite(self.vignette)
        out = fx.flash(canvas.convert("RGB"), (255, 255, 255), 0.7 * pulse(t, 0, 0.16))
        return fx.flash(out, (0, 0, 0), ramp(t, self.duration - self.fade, self.duration, in_out_sine))

    def cues(self) -> list[Cue]:
        return [Cue(0.3, LINE, 1.15)]
