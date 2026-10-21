import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from synth_audio import SR, edge_fade, music_box_note, music_fight, noise, normalize, rng_for  # noqa: E402


def test_rng_for_is_stable_per_name_and_independent_of_other_draws():
    a1 = noise(rng_for("jason"), 64)
    other = rng_for("freddy")
    noise(other, 10_000)  # drawing from another asset's generator must not shift jason's stream
    a2 = noise(rng_for("jason"), 64)
    assert np.array_equal(a1, a2)
    assert not np.array_equal(a1, noise(rng_for("freddy"), 64))


def test_helpers_draw_only_from_the_given_generator():
    assert np.array_equal(music_box_note(rng_for("x"), 440.0), music_box_note(rng_for("x"), 440.0))


def test_sfx_peak_lands_at_target_after_fade_then_normalize():
    x = np.zeros(SR // 10)
    x[3] = 1.0  # the loudest sample sits inside the 5 ms fade-in
    x[1000] = 0.5
    y = normalize(edge_fade(x), -1.0)
    assert np.abs(y).max() == pytest.approx(10 ** (-1.0 / 20))


def test_fight_loop_is_64_seconds():
    assert len(music_fight()) == 64 * SR
