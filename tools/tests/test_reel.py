import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from reel import title, trailer  # noqa: E402
from reel.crt import CrtIntro  # noqa: E402
from reel.evolve import FightToEvolve  # noqa: E402
from reel.intro import CharacterIntro  # noqa: E402
from reel.pvp import OnlinePvp, clear_x  # noqa: E402
from reel.raise_train import RaiseTrain  # noqa: E402
from reel.title import TitleCard  # noqa: E402


@pytest.fixture(scope="module")
def seq():
    return trailer.make()


def start_of(seq, kind):
    return next((s, c) for s, c in zip(seq.starts, seq.scenes) if isinstance(c, kind))


def in_song(seq, t: float) -> float:
    """Where video time [t] falls in the song, across the edit."""
    cue = [c for c in seq.cues() if c.music and c.t <= t + 1e-9][-1]
    return cue.skip + (t - cue.t)


def test_the_killers_land_on_the_drops(seq):
    starts = [s for s, c in zip(seq.starts, seq.scenes) if isinstance(c, CharacterIntro)]
    beats = [0] + [b for _, b in trailer.ORDER]
    assert starts == pytest.approx([starts[0] + sum(beats[:k + 1]) * trailer.BEAT for k in range(7)])
    assert in_song(seq, starts[0]) == pytest.approx(trailer.bar(8))  # Jason on the first drop
    assert in_song(seq, starts[5]) == pytest.approx(trailer.bar(16))  # Michael on the second


def test_start_is_pressed_on_bar_two_and_raise_and_train_comes_before_the_killers(seq):
    start, crt = start_of(seq, CrtIntro)
    assert start == 0 and crt.press == pytest.approx(trailer.bar(2))
    shout = next(c for c in seq.cues() if c.path == title.SHOUT)
    assert shout.t + title.SHOUT_FIRST == pytest.approx(crt.press + 0.12)
    raise_start, _ = start_of(seq, RaiseTrain)
    assert raise_start == pytest.approx(trailer.bar(4))


def test_the_name_is_shown_and_shouted_once_and_the_hits_land_on_a_drop(seq):
    assert not any(isinstance(c, TitleCard) for c in seq.scenes)  # the title screen on the TV is the only one
    assert sum(c.path == title.SHOUT for c in seq.cues()) == 1
    start, fights = start_of(seq, FightToEvolve)
    assert in_song(seq, start) == pytest.approx(trailer.bar(15))  # the fill, under the title
    assert in_song(seq, start + fights.cuts[1].at) == pytest.approx(trailer.bar(16))  # the hits start on the drop
    assert fights.specials_at == pytest.approx(fights.cuts[1].at + 5 * trailer.BEAT)  # five hits, then the specials


def test_the_phones_tap_as_the_breakdown_starts_and_fight_from_the_last_drop(seq):
    start, pvp = start_of(seq, OnlinePvp)
    assert in_song(seq, start + pvp.tap) == pytest.approx(trailer.bar(24))
    assert in_song(seq, start + pvp.fight_at) == pytest.approx(trailer.bar(32))
    assert in_song(seq, start + pvp.duration) == pytest.approx(trailer.bar(32))  # the outro on the last drop, again


def test_every_jump_in_the_song_lands_on_a_beat_of_it(seq):
    first, *rest = [c for c in seq.cues() if c.music]
    assert first.t == 0 and first.skip == 0  # the video starts with the song
    for c in rest:
        assert round((c.skip - trailer.DOWNBEAT) / trailer.BEAT, 6) % 1 == 0


def test_grid_changes_land_every_half_bar(seq):
    from reel.raise_train import RaiseTrain
    _, grid = start_of(seq, RaiseTrain)
    changes = sorted({p.enter for p in grid.panels} - {0})
    assert changes == pytest.approx([trailer.BAR * (1 + 0.5 * k) for k in range(len(changes))])


def test_the_phones_never_pass_through_each_other(seq):
    _, pvp = start_of(seq, OnlinePvp)
    for i in range(round(pvp.duration * 60) + 1):
        left, right = pvp.shot(i / 60).phones
        assert left.position[0] == pytest.approx(-right.position[0])
        assert right.position[0] >= clear_x(right.rotation[1], 0) - 1e-6, i / 60


def test_the_camera_glides_into_the_tv_without_turning_back(seq):
    _, crt = start_of(seq, CrtIntro)
    ts = [k / 60 for k in range(round(crt.rush[1] * 60) + 1)]
    boxes = [crt.view(crt.progress(t)) for t in ts]
    for (a0, b0, c0, d0), (a1, b1, c1, d1) in zip(boxes, boxes[1:]):
        assert a1 >= a0 - 1e-6 and b1 >= b0 - 1e-6 and c1 <= c0 + 1e-6 and d1 <= d0 + 1e-6  # only ever closing in
    w, h = crt.room.size
    assert all(x0 >= -0.5 and y0 >= -0.5 and x1 <= w + 0.5 and y1 <= h + 0.5 for x0, y0, x1, y1 in boxes)
    speeds = [(crt.progress(b) - crt.progress(a)) * 60 for a, b in zip(ts, ts[1:])]
    assert max(abs(v1 - v0) for v0, v1 in zip(speeds, speeds[1:])) < 0.1 * max(speeds)  # no jolts
    assert crt.progress(crt.rush[1]) == pytest.approx(1.0)


def test_the_blows_land_on_their_frames_and_the_bell_rings_after_the_ko(seq):
    _, fights = start_of(seq, FightToEvolve)
    pvp_at, pvp = start_of(seq, OnlinePvp)
    for c in [*fights.cues(), *pvp.cues()]:
        if not c.ducking:  # a blow: its loudest moment on the frame it lands
            assert "video/sfx" in str(c.path)
    bell = next(c for c in pvp.cues() if c.path.name == "bell.mp3")
    assert bell.t >= pvp.results_at and bell.t >= pvp.ko_hit + 0.8
