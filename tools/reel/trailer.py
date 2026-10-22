"""The launch video, cut to "wub slasher synth" (tools/generate_music.py's slasher-synth track): every scene lands on
its bars.

The song is 150 BPM, a bar every 1.6 s from 0.04 s in. Its synth arpeggio plays alone for 4 bars and builds for 4
more, the first drop lands on bar 8, the second on bar 16 after a bar of fills, the breakdown runs bars 24-31, the
last drop lands on bar 32 and the song ends on bar 36. The video plays the song through bar 18, goes back to its fill bar
15 (so the fights drop again), plays on to bar 26 and jumps to bar 31, cutting most of the breakdown (see EDIT):

  bars 0-3    the CRT TV in the basement: static, a channel change to the title screen, the rush in, PRESS START,
              and the title in layers as the name is shouted
  bars 4-7    raise & train your killer, the announcer calling CHOOSE YOUR KILLER as its title lands
  bars 8-18   the seven killers from the first drop, each with their own line, Michael on the second drop
  bars 19-25  fight to evolve your skills: a fight under the title over the fill (song bar 15), hits from the drop,
              specials, a K.O., then over sixty attacks as cards
  bars 26-34  online & local PvP: the phones fly in and tap as the breakdown starts, TAP TO FIGHT YOUR FRIENDS, VS,
              a fight from the last drop, the K.O. and the results
  bar 35-     the outro as the song ends, the announcer shouting its line
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
# The killers in order, and how many beats each one's intro runs: long enough for their line (cast.intro_sound),
# Michael's short and silent, landing on the second drop.
ORDER = [("jason", 6), ("freddy", 6), ("ghostface", 8), ("chucky", 6), ("pinhead", 6), ("michael", 4),
         ("leatherface", 8)]
OUTRO = 5.0


def bar(n: float) -> float:
    """Where bar [n] starts, in seconds into the song (and the video, which starts with it)."""
    return DOWNBEAT + n * BAR


# The song as the video plays it: from each (video bar, song bar) on, until the next. The intros run past the first
# drop into the second, so the song goes back to its fill bar (15) for the fights' title and drops again on their hits,
# and later skips most of the breakdown so the phones fight on the last drop.
EDIT = [(0, 0), (19, 15), (30, 31)]


def song_cues() -> tuple[Cue, ...]:
    cues = []
    for k, (video, song) in enumerate(EDIT):
        # The first piece starts with the video, at the song's very start; the rest start on their bars.
        start, skip = (0.0, 0.0) if k == 0 else (bar(video), bar(song))
        last = k == len(EDIT) - 1
        length = None if last else bar(EDIT[k + 1][0]) - start + 0.02
        cues.append(Cue(start, SONG, 0.85, skip=skip, length=length, fade_in=0.02, fade_out=0.0 if last else 0.03,
                        music=True))
    return tuple(cues)


def make() -> Sequence:
    crt = CrtIntro(bar(4), channel=bar(0) + 3 * BEAT, rush=(bar(1), bar(1) + 2 * BEAT), press=bar(2))
    raise_train = RaiseTrain(BAR, bars=4)
    cuts = [CharacterIntro(cast.BY_ID[pet], beats * BEAT, BEAT) for pet, beats in ORDER]
    fights = FightToEvolve(BAR, BEAT, drop=BAR)
    pvp = OnlinePvp(BAR, BEAT, approach=2, vs=2, turns=2, ko_bars=2)
    scenes = [crt, raise_train, *cuts, fights, pvp, Outro(OUTRO)]
    return Sequence(scenes, (*song_cues(), Cue(bar(4) + 0.25, cast.voice("choose_your_killer"), 1.1)))
