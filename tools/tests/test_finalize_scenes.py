import colorsys
import sys
from pathlib import Path

import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from finalize_scenes import floor_y_full, sample_tint  # noqa: E402


def test_floor_y_full_maps_crop_fraction_to_full_image_height():
    # 1920x1280 (3:2): the 16:9 crop is 1080 tall and starts at y=100
    assert floor_y_full(0.80, (1920, 1280)) == pytest.approx((100 + 0.8 * 1080) / 1280, abs=1e-4)
    assert floor_y_full(0.80, (1920, 1280)) == 0.7531
    assert floor_y_full(0.0, (1920, 1280)) == pytest.approx(100 / 1280, abs=1e-4)
    assert floor_y_full(1.0, (1920, 1280)) == pytest.approx(1180 / 1280, abs=1e-4)


def test_floor_y_full_is_identity_when_there_is_no_vertical_crop():
    assert floor_y_full(0.8, (1920, 1080)) == 0.8
    assert floor_y_full(0.8, (2400, 1000)) == 0.8  # wider than 16:9: cropped horizontally


def value_of(hex_color: str) -> float:
    r, g, b = (int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))
    return colorsys.rgb_to_hsv(r, g, b)[2]


def test_sample_tint_keeps_real_luminance_within_clamp():
    dark = Image.new("RGB", (64, 64), (20, 24, 60))      # night scene
    bright = Image.new("RGB", (64, 64), (250, 240, 200))  # daylight scene
    assert value_of(sample_tint(dark)) == pytest.approx(0.35, abs=0.01)
    assert value_of(sample_tint(bright)) == pytest.approx(0.80, abs=0.01)
    mid = Image.new("RGB", (64, 64), (120, 100, 90))
    assert value_of(sample_tint(mid)) == pytest.approx(120 / 255, abs=0.01)
