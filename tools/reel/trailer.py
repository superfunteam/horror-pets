"""The launch video, cut to "Spooky Gabber at a Distance" by Nick Panek: every cut lands on one of the high blasts
the song has on each bar's downbeat, from its first one until the kick drops.

It opens on the storm one bar before the first blast. Then each killer gets a bar of their own (Ghostface gets the
long two-bar blast), and the title card takes the last four blasts: the killers fly into place a few at a time, the
logo slams down on the final blast as the announcer starts shouting the game's name, and TURBO lands on the drop.
"""
from __future__ import annotations

from . import cast
from .intro import CharacterIntro
from .render import Cue, Sequence
from .title import TitleCard

# Not in the repo: the track is licensed for use in the video, not for passing around on its own.
SONG = cast.ROOT / "art-work/video/music/spooky-gabber.mp3"
BPM = 170
BEAT = 60 / BPM
BAR = 4 * BEAT
FIRST_BLAST = 6.072  # seconds into the song; the blasts then fall on every bar's downbeat until the drop, 12 bars on
START = FIRST_BLAST - BAR  # where in the song the video starts

CUTS = [("jason", 1), ("freddy", 1), ("pinhead", 1), ("ghostface", 2), ("chucky", 1), ("michael", 1), ("leatherface", 1)]
TITLE_BARS = 8  # four of blasts to bring the cast in, the drop, and four more to hold the logo


def make() -> Sequence:
    opening = TitleCard(BAR, fade_in=0.85 * BAR, lightning=(0.3 * BAR, 0.62 * BAR, 0.8 * BAR))
    cuts = [CharacterIntro(cast.BY_ID[pet], bars * BAR, BEAT) for pet, bars in CUTS]
    title = TitleCard(
        TITLE_BARS * BAR,
        arrivals={"ghostface": 0, "pinhead": 0, "jason": BAR, "freddy": BAR, "michael": 1.5 * BAR,
                  "leatherface": 1.5 * BAR, "chucky": 2 * BAR},
        logo_at=3 * BAR,
        turbo_at=4 * BAR,
        fade_out=0.5 * BAR,
    )
    scenes = [opening, *cuts, title]
    length = sum(s.duration for s in scenes)
    music = Cue(0, SONG, 0.8, skip=START, length=length, fade_in=0.02, fade_out=0.5 * BAR, music=True)
    return Sequence(scenes, (music,))
