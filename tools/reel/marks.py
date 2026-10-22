"""The marks a learned attack leaves, drawn the way the game draws them (ui/fight/AttackMarks.kt): Jason's machete
arcs, Freddy's claw rakes, Pinhead's hooked chains, Ghostface's knife slashes, Chucky's stabs and voodoo pins, Michael's
long white-hot cut and Leatherface's jagged saw tears, more and bigger with each slot and tier.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

from PIL import Image, ImageDraw, ImageFilter

from . import fx

# Each killer's ink: outer glow, body, bright core (AttackMarks.kt inkOf).
INK = {
    "jason": ((255, 45, 58), (193, 18, 31), (255, 214, 217)),
    "freddy": ((99, 242, 90), (46, 158, 42), (230, 255, 217)),
    "pinhead": ((127, 178, 255), (138, 147, 166), (233, 238, 248)),
    "ghostface": ((155, 155, 168), (218, 218, 226), (255, 255, 255)),
    "chucky": ((178, 77, 255), (89, 255, 140), (241, 230, 255)),
    "michael": ((255, 179, 160), (242, 242, 242), (255, 255, 255)),
    "leatherface": ((255, 138, 31), (224, 38, 47), (255, 224, 138)),
}


@dataclass(frozen=True)
class Plan:
    """How many marks, how long (a share of the body's height) and thick, an impact ring, sparks, a color flash."""
    count: int
    length: float
    width: float
    ring: bool
    sparks: int
    flash: bool


def plan(slot: str, tier: int) -> Plan | None:
    """AttackMarks.kt markPlan: nothing at tier 0, then more and bigger marks each tier."""
    if tier == 0:
        return None
    if slot == "light":
        return Plan(tier, 0.45, 4, False, 14 if tier == 3 else 0, False)
    if slot == "heavy":
        return Plan(3 if tier == 3 else tier, 0.72, 7, True, 10 + 8 * tier if tier >= 2 else 0, tier == 3)
    return Plan(1 + tier, 0.85, 6, tier >= 2, 16 * tier if tier >= 2 else 0, tier >= 2)


def hash01(seed: int, salt: int) -> float:
    x = (seed * 374761393 + salt * 668265263) & 0xFFFFFFFF
    x = ((x ^ (x >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((x ^ (x >> 16)) & 0xFFFFFF) / 0xFFFFFF


def _quad(a, mid, b, n=18):
    return [((1 - t) ** 2 * a[0] + 2 * (1 - t) * t * mid[0] + t * t * b[0],
             (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * mid[1] + t * t * b[1]) for t in (i / n for i in range(n + 1))]


def _rgba(c, a):
    return c + (max(0, min(255, round(255 * a))),)


class _Pen:
    """Strokes into a sharp layer and a glow layer (blurred and added on top at the end)."""

    def __init__(self, size):
        self.sharp = Image.new("RGBA", size, (0, 0, 0, 0))
        self.glow = Image.new("RGBA", size, (0, 0, 0, 0))
        self.ds, self.dg = ImageDraw.Draw(self.sharp), ImageDraw.Draw(self.glow)

    def line(self, pts, color, alpha, width, glow=False):
        d = self.dg if glow else self.ds
        w = max(1, round(width))
        d.line(pts, fill=_rgba(color, alpha), width=w, joint="curve")
        for x, y in (pts[0], pts[-1]):  # round caps
            d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=_rgba(color, alpha))

    def layered(self, pts, alpha, width, ink):
        glow, body, core = ink
        self.line(pts, glow, alpha * 0.5, width * 2.6, glow=True)
        self.line(pts, body, alpha, width)
        self.line(pts, core, alpha, width * 0.35)


def draw(canvas: Image.Image, pet: str, slot: str, tier: int, cx: float, cy: float, body_h: float, p: float,
         seed: int = 0, unit: float = 2.0) -> None:
    """[pet]'s [slot] tier [tier] marks at [p] (0..1 through the showing) on a body centered at (cx, cy), body_h
    tall, onto [canvas] (RGBA). [unit] is how many pixels a dp is, as the marks' widths are in dp."""
    pl = plan(slot, tier)
    if pl is None or not 0 < p < 1:
        return
    ink = INK[pet]
    glow_c, body_c, core_c = ink
    pen = _Pen(canvas.size)
    fade = min(1.0, max(0.0, (1 - p) / 0.34)) if p > 0.66 else 1.0
    if pl.flash and p < 0.35:  # a soft flash of the killer's color behind the cuts
        r = round(body_h * 0.9)
        fx.place(canvas, fx.radial(2 * r, 2 * r, glow_c, (1 - p / 0.35) * 0.45), cx, cy)
    if pl.ring:
        r01 = min(1.0, p / 0.45)
        if r01 < 1:
            r = body_h * (0.15 + 0.45 * r01)
            pen.ds.ellipse([cx - r, cy - r, cx + r, cy + r], outline=_rgba(core_c, (1 - r01) * 0.8),
                           width=max(1, round(unit * (6 - 4 * r01))))
    length, width = body_h * pl.length, pl.width * unit
    for i in range(pl.count):
        start = 0.0 if pl.count <= 1 else 0.32 * i / (pl.count - 1)
        cut = min(1.0, max(0.0, (p - start) / 0.14))
        if cut <= 0:
            continue
        if slot == "heavy" and pl.count == 3:
            base = [-40, 40, 0][i]
        else:
            base = -55 + 110 * hash01(seed + i * 17, 3) + (90 if i % 2 == 1 else 0)
        ang = math.radians(base)
        ox = (hash01(seed + i * 29, 5) - 0.5) * body_h * 0.25
        oy = (hash01(seed + i * 31, 7) - 0.5) * body_h * 0.3
        dx, dy = math.cos(ang), math.sin(ang)
        a = (cx + ox - dx * length / 2, cy + oy - dy * length / 2)
        b = (cx + ox + dx * length / 2, cy + oy + dy * length / 2)
        _mark(pen, pet, a, b, cut, fade, width, ink, seed + i)
    if pl.sparks:
        t = min(1.0, max(0.0, (p - 0.08) / 0.8))
        if 0 < t < 1:
            for k in range(pl.sparks):
                ang = hash01(seed + k * 7, 13) * 2 * math.pi
                speed = body_h * (0.35 + 0.5 * hash01(seed + k * 7, 17))
                x = cx + math.cos(ang) * speed * t
                y = cy + math.sin(ang) * speed * t + body_h * 0.5 * t * t
                r = unit * (1.5 + 2 * hash01(seed + k, 19)) * (1 - t * 0.6)
                pen.ds.ellipse([x - r, y - r, x + r, y + r], fill=_rgba(core_c if k % 3 == 0 else glow_c, 1 - t))
    glow = pen.glow.filter(ImageFilter.GaussianBlur(max(2, width * 0.8)))
    canvas.alpha_composite(glow)
    canvas.alpha_composite(pen.sharp)


def _mark(pen: _Pen, pet, a, b, cut, alpha, width, ink, seed):
    glow_c, body_c, core_c = ink
    d = (b[0] - a[0], b[1] - a[1])
    dist = max(1.0, math.hypot(*d))
    nx, ny = -d[1] / dist, d[0] / dist
    end = (a[0] + d[0] * cut, a[1] + d[1] * cut)
    if pet == "jason":  # a thick bowed machete arc
        mid = ((a[0] + end[0]) / 2 + nx * dist * 0.12 * cut, (a[1] + end[1]) / 2 + ny * dist * 0.12 * cut)
        pen.layered(_quad(a, mid, end), alpha, width * 1.3, ink)
    elif pet == "freddy":  # four claws raking side by side
        for k in range(-1, 3):
            s = width * 2.4 * (k - 0.5)
            aa, ee = (a[0] + nx * s, a[1] + ny * s), (end[0] + nx * s, end[1] + ny * s)
            mid = ((aa[0] + ee[0]) / 2 + nx * dist * 0.06, (aa[1] + ee[1]) / 2 + ny * dist * 0.06)
            pen.layered(_quad(aa, mid, ee), alpha, width * 0.6, ink)
    elif pet == "pinhead":  # a chain of links ending in a hook
        links = 9
        for k in range(max(1, int(links * cut))):
            c = (a[0] + d[0] * k / links, a[1] + d[1] * k / links)
            pen.ds.ellipse([c[0] - width * 1.1, c[1] - width * 0.7, c[0] + width * 1.1, c[1] + width * 0.7],
                           outline=_rgba(body_c, alpha), width=max(1, round(width * 0.45)))
        if cut >= 1:
            hook = _quad(b, (b[0] + nx * width * 4, b[1] + ny * width * 4), (b[0] - d[0] * 0.08, b[1] - d[1] * 0.08))
            pen.line(hook, core_c, alpha, width * 0.6)
    elif pet == "ghostface":  # a thin clean slash and a couple of stab flecks
        pen.layered([a, end], alpha, width * 0.6, ink)
        if cut >= 1:
            for k in range(2):
                t = 0.3 + 0.4 * hash01(seed + k, 11)
                s = width * 3 * (1 if k == 0 else -1)
                c = (a[0] + d[0] * t + nx * s, a[1] + d[1] * t + ny * s)
                pen.line([c, (c[0] + d[0] * 0.05, c[1] + d[1] * 0.05)], core_c, alpha, width * 0.5)
    elif pet == "chucky":  # short stabs, each with a voodoo pin's head
        for k in range(3):
            t0 = k / 3
            if cut < t0:
                break
            c = (a[0] + d[0] * (t0 + 0.12), a[1] + d[1] * (t0 + 0.12))
            tip = (c[0] + d[1] * 0.12, c[1] - d[0] * 0.12)
            pen.line([c, tip], glow_c, alpha * 0.6, width * 1.4)
            pen.line([c, tip], core_c, alpha, width * 0.5)
            r = width * 0.9
            pen.ds.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=_rgba(body_c, alpha))
    elif pet == "michael":  # one long white-hot diagonal
        far = (a[0] - d[0] * 0.2, a[1] - d[1] * 0.2)
        pen.layered([far, (far[0] + d[0] * 1.4 * cut, far[1] + d[1] * 1.4 * cut)], alpha, width * 1.1, ink)
    else:  # leatherface: a jagged saw tear
        teeth = 10
        pts = [a]
        for k in range(1, max(1, int(teeth * cut)) + 1):
            t = k / teeth
            s = width * 1.6 * (1 if k % 2 == 0 else -1)
            pts.append((a[0] + d[0] * t + nx * s, a[1] + d[1] * t + ny * s))
        pen.layered(pts, alpha, width * 0.8, ink)
