"""The launch video, cut to "wub slasher synth" (tools/generate_music.py's slasher-synth track): every scene lands on
its bars.

The song is 150 BPM, a bar every 1.6 s from 0.04 s in. Its synth arpeggio plays alone for 4 bars and builds for 4
more, the first drop lands on bar 8, the second on bar 16 after a bar of fills, the breakdown runs bars 24-31, the
last drop lands on bar 32 and the song ends on bar 36. The video edits it (see make) so its drops land where they should:

  4 bars        the CRT TV in the basement: static, a channel change to the title screen, the rush in, PRESS START,
                and the title in layers as the name is shouted
  3.5 bars      raise & train your killer, CHOOSE YOUR KILLER as its title lands, a change every half bar
  11 bars       the seven killers from the first drop, each with their own line, Michael on the second drop
  6.5 bars      fight to evolve your skills: a fight under the title over the fill bar, hits from the drop,
                specials, a K.O., then over sixty attacks as cards
  11 beats      over 25 unique environments, flipping by on film
  12 bars       online & local PvP: the phones fly in and tap as the breakdown starts, TAP TO KILL YOUR FRIENDS,
                VS, blows traded from the last drop, the K.O. and the results
  9.5 s         the outro, over the last drop again, lingering on a pet being fed
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


def song_cues(edit: list[tuple[float, float]]) -> tuple[Cue, ...]:
    """The song as the video plays it: from each (video seconds, song seconds) in [edit] on, until the next."""
    cues = []
    for k, (start, skip) in enumerate(edit):
        last = k == len(edit) - 1
        length = None if last else edit[k + 1][0] - start + 0.02
        cues.append(Cue(start, SONG, 0.85, skip=skip, length=length, fade_in=0.02, fade_out=0.0 if last else 0.03,
                        music=True))
    return tuple(cues)


def make() -> Sequence:
    crt = CrtIntro(bar(4), channel=bar(0) + 3 * BEAT, rush=(bar(1), bar(1) + 2 * BEAT), press=bar(2))
    raise_train = RaiseTrain(BAR, bars=3.5)
    cuts = [CharacterIntro(cast.BY_ID[pet], beats * BEAT, BEAT) for pet, beats in ORDER]
    fights = FightToEvolve(BAR, BEAT, drop=BAR, bars=6.5)
    environments = Environments(11 * BEAT)
    pvp = OnlinePvp(BAR, BEAT, approach=1, vs=2.5, turns=2, results=1, hold=0.25)
    scenes = [crt, raise_train, *cuts, fights, environments, pvp, Outro(OUTRO)]
    seq = Sequence(scenes)
    at: dict[str, float] = {}  # each kind of scene's first start
    for scene, start in zip(scenes, seq.starts):
        at.setdefault(type(scene).__name__, start)
    tap = at["OnlinePvp"] + pvp.tap
    # The song is cut to fit the scenes, always on its own bars (or the beat a cut lands on): the intros start on the
    # first drop; the fights go back to the fill bar (15) and drop again on their hits; the environments go back a bar
    # further, so the phones tap as the breakdown starts (a beat of the drop cut at the tap); most of the breakdown is
    # skipped so they fight on the last drop; and the outro gets the last drop again, running out to the song's end.
    edit = [(0.0, 0.0), (at["CharacterIntro"], bar(8)), (at["FightToEvolve"], bar(15)),
            (at["Environments"], bar(15) + fights.duration - 2 * BAR), (tap, bar(24)), (at["OnlinePvp"] + pvp.fight_at - BAR, bar(31)),
            (at["Outro"], bar(32))]
    return Sequence(scenes, (*song_cues(edit), Cue(bar(4) + 0.25, cast.voice("choose_your_killer"), 1.1)))
