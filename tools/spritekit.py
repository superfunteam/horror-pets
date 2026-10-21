"""Pure image helpers for turning hi-res chroma-keyed generator rows into clean sprite cells.

All arrays are NumPy uint8, HxWx3 (RGB) or HxWx4 (RGBA).
"""
from __future__ import annotations

import numpy as np
from PIL import Image
from scipy import ndimage

KEY = np.array([255, 0, 255], dtype=np.float32)
KEY_INNER = 60.0   # distance to key at or below which a pixel is fully transparent
KEY_OUTER = 140.0  # distance to key at or above which a pixel is fully opaque
SPILL_SLACK = 8    # tolerated R/B excess over G before despill kicks in
ALPHA_THRESH = 16


def key_magenta(rgb: np.ndarray) -> np.ndarray:
    """Soft-key #FF00FF to alpha and remove magenta spill."""
    px = rgb[..., :3].astype(np.float32)
    dist = np.sqrt(((px - KEY) ** 2).sum(axis=-1))
    alpha = np.clip((dist - KEY_INNER) / (KEY_OUTER - KEY_INNER), 0.0, 1.0)

    out = np.empty(rgb.shape[:2] + (4,), dtype=np.uint8)
    out[..., :3] = rgb[..., :3]
    out[..., 3] = np.round(alpha * 255)
    return despill(out)


def despill(rgba: np.ndarray) -> np.ndarray:
    """Pull R and B down toward G wherever both exceed it (magenta spill); clear fully transparent RGB."""
    px = rgba[..., :3].astype(np.float32)
    r, g, b = px[..., 0], px[..., 1], px[..., 2]
    spill = np.maximum(0.0, np.minimum(r, b) - g - SPILL_SLACK)
    out = rgba.copy()
    out[..., 0] = np.clip(r - spill, 0, 255)
    out[..., 2] = np.clip(b - spill, 0, 255)
    out[out[..., 3] == 0, :3] = 0
    return out


def alpha_bbox(rgba: np.ndarray, thresh: int = ALPHA_THRESH) -> tuple[int, int, int, int]:
    """(x0, y0, x1, y1) of pixels with alpha > thresh; x1/y1 exclusive."""
    ys, xs = np.nonzero(rgba[..., 3] > thresh)
    if len(xs) == 0:
        raise ValueError("empty image")
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def split_components(rgba: np.ndarray, expected: int, min_area: int = 2000) -> list[np.ndarray]:
    """Split a row of poses into `expected` crops, ordered left to right.

    Blobs smaller than `min_area` (detached claw tips, sparks, specks) are merged into the
    frame whose x-range contains their x-center, or the nearest frame otherwise.
    """
    mask = rgba[..., 3] > ALPHA_THRESH
    labels, n = ndimage.label(mask, structure=np.ones((3, 3)))
    if n == 0:
        raise ValueError("no components")
    idx = np.arange(1, n + 1)
    areas = ndimage.sum(np.ones_like(labels), labels, idx).astype(int)
    slices = ndimage.find_objects(labels)

    order = np.argsort(-areas)
    big = [int(i) for i in order if areas[i] >= min_area]
    if len(big) < expected:
        raise ValueError(f"found {len(big)} large components, expected {expected}")
    if len(big) > expected:
        anchors = big[:expected]
        median = float(np.median([areas[i] for i in anchors]))
        if areas[big[expected]] > 0.35 * median:
            raise ValueError(f"found {len(big)} large components, expected {expected}")
        big = anchors
    big.sort(key=lambda i: (slices[i][1].start + slices[i][1].stop) / 2)

    groups: dict[int, list[int]] = {i: [i] for i in big}
    for i in range(n):
        if i in groups or areas[i] < 12:
            continue
        cx = (slices[i][1].start + slices[i][1].stop) / 2
        owner = None
        for b in big:
            if slices[b][1].start <= cx < slices[b][1].stop:
                owner = b
                break
        if owner is None:
            owner = min(big, key=lambda b: min(abs(cx - slices[b][1].start), abs(cx - slices[b][1].stop)))
        groups[owner].append(i)

    crops = []
    for b in big:
        members = groups[b]
        y0 = min(slices[m][0].start for m in members)
        y1 = max(slices[m][0].stop for m in members)
        x0 = min(slices[m][1].start for m in members)
        x1 = max(slices[m][1].stop for m in members)
        keep = np.isin(labels[y0:y1, x0:x1], [m + 1 for m in members])
        # keep the soft (sub-threshold) edge pixels that touch this frame's parts
        keep = ndimage.binary_dilation(keep, iterations=2)
        crop = rgba[y0:y1, x0:x1].copy()
        crop[~keep] = 0
        crops.append(crop)
    return crops


