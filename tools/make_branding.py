"""Compose the Android TV launcher banner and the app icon.

The banner is the "Virtual Pet Slasher 2 Turbo" cover: the key art in branding/cover-art.webp with the logo in
branding/logo.png laid over the open sky at the top (both made by tools/generate_cover.py and picked by eye).
The icon is Jason's mask, which still reads at 48 px.

On Android 8+ the launcher draws the adaptive icon (mipmap-anydpi-v26): a full-bleed background (the legacy icon's
dark red glow), Jason's mask alone on a transparent foreground, kept inside the 66 dp safe zone of the 108 dp layer
so no launcher's mask (circle, squircle, teardrop) crops it, and a monochrome copy of the mask for Android 13's
themed icons. Older launchers get the legacy square icon, and the round one where they ask for it (Android 7.1).

Outputs:
  app/src/main/res/drawable-{xhdpi,xxhdpi,xxxhdpi}/banner.png   320x180 / 480x270 / 640x360
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png         48 / 72 / 96 / 144 / 192 (legacy, API < 26)
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png   the same, cut to a circle (legacy, API 25)
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_{background,foreground,monochrome}.png
                                                                  108 / 162 / 216 / 324 / 432: the adaptive layers
  app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml     the adaptive icon (API 26+)
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
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
COVER_ART = ROOT / "branding/cover-art.webp"
LOGO = ROOT / "branding/logo.png"
INK = (7, 7, 10)
BLOOD = (193, 18, 31)
BANNERS = {"xhdpi": (320, 180), "xxhdpi": (480, 270), "xxxhdpi": (640, 360)}
ICONS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# The adaptive icon's layers are 108 dp. A launcher shows the middle 72 dp (the rest is room for its motion) and
# masks it; nothing inside a circle 66 dp across is ever cropped.
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
ADAPTIVE_MASTER = 864
VIEWPORT = 72 / 108
SAFE_RADIUS = 33 / 108
MASK_REACH = 0.29  # the mask's farthest pixel from the middle, of the layer: just inside the safe zone
# Jason's mask in his first idle cell (px): an oval around its middle that holds all of it, down to the chin.
# Outside it the art (his collar) fades out by MASK_FADE times its size; the one-color themed icon is the mask
# alone, so it fades out at once (MONO_FADE).
MASK_CENTER = (205, 90)
MASK_RADII = (62, 70)
MASK_FADE = 1.3
MONO_FADE = 1.06
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


def red_glow(size: int, cy: float, radius: float) -> Image.Image:
    """The icon's backdrop: dark blood red glowing from (size / 2, [cy]) out to ink at [radius] px, [size] square."""
    y, x = np.mgrid[0:size, 0:size]
    d = np.sqrt((x - size / 2) ** 2 + (y - cy) ** 2) / radius
    t = np.clip(d, 0, 1)[..., None]
    bg = (np.array(BLOOD) * 0.55 * (1 - t) + np.array(INK) * t).astype(np.uint8)
    return Image.fromarray(bg, "RGB")


