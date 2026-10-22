"""Online PvP on two real phones (3D, see phones.py), each playing its own side of one real match.

The match was recorded on two phone emulators, Jason hosting and Freddy joining (art-work/video/capture/pvp-*.mp4),
and each phone's home screen beside it (home-*-phone.mp4). The phones fly in out of the storm, each showing its own
killer at home, then swing round and press back to back, an NFC burst between them, and the tap is all it takes: both
screens cut straight to the VS card as they spin out to face us, FIGHT! under the title, and on the drop they fight:
the same turns on both screens, each from its own side: blows traded back and forth, a last exchange, and a K.O. that
reads YOU WIN on one phone and YOU LOSE on the other.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

from . import cast, fx, phones
from .footage import Clip
from .lettering import Slam
from .motion import in_cubic, in_out_sine, jitter, lerp, out_cubic, pulse, ramp
from .render import FPS, H, W, Cue

TITLE = cast.ROOT / "video/art/title-pvp-local.webp"
STORM = cast.ROOT / "site/art/backdrop.webp"
RIM = (255, 60, 110)
NFC = (150, 215, 255)
LAG = 1.667  # Freddy's recording runs this far behind Jason's (lined up on Jason's super)
# Where each phone's VS card starts in its recording, and how long the stretch through FIGHT! runs.
VS_JASON, VS_FREDDY, VS_LEN = 30.3, 29.0, 5.0
FIGHT_SLAM = 1.13 + 1.89  # FIGHT! lands this long after the VS card starts, in Jason's
# The blows traded, in Jason's recording (Freddy's runs LAG behind): Jason cuts Freddy, Freddy's claws drain Jason.
TRADES = [(84.7, 86.9), (92.6, 95.4), (108.6, 110.8)]
# In Jason's K.O. recording (Freddy's runs a second behind): the last exchange (both call their moves, Jason lands a
# combo, Freddy whiffs) until K.O. lands, and the results.
KO_EXCHANGE, KO_LANDS, RESULTS = (6.6, 11.3), 10.75, (13.3, 16.8)
TAP_LINE = cast.ROOT / "video/voice/tap.wav"  # "Tap to KILL your friends!" (tools/generate_video_voice.py)
HALF_W, HALF_D = 8.0, 0.58  # half a phone's width, and half its depth with the camera bar (backs touch 1.16 apart)
START_YAW, START_GAP = 0.15, 40.0  # how the phones start: turned a little toward each other, this far out of place


def clear_x(yaw: float, gap: float) -> float:
    """How far from the middle a phone turned [yaw] about its upright axis must sit for its nearest point to be
    [gap] from it: half its width while it faces us, down to half its depth (with the camera bar) edge-on."""
    return HALF_W * abs(math.cos(yaw)) + HALF_D * abs(math.sin(yaw)) + gap


@dataclass(frozen=True)
class Feed:
    """What one phone shows from [at] (seconds into the scene): [clip] played over [length] seconds."""
    at: float
    length: float
    clip: Clip


def feed(file: str, at: float, length: float, start: float, end: float) -> Feed:
    return Feed(at, length, Clip(file, start, end, speed=(end - start) / length))


class OnlinePvp:
    """[bar] seconds a bar, [beat] a beat: [approach] bars of the phones flying in and turning to meet in one move, the
    tap on the next downbeat, [vs] bars of VS after that bar, [turns] bars of blows traded, then two bars of the last
    exchange and the K.O., and [results] bars of the results."""

    def __init__(self, bar: float, beat: float, approach: float = 3, vs: int = 2, turns: int = 2, results: int = 2):
        self.bar, self.beat = bar, beat
        b = bar
        self.tap = approach * b
        self.vs_at = self.tap + b
        self.fight_at = self.vs_at + vs * b
        self.ko = self.fight_at + turns * b  # the last exchange
        self.results_at = self.ko + 2 * b
        self.duration = self.results_at + results * b
        stretch = (KO_EXCHANGE[1] - KO_EXCHANGE[0]) / (2 * b)
        self.ko_hit = self.ko + (KO_LANDS - KO_EXCHANGE[0]) / stretch  # when K.O. hits the screens
        played = TRADES[:turns]
        # Jason's phone (left) and Freddy's (right): the same moments, each recording's own times.
        self.feeds = tuple(
            [feed(f"home-{who}-phone.mp4", 0, self.tap, 1.5, 1.5 + self.tap),
             feed(f"pvp-{who}.mp4", self.tap, self.fight_at - self.tap, vs_from, vs_from + VS_LEN)]
            + [feed(f"pvp-{who}.mp4", self.fight_at + k * b, b, s + lag, e + lag) for k, (s, e) in enumerate(played)]
            + [feed(f"pvp-{who}-ko.mp4", self.ko, 2 * b, KO_EXCHANGE[0] + ko_lag, KO_EXCHANGE[1] + ko_lag),
               feed(f"pvp-{who}-ko.mp4", self.results_at, results * b, RESULTS[0] + ko_lag, RESULTS[1] + ko_lag)]
            for who, vs_from, lag, ko_lag in (("jason", VS_JASON, 0.0, 0.0), ("freddy", VS_FREDDY, LAG, 1.0))
        )
        storm = fx.cover(Image.open(STORM).convert("RGB"), round(W * 1.1), round(H * 1.1))
        self.storm = ImageEnhance.Brightness(storm.filter(ImageFilter.GaussianBlur(3))).enhance(0.38)
        self.vignette = fx.vignette(W, H, 0.75)
        self.embers = fx.Embers(50, 17, [RIM, (255, 170, 80), (255, 235, 200)], W, H)
        self.title = Slam(Image.open(TITLE).convert("RGBA"), (W / 2, H * 0.245), 960, land=self.vs_at + 0.12,
                          leave=self.fight_at - 0.25, exit_dx=0, glint_at=self.vs_at + 0.9)
        self._shots: dict[int, phones.Shot] | None = None

    # The phones --------------------------------------------------------------------------------------------------

    def screen(self, side: int, t: float) -> str:
        f = next(f for f in reversed(self.feeds[side]) if f.at <= t)
        return str(f.clip.path(t - f.at))

    def shot(self, t: float) -> phones.Shot:
        """Where the phones and camera are at [t], and what each screen shows. The phones never pass through each
        other: while they turn, each keeps as far from the middle as its turn needs (clear_x), so they meet back to
        back, and part the same way."""
        if t < self.tap:  # one smooth move: flying in from the sides while turning edge-on, until their backs touch
            u = t / self.tap
            swing = in_out_sine(u)
            yaw = lerp(START_YAW, math.pi / 2, swing)
            x = clear_x(yaw, START_GAP * (1 - u) ** 1.7)
            z, still = lerp(-8, 0, out_cubic(min(1.0, u / 0.6))), swing
            orbit, dist, lift = 0.75 * swing, lerp(44, 36, swing), 5 * swing
        elif t < self.vs_at:  # a little recoil from the tap, then parting and spinning back to face us
            out = ramp(t, self.tap + 0.1, self.vs_at - 0.2, out_cubic)
            yaw = lerp(math.pi / 2, 0.12, out)
            x = max(lerp(0.58, 9.2, out), clear_x(yaw, 0.3 * out)) + 0.25 * pulse(t, self.tap, 0.14)
            z, still = 0.0, 1 - out
            back = ramp(t, self.tap, self.vs_at, out_cubic)
            orbit, dist, lift = lerp(0.75, 0, back), lerp(36, 44, back), lerp(5, 0, back)
        else:
            near = ramp(t, self.fight_at - 0.3, self.fight_at + 0.2, out_cubic)
            x, yaw, z, still = lerp(9.2, 8.45, near), lerp(0.12, 0.08, near), 0.0, 0.0
            orbit, dist, lift = 0.0, lerp(44, 36, near), 0.0
        # Drifting while apart; held still and level while pressed together.
        bob, tilt = 0.25 * math.sin(t * 2.1) * (1 - still), 0.04 * (1 - still)
        # Lowered under the title while it is up, then centered for the fight.
        y = -3.4 * ramp(t, self.tap + 0.2, self.vs_at, out_cubic) * (1 - ramp(t, self.fight_at - 0.3, self.fight_at + 0.2, out_cubic))
        punch = 1 + 0.03 * pulse(t % self.beat, 0, 0.15) * (t >= self.fight_at)
        cam = (dist * math.sin(orbit), lift, dist * math.cos(orbit))
        left = phones.Phone((-x, y + bob, z), (tilt * math.sin(t * 1.3), -yaw, 0.0), self.screen(0, t), scale=punch)
        right = phones.Phone((x, y - bob, z), (tilt * math.cos(t * 1.1), yaw, 0.0), self.screen(1, t), scale=punch)
        return phones.Shot((left, right), camera=cam, rim=RIM)

    def shots(self) -> dict[int, phones.Shot]:
        if self._shots is None:
            self._shots = {i: self.shot(i / FPS) for i in range(round(self.duration * FPS) + 1)}
        return self._shots

    def prepare(self) -> None:
        for side in self.feeds:
            for f in side:
                f.clip.extract()
        phones.render_all(list(self.shots().values()))

    # Drawing -------------------------------------------------------------------------------------------------------

    def burst(self, canvas: Image.Image, t: float) -> None:
        """The NFC tap: rings rippling out from where the phones meet, and a flash."""
        u = ramp(t, self.tap, self.tap + 0.7)
        if not 0 < u < 1:
            return
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        for k in range(4):
            r = 60 + (u * 900 - k * 120)
            if r > 20:
                a = round(220 * (1 - u) * (1 - k * 0.2))
                d.ellipse([W / 2 - r, H / 2 - r * 0.8, W / 2 + r, H / 2 + r * 0.8], outline=NFC + (a,), width=10 - 2 * k)
        canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(2)))
        fx.place(canvas, fx.radial(900, 900, NFC, 0.8 * (1 - u)), W / 2, H / 2)

    def frame(self, t: float) -> Image.Image:
        hit = 30 * pulse(t, self.tap, 0.35) + 26 * pulse(t, self.ko_hit, 0.4)
        jx, jy = jitter(t, FPS, 23)
        dx, dy = jx * hit, jy * hit
        z = 1 + 0.05 * t / self.duration
        bw, bh = self.storm.size
        vw, vh = W * 1.1 / z, H * 1.1 / z
        cx = min(max(bw / 2 - dx, vw / 2), bw - vw / 2)
        cy = min(max(bh / 2 - dy, vh / 2), bh - vh / 2)
        box = (cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2)
        canvas = self.storm.resize((W, H), Image.BILINEAR, box=box).convert("RGBA")
        self.embers.draw(canvas, t, False, dx, dy)
        self.burst(canvas, t)
        shot = self.shots()[min(round(t * FPS), len(self.shots()) - 1)]
        fx.place(canvas, phones.load(shot), W / 2 + dx, H / 2 + dy)
        self.title.draw(canvas, t, dx, dy)
        self.embers.draw(canvas, t, True, dx, dy)
        canvas.alpha_composite(self.vignette)
        out = canvas.convert("RGB")
        out = fx.chroma(out, 12 * pulse(t, self.tap, 0.25) + 10 * pulse(t, self.ko_hit, 0.25))
        return fx.flash(out, (255, 255, 255), max(0.75 * pulse(t, self.tap, 0.16), 0.4 * pulse(t, self.ko_hit, 0.12)))

    def cues(self) -> list[Cue]:
        fight = self.tap + FIGHT_SLAM * (self.fight_at - self.tap) / VS_LEN
        return [Cue(self.tap + 0.25, TAP_LINE, 1.15), Cue(fight, cast.voice("fight"), 1.1),
                Cue(self.ko_hit, cast.voice("ko"), 1.1)]