def resize_rgba(rgba: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    """Lanczos resize on premultiplied alpha so edges don't pick up dark halos."""
    img = Image.fromarray(rgba, "RGBA").convert("RGBa")
    out = np.asarray(img.resize(size, Image.LANCZOS).convert("RGBA")).copy()
    # near-transparent pixels carry garbage color after un-premultiplying; drop them
    out[out[..., 3] < 8] = 0
    return despill(out)


def _paste(canvas: np.ndarray, sprite: np.ndarray, x: int, y: int) -> None:
    h, w = sprite.shape[:2]
    ch, cw = canvas.shape[:2]
    sx0, sy0 = max(0, -x), max(0, -y)
    dx0, dy0 = max(0, x), max(0, y)
    dx1, dy1 = min(cw, x + w), min(ch, y + h)
    if dx1 <= dx0 or dy1 <= dy0:
        return
    canvas[dy0:dy1, dx0:dx1] = sprite[sy0:sy0 + (dy1 - dy0), sx0:sx0 + (dx1 - dx0)]


def _iou(a: np.ndarray, b: np.ndarray) -> float:
    union = np.logical_or(a, b).sum()
    return float(np.logical_and(a, b).sum() / union) if union else 0.0


def register_to_reference(hi: list[np.ndarray], ref_cells: list[np.ndarray],
                          cell: tuple[int, int] = (384, 416), search: int = 8) -> list[np.ndarray]:
    """Scale hi-res frames into `cell`-sized canvases that line up with the 2x low-res reference.

    One uniform scale per row (median of per-frame height ratios) avoids scale jitter; each frame
    starts bottom-center aligned to its reference bbox, then the translation is refined within
    ±`search` px by maximizing alpha IoU against the upscaled reference.
    """
    cw, ch = cell
    fx = cw / ref_cells[0].shape[1]
    fy = ch / ref_cells[0].shape[0]
    ratios = []
    for h, r in zip(hi, ref_cells):
        _, hy0, _, hy1 = alpha_bbox(h)
        _, ry0, _, ry1 = alpha_bbox(r)
        ratios.append((ry1 - ry0) * fy / (hy1 - hy0))
    scale = float(np.median(ratios))

    out = []
    for h, r in zip(hi, ref_cells):
        hx0, hy0, hx1, hy1 = alpha_bbox(h)
        body = h[hy0:hy1, hx0:hx1]
        size = (max(1, round(body.shape[1] * scale)), max(1, round(body.shape[0] * scale)))
        scaled = resize_rgba(body, size)

        rx0, _, rx1, ry1 = alpha_bbox(r)
        x = round((rx0 + rx1) / 2 * fx - size[0] / 2)
        y = round(ry1 * fy - size[1])

        ref_big = np.asarray(Image.fromarray(r[..., 3]).resize((cw, ch), Image.BILINEAR)) > 64
        best, best_xy = -1.0, (x, y)
        for dy in range(-search, search + 1):
            for dx in range(-search, search + 1):
                trial = np.zeros((ch, cw), dtype=bool)
                _paste(trial, scaled[..., 3] > 64, x + dx, y + dy)
                score = _iou(trial, ref_big)
                if score > best:
                    best, best_xy = score, (x + dx, y + dy)

        canvas = np.zeros((ch, cw, 4), dtype=np.uint8)
        _paste(canvas, scaled, *best_xy)
        out.append(canvas)
    return out


def iou_vs_reference(cell_2x: np.ndarray, ref_cell: np.ndarray) -> float:
    """Alpha IoU after downscaling the big cell to the reference cell's size."""
    rh, rw = ref_cell.shape[:2]
    small = np.asarray(Image.fromarray(cell_2x[..., 3]).resize((rw, rh), Image.BOX)) > 64
    return _iou(small, ref_cell[..., 3] > 64)


def fringe_ratio(rgba: np.ndarray) -> float:
    """Fraction of visible pixels that still read as magenta."""
    a = rgba[..., 3] > 0
    if not a.any():
        return 0.0
    px = rgba[..., :3].astype(int)
    pink = a & (px[..., 0] - px[..., 1] > 40) & (px[..., 2] - px[..., 1] > 40)
    return float(pink.sum() / a.sum())
