"""Fight to evolve your skills: real fights cut on the beat, then a wall of attack cards.

First, gameplay recorded off the TV emulator (driven by the app's puppet mode), cut on the beat: Leatherface and Michael
trading blows under the title, then from the song's drop a different killer landing a hit every beat (Chucky,
Ghostface, Jason, Freddy, Leatherface), then specials one after another (Pinhead's chains, Chucky's voodoo,
Ghostface's knives, Leatherface's saw dance) and a K.O. Then the game's learnable attacks recut as cards, as the
announcer shouts that killing unlocks over sixty: two rows sliding opposite ways, each card a killer swinging their
sprite on their stage while the attack's marks (AttackMarks.kt, see marks.py) rip across it, its name and its four lit
tiers underneath.
"""
from __future__ import annotations

from dataclasses import dataclass

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

from . import cast, fx, marks, sprites
from .footage import Clip
from .lettering import Slam
from .motion import pulse
from .render import H, W, Cue

TITLE = cast.ROOT / "video/art/title-fight.webp"
ATTACKS = cast.ROOT / "video/voice/attacks.wav"  # "Kill to unlock over sixty special attacks!" (generate_video_voice.py)
FONT = str(cast.ROOT / "app/src/main/res/font/barlow_condensed_extrabold.ttf")
STORM = cast.ROOT / "site/art/backdrop.webp"
CARD_W, CARD_H, CARD_GAP = 380, 520, 44


@dataclass(frozen=True)
class Cut:
    """[clip] shown for [length] seconds from [at] into the scene, cropped in [zoom] times on [focus] (a fraction of
    the frame)."""
    at: float
    length: float
    clip: Clip
    zoom: float = 1.28  # fights are cropped in on the fighters
    focus: tuple[float, float] = (0.5, 0.56)


@dataclass(frozen=True)
class Card:
    pet: str
    slot: str  # "heavy" or "special"
    name: str


SPECIALS = [Card("jason", "special", "Never Dies"), Card("freddy", "special", "Welcome to Prime Time"),
            Card("pinhead", "special", "Leviathan"), Card("ghostface", "special", "Ultimate Scream"),
            Card("chucky", "special", "Friends to the End"), Card("michael", "special", "He Came Home"),
            Card("leatherface", "special", "Chainsaw Massacre")]
HEAVIES = [Card("leatherface", "heavy", "Texas Chainsaw"), Card("michael", "heavy", "The Boogeyman"),
           Card("chucky", "heavy", "Charles Lee Ray"), Card("ghostface", "heavy", "Final Act"),
           Card("pinhead", "heavy", "Tear Your Soul Apart"), Card("freddy", "heavy", "Freddy's Coming"),
           Card("jason", "heavy", "Final Chapter")]
STAGES = {"jason": "dock", "freddy": "boiler", "pinhead": "chamber", "ghostface": "house", "chucky": "toystore",
          "michael": "haddonfield", "leatherface": "butcher"}


