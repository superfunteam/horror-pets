"""Image effects for scenes. Layers are PIL RGBA images placed by their center; whole frames are RGB.

Most of these are built once per scene (textures, glows, text) and only placed, blended or shifted per frame.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ALPHA_FLOOR = 8  # specks fainter than this are the image model's haze


def trim(im: Image.Image) -> Image.Image:
    """[im] cropped to its visible pixels."""
    a = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im = im.copy()
    im.putalpha(a)
    return im.crop(a.getbbox())


def fit(im: Image.Image, w: float, h: float) -> Image.Image:
    """[im] scaled to fit inside w x h."""
    s = min(w / im.width, h / im.height)
    return im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)


def cover(im: Image.Image, w: int, h: int) -> Image.Image:
    """[im] scaled to cover w x h and cropped to it, centered."""
    s = max(w / im.width, h / im.height)
    im = im.resize((math.ceil(im.width * s), math.ceil(im.height * s)), Image.LANCZOS)
    x, y = (im.width - w) // 2, (im.height - h) // 2
    return im.crop((x, y, x + w, y + h))


def faded(im: Image.Image, alpha: float) -> Image.Image:
    """[im] with its alpha scaled by [alpha]."""
    if alpha >= 1:
        return im
    out = im.copy()
    out.putalpha(im.getchannel("A").point(lambda v: round(v * alpha)))
    return out


def silhouette(im: Image.Image, color: tuple[int, int, int]) -> Image.Image:
    """[im]'s shape, flat in [color]."""
    out = Image.new("RGBA", im.size, color + (0,))
    out.putalpha(im.getchannel("A"))
    return out


def tinted(im: Image.Image, color: tuple[int, int, int], amount: float) -> Image.Image:
    """[im] washed toward [color] by [amount], keeping its shape and detail."""
    flat = Image.new("RGBA", im.size, color + (255,))
    out = Image.blend(im, flat, amount)
    out.putalpha(im.getchannel("A"))
    return out


def glow(im: Image.Image, color: tuple[int, int, int], radius: int, strength: float = 1.0) -> Image.Image:
    """[im] over a soft halo of [color], padded by [radius] * 2 on every side so the halo is not cut off."""
    pad = radius * 2
    out = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), color + (0,))
    a = Image.new("L", out.size, 0)
    a.paste(im.getchannel("A"), (pad, pad))
    a = a.filter(ImageFilter.GaussianBlur(radius)).point(lambda v: min(255, round(v * strength)))
    out.putalpha(a)
    out.alpha_composite(im, (pad, pad))
    return out


def shadowed(im: Image.Image, dy: int, radius: int, alpha: float) -> Image.Image:
    """[im] over a soft black shadow [dy] px below it, padded evenly so its center stays where it was."""
    pad = radius * 2 + abs(dy)
    out = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    a = Image.new("L", out.size, 0)
    a.paste(im.getchannel("A"), (pad, pad + dy))
    out.putalpha(a.filter(ImageFilter.GaussianBlur(radius)).point(lambda v: round(v * alpha)))
    out.alpha_composite(im, (pad, pad))
    return out


def transformed(im: Image.Image, scale: float = 1.0, angle: float = 0.0) -> Image.Image:
    """[im] scaled, then turned [angle] degrees counterclockwise about its center."""
    if abs(scale - 1) > 1e-3:
        im = im.resize((max(1, round(im.width * scale)), max(1, round(im.height * scale))), Image.BILINEAR)
    if abs(angle) > 0.05:
        im = im.rotate(angle, Image.BILINEAR, expand=True)
    return im


def place(canvas: Image.Image, im: Image.Image, cx: float, cy: float) -> None:
    """Composites [im] onto [canvas] centered at (cx, cy); whatever falls off the edges is dropped."""
    x, y = round(cx - im.width / 2), round(cy - im.height / 2)
    left, top = max(0, -x), max(0, -y)
    right, bottom = min(im.width, canvas.width - x), min(im.height, canvas.height - y)
    if right <= left or bottom <= top:
        return
    if (left, top, right, bottom) != (0, 0, im.width, im.height):
        im = im.crop((left, top, right, bottom))
    canvas.alpha_composite(im, (x + left, y + top))


def add_light(canvas: Image.Image, im: Image.Image, x: int, y: int, amount: float = 1.0) -> None:
    """Adds [im]'s color, weighted by its alpha and [amount], onto [canvas] at (x, y): light, never darkening."""
    box = (max(0, x), max(0, y), min(canvas.width, x + im.width), min(canvas.height, y + im.height))
    if box[2] <= box[0] or box[3] <= box[1]:
        return
    src = np.asarray(im.crop((box[0] - x, box[1] - y, box[2] - x, box[3] - y)), dtype=np.float32)
    dst = np.asarray(canvas.crop(box), dtype=np.float32)
    rgb = dst[..., :3] + src[..., :3] * (src[..., 3:4] / 255.0) * amount
    out = np.concatenate([np.clip(rgb, 0, 255), dst[..., 3:4]], axis=2).astype(np.uint8)
    canvas.paste(Image.fromarray(out, "RGBA"), box[:2])


