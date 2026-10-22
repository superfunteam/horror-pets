import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from reel import title, trailer  # noqa: E402
from reel.intro import CharacterIntro  # noqa: E402
from reel.title import TitleCard  # noqa: E402


@pytest.fixture(scope="module")
def seq():
    return trailer.make()


def song_time(t: float) -> float:
    return trailer.START + t


def test_every_cut_starts_on_a_blast(seq):
    bar = 0
    for start, scene in zip(seq.starts, seq.scenes):
        if isinstance(scene, CharacterIntro):
            assert song_time(start) == pytest.approx(trailer.FIRST_BLAST + bar * trailer.BAR)
            bar += round(scene.duration / trailer.BAR)
    assert bar == 8


def test_the_logo_lands_on_the_last_blast_and_turbo_on_the_drop(seq):
    start, card = next((s, c) for s, c in zip(seq.starts, seq.scenes) if isinstance(c, TitleCard) and c.logo_at)
    assert song_time(start + card.logo_at) == pytest.approx(trailer.FIRST_BLAST + 11 * trailer.BAR)
    assert song_time(start + card.turbo_at) == pytest.approx(trailer.FIRST_BLAST + 12 * trailer.BAR)
    shout = next(c for c in seq.cues() if c.path == title.SHOUT)
    assert shout.t + title.SHOUT_FIRST / shout.tempo == pytest.approx(start + card.logo_at)
    assert shout.t + title.SHOUT_TURBO / shout.tempo == pytest.approx(start + card.turbo_at)


def test_the_music_runs_the_whole_video_from_one_bar_before_the_first_blast(seq):
    (music,) = [c for c in seq.cues() if c.music]
    assert music.t == 0 and music.skip == pytest.approx(trailer.FIRST_BLAST - trailer.BAR)
    assert music.length == pytest.approx(seq.duration)