def _text(s: str, size: int, fill, width: float) -> Image.Image:
    """[s] in the game's display face, stepping down in size until it fits [width]."""
    while True:
        font = ImageFont.truetype(FONT, size)
        w = font.getlength(s)
        if w <= width or size <= 14:
            break
        size -= 2
    im = Image.new("RGBA", (round(w) + 8, size + 12), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((4, 2), s, font=font, fill=fill, stroke_width=2, stroke_fill=(10, 4, 14))
    return im


class FightToEvolve:
    """[bars] bars of [bar] seconds: [montage_bars] of fights cut on the beat, the rest of cards. A fight plays under
    the title up to the [drop] (seconds in), where the hits start flying, a different killer every beat, then their
    specials one after another (the attacks themselves, without their name banners), and a K.O."""

    def __init__(self, bar: float, beat: float, drop: float, bars: int = 7, montage_bars: int = 4):
        self.bar, self.beat, self.drop = bar, beat, drop
        self.duration = bars * bar
        self.cards_at = montage_bars * bar
        # (beats long, recording, from, to): the montage, cut on the beat.
        montage = [
            (round(drop / beat), "tv-fight-leatherface-michael.mp4", 7.0, 9.6),  # Leatherface vs Michael, under the title
            (1, "tv-fight-chucky-ghostface.mp4", 7.1, 7.9),  # Chucky's crit
            (1, "tv-fight-ghostface-freddy.mp4", 20.6, 21.5),  # Ghostface's combo crit
            (1, "tv-fight-pinhead.mp4", 7.7, 8.6),  # Jason's crit on Pinhead
            (1, "tv-fight-freddy.mp4", 28.6, 29.8),  # Freddy's claws draining Jason
            (1, "tv-fight-leatherface-michael.mp4", 12.6, 13.5),  # Leatherface's saw, a crit
            (2, "tv-fight-pinhead.mp4", 28.8, 30.3),  # specials: Pinhead's chains for 55,
            (1, "tv-fight-chucky-ghostface.mp4", 22.6, 23.6),  # Chucky's voodoo for 103,
            (1, "tv-fight-ghostface-freddy.mp4", 30.4, 31.6),  # Ghostface's knives,
            (1, "tv-fight-leatherface-michael.mp4", 37.2, 38.6),  # Leatherface's saw dance
        ]
        ko = montage_bars * 4 - sum(m[0] for m in montage)
        assert ko >= 2, "the montage needs at least two beats for the K.O."
        montage.append((ko, "tv-fight-freddy.mp4", 42.3, 43.8))  # K.O.
        self.cuts, at = [], 0.0
        for beats, file, start, end in montage:
            self.cuts.append(Cut(at, beats * beat, Clip(file, start, end, speed=(end - start) / (beats * beat))))
            at += beats * beat
        self.specials_at = self.cuts[6].at
        self.title = Slam(Image.open(TITLE).convert("RGBA"), (W / 2, H * 0.36), 980, land=0.14, leave=drop - 0.25,
                          exit_dx=2400, glint_at=0.55)
        storm = fx.cover(Image.open(STORM).convert("RGB"), W, H).filter(ImageFilter.GaussianBlur(4))
        self.storm = ImageEnhance.Brightness(storm).enhance(0.4).convert("RGBA")
        self.vignette = fx.vignette(W, H, 0.7)
        self.faces = {c: self.card_face(c) for c in SPECIALS + HEAVIES}

    def prepare(self) -> None:
        for cut in self.cuts:
            cut.clip.extract()

    # Footage -----------------------------------------------------------------------------------------------------

    def footage(self, t: float) -> Image.Image:
        cut = next(c for c in reversed(self.cuts) if c.at <= t)
        local = t - cut.at
        im = cut.clip.frame(local)
        z = cut.zoom + 0.05 * local / cut.length
        cw, ch = im.width / z, im.height / z
        cx = min(max(cut.focus[0] * im.width, cw / 2), im.width - cw / 2)
        cy = min(max(cut.focus[1] * im.height, ch / 2), im.height - ch / 2)
        box = (cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        out = im.resize((W, H), Image.BILINEAR, box=box)
        return fx.flash(out, (255, 255, 255), 0.55 * pulse(local, 0, 0.12))

    # Cards ------------------------------------------------------------------------------------------------------

    def card_face(self, c: Card) -> Image.Image:
        """The parts of [c]'s card that don't move: its stage, frame, name and tiers."""
        accent = cast.accent(cast.Killer(c.pet, STAGES[c.pet], "right"))
        stage = cast.backdrop(cast.Killer(c.pet, STAGES[c.pet], "right"))
        bg = ImageEnhance.Brightness(fx.cover(stage, CARD_W, CARD_H)).enhance(0.55).convert("RGBA")
        shade = Image.new("RGBA", (CARD_W, CARD_H), (0, 0, 0, 0))
        d = ImageDraw.Draw(shade)
        for y in range(CARD_H):  # darker toward the bottom, where the name sits
            d.line([(0, y), (CARD_W, y)], fill=(8, 3, 12, round(235 * max(0.0, (y / CARD_H - 0.55) / 0.45))))
        bg.alpha_composite(shade)
        cut = 22
        poly = [(cut, 0), (CARD_W, 0), (CARD_W, CARD_H - cut), (CARD_W - cut, CARD_H), (0, CARD_H), (0, cut)]
        mask = Image.new("L", (CARD_W, CARD_H), 0)
        ImageDraw.Draw(mask).polygon(poly, fill=255)
        face = Image.new("RGBA", (CARD_W, CARD_H), (0, 0, 0, 0))
        face.paste(bg, (0, 0), mask)
        ImageDraw.Draw(face).line(poly + [poly[0]], fill=accent + (255,), width=4)
        label = _text(c.slot.upper(), 22, accent + (255,), CARD_W - 40)
        face.alpha_composite(label, (18, 14))
        name = _text(c.name.upper(), 40, (255, 255, 255, 255), CARD_W - 36)
        face.alpha_composite(name, ((CARD_W - name.width) // 2, CARD_H - 92))
        pips = ImageDraw.Draw(face)
        for k in range(4):
            x = CARD_W / 2 + (k - 1.5) * 26
            pips.ellipse([x - 8, CARD_H - 36, x + 8, CARD_H - 20], fill=(255, 196, 61, 255))
        return fx.glow(face, accent, 14, 0.7)

    def card(self, c: Card, t: float, seed: int) -> Image.Image:
        """[c]'s card at [t]: its killer swinging on a loop, the marks landing each swing."""
        face = self.faces[c].copy()
        pad = (face.width - CARD_W) // 2
        loop = 2 * self.beat
        local = (t + seed * 0.13) % loop
        cell_h = 330
        k = sprites.frame_at(c.pet, "attack", local)
        cell = sprites.cell(c.pet, "attack", k, cell_h)
        ax, by = sprites.anchor(c.pet, cell_h)
        feet = (pad + CARD_W * 0.42, pad + CARD_H - 112)
        face.alpha_composite(cell, (round(feet[0] - ax), round(feet[1] - by)))
        p = (local - 0.22) / (loop - 0.22)
        marks.draw(face, c.pet, c.slot, 3, pad + CARD_W * 0.6, pad + CARD_H * 0.4, CARD_H * 0.42, p,
                   seed=seed * 7 + round(t // loop), unit=1.4)
        return face

    def cards(self, t: float) -> Image.Image:
        canvas = self.storm.copy()
        local = t - self.cards_at
        step = CARD_W + CARD_GAP
        bump = 1 + 0.035 * max(pulse(local % self.beat, 0, 0.18), 0)
        for row, (deck, y, speed) in enumerate(((SPECIALS, H * 0.285, -520), (HEAVIES, H * 0.735, 520))):
            span = step * len(deck)
            for i, c in enumerate(deck):
                x = (i * step + speed * local + (row * step / 2)) % span - step / 2
                if -step < x < W + step:
                    im = self.card(c, local, seed=i + 10 * row)
                    fx.place(canvas, fx.transformed(im, 0.98 * bump, 0), x, y)
        canvas.alpha_composite(self.vignette)
        out = canvas.convert("RGB")
        return fx.flash(out, (255, 255, 255), 0.6 * pulse(local, 0, 0.14))

    def frame(self, t: float) -> Image.Image:
        if t >= self.cards_at:
            return self.cards(t)
        canvas = self.footage(t).convert("RGBA")
        self.title.draw(canvas, t)
        return canvas.convert("RGB")

    def cues(self) -> list[Cue]:
        return [Cue(self.specials_at, cast.voice("special_move"), 1.1), Cue(self.cards_at + 0.1, ATTACKS, 1.15)]
