"""Raise & train your killer: the game's own sprites in their own scenes, splitting Brady Bunch style.

Leatherface dances and swings his saw alone in his butcher room as the title slams in. On the next bar the frame splits
and Jason gets a drumstick dropped at his feet on the dock; then, on every half bar after, it splits again (Freddy
slashing in the boiler room, Chucky stabbing in the toy store) and the others swap in a panel at a time, each with a
flash: Ghostface, Pinhead, Michael. Every panel's stage drifts a little behind its killer.
"""
from __future__ import annotations

from dataclasses import dataclass

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

from . import cast, fx, sprites
from .lettering import Slam
from .motion import lerp, out_cubic, pulse, ramp
from .render import H, W, Cue

TITLE = cast.ROOT / "video/art/title-raise.webp"
FOOD = cast.ROOT / "sprites-final/food/drumstick.webp"
GAP = 6
INK = (14, 8, 18)


@dataclass(frozen=True)
class Panel:
    """A killer on a stage in panel [slot] (0 top left, 1 top right, 2 bottom left, 3 bottom right) from [enter]
    (seconds into the scene), cycling through [anims] a half bar each."""
    pet: str
    stage: str
    slot: int
    enter: float
    anims: tuple[str, ...]
    x: float = 0.5  # where across the panel they stand


