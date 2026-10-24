import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from intake_sprites import IntakeError, align_baselines, ground_row, scale_refs, shift_down  # noqa: E402


def cell_with_feet(foot: int, h: int = 40, w: int = 20) -> np.ndarray:
    """Opaque 10px-tall block whose bottom edge (exclusive) is row `foot`."""
    img = np.zeros((h, w, 4), dtype=np.uint8)
    img[foot - 10:foot, 5:15] = (90, 90, 90, 255)
    return img


def anim(*feet: int) -> dict:
    return {"cells": [cell_with_feet(f) for f in feet]}


def test_shift_down_zero_fills_instead_of_wrapping():
    img = np.zeros((10, 4, 4), dtype=np.uint8)
    img[0] = 255   # content on the top row
    img[9] = 200   # content on the bottom row
    down = shift_down(img, 3)
    assert (down[:3] == 0).all()       # vacated rows are transparent, not the old bottom rows
    assert (down[3] == 255).all()
    up = shift_down(img, -3)
    assert (up[-3:] == 0).all()        # the old top row must not reappear at the bottom
    assert (up[6] == 200).all()
    assert shift_down(img, 0).tolist() == img.tolist()


def test_ground_row_uses_median_except_for_jump():
    cells = [cell_with_feet(f) for f in (30, 30, 30, 20, 20)]
    assert ground_row("run-right", cells) == 30   # median, not the extremes
    assert ground_row("jump", cells) == 30
    airborne = [cell_with_feet(f) for f in (30, 20, 20, 20, 20)]
    assert ground_row("jump", airborne) == 30     # lowest point: the takeoff/landing frame
    assert ground_row("wave", airborne) == 20     # median ignores the odd low frame


def test_align_baselines_shifts_onto_idle_and_logs():
    anims = {"idle": anim(30, 30), "wave": anim(28, 28, 28)}
    log: list[str] = []
    assert align_baselines("pet", anims, log) == 30
    assert ground_row("wave", anims["wave"]["cells"]) == 30
    assert any("wave" in line and "+2px" in line for line in log)


def test_align_baselines_rejects_large_drift():
    anims = {"idle": anim(30), "wave": anim(10)}
    with pytest.raises(IntakeError):
        align_baselines("pet", anims, [])


def test_a_swimmer_stands_on_his_lowest_point_in_every_animation():
    # Jaws has no feet: his fins flap up and down, so each animation's ground is its lowest fin tip, as in his sheet.
    flapping = [cell_with_feet(f) for f in (30, 20, 20, 20, 20)]
    assert ground_row("run-right", flapping) == 20
    assert ground_row("run-right", flapping, lowest=True) == 30
    anims = {"idle": anim(30, 29, 30), "run-right": {"cells": flapping}}
    log: list[str] = []
    assert align_baselines("pet", anims, log, lowest=True) == 30
    assert ground_row("run-right", anims["run-right"]["cells"], lowest=True) == 30
    assert not any("run-right" in line for line in log)  # already on idle's ground: no shift


def test_a_ground_offset_lines_up_his_body_not_the_effects_hanging_below_it():
    # Swooshes hang 6px below him: his own ground is 6px above the animation's lowest pixels.
    anims = {"idle": anim(30, 30), "thrash": {"cells": [cell_with_feet(32)], "spec": {"groundOffset": 6}}}
    log: list[str] = []
    align_baselines("pet", anims, log, lowest=True)
    assert ground_row("thrash", anims["thrash"]["cells"]) == 36  # his body (26) sits on idle's ground (30)
    assert any("thrash" in line and "+4px" in line for line in log)


def test_scale_refs_scales_a_whole_row_about_its_bottom_centre():
    def block(x0, y0, x1, y1):
        img = np.zeros((40, 40, 4), dtype=np.uint8)
        img[y0:y1, x0:x1] = (90, 90, 90, 255)
        return img

    def bbox(img):
        ys, xs = np.nonzero(img[..., 3] > 64)
        return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1

    row = [block(10, 10, 30, 30), block(14, 14, 26, 30)]
    assert all((a == b).all() for a, b in zip(scale_refs(row, 1.0), row))
    half = scale_refs(row, 0.5)
    assert [h.shape for h in half] == [r.shape for r in row]  # same cell size
    assert bbox(half[0]) == (15, 20, 25, 30)                  # about the row's bottom centre (20, 30): his ground stays
    assert bbox(half[1]) == (17, 22, 23, 30)                  # the second frame keeps its place in the row
