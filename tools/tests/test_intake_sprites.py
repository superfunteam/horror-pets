import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from intake_sprites import IntakeError, align_baselines, ground_row, shift_down  # noqa: E402


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
