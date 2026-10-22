"""Over 25 unique environments: the game's stages run through a film projector, faster and faster.

Each stage is a frame on a strip of film between black edges punched with sprocket holes. The first, the most
recognizable (1428 Elm St, Crystal Lake, the Myers house...), hold for a moment and are pulled into the gate one at a
time; every frame after holds a little less, until they flicker past fifteen a second. The film itself never stops:
its sprocket holes stream past the gate, faster and faster, until they blur. Grain, flicker, a scratch or two and a
warm cast sell the film. The title slams down as the announcer shouts it, then zips off, leaving the stages in the
clear.
"""
from __future__ import annotations

import random

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

from . import cast, fx
from .lettering import Slam
from .motion import in_out_sine, pulse, ramp
from .render import FPS, H, W, Cue

TITLE = cast.ROOT / "video/art/title-environments-25.webp"
LINE = cast.ROOT / "video/voice/environments.wav"  # the title, shouted (tools/generate_video_voice.py)
PROJECTOR = cast.ROOT / "video/sfx/projector.wav"
# The stages in the order they run: the most recognizable first, then the rest.
ORDER = [("freddy", "house"), ("jason", "dock"), ("michael", "myershouse"), ("leatherface", "house"),
         ("ghostface", "house"), ("pinhead", "chamber"), ("chucky", "toystore"), ("freddy", "boiler"),
         ("jason", "cabin"), ("michael", "haddonfield"), ("ghostface", "school"), ("pinhead", "labyrinth"),
         ("leatherface", "dining"), ("chucky", "livingroom"), ("freddy", "bedroom"), ("jason", "woods"),
         ("ghostface", "kitchen"), ("michael", "closet"), ("pinhead", "attic"), ("leatherface", "butcher"),
         ("chucky", "department")]
EDGE = 150  # the black film edge on each side, with its sprocket holes
FRAME_H = round(H * 0.88)  # a frame's height; the rest of its pitch is the black line between frames
PITCH = H  # the strip moves a frame along by this much
HOLES = PITCH / 4  # the sprocket holes' spacing down each edge
FIRST, SHRINK, FASTEST = 0.5, 0.86, 1 / 15  # the first frame's hold, how much shorter each gets, and the shortest
PULL = 0.09  # the longest the gate takes to pull the next frame down (less of a hold, the faster it goes)
CUT = 0.15  # frames held shorter than this just cut from one to the next


def vertical_smear(im: Image.Image, px: float) -> Image.Image:
    """[im] blurred [px] along its height, as film running past."""
    return fx.smear(im.transpose(Image.Transpose.ROTATE_90), px).transpose(Image.Transpose.ROTATE_270)