class RaiseTrain:
    """[bars] bars of [bar] seconds: a bar of one panel under the title, then a change every half bar: the split into
    two, the split into four, and the others swapping in a panel at a time."""

    def __init__(self, bar: float, bars: float = 3.5):
        self.bar = bar
        self.duration = bars * bar
        b = bar
        self.split, self.quad = b, 1.5 * b  # when the frame splits in two, then in four
        self.panels = [
            Panel("leatherface", "butcher", 0, 0, ("dance", "attack")),
            Panel("jason", "dock", 1, self.split, ("jump", "attack")),
            Panel("freddy", "boiler", 2, self.quad, ("attack",)),
            Panel("chucky", "toystore", 3, self.quad, ("jump", "attack")),
            Panel("ghostface", "house", 1, 2 * b, ("attack", "jump")),
            Panel("pinhead", "labyrinth", 2, 2.5 * b, ("attack", "wave")),
            Panel("michael", "myershouse", 3, 3 * b, ("attack",)),
        ]
        self.panels = [p for p in self.panels if p.enter < self.duration]
        self.stages = {}
        for p in self.panels:
            k = cast.Killer(p.pet, p.stage, "right")
            bg = ImageEnhance.Brightness(cast.backdrop(k)).enhance(0.85)
            self.stages[p.pet] = (bg, cast.stage(k))
        self.food = fx.trim(Image.open(FOOD).convert("RGBA"))
        self.title = Slam(Image.open(TITLE).convert("RGBA"), (W * 0.3, H * 0.42), 820, land=0.14, leave=b - 0.2,
                          glint_at=0.6)

    def occupant(self, slot: int, t: float) -> Panel | None:
        """Who is in panel [slot] at [t]: the latest to enter it."""
        there = [p for p in self.panels if p.slot == slot and p.enter <= t]
        return there[-1] if there else None

    def rect(self, i: int, t: float) -> tuple[float, float, float, float] | None:
        """Panel slot [i]'s rectangle at [t]: the splits sweep in over a quarter beat from each downbeat."""
        split = ramp(t, self.split, self.split + 0.22, out_cubic)  # the vertical split
        split2 = ramp(t, self.quad, self.quad + 0.22, out_cubic)  # the horizontal one
        mid_x = lerp(W + GAP, W / 2, split)
        mid_y = lerp(H + GAP, H / 2, split2)
        left, right = (0, mid_x - GAP / 2), (mid_x + GAP / 2, W)
        top, bottom = (0, mid_y - GAP / 2), (mid_y + GAP / 2, H)
        boxes = [(left, top), (right, top), (left, bottom), (right, bottom)]
        (x0, x1), (y0, y1) = boxes[i]
        if self.occupant(i, t) is None or x1 - x0 < 4 or y1 - y0 < 4:
            return None
        return x0, y0, x1, y1

    def anim_at(self, p: Panel, t: float) -> tuple[str, float]:
        """Which animation [p] is in at [t], and how far into it: a new one every half bar, idling once it ends."""
        local = t - p.enter
        k = int(local // (self.bar / 2))
        a, at = p.anims[k % len(p.anims)], local - k * self.bar / 2
        if p.pet == "jason" and k == 0:  # landing first, then the drumstick drops
            at -= 0.35
        if at < 0 or at > sprites.length(p.pet, a) + 0.15:
            return "idle", local
        return a, at

    def draw_panel(self, frame: Image.Image, i: int, t: float) -> None:
        box = self.rect(i, t)
        if box is None:
            return
        p = self.occupant(i, t)
        x0, y0, x1, y1 = (round(v) for v in box)
        w, h = x1 - x0, y1 - y0
        bg, stage = self.stages[p.pet]
        # The stage always fills the full frame's proportions, drifting in, and the panel shows its middle.
        drift = 1.06 + 0.05 * ramp(t, p.enter, self.duration)
        fw, fh = max(w, round(h * 16 / 9)), max(h, round(w * 9 / 16))
        scene = fx.cover(bg, round(fw * drift), round(fh * drift))
        left, top = (scene.width - w) // 2, (scene.height - h) // 2
        panel = scene.crop((left, top, left + w, top + h)).convert("RGBA")
        floor = stage["floorY"] * scene.height - top
        cell_h = round(scene.height * 0.62 * sprites.manifest(p.pet).get("displayScale", 1.0))  # Chucky is a doll
        anim, at = self.anim_at(p, t)
        k = sprites.frame_at(p.pet, anim, at)
        cell = sprites.cell(p.pet, anim, k, cell_h)
        ax, by = sprites.anchor(p.pet, cell_h)
        # Alone, Leatherface stands right of the title; he slides to the middle of his panel as it splits.
        sx = (lerp(0.68, p.x, ramp(t, self.split, self.split + 0.22, out_cubic)) if p.pet == "leatherface" else p.x) * w
        shadow = Image.new("RGBA", (round(cell_h * 0.42), round(cell_h * 0.07)), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).ellipse([0, 0, shadow.width - 1, shadow.height - 1],
                                        fill=(0, 0, 0, round(255 * stage["shadowOpacity"])))
        fx.place(panel, shadow.filter(ImageFilter.GaussianBlur(cell_h * 0.015)), sx, floor)
        if p.pet == "jason" and t >= p.enter:
            self.draw_food(panel, t - p.enter, sx + cell_h * 0.3, floor, cell_h)
        panel.alpha_composite(cell, (round(sx - ax), round(floor - by)))
        flash = pulse(t, p.enter, 0.16)
        if flash > 0.02:
            panel = Image.alpha_composite(panel, Image.new("RGBA", panel.size, (255, 255, 255, round(200 * flash))))
        frame.paste(panel.convert("RGB"), (x0, y0))

    def draw_food(self, panel: Image.Image, t: float, x: float, floor: float, cell_h: int) -> None:
        """A drumstick dropped from above, bouncing once where it lands."""
        size = cell_h * 0.2
        food = fx.fit(self.food, size, size)
        drop = ramp(t, 0.0, 0.3, lambda u: u * u)
        y = lerp(-size, floor - size * 0.4, drop) - size * 0.35 * pulse(t, 0.3, 0.25) ** 0.5 * (t > 0.3)
        fx.place(panel, fx.transformed(food, 1.0, 25 * (1 - drop)), x, y)

    def frame(self, t: float) -> Image.Image:
        frame = Image.new("RGB", (W, H), INK)
        for slot in range(4):
            self.draw_panel(frame, slot, t)
        canvas = frame.convert("RGBA")
        self.title.draw(canvas, t)
        return canvas.convert("RGB")

    def cues(self) -> list[Cue]:
        return []
