"""The launch video, cut to "wub slasher synth" (tools/generate_music.py's slasher-synth track): every scene lands on
its bars.

The song is 150 BPM, a bar every 1.6 s from 0.04 s in. Its synth arpeggio plays alone for 4 bars and builds for 4
more, the first drop lands on bar 8, the second on bar 16 after a bar of fills, the breakdown runs bars 24-31, the
last drop lands on bar 32 and the song ends on bar 36. The video edits it (see EDIT) so its drops land where they should:

  bars 0-3      the CRT TV in the basement: static, a channel change to the title screen, the rush in, PRESS START,
                and the title in layers as the name is shouted
  bars 4-8      raise & train your killer (a build bar repeated), CHOOSE YOUR KILLER as its title lands
  bars 9-19     the seven killers from the first drop, each with their own line, Michael on the second drop
  bars 20-26.5  fight to evolve your skills: a fight under the title over the fill bar, hits from the drop,
                specials, a K.O., then over sixty attacks as cards
  bars 26.5-29  over 20 unique environments, flipping by on film (two drop bars repeated)
  bars 29.5-39  online & local PvP: the phones fly in and tap as the breakdown starts, TAP TO KILL YOUR FRIENDS,
                VS, blows traded from the last drop, the K.O. and the results
  bar 40-       the outro, over the last drop again, lingering on a pet being fed
"""
from __future__ import annotations

from . import cast
from .crt import CrtIntro
from .environments import Environments
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
OUTRO = 9.5


def bar(n: float) -> float:
    """Where bar [n] starts, in seconds into the song (and the video, which starts with it)."""
    return DOWNBEAT + n * BAR


# The song as the video plays it: from each (video bar, song bar) on, until the next. A build bar is repeated so raise
# & train can run five bars; the intros run past the first drop into the second, so the song goes back to its fill bar
# (15) for the fights' title and drops again on their hits; two drop bars repeat under the environments, so the phones
# tap as the breakdown starts; most of the breakdown is skipped so they fight on the last drop; and the outro gets the
# last drop again, running out to the song's end.
EDIT = [(0, 0), (7, 6), (20, 15), (28, 21), (33, 31), (40, 32)]


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
    raise_train = RaiseTrain(BAR, bars=5)
    cuts = [CharacterIntro(cast.BY_ID[pet], beats * BEAT, BEAT) for pet, beats in ORDER]
    fights = FightToEvolve(BAR, BEAT, drop=BAR, bars=6.5)
    pvp = OnlinePvp(BAR, BEAT, approach=1.5, vs=2, turns=2, results=2)
    scenes = [crt, raise_train, *cuts, fights, Environments(3 * BAR), pvp, Outro(OUTRO)]
    return Sequence(scenes, (*song_cues(), Cue(bar(4) + 0.25, cast.voice("choose_your_killer"), 1.1)))
