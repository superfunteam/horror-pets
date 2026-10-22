"""Timing and easing for scenes: every curve takes and returns 0..1, and ramp() maps a stretch of time onto one."""
from __future__ import annotations

import math
import random
from typing import Callable

Ease = Callable[[float], float]


def clamp01(x: float) -> float:
    return 0.0 if x < 0 else 1.0 if x > 1 else x


def lerp(a: float, b: float, u: float) -> float:
    return a + (b - a) * u


def linear(u: float) -> float:
    return u


def in_cubic(u: float) -> float:
    return u * u * u


def in_quint(u: float) -> float:
    return u ** 5


def out_cubic(u: float) -> float:
    return 1 - (1 - u) ** 3


def out_expo(u: float) -> float:
    return 1.0 if u >= 1 else 1 - 2 ** (-10 * u)


def out_back(u: float, s: float = 1.9) -> float:
    """Overshoots past 1 and settles back, for things that land with a bounce."""
    v = u - 1
    return 1 + (s + 1) * v ** 3 + s * v ** 2


def in_out_sine(u: float) -> float:
    return -(math.cos(math.pi * u) - 1) / 2


def ramp(t: float, t0: float, t1: float, ease: Ease = linear) -> float:
    """How far through [t0, t1] t is, eased: 0 before, 1 after."""
    return ease(clamp01((t - t0) / (t1 - t0)))


def pulse(t: float, t0: float, dur: float) -> float:
    """A hit at t0 that falls off quickly over dur: 1 at t0, 0 before it and from t0 + dur on."""
    if t < t0 or t >= t0 + dur:
        return 0.0
    return (1 - (t - t0) / dur) ** 2


def jitter(t: float, fps: int, seed: int) -> tuple[float, float]:
    """A fresh random direction each frame (the same one every render), for shakes."""
    rng = random.Random(round(t * fps) * 7919 + seed)
    return rng.uniform(-1, 1), rng.uniform(-1, 1)