def speed_lines(w: int, h: int, n: int, seed: int, color=(255, 255, 255)) -> Image.Image:
    """Horizontal streaks tapering to the left, over w x h, tiling across its width so it can scroll."""
    rng = random.Random(seed)
    out = Image.new("RGBA", (w, h), color + (0,))
    a = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(a)
    for _ in range(n):
        y, length, th = rng.uniform(0, h), rng.uniform(w * 0.08, w * 0.4), rng.uniform(1.5, 7)
        x = rng.uniform(0, w)
        for dx in (0, -w):  # drawn twice so a streak running off the right edge comes back on the left
            d.polygon([(x + dx, y), (x + dx + length, y - th / 2), (x + dx + length, y + th / 2)], fill=rng.randint(60, 200))
    out.putalpha(a.filter(ImageFilter.GaussianBlur(1.2)))
    return out


def focus_lines(w: int, h: int, cx: float, cy: float, clear: float, n: int, seed: int, color=(255, 255, 255)) -> Image.Image:
    """Manga focus lines: thin wedges from beyond the frame toward (cx, cy), leaving an oval of [clear] open."""
    rng = random.Random(seed)
    a = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(a)
    far = math.hypot(w, h)
    for _ in range(n):
        ang = rng.uniform(0, 2 * math.pi)
        half = rng.uniform(0.004, 0.018)
        r = clear * rng.uniform(1.0, 1.7)
        tip = (cx + math.cos(ang) * r * 1.6, cy + math.sin(ang) * r)
        d.polygon([tip, (cx + math.cos(ang - half) * far, cy + math.sin(ang - half) * far),
                   (cx + math.cos(ang + half) * far, cy + math.sin(ang + half) * far)], fill=rng.randint(90, 230))
    out = Image.new("RGBA", (w, h), color + (0,))
    out.putalpha(a)
    return out


def soft_dot(radius: int, color: tuple[int, int, int]) -> Image.Image:
    """A glowing ember: a bright core in a soft halo, 4 * radius across."""
    size = radius * 4
    a = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(a)
    c = size / 2
    d.ellipse([c - radius, c - radius, c + radius, c + radius], fill=255)
    a = a.filter(ImageFilter.GaussianBlur(radius * 0.8))
    core = Image.new("L", (size, size), 0)
    ImageDraw.Draw(core).ellipse([c - radius / 2, c - radius / 2, c + radius / 2, c + radius / 2], fill=255)
    out = Image.new("RGBA", (size, size), color + (0,))
    out.putalpha(ImageChops.lighter(a, core))
    return out


def vignette(w: int, h: int, strength: float) -> Image.Image:
    """Black that darkens the frame's edges and corners by up to [strength]."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2)) / math.sqrt(2)
    a = np.clip((r - 0.45) / 0.55, 0, 1) ** 1.6 * strength * 255
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.putalpha(Image.fromarray(a.astype(np.uint8), "L"))
    return out


def radial(w: int, h: int, color: tuple[int, int, int], alpha: float) -> Image.Image:
    """A soft oval of [color], strongest in the middle, filling w x h."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2))
    a = np.clip(1 - r, 0, 1) ** 2 * alpha * 255
    out = Image.new("RGBA", (w, h), color + (0,))
    out.putalpha(Image.fromarray(a.astype(np.uint8), "L"))
    return out


def chroma(frame: Image.Image, px: float) -> Image.Image:
    """The RGB split of a hard hit: red pushed [px] left and blue [px] right."""
    n = round(px)
    if n < 1:
        return frame
    r, g, b = frame.split()
    return Image.merge("RGB", (ImageChops.offset(r, -n, 0), g, ImageChops.offset(b, n, 0)))


def smear(frame: Image.Image, px: float) -> Image.Image:
    """Horizontal motion blur [px] long, for a whip pan."""
    n = round(px)
    if n < 2:
        return frame
    a = np.asarray(frame, dtype=np.float32)
    c = np.cumsum(np.pad(a, ((0, 0), (n, 0), (0, 0)), mode="edge"), axis=1)
    out = (c[:, n:] - c[:, :-n]) / n
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGB")


def flash(frame: Image.Image, color: tuple[int, int, int], amount: float) -> Image.Image:
    """[frame] washed toward [color] by [amount]."""
    if amount <= 0.004:
        return frame
    return Image.blend(frame, Image.new("RGB", frame.size, color), min(1.0, amount))


class Embers:
    """Sparks drifting up through the frame in [tones], some meant to go behind the cast and some in front."""

    def __init__(self, n: int, seed: int, tones: list[tuple[int, int, int]], w: int, h: int):
        rng = random.Random(seed)
        self.w, self.h = w, h
        self.sprites = [[soft_dot(r, c) for c in tones] for r in (2, 3, 5)]
        self.items = [
            dict(x=rng.uniform(0, w), y=rng.uniform(0, h), vy=rng.uniform(50, 190), sway=rng.uniform(8, 40),
                 freq=rng.uniform(0.8, 2.4), phase=rng.uniform(0, 6.3), size=rng.choices([0, 1, 2], [5, 3, 1])[0],
                 tone=rng.randrange(len(tones)), front=rng.random() < 0.3)
            for _ in range(n)
        ]

    def draw(self, canvas: Image.Image, t: float, front: bool, dx: float = 0, dy: float = 0) -> None:
        """The embers at [t] that go [front] of the cast (or behind it), shifted by (dx, dy); the front ones bigger."""
        for e in self.items:
            if e["front"] != front:
                continue
            y = (e["y"] - e["vy"] * t) % (self.h + 120) - 60
            x = e["x"] + e["sway"] * math.sin(e["freq"] * t + e["phase"])
            place(canvas, self.sprites[min(2, e["size"] + front)][e["tone"]], x + dx, y + dy)
