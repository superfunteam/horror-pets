"""The launch video, cut to "wub hell" (tools/generate_music.py's hell-organ track): every scene lands on its bars.

The song is 150 BPM, a bar every 1.6 s from 0.05 s in. Its organ-and-bells intro runs 7 bars, the first drop lands on
bar 7, the second a beat after bar 15, a quiet breakdown sits at bars 25-27 with a lone bass hit on bar 28, the last
drop lands on bar 31 and the final hit on bar 36.

  bars 0-3    the CRT TV: static, a channel change to the title screen, the rush in, PRESS START and the name
  bars 4-6    raise & train your killer
  bars 7-13   the seven killers, one a bar, from the first drop
  bars 14-24  fight to evolve your skills: fights (Pinhead's super on the second drop), an unlock, a new attack, cards
  bars 25-35  online & local PvP: the phones meet in the breakdown, tap on the bass hit, fight through the last drop
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

# Not in the repo: made with tools/generate_music.py (the hell-organ take kept as wub-hell-organ.mp3).
SONG = cast.ROOT / "art-work/video/music/wub-hell-organ.mp3"
BPM = 150
BEAT = 60 / BPM
BAR = 4 * BEAT
DOWNBEAT = 0.05  # where bar 0 starts in the song
ORDER = ["jason", "freddy", "pinhead", "ghostface", "chucky", "michael", "leatherface"]
OUTRO = 5.0


def bar(n: float) -> float:
    """Where bar [n] starts, in seconds into the song (and the video, which starts with it)."""
    return DOWNBEAT + n * BAR


def make() -> Sequence:
    crt = CrtIntro(bar(4), channel=bar(0) + 3 * BEAT, rush=(bar(1), bar(1) + 2 * BEAT), press=bar(2))
    cuts = [CharacterIntro(cast.BY_ID[pet], BAR, BEAT) for pet in ORDER]
    fights = FightToEvolve(BAR, BEAT, drop=bar(15) + BEAT - bar(14))
    scenes = [crt, RaiseTrain(BAR), *cuts, fights, OnlinePvp(BAR, BEAT), Outro(OUTRO)]
    return Sequence(scenes, (Cue(0, SONG, 0.85, fade_in=0.02, music=True),))
