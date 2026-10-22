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


def test_the_killers_land_one_a_bar_from_the_first_drop(seq):
    starts = [s for s, c in zip(seq.starts, seq.scenes) if isinstance(c, CharacterIntro)]
    assert starts == pytest.approx([trailer.bar(8 + k) for k in range(7)])


def test_start_is_pressed_on_bar_two_and_raise_and_train_comes_before_the_killers(seq):
    start, crt = start_of(seq, CrtIntro)
    assert start == 0 and crt.press == pytest.approx(trailer.bar(2))
    shout = next(c for c in seq.cues() if c.path == title.SHOUT)
    assert shout.t + title.SHOUT_FIRST == pytest.approx(crt.press + 0.12)
    raise_start, _ = start_of(seq, RaiseTrain)
    assert raise_start == pytest.approx(trailer.bar(4))


def test_the_name_is_shown_and_shouted_once_and_the_hits_start_on_the_second_drop(seq):
    assert not any(isinstance(c, TitleCard) for c in seq.scenes)  # the title screen on the TV is the only one
    assert sum(c.path == title.SHOUT for c in seq.cues()) == 1
    start, fights = start_of(seq, FightToEvolve)
    assert start == pytest.approx(trailer.bar(15))
    assert start + fights.cuts[1].at == pytest.approx(trailer.bar(16))  # the hits start on the drop
    assert fights.special_at > fights.cuts[6].at  # Pinhead's super comes after six of them


def test_the_phones_tap_in_the_breakdown_and_fight_from_the_last_drop(seq):
    start, pvp = start_of(seq, OnlinePvp)
    assert start + pvp.tap == pytest.approx(trailer.bar(28))
    assert start + pvp.fight_at == pytest.approx(trailer.bar(32))
    assert start + pvp.duration == pytest.approx(trailer.bar(36))


def test_the_music_starts_with_the_video(seq):
    (music,) = [c for c in seq.cues() if c.music]
    assert music.t == 0 and music.skip == 0 and music.path == trailer.SONG


def test_the_phones_never_pass_through_each_other(seq):
    _, pvp = start_of(seq, OnlinePvp)
    for i in range(round(pvp.duration * 60) + 1):
        left, right = pvp.shot(i / 60).phones
        assert left.position[0] == pytest.approx(-right.position[0])
        assert right.position[0] >= clear_x(right.rotation[1], 0) - 1e-6, i / 60
