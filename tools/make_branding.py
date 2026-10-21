"""Compose the Android TV launcher banner and the app icon from the finished sprites and scenes.

Outputs:
  app/src/main/res/drawable-{xhdpi,xxhdpi,xxxhdpi}/banner.png   320x180 / 480x270 / 640x360
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png         48 / 72 / 96 / 144 / 192
  docs/banner.png                                                 1280x720 (README header)

Usage: tools/.venv/bin/python -I tools/make_branding.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
FONT = RES / "font/barlow_condensed_extrabold.ttf"
INK = (7, 7, 10)
BLOOD = (193, 18, 31)
GLOW = (255, 77, 87)
PETS = ["jason", "freddy", "pinhead", "ghostface"]
BANNERS = {"xhdpi": (320, 180), "xxhdpi": (480, 270), "xxxhdpi": (640, 360)}
ICONS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
MASTER = (1280, 720)


def idle_frame(pet: str) -> tuple[Image.Image, int]:
    """First idle cell of a pet and its foot baseline (cell coordinates)."""
    m = json.loads((ROOT / f"sprites-final/{pet}/manifest.json").read_text())
    cw, ch = m["cell"]
    strip = Image.open(ROOT / f"sprites-final/{pet}/idle.png").convert("RGBA")
    return strip.crop((0, 0, cw, ch)), m["baselineY"]


def vignette(size: tuple[int, int], strength: float) -> Image.Image:
    w, h = size
    y, x = np.mgrid[0:h, 0:w]
    d = np.sqrt(((x - w / 2) / (w / 2)) ** 2 + ((y - h / 2) / (h / 2)) ** 2)
    a = np.clip((d - 0.35) / 0.9, 0, 1) ** 1.4 * strength
    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[..., :3] = INK
    out[..., 3] = (a * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def glow_text(base: Image.Image, text: str, size: int, center: tuple[int, int], tracking: float) -> None:
    """White Barlow ExtraBold title with a soft blood-red glow and a dark drop shadow."""
    font = ImageFont.truetype(str(FONT), size)
    widths = [font.getlength(c) for c in text]
    total = sum(widths) + tracking * size * (len(text) - 1)
    x0 = center[0] - total / 2
    ascent, descent = font.getmetrics()
    y0 = center[1] - (ascent + descent) / 2

    def draw_on(layer: Image.Image, fill, offset=(0, 0)) -> None:
        d = ImageDraw.Draw(layer)
        x = x0
        for c, cw in zip(text, widths):
            d.text((x + offset[0], y0 + offset[1]), c, font=font, fill=fill)
            x += cw + tracking * size

    glow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw_on(glow, GLOW + (255,))
    base.alpha_composite(glow.filter(ImageFilter.GaussianBlur(size * 0.18)))
    base.alpha_composite(glow.filter(ImageFilter.GaussianBlur(size * 0.06)))
    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw_on(shadow, (0, 0, 0, 200), (0, size * 0.04))
    base.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(size * 0.03)))
    face = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw_on(face, (242, 242, 242, 255))
    base.alpha_composite(face)


def banner_master() -> Image.Image:
    w, h = MASTER
    scene = Image.open(ROOT / "scenes-final/pinhead/chamber.webp").convert("RGB")
    sw, sh = scene.size
    ch = round(sw * h / w)
    scene = scene.crop((0, (sh - ch) // 2, sw, (sh - ch) // 2 + ch)).resize((w, h), Image.LANCZOS)
    scene = ImageEnhance.Brightness(scene).enhance(0.42)
    scene = ImageEnhance.Color(scene).enhance(0.8)
    base = scene.convert("RGBA")
    base.alpha_composite(vignette((w, h), 0.85))

    floor_y = round(h * 0.93)
    cell_h = round(h * 0.62)
    for i, pet in enumerate(PETS):
        cell, baseline = idle_frame(pet)
        scale = cell_h / cell.height
        sprite = cell.resize((round(cell.width * scale), cell_h), Image.LANCZOS)
        cx = round(w * (0.2 + 0.2 * i))
        pool = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        pw = round(sprite.width * 0.55)
        ImageDraw.Draw(pool).ellipse((cx - pw, floor_y - pw // 6, cx + pw, floor_y + pw // 6), fill=BLOOD + (90,))
        base.alpha_composite(pool.filter(ImageFilter.GaussianBlur(18)))
        shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        sw2 = round(sprite.width * 0.32)
        ImageDraw.Draw(shadow).ellipse((cx - sw2, floor_y - sw2 // 7, cx + sw2, floor_y + sw2 // 7), fill=(0, 0, 0, 170))
        base.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(6)))
        base.alpha_composite(sprite, (cx - sprite.width // 2, floor_y - round(baseline * scale)))

    glow_text(base, "HORROR PETS", round(h * 0.21), (w // 2, round(h * 0.17)), 0.06)
    return base.convert("RGB")


def icon_master(size: int = 768) -> Image.Image:
    """Jason's mask and shoulders on a dark radial glow — the most recognizable silhouette at 48px."""
    y, x = np.mgrid[0:size, 0:size]
    d = np.sqrt((x - size / 2) ** 2 + (y - size * 0.42) ** 2) / (size * 0.62)
    t = np.clip(d, 0, 1)[..., None]
    bg = (np.array(BLOOD) * 0.55 * (1 - t) + np.array(INK) * t).astype(np.uint8)
    base = Image.fromarray(bg, "RGB").convert("RGBA")

    cell, _ = idle_frame("jason")
    x0, y0, x1, y1 = cell.getbbox()
    crop = cell.crop((x0, y0, x1, y0 + round((y1 - y0) * 0.62)))
    scale = size * 0.9 / crop.width if crop.width > crop.height else size * 0.92 / crop.height
    sprite = crop.resize((round(crop.width * scale), round(crop.height * scale)), Image.LANCZOS)
    base.alpha_composite(sprite, ((size - sprite.width) // 2, size - sprite.height + round(size * 0.02)))
    return base.convert("RGB")


def main() -> int:
    master = banner_master()
    (ROOT / "docs").mkdir(exist_ok=True)
    master.save(ROOT / "docs/banner.png", optimize=True)
    for density, size in BANNERS.items():
        d = RES / f"drawable-{density}"
        d.mkdir(parents=True, exist_ok=True)
        master.resize(size, Image.LANCZOS).save(d / "banner.png", optimize=True)
        print(f"drawable-{density}/banner.png {size[0]}x{size[1]}")
    icon = icon_master()
    for density, px in ICONS.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        icon.resize((px, px), Image.LANCZOS).save(d / "ic_launcher.png", optimize=True)
        print(f"mipmap-{density}/ic_launcher.png {px}x{px}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