def icon_master(size: int = 768) -> Image.Image:
    """Jason's mask and shoulders on a dark radial glow — the most recognizable silhouette at 48px."""
    base = red_glow(size, size * 0.42, size * 0.62).convert("RGBA")

    cell, _ = idle_frame("jason")
    x0, y0, x1, y1 = cell.getbbox()
    crop = cell.crop((x0, y0, x1, y0 + round((y1 - y0) * 0.62)))
    scale = size * 0.9 / crop.width if crop.width > crop.height else size * 0.92 / crop.height
    sprite = crop.resize((round(crop.width * scale), round(crop.height * scale)), Image.LANCZOS)
    base.alpha_composite(sprite, ((size - sprite.width) // 2, size - sprite.height + round(size * 0.02)))
    return base.convert("RGB")


def round_icon(icon: Image.Image) -> Image.Image:
    """[icon] cut to the circle that fills it, its edge smoothed (drawn 4x and scaled down)."""
    big = Image.new("L", (icon.width * 4, icon.height * 4), 0)
    ImageDraw.Draw(big).ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
    out = icon.convert("RGBA")
    out.putalpha(big.resize(icon.size, Image.LANCZOS))
    return out


def icon_background(size: int = ADAPTIVE_MASTER) -> Image.Image:
    """The adaptive icon's back layer: the legacy icon's glow, as big in the middle 72 dp a launcher shows."""
    top = size * (1 - VIEWPORT) / 2
    return red_glow(size, top + size * VIEWPORT * 0.42, size * VIEWPORT * 0.62)


def mask_cutout(fade: float = MASK_FADE) -> Image.Image:
    """Jason's mask from his first idle cell (the art is cut out already), his collar faded away by [fade]."""
    cell, _ = idle_frame("jason")
    y, x = np.mgrid[0:cell.height, 0:cell.width]
    d = np.sqrt(((x - MASK_CENTER[0]) / MASK_RADII[0]) ** 2 + ((y - MASK_CENTER[1]) / MASK_RADII[1]) ** 2)
    keep = np.clip((fade - d) / (fade - 1), 0, 1)
    rgba = np.array(cell)
    rgba[..., 3] = (rgba[..., 3] * keep).round().astype(np.uint8)
    out = Image.fromarray(rgba, "RGBA")
    return out.crop(out.getbbox())


def icon_foreground(size: int = ADAPTIVE_MASTER, fade: float = MASK_FADE) -> Image.Image:
    """The adaptive icon's front layer: the mask (see [mask_cutout]) in the middle, transparent round it, all of it
    within MASK_REACH of the middle."""
    cut = mask_cutout(fade)
    a = np.array(cut)[..., 3]
    ys, xs = np.nonzero(a > 0)
    far = np.sqrt((xs + 0.5 - cut.width / 2) ** 2 + (ys + 0.5 - cut.height / 2) ** 2).max()
    k = MASK_REACH * size / far
    cut = cut.resize((round(cut.width * k), round(cut.height * k)), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(cut, ((size - cut.width) // 2, (size - cut.height) // 2))
    return out


def icon_monochrome(fg: Image.Image) -> Image.Image:
    """Android 13's themed icon from a front layer [fg] of the mask alone (see MONO_FADE): the mask in white, its eye
    and air holes and its dark red marks see-through. The launcher tints it."""
    rgba = np.array(fg).astype(np.float64)
    lum = rgba[..., 0] * 0.299 + rgba[..., 1] * 0.587 + rgba[..., 2] * 0.114
    alpha = rgba[..., 3] * np.clip((lum - 70) / 90, 0, 1)
    out = np.full(rgba.shape, 255, np.uint8)
    out[..., 3] = alpha.round().astype(np.uint8)
    return Image.fromarray(out, "RGBA")


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- Made by tools/make_branding.py: the launcher icon on Android 8+ (the legacy PNGs serve older launchers). -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""


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
        small = icon.resize((px, px), Image.LANCZOS)
        small.save(d / "ic_launcher.png", optimize=True)
        round_icon(small).save(d / "ic_launcher_round.png", optimize=True)
        print(f"mipmap-{density}/ic_launcher.png, ic_launcher_round.png {px}x{px}")
    background = icon_background()
    foreground = icon_foreground()
    monochrome = icon_monochrome(icon_foreground(fade=MONO_FADE))
    for density, px in ADAPTIVE.items():
        d = RES / f"mipmap-{density}"
        for name, layer in (("background", background), ("foreground", foreground), ("monochrome", monochrome)):
            layer.resize((px, px), Image.LANCZOS).save(d / f"ic_launcher_{name}.png", optimize=True)
        print(f"mipmap-{density}/ic_launcher_{{background,foreground,monochrome}}.png {px}x{px}")
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        (anydpi / f"{name}.xml").write_text(ADAPTIVE_XML)
    print("mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
