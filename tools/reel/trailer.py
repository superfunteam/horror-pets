"""The launch video, cut to "wub slasher synth" (tools/generate_music.py's slasher-synth track): every scene lands on
its bars.

The song is 150 BPM, a bar every 1.6 s from 0.04 s in. Its synth arpeggio plays alone for 4 bars and builds for 4
more, the first drop lands on bar 8, the second on bar 16 after a bar of fills, the breakdown runs bars 24-31, the
last drop lands on bar 32 and the song ends on bar 36.

  bars 0-3    the CRT TV in the basement: static, a channel change to the title screen, the rush in, PRESS START
              and the name
  bars 4-7    raise & train your killer, the announcer calling CHOOSE YOUR KILLER into the drop
  bars 8-14   the seven killers, one a bar, from the first drop
  bars 15-23  fight to evolve your skills: fights (Pinhead's super on the second drop), an unlock, a new attack, cards
  bars 24-35  online & local PvP: the phones meet in the breakdown, tap on bar 28, VS, and fight from the last drop
  bar 36-     the outro
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

# Not in the repo: made with tools/generate_music.py (the slasher-synth take kept as wub-slasher-synth.mp3).
SONG = cast.ROOT / "art-work/video/music/wub-slasher-synth.mp3"
BPM = 150
BEAT = 60 / BPM
BAR = 4 * BEAT
DOWNBEAT = 0.04  # where bar 0 starts in the song
ORDER = ["jason", "freddy", "pinhead", "ghostface", "chucky", "michael", "leatherface"]
OUTRO = 5.0


def bar(n: float) -> float:
    """Where bar [n] starts, in seconds into the song (and the video, which starts with it)."""
    return DOWNBEAT + n * BAR


def make() -> Sequence:
    crt = CrtIntro(bar(4), channel=bar(0) + 3 * BEAT, rush=(bar(1), bar(1) + 2 * BEAT), press=bar(2))
    raise_train = RaiseTrain(BAR, bars=4)
    cuts = [CharacterIntro(cast.BY_ID[pet], BAR, BEAT) for pet in ORDER]
    fights = FightToEvolve(BAR, BEAT, drop=bar(16) - bar(15), bars=9, montage_bars=4)
    pvp = OnlinePvp(BAR, BEAT, approach=4, vs=3, fight=4)
    scenes = [crt, raise_train, *cuts, fights, pvp, Outro(OUTRO)]
    extra = (Cue(0, SONG, 0.85, fade_in=0.02, music=True),
             Cue(bar(7) + 0.1, cast.voice("choose_your_killer"), 1.1))
    return Sequence(scenes, extra)
