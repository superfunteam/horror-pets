import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from reel import title, trailer  # noqa: E402
from reel.intro import CharacterIntro  # noqa: E402
from reel.pvp import OnlinePvp  # noqa: E402
from reel.title import TitleCard  # noqa: E402


@pytest.fixture(scope="module")
def seq():
    return trailer.make()


def start_of(seq, kind):
    return next((s, c) for s, c in zip(seq.starts, seq.scenes) if isinstance(c, kind))


def test_the_killers_land_one_a_bar_from_the_first_drop(seq):
    starts = [s for s, c in zip(seq.starts, seq.scenes) if isinstance(c, CharacterIntro)]
    assert starts == pytest.approx([trailer.bar(trailer.INTRO_BARS + k) for k in range(7)])


def test_the_logo_lands_a_beat_into_the_title_and_turbo_on_the_second_drop(seq):
    start, card = start_of(seq, TitleCard)
    assert start == pytest.approx(trailer.bar(14))
    assert start + card.logo_at == pytest.approx(trailer.bar(14) + trailer.BEAT)
    assert start + card.turbo_at == pytest.approx(trailer.bar(15) + trailer.BEAT)
    shout = next(c for c in seq.cues() if c.path == title.SHOUT)
    assert shout.t + title.SHOUT_FIRST / shout.tempo == pytest.approx(start + card.logo_at)
    assert shout.t + title.SHOUT_TURBO / shout.tempo == pytest.approx(start + card.turbo_at)


def test_the_phones_tap_on_the_breakdown_bass_hit_and_fight_from_the_last_drop(seq):
    start, pvp = start_of(seq, OnlinePvp)
    assert start + pvp.tap == pytest.approx(trailer.bar(28))
    assert start + pvp.fight_at == pytest.approx(trailer.bar(31))
    assert start + pvp.duration == pytest.approx(trailer.bar(36))


def test_the_music_starts_with_the_video(seq):
    (music,) = [c for c in seq.cues() if c.music]
    assert music.t == 0 and music.skip == 0 and music.path == trailer.SONG
