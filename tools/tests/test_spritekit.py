import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from spritekit import (  # noqa: E402
    alpha_bbox,
    iou_vs_reference,
    key_magenta,
    register_to_reference,
    split_components,
)


def rgb(h, w, color):
    img = np.zeros((h, w, 3), dtype=np.uint8)
    img[:] = color
    return img


def test_key_magenta_pure_key_is_transparent():
    out = key_magenta(rgb(4, 4, (255, 0, 255)))
    assert out.shape == (4, 4, 4)
    assert (out[..., 3] == 0).all()


def test_key_magenta_keeps_subject_opaque():
    out = key_magenta(rgb(2, 2, (40, 60, 30)))
    assert (out[..., 3] == 255).all()
    assert (out[0, 0, :3] == [40, 60, 30]).all()


def test_key_magenta_despills_edge():
    out = key_magenta(rgb(1, 1, (200, 90, 200)))
    r, g, b = (int(v) for v in out[0, 0, :3])
    assert r - g < 40
    assert b - g < 40


def keyed_canvas(h, w):
    img = np.zeros((h, w, 4), dtype=np.uint8)
    return img


def test_split_components_orders_left_to_right_and_merges_specks():
    img = keyed_canvas(200, 520)
    for x in (10, 200, 400):
        img[40:160, x:x + 60] = (90, 90, 90, 255)
    img[100:105, 262:267] = (90, 90, 90, 255)  # speck just right of the 2nd block (200..260)
    img[100:105, 215:220] = (90, 90, 90, 255)  # speck inside the 2nd block's x-range
    crops = split_components(img, expected=3)
    assert len(crops) == 3
    # crops come back left→right; the middle one must include the in-range speck
    assert crops[1].shape[1] >= 60


def test_split_components_merges_speck_by_x_overlap():
    img = keyed_canvas(200, 520)
    for x in (10, 200, 400):
        img[60:160, x:x + 60] = (90, 90, 90, 255)
    img[20:25, 215:220] = (200, 200, 200, 255)  # floating speck above block 2
    crops = split_components(img, expected=3)
    mid = crops[1]
    # the speck sits 40px above the block, so the merged crop is taller than the block
    assert mid.shape[0] >= 140


def test_split_components_wrong_count_raises():
    img = keyed_canvas(200, 520)
    for x in (10, 200):
        img[40:160, x:x + 60] = (90, 90, 90, 255)
    with pytest.raises(ValueError):
        split_components(img, expected=3)


def test_register_matches_reference():
    ref = np.zeros((208, 192, 4), dtype=np.uint8)
    ref[40:190, 70:110] = (120, 30, 30, 255)
    scale = 3.6
    hi = np.zeros((int(150 * scale) + 20, int(40 * scale) + 20, 4), dtype=np.uint8)
    hi[10:10 + int(150 * scale), 10:10 + int(40 * scale)] = (120, 30, 30, 255)
    out = register_to_reference([hi], [ref], cell=(384, 416))
    assert len(out) == 1
    cell = out[0]
    assert cell.shape == (416, 384, 4)
    assert iou_vs_reference(cell, ref) >= 0.95
    _, _, _, bottom = alpha_bbox(cell)
    _, _, _, ref_bottom = alpha_bbox(ref)
    assert abs(bottom - 2 * ref_bottom) <= 2
