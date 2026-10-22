"""The launch video, cut to "wub hell" (tools/generate_music.py's hell-organ track): every scene lands on its bars.

The song is 150 BPM, a bar every 1.6 s from 0.05 s in. Its organ-and-bells intro runs 7 bars, the first drop lands on
bar 7, the second a beat after bar 15, a quiet breakdown sits at bars 25-27 with a lone bass hit on bar 28, the last
drop lands on bar 31 and the final hit on bar 36.

  bars 0-6    the CRT living room, pushing into the screen
  bars 7-13   the seven killers, one a bar
  bars 14-15  the title: the cast lands, the logo slams on the next beat, TURBO on the second drop
  bars 16-18  raise & train your killer
  bars 19-24  fight to evolve your skills
  bars 25-35  online PvP: the phones meet in the breakdown, tap on the bass hit, fight through the last drop
  bar 36-     the outro on the final hit
"""
from __future__ import annotations

from . import cast
from .crt import CrtIntro
from .evolve import FightToEvolve
from .intro import CharacterIntro
from .outro import Outro
from .pvp import OnlinePvp
from .raise_train import RaiseTrain
from .render import Cue, Sequence
from .title import TitleCard

# Not in the repo: made with tools/generate_music.py (the hell-organ take kept as wub-hell-organ.mp3).
SONG = cast.ROOT / "art-work/video/music/wub-hell-organ.mp3"
BPM = 150
BEAT = 60 / BPM
BAR = 4 * BEAT
DOWNBEAT = 0.05  # where bar 0 starts in the song
INTRO_BARS = 7
ORDER = ["jason", "freddy", "pinhead", "ghostface", "chucky", "michael", "leatherface"]
OUTRO = 5.0


def bar(n: float) -> float:
    """Where bar [n] starts, in seconds into the song (and the video, which starts with it)."""
    return DOWNBEAT + n * BAR


def make() -> Sequence:
    crt = CrtIntro(bar(INTRO_BARS), bolts=(bar(2), bar(4), bar(6)))
    cuts = [CharacterIntro(cast.BY_ID[pet], BAR, BEAT) for pet in ORDER]
    title = TitleCard(
        2 * BAR,
        arrivals={"ghostface": 0, "pinhead": 0, "jason": 0, "freddy": 0, "michael": BEAT / 2, "leatherface": BEAT / 2,
                  "chucky": BEAT / 2},
        logo_at=BEAT,
        turbo_at=bar(15) + BEAT - bar(14),
    )
    scenes = [crt, *cuts, title, RaiseTrain(BAR), FightToEvolve(BAR, BEAT), OnlinePvp(BAR, BEAT), Outro(OUTRO)]
    return Sequence(scenes, (Cue(0, SONG, 0.85, fade_in=0.02, music=True),))