class Environments:
    """[duration] long, with the title landing [title_at] seconds in and leaving at [title_off]."""

    def __init__(self, duration: float, title_at: float = 0.14, title_off: float = 3.05):
        self.duration = duration
        width = W - 2 * EDGE
        self.frames = []
        for pet, stage in ORDER:
            im = cast.backdrop(cast.Killer(pet, stage, "right"))
            im = ImageEnhance.Color(fx.cover(im, width, FRAME_H)).enhance(0.85)
            self.frames.append(Image.blend(im, Image.new("RGB", im.size, (255, 200, 140)), 0.08))
        # When each frame starts: a little sooner each time, cycling through the stages until the scene ends.
        self.starts, t, hold = [], 0.0, FIRST
        while t < duration:
            self.starts.append(t)
            t += hold
            hold = max(FASTEST, hold * SHRINK)
        self.edge = self.film_edge()
        self.title = Slam(Image.open(TITLE).convert("RGBA"), (W / 2, H * 0.5), 900, land=title_at, glint_at=1.2,
                          leave=title_off, exit_dx=-2400)
        rng = random.Random(41)
        self.grain = [Image.fromarray(np.array([[rng.randrange(256) for _ in range(240)] for _ in range(135)], np.uint8),
                                      "L").resize((W, H), Image.BILINEAR) for _ in range(4)]
        self.vignette = fx.vignette(W, H, 0.8)

    def film_edge(self) -> Image.Image:
        """One of the film's black edges with its sprocket holes, a hole's spacing longer than the frame, to run."""
        edge = Image.new("RGB", (EDGE, round(H + HOLES)), (8, 6, 6))
        d = ImageDraw.Draw(edge)
        hole_w, hole_h = EDGE * 0.42, PITCH * 0.11
        x = EDGE / 2 - hole_w / 2
        for k in range(int(edge.height // HOLES) + 1):
            y = HOLES * (k + 0.5) - hole_h / 2
            d.rounded_rectangle([x, y, x + hole_w, y + hole_h], radius=hole_w * 0.22, fill=(205, 196, 180))
        return edge

    def run(self, t: float) -> float:
        """How many frames of film have run through by [t]: the film itself runs on without stopping, a frame per
        frame's hold, however the pictures are pulled into the gate."""
        k = max(i for i, s in enumerate(self.starts) if s <= t + 1e-9)
        end = self.starts[k + 1] if k + 1 < len(self.starts) else self.duration
        return k + (t - self.starts[k]) / (end - self.starts[k])

    def position(self, t: float) -> float:
        """How many frames along the strip is at [t]: each frame pulled down into the gate and held still, as a
        projector's intermittent movement does, however fast they go."""
        k = max(i for i, s in enumerate(self.starts) if s <= t + 1e-9)
        if k == 0:
            return 0.0
        hold = (self.starts[k + 1] if k + 1 < len(self.starts) else self.duration) - self.starts[k]
        if hold < CUT:  # too quick to see the pull: a straight cut, frame to frame
            return float(k)
        return k - 1 + in_out_sine(min(1.0, (t - self.starts[k]) / min(PULL, 0.4 * hold)))

    def frame(self, t: float) -> Image.Image:
        pos = self.position(t)
        canvas = Image.new("RGB", (W, H), (8, 6, 6))
        top = (H - FRAME_H) / 2
        base = int(pos)
        for j in (base - 1, base, base + 1):  # the frame in the gate, and the ones above and below it
            if j < 0:
                continue
            y = top + (j - pos) * PITCH
            if -PITCH < y < H:  # (the black line between frames is the film showing through)
                canvas.paste(self.frames[j % len(self.frames)], (EDGE, round(y)))
        # A picture being pulled into the gate blurs along the film.
        step = self.position(min(self.duration, t + 1 / FPS)) - pos
        speed = step * PITCH if step < 0.9 else 0.0  # a hard cut is a jump, not motion
        if speed > 12:
            canvas = vertical_smear(canvas, min(speed * 0.6, 110))
        # The edges run on, the sprocket holes streaming past and blurring as the film speeds up.
        ran = self.run(t) * PITCH
        flow = self.run(min(self.duration, t + 1 / FPS)) * PITCH - ran
        y0 = ran % HOLES
        edge = self.edge.crop((0, round(y0), EDGE, round(y0) + H))
        if flow > 3:
            edge = vertical_smear(edge, min(flow, HOLES))
        canvas.paste(edge, (0, 0))
        canvas.paste(edge, (W - EDGE, 0))
        rng = random.Random(round(t * FPS))
        canvas = ImageEnhance.Brightness(canvas).enhance(0.94 + 0.08 * rng.random())  # projector flicker
        grain = self.grain[round(t * FPS) % len(self.grain)]
        canvas = Image.blend(canvas, Image.merge("RGB", (grain, grain, grain)), 0.07)
        if rng.random() < 0.35:  # a scratch down the film
            d = ImageDraw.Draw(canvas)
            x = rng.uniform(EDGE, W - EDGE)
            d.line([(x, 0), (x + rng.uniform(-6, 6), H)], fill=(225, 220, 205), width=rng.choice((1, 2)))
        out = canvas.convert("RGBA")
        out.alpha_composite(self.vignette)
        self.title.draw(out, t)
        rgb = out.convert("RGB")
        return fx.flash(rgb, (255, 255, 255), max(0.6 * pulse(t, 0, 0.12), 0.7 * ramp(t, self.duration - 0.1, self.duration)))

    def cues(self) -> list[Cue]:
        return [Cue(0.0, PROJECTOR, 0.7, length=self.duration, fade_out=0.3), Cue(0.15, LINE, 1.15)]
