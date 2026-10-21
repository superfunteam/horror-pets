"""Compose the Android TV launcher banner and the app icon.

The banner is the "Virtual Pet Slasher 2 Turbo" cover: the key art in branding/cover-art.webp with the logo in
branding/logo.png laid over the open sky at the top (both made by tools/generate_cover.py and picked by eye).
The icon is Jason's mask, which still reads at 48 px.

Outputs:
  app/src/main/res/drawable-{xhdpi,xxhdpi,xxxhdpi}/banner.png   320x180 / 480x270 / 640x360
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png         48 / 72 / 96 / 144 / 192
  docs/banner.png                                                 1280x720 (README header)
  sprites-final/title/{cover,logo}.webp                           the title screen's art and logo, apart, so the
                                                                  screen can crop the art and size the logo to fit

Usage: tools/.venv/bin/python -I tools/make_branding.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
COVER_ART = ROOT / "branding/cover-art.webp"
LOGO = ROOT / "branding/logo.png"
INK = (7, 7, 10)
BLOOD = (193, 18, 31)
BANNERS = {"xhdpi": (320, 180), "xxhdpi": (480, 270), "xxxhdpi": (640, 360)}
ICONS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
MASTER = (1280, 720)
TITLE_ART = (1920, 1080)
TITLE_LOGO_WIDTH = 1200
LOGO_WIDTH = 0.58  # of the cover width
LOGO_TOP = 0.04    # of the cover height
HALO_OPACITY = 0.75


def idle_frame(pet: str) -> tuple[Image.Image, int]:
    """First idle cell of a pet and its foot baseline (cell coordinates)."""
    m = json.loads((ROOT / f"sprites-final/{pet}/manifest.json").read_text())
    cw, ch = m["cell"]
    strip = Image.open(ROOT / f"sprites-final/{pet}/idle.png").convert("RGBA")
    return strip.crop((0, 0, cw, ch)), m["baselineY"]


def banner_master() -> Image.Image:
    """Key art with the logo centered over the open sky, plus a soft dark halo so it reads at 320x180."""
    art = Image.open(COVER_ART).convert("RGBA").resize(MASTER, Image.LANCZOS)
    w, h = art.size
    logo = Image.open(LOGO).convert("RGBA")
    logo = logo.crop(logo.getbbox())
    lw = round(w * LOGO_WIDTH)
    logo = logo.resize((lw, round(logo.height * lw / logo.width)), Image.LANCZOS)
    x, y = (w - lw) // 2, round(h * LOGO_TOP)

    shadow = Image.new("L", (w, h), 0)
    shadow.paste(logo.getchannel("A"), (x, y))
    shadow = shadow.filter(ImageFilter.GaussianBlur(w * 0.02))
    halo = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    halo.putalpha(shadow.point(lambda v: round(v * HALO_OPACITY)))
    art.alpha_composite(halo)
    art.alpha_composite(logo, (x, y))
    return art.convert("RGB")


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
    title = ROOT / "sprites-final/title"
    title.mkdir(parents=True, exist_ok=True)
    Image.open(COVER_ART).convert("RGB").resize(TITLE_ART, Image.LANCZOS).save(title / "cover.webp", "WEBP", quality=85, method=6)
    logo = Image.open(LOGO).convert("RGBA")
    logo = logo.crop(logo.getbbox())
    logo = logo.resize((TITLE_LOGO_WIDTH, round(logo.height * TITLE_LOGO_WIDTH / logo.width)), Image.LANCZOS)
    logo.save(title / "logo.webp", "WEBP", quality=90, alpha_quality=100, method=6)
    print("sprites-final/title/cover.webp, logo.webp")
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
