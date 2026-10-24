"""The adaptive launcher icon's layers: a full-bleed back, and the mask in front where no launcher's mask crops it."""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import make_branding as mb  # noqa: E402

SIZE = 216  # small enough to be quick; the layers scale


def test_the_background_fills_the_whole_layer():
    bg = mb.icon_background(SIZE)
    assert bg.mode == "RGB"
    assert bg.size == (SIZE, SIZE)
    # The glow sits where the legacy icon's does, in the middle 72 dp a launcher shows: red there, ink at the edge.
    px = np.array(bg)
    middle = px[round(SIZE * 0.45), SIZE // 2]
    assert middle[0] > 80 and middle[0] > middle[2] * 3
    assert tuple(px[0, 0]) == mb.INK


def test_the_mask_stays_inside_the_safe_zone_and_fills_it():
    for fade in (mb.MASK_FADE, mb.MONO_FADE):
        fg = mb.icon_foreground(SIZE, fade=fade)
        a = np.array(fg)[..., 3]
        # Transparent round the mask: the corners and the edges of the layer show the background.
        assert a[0, 0] == 0 and a[:, 0].max() == 0 and a[0, :].max() == 0
        ys, xs = np.nonzero(a > 0)
        far = np.sqrt((xs + 0.5 - SIZE / 2) ** 2 + (ys + 0.5 - SIZE / 2) ** 2).max()
        # Nothing reaches the edge of the 66 dp circle no launcher's mask cuts into...
        assert far <= mb.SAFE_RADIUS * SIZE
        # ...and it is no postage stamp: it reaches most of the way to it.
        assert far >= 0.9 * mb.MASK_REACH * SIZE


def test_the_themed_icon_is_one_color_cut_from_the_mask():
    fg = mb.icon_foreground(SIZE, fade=mb.MONO_FADE)
    mono = np.array(mb.icon_monochrome(fg))
    assert (mono[..., :3] == 255).all()
    # Only the mask's own pixels, and its eye holes see-through: less of it is solid than of the colored mask.
    assert (mono[..., 3] <= np.array(fg)[..., 3]).all()
    assert (mono[..., 3] > 128).sum() < (np.array(fg)[..., 3] > 128).sum()


def test_the_legacy_round_icon_is_the_square_one_cut_to_a_circle():
    icon = mb.icon_master(192).resize((48, 48))
    r = np.array(mb.round_icon(icon))
    assert r[0, 0, 3] == 0 and r[47, 47, 3] == 0
    assert r[24, 24, 3] == 255
    assert (r[24, 24, :3] == np.array(icon)[24, 24]).all()
