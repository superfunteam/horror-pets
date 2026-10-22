"""The outro, on the song's final hit: a TV and a phone running the game rise out of the storm, the line "Slashing on
Android phones & Android TV this Halloween" slams down over them, the site's address fades up, and it all goes dark.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

from . import cast, fx, phones
from .footage import Clip
from .lettering import Slam
from .motion import in_out_sine, lerp, out_back, out_cubic, pulse, ramp
from .render import FPS, H, W, Cue

TITLE = cast.ROOT / "video/art/title-outro.webp"
TV = cast.ROOT / "video/art/tv.webp"
STORM = cast.ROOT / "site/art/backdrop.webp"
FONT = str(cast.ROOT / "app/src/main/res/font/barlow_condensed_extrabold.ttf")
URL = "PETS.SUPERFUN.GAMES"
RIM = (255, 60, 110)


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
        self.title = Slam(Image.open(TITLE).convert("RGBA"), (W / 2, H * 0.2), 1420, land=0.14, glint_at=1.4)
        font = ImageFont.truetype(FONT, 56)
        self.url = Image.new("RGBA", (W, 90), (0, 0, 0, 0))
        ImageDraw.Draw(self.url).text((W / 2, 45), URL, font=font, fill=(255, 255, 255), anchor="mm", stroke_width=3,
                                      stroke_fill=(20, 8, 26))
        self._shots: dict[int, phones.Shot] | None = None

    def shot(self, t: float) -> phones.Shot:
        rise = ramp(t, 0.05, 0.55, out_back)
        y = lerp(-14, -3.4, rise)
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
        rise = ramp(t, 0.0, 0.5, out_back)
        tv = self.tv.copy()
        x0, y0, x1, y1 = self.tv_box
        pic = fx.cover(self.tv_feed.frame(t), x1 - x0, y1 - y0)
        layer = Image.new("RGB", tv.size)
        layer.paste(pic, (x0, y0))
        tv.paste(layer, (0, 0), self.tv_screen)
        fx.place(canvas, fx.shadowed(tv, 16, 14, 0.6), W * 0.36, lerp(H + 400, H * 0.63, rise))

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
        url = ramp(t, 0.7, 1.1, out_cubic)
        if url > 0.01:
            canvas.alpha_composite(fx.faded(self.url, url), (0, round(H - 104 + 20 * (1 - url))))
        self.embers.draw(canvas, t, True)
        canvas.alpha_composite(self.vignette)
        out = fx.flash(canvas.convert("RGB"), (255, 255, 255), 0.7 * pulse(t, 0, 0.16))
        return fx.flash(out, (0, 0, 0), ramp(t, self.duration - self.fade, self.duration, in_out_sine))

    def cues(self) -> list[Cue]:
        return []
