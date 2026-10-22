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


def test_every_jump_in_the_song_is_on_a_bar(seq):
    first, *rest = [c for c in seq.cues() if c.music]
    assert first.t == 0 and first.skip == 0  # the video starts with the song
    for c in rest:
        assert round((c.t - trailer.DOWNBEAT) / trailer.BAR, 6) % 1 == 0
        assert c.skip == 0 or round((c.skip - trailer.DOWNBEAT) / trailer.BAR, 6) % 1 == 0


def test_the_phones_never_pass_through_each_other(seq):
    _, pvp = start_of(seq, OnlinePvp)
    for i in range(round(pvp.duration * 60) + 1):
        left, right = pvp.shot(i / 60).phones
        assert left.position[0] == pytest.approx(-right.position[0])
        assert right.position[0] >= clear_x(right.rotation[1], 0) - 1e-6, i / 60
