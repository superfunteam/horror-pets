"""Synthesize every original music loop and sound effect into audio-final/.

Music is Opus in .ogg (ExoPlayer plays it on every supported Android version). Sound effects are
16-bit PCM mono .wav at 44.1 kHz instead: SoundPool cannot decode Opus-in-Ogg below Android 10.

Everything here is generated from scratch with NumPy, so the audio has no third-party license.
Usage: tools/.venv/bin/python -I tools/synth_audio.py [--only NAME ...]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np
from scipy import signal

ROOT = Path(__file__).resolve().parents[1]
SR = 48_000
SFX_SR = 44_100  # sound effects ship as 44.1 kHz mono WAV
SEED = 1313


def rng_for(name: str) -> np.random.Generator:
    """Per-asset generator seeded from the asset name, so `--only NAME` renders exactly what a full run does."""
    return np.random.default_rng([SEED, *name.encode()])


# ---------------------------------------------------------------- primitives

def t_axis(dur: float) -> np.ndarray:
    return np.arange(int(dur * SR)) / SR


def note_hz(name: str) -> float:
    names = {"C": -9, "C#": -8, "Db": -8, "D": -7, "D#": -6, "Eb": -6, "E": -5, "F": -4, "F#": -3,
             "Gb": -3, "G": -2, "G#": -1, "Ab": -1, "A": 0, "A#": 1, "Bb": 1, "B": 2}
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[pitch] + (octave - 4) * 12) / 12)


def env_exp(n: int, decay: float, attack: float = 0.002) -> np.ndarray:
    t = np.arange(n) / SR
    a = np.clip(t / attack, 0, 1) if attack > 0 else 1.0
    return a * np.exp(-t / decay)


def env_adsr(n: int, a: float, d: float, s: float, r: float) -> np.ndarray:
    t = np.arange(n) / SR
    total = n / SR
    e = np.where(t < a, t / max(a, 1e-6),
                 np.where(t < a + d, 1 - (1 - s) * (t - a) / max(d, 1e-6), s))
    rel_start = max(total - r, a + d)
    return e * np.clip((total - t) / max(r, 1e-6), 0, 1) * (t <= total) if r > 0 else e * (t >= 0) * (t <= rel_start + r)


def noise(rng: np.random.Generator, n: int) -> np.ndarray:
    return rng.standard_normal(n)


def brown(rng: np.random.Generator, n: int) -> np.ndarray:
    x = np.cumsum(rng.standard_normal(n))
    x = signal.lfilter([1, -1], [1, -0.995], x)
    return x / (np.abs(x).max() + 1e-9)


def bandpass(x: np.ndarray, lo: float, hi: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, [lo, hi], btype="band", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def lowpass(x: np.ndarray, hz: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, hz, btype="low", fs=SR, output="sos"), x)


def highpass(x: np.ndarray, hz: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, hz, btype="high", fs=SR, output="sos"), x)


def saw(freq: float | np.ndarray, n: int, phase: float = 0.0) -> np.ndarray:
    ph = phase + np.cumsum(np.broadcast_to(freq, (n,)) / SR)
    return 2 * (ph % 1.0) - 1


def sine(freq: float | np.ndarray, n: int, phase: float = 0.0) -> np.ndarray:
    ph = phase + 2 * np.pi * np.cumsum(np.broadcast_to(freq, (n,)) / SR)
    return np.sin(ph)


def reverb(rng: np.random.Generator, x: np.ndarray, seconds: float = 2.5, mix: float = 0.3,
           damp: float = 6000) -> np.ndarray:
    n = int(seconds * SR)
    ir = noise(rng, n) * np.exp(-np.arange(n) / SR * 6.9 / seconds)
    ir = lowpass(ir, damp)
    ir /= np.sqrt((ir ** 2).sum())
    wet = signal.fftconvolve(x, ir)[: len(x)]
    return (1 - mix) * x + mix * wet * 0.8


def place(buf: np.ndarray, sound: np.ndarray, at: float, gain: float = 1.0) -> None:
    i = int(at * SR)
    if i >= len(buf):
        return
    end = min(len(buf), i + len(sound))
    buf[i:end] += sound[: end - i] * gain


def loopify(x: np.ndarray, length: float, fade: float = 2.0) -> np.ndarray:
    """x is rendered `fade` seconds longer than `length`; fold the tail over the head for a seamless loop."""
    n, f = int(length * SR), int(fade * SR)
    out = x[:n].copy()
    ramp = np.linspace(0, 1, f)
    out[:f] = out[:f] * np.sqrt(ramp) + x[n:n + f] * np.sqrt(1 - ramp)
    return out


def normalize(x: np.ndarray, peak_db: float = -1.0) -> np.ndarray:
    p = np.abs(x).max()
    return x if p == 0 else x / p * 10 ** (peak_db / 20)


def edge_fade(x: np.ndarray, ms: float = 5) -> np.ndarray:
    n = int(ms / 1000 * SR)
    x = x.copy()
    x[:n] *= np.linspace(0, 1, n)
    x[-n:] *= np.linspace(1, 0, n)
    return x


def pluck(rng: np.random.Generator, freq: float, dur: float, bright: float = 0.5) -> np.ndarray:
    """Karplus-Strong string pluck."""
    n = int(dur * SR)
    period = int(SR / freq)
    buf = lowpass(noise(rng, period * 4), 900 + 2400 * bright)[-period:]  # softer excitation = rounder pizzicato
    buf = buf / (np.abs(buf).max() + 1e-9) * 0.5
    out = np.zeros(n)
    k = 0
    for i in range(n):
        out[i] = buf[k]
        nxt = (k + 1) % period
        buf[k] = 0.5 * (buf[k] + buf[nxt]) * 0.995
        k = nxt
    return out * env_exp(n, dur * 0.35, 0.001)


def bell(freq: float, dur: float, partials=((1, 1), (2.76, 0.5), (5.4, 0.25), (8.93, 0.12))) -> np.ndarray:
    n = int(dur * SR)
    out = np.zeros(n)
    for ratio, amp in partials:
        out += amp * sine(freq * ratio, n) * env_exp(n, dur / (1 + ratio * 0.6), 0.002)
    return out


def music_box_note(rng: np.random.Generator, freq: float, dur: float = 1.6) -> np.ndarray:
    n = int(dur * SR)
    tone = sine(freq, n) + 0.35 * sine(freq * 2.0, n) + 0.12 * sine(freq * 4.2, n) + 0.06 * sine(freq * 6.8, n)
    click = highpass(noise(rng, n), 3000) * env_exp(n, 0.004)
    return (tone * env_exp(n, 0.55, 0.001) + 0.15 * click) * 0.5


def pad(rng: np.random.Generator, freqs: list[float], dur: float, cutoff: float = 900,
        detune: float = 0.004) -> np.ndarray:
    n = int(dur * SR)
    out = np.zeros(n)
    for f in freqs:
        for d in (-detune, 0, detune):
            out += saw(f * (1 + d), n, rng.random())
    out = lowpass(out, cutoff, 2) / (len(freqs) * 3)
    return out * env_adsr(n, dur * 0.25, 0.1, 1.0, dur * 0.3)


def stereo(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    return np.stack([left, right], axis=1)


def widen(x: np.ndarray, ms: float = 11) -> np.ndarray:
    """Haas-style width; the delay wraps around because every caller passes a seamless loop."""
    return stereo(x, np.roll(x, int(ms / 1000 * SR)))


def level_music(x: np.ndarray, rms_db: float = -20.0, ceiling_db: float = -1.0) -> np.ndarray:
    """Match loudness across tracks, then soft-limit any peaks above the ceiling."""
    x = x * 10 ** (rms_db / 20) / (np.sqrt((x ** 2).mean()) + 1e-12)
    c = 10 ** (ceiling_db / 20)
    return np.where(np.abs(x) > c * 0.8, np.sign(x) * (c * 0.8 + c * 0.2 * np.tanh((np.abs(x) - c * 0.8) / (c * 0.2))), x)


# ---------------------------------------------------------------- music

def music_jason() -> np.ndarray:
    rng = rng_for("jason")
    L, F = 72.0, 3.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    # lake: low brown noise with slow lapping
    lap = 0.6 + 0.4 * np.sin(2 * np.pi * 0.11 * t_axis(L + F))
    mix += lowpass(brown(rng, n), 500) * lap * 0.25
    # crickets: chirp triplets of narrow band noise, scattered
    for _ in range(140):
        at = rng.uniform(0, L + F - 1)
        hz = rng.uniform(3800, 5200)
        chirp = np.zeros(int(0.25 * SR))
        for k in range(3):
            place(chirp, bandpass(noise(rng, int(0.035 * SR)), hz - 150, hz + 150, 2) * env_exp(int(0.035 * SR), 0.012), k * 0.045)
        place(mix, chirp, at, rng.uniform(0.04, 0.12))
    # low drone that swells in and out
    tt = t_axis(L + F)
    swell = 0.5 + 0.5 * np.sin(2 * np.pi * tt / 24 - np.pi / 2)
    drone = lowpass(saw(note_hz("D2"), n) + saw(note_hz("D2") * 1.003, n) + 0.6 * saw(note_hz("A2"), n), 380)
    mix += drone * swell * 0.05
    # breathy pulses: soft filtered breaths in an uneven 3-pulse figure every 6s
    for bar in range(int((L + F) / 6) + 1):  # +1: the last bar starts inside the crossfade tail
        for k, g in enumerate((1.0, 0.7, 0.5)):
            b = bandpass(noise(rng, int(0.16 * SR)), 1800, 3600) * env_adsr(int(0.16 * SR), 0.02, 0.05, 0.4, 0.08)
            place(mix, b, bar * 6 + 1.2 + k * 0.22, 0.05 * g)
    mix = reverb(rng, mix, 2.8, 0.35)
    return widen(loopify(mix, L, F))


FREDDY_MELODY = [  # original minor-key lullaby, (note, beats)
    ("E5", 1), ("C5", 1), ("A4", 2), ("B4", 1), ("C5", 1), ("B4", 1), ("G#4", 1),
    ("A4", 1), ("E4", 1), ("A4", 1), ("C5", 1), ("D5", 2), ("C5", 1), ("B4", 1),
    ("E5", 1), ("F5", 1), ("E5", 2), ("D5", 1), ("C5", 1), ("B4", 1), ("A4", 1),
    ("G#4", 1), ("B4", 1), ("E5", 2), ("D5", 1), ("B4", 1), ("A4", 2),
]
FREDDY_CHORDS = [["A3", "C4", "E4"], ["F3", "A3", "C4"], ["D3", "F3", "A3"], ["E3", "G#3", "B3"]]


def music_freddy() -> np.ndarray:
    rng = rng_for("freddy")
    bpm = 84
    beat = 60 / bpm
    beats = sum(b for _, b in FREDDY_MELODY)  # 32
    L = beats * beat * 3  # three passes: plain, octave up, with a sixth below
    F = 3.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    t = 0.0
    for rep in range(4):  # the 4th pass only feeds the loop crossfade, so it matches pass 1
        style = rep % 3
        for name, b in FREDDY_MELODY:
            hz = note_hz(name) * (1 + rng.uniform(-0.002, 0.002))
            place(mix, music_box_note(rng, hz * (2 if style == 1 else 1)), t, 0.4 if style == 1 else 0.55)
            if style == 2:
                place(mix, music_box_note(rng, hz * 2 ** (-9 / 12)), t + 0.01, 0.25)
            t += b * beat
    bar = 8 * beat
    for i in range(int((L + F) / bar) + 1):
        chord = FREDDY_CHORDS[i % 4]
        place(mix, pad(rng, [note_hz(c) / 2 for c in chord], bar + 1.0, 700), i * bar, 0.35)
    mix = reverb(rng, mix, 3.0, 0.4)
    # slight tape wobble for unease
    mix = mix * (1 + 0.01 * np.sin(2 * np.pi * 0.3 * t_axis((len(mix)) / SR)))
    return widen(loopify(mix, L, F), 17)


def music_pinhead() -> np.ndarray:
    rng = rng_for("pinhead")
    L, F = 80.0, 4.0
    n = int((L + F) * SR)
    tt = t_axis(L + F)
    mix = 0.18 * sine(55.0, n) + 0.16 * sine(55.35, n) + 0.08 * sine(82.41, n) + 0.05 * sine(110.6, n)
    mix *= 0.8 + 0.2 * np.sin(2 * np.pi * tt / 20)
    mix += lowpass(brown(rng, n), 200) * 0.12
    for at in np.arange(3.0, L + F - 4, 9.5):
        place(mix, bell(note_hz("C#3") * rng.choice([1, 1.5, 0.75]), 6.0), at + rng.uniform(-1, 1), 0.22)
    for _ in range(10):
        dur = rng.uniform(0.6, 1.4)
        m = int(dur * SR)
        f = np.linspace(rng.uniform(300, 600), rng.uniform(900, 1400), m)
        creak = bandpass(noise(rng, m) * (0.5 + 0.5 * signal.square(2 * np.pi * np.cumsum(f / 40) / SR * 40)), 250, 1600)
        place(mix, creak * env_adsr(m, 0.1, 0.2, 0.6, 0.3), rng.uniform(0, L), 0.05)
    for _ in range(40):  # distant chain clinks
        place(mix, bell(rng.uniform(1800, 3200), 0.3, ((1, 1), (2.3, 0.4))), rng.uniform(0, L + F - 1), 0.03)
    mix = reverb(rng, mix, 4.5, 0.45, 4000)
    return widen(loopify(mix, L, F), 23)


def music_ghostface() -> np.ndarray:
    rng = rng_for("ghostface")
    bpm = 100
    beat = 60 / bpm
    L = 128 * beat
    F = 2.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    ostinato = ["E3", "G3", "B3", "G3", "E3", "G3", "C4", "B3", "E3", "G3", "B3", "G3", "D#3", "F#3", "B3", "F#3"]
    for i in range(int((L + F) / (beat / 2))):
        name = ostinato[i % len(ostinato)]
        place(mix, pluck(rng, note_hz(name), 0.4, 0.2), i * beat / 2, 0.28)
    cello = lowpass(saw(note_hz("E2"), n) + saw(note_hz("E2") * 1.002, n), 500) * 0.08
    mix += cello * (0.6 + 0.4 * np.sin(2 * np.pi * t_axis(L + F) / (16 * beat)))
    ring_n = int(1.2 * SR)
    tones = sine(440, ring_n) + sine(480, ring_n)
    gate = (signal.square(2 * np.pi * 20 * np.arange(ring_n) / SR) > 0) * env_adsr(ring_n, 0.01, 0.0, 1.0, 0.05)
    ring = lowpass(tones * gate, 3000)
    for k in range(int(L / (16 * beat))):
        at = k * 16 * beat + 12 * beat
        place(mix, ring, at, 0.07)
        place(mix, ring, at + 1.8, 0.05)
    mix = lowpass(reverb(rng, mix, 2.0, 0.25), 5000)
    return widen(loopify(mix, L, F), 9)


def drum_kick() -> np.ndarray:
    m = int(0.35 * SR)
    f = 50 + 110 * np.exp(-np.arange(m) / SR / 0.04)
    return sine(f, m) * env_exp(m, 0.18, 0.001)


def drum_snare(rng: np.random.Generator) -> np.ndarray:
    m = int(0.25 * SR)
    return (0.6 * bandpass(noise(rng, m), 1500, 7000) + 0.5 * sine(190, m)) * env_exp(m, 0.08, 0.001)


def drum_hat(rng: np.random.Generator) -> np.ndarray:
    m = int(0.06 * SR)
    return highpass(noise(rng, m), 7000) * env_exp(m, 0.02, 0.0005)


def drum_tom(hz: float) -> np.ndarray:
    m = int(0.5 * SR)
    f = hz + hz * 0.6 * np.exp(-np.arange(m) / SR / 0.05)
    return sine(f, m) * env_exp(m, 0.25, 0.001)


def music_fight() -> np.ndarray:
    rng = rng_for("fight")
    bpm = 120
    beat = 60 / bpm
    bars = 32  # 32 bars x 4 beats at 120 BPM = 64 s
    L = bars * 4 * beat
    F = 1.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    bass_line = ["E2", "E2", "G2", "E2", "D2", "E2", "B1", "D2"]
    for b in range(int((L + F) / beat)):
        t = b * beat
        if b % 4 in (0, 2):
            place(mix, drum_kick(), t, 0.9)
        if b % 4 in (1, 3):
            place(mix, drum_snare(rng), t, 0.45)
        for h in (0, 0.5):
            place(mix, drum_hat(rng), t + h * beat, 0.18)
        if b % 16 == 15:
            for k, hz in enumerate((140, 110, 85)):
                place(mix, drum_tom(hz), t + k * beat / 3, 0.5)
        m = int(beat * 0.9 * SR)
        bass = lowpass(saw(note_hz(bass_line[b % 8]), m) + 0.5 * sine(note_hz(bass_line[b % 8]), m), 600)
        place(mix, bass * env_adsr(m, 0.005, 0.1, 0.6, 0.05), t, 0.28)
    stab_chords = [["E3", "B3", "E4", "G4"], ["C3", "G3", "C4", "E4"], ["D3", "A3", "D4", "F#4"], ["B2", "F#3", "B3", "D#4"]]
    for bar in range(bars + 1):
        chord = stab_chords[bar % 4]
        m = int(0.4 * SR)
        st = sum(saw(note_hz(c) * (1 + d), m) for c in chord for d in (-0.003, 0.003))
        cutoff_env = 400 + 3000 * np.exp(-np.arange(m) / SR / 0.08)
        st = signal.lfilter(*signal.butter(2, 2000, fs=SR), st)  # gentle tame
        st = st * env_exp(m, 0.18, 0.004) * (cutoff_env / cutoff_env.max())
        place(mix, st, bar * 4 * beat + 2.5 * beat, 0.08)
    mix = reverb(rng, mix, 1.2, 0.15)
    return widen(loopify(mix, L, F), 7)


def music_switcher() -> np.ndarray:
    rng = rng_for("switcher")
    L, F = 60.0, 3.0
    n = int((L + F) * SR)
    tt = t_axis(L + F)
    raw = saw(note_hz("C2"), n) + saw(note_hz("C2") * 1.004, n) + 0.7 * saw(note_hz("G2") * 0.998, n)
    cutoff = 250 + 200 * (0.5 + 0.5 * np.sin(2 * np.pi * tt / 15))
    out = np.zeros(n)
    step = SR // 10
    for i in range(0, n, step):  # block-wise filter sweep
        out[i:i + step] = lowpass(raw[max(0, i - 4800):i + step], float(cutoff[i]))[-len(raw[i:i + step]):]
    mix = out * 0.25 + lowpass(brown(rng, n), 300) * 0.15
    for at in np.arange(5, L + F - 3, 15):
        boom = drum_tom(42)
        place(mix, lowpass(boom, 200), at, 0.6)
    mix = reverb(rng, mix, 3.5, 0.4)
    return widen(loopify(mix, L, F), 13)


# ---------------------------------------------------------------- sfx

def swoosh(rng: np.random.Generator, dur: float, lo: float, hi: float, down: bool = True) -> np.ndarray:
    m = int(dur * SR)
    out = np.zeros(m)
    seg = SR // 100
    freqs = np.linspace(hi, lo, m // seg + 1) if down else np.linspace(lo, hi, m // seg + 1)
    src = noise(rng, m)
    for k, i in enumerate(range(0, m, seg)):
        f = freqs[k]
        out[i:i + seg] = bandpass(src[max(0, i - 2 * seg):i + seg], f * 0.7, min(f * 1.4, SR / 2 - 100))[-len(src[i:i + seg]):]
    return out * np.sin(np.linspace(0, np.pi, m)) ** 1.5


def punch(rng: np.random.Generator, weight: float = 1.0) -> np.ndarray:
    m = int(0.4 * SR)
    f = 55 + 120 * np.exp(-np.arange(m) / SR / (0.03 * weight))
    body = sine(f, m) * env_exp(m, 0.12 * weight, 0.001)
    crack = bandpass(noise(rng, m), 800, 5000) * env_exp(m, 0.015 * weight, 0.0005)
    return body + 0.6 * crack


def clang(rng: np.random.Generator, base: float = 520, dur: float = 0.6) -> np.ndarray:
    return bell(base, dur, ((1, 1), (2.41, 0.7), (3.87, 0.5), (5.13, 0.4), (7.2, 0.25))) + \
        0.4 * highpass(noise(rng, int(dur * SR)), 2000) * env_exp(int(dur * SR), 0.01)


def sfx_all() -> dict[str, np.ndarray]:
    rng = rng_for("sfx")
    s: dict[str, np.ndarray] = {}
    m = int(0.06 * SR)
    s["ui_move"] = sine(1250, m) * env_exp(m, 0.015) + 0.2 * highpass(noise(rng, m), 4000) * env_exp(m, 0.003)
    m = int(0.18 * SR)
    blip = lambda hz, d: sine(hz, int(d * SR)) * env_exp(int(d * SR), d / 3)  # noqa: E731
    s["ui_select"] = np.concatenate([blip(880, 0.07), blip(1320, 0.11)])
    s["ui_back"] = np.concatenate([blip(990, 0.07), blip(660, 0.11)])
    crunch = lambda: bandpass(noise(rng, int(0.12 * SR)), 300, 1800) * env_exp(int(0.12 * SR), 0.04) + 0.6 * punch(rng, 0.4)[: int(0.12 * SR)]  # noqa: E731
    chomp = np.zeros(int(0.55 * SR))
    place(chomp, crunch(), 0.0)
    place(chomp, crunch(), 0.24, 0.85)
    s["chomp"] = chomp
    m = int(0.5 * SR)
    f = np.linspace(300, 900, m) * (1 + 0.03 * np.sin(2 * np.pi * 18 * np.arange(m) / SR))
    sparkle = np.zeros(m)
    for k in range(5):
        place(sparkle, bell(2000 + 400 * k, 0.2), 0.25 + 0.04 * k, 0.15)
    s["play"] = sine(f, m) * env_adsr(m, 0.01, 0.1, 0.7, 0.15) * 0.6 + sparkle
    s["train"] = swoosh(rng, 0.35, 400, 4000) * 0.9
    s["hit"] = punch(rng, 1.0)
    s["heavy_hit"] = punch(rng, 1.8) + 0.5 * lowpass(noise(rng, int(0.4 * SR)), 300) * env_exp(int(0.4 * SR), 0.15)
    crit = punch(rng, 1.6)
    ring = clang(rng, 1400, 0.6)
    crit_out = np.zeros(int(0.7 * SR))
    place(crit_out, crit, 0)
    place(crit_out, ring, 0.0, 0.35)
    s["crit"] = crit_out
    s["block"] = clang(rng, 520, 0.5)
    s["miss"] = swoosh(rng, 0.28, 600, 3000) * 0.7
    sj = np.zeros(int(0.9 * SR))
    for k in range(3):
        place(sj, swoosh(rng, 0.18, 1500, 7000), k * 0.16)
    place(sj, clang(rng, 2200, 0.5), 0.5, 0.4)
    s["special_jason"] = sj
    sf = np.zeros(int(1.2 * SR))
    for k in range(4):
        mm = int(0.6 * SR)
        fr = np.linspace(2600 + 150 * k, 1700 + 150 * k, mm)
        place(sf, sine(fr, mm) * env_exp(mm, 0.25) * 0.3 + bandpass(noise(rng, mm), 2000, 6000) * env_exp(mm, 0.1) * 0.3, k * 0.03)
    s["special_freddy"] = reverb(rng, sf, 1.5, 0.45)
    sp = np.zeros(int(1.4 * SR))
    for _ in range(18):
        place(sp, bell(rng.uniform(1500, 3500), 0.25, ((1, 1), (2.3, 0.5))), rng.uniform(0, 0.8), 0.35)
    place(sp, punch(rng, 2.2), 0.75, 0.9)
    s["special_pinhead"] = reverb(rng, sp, 1.8, 0.35)
    sg = np.zeros(int(1.0 * SR))
    for k in range(5):
        place(sg, swoosh(rng, 0.12, 2000, 8000) + 0.4 * punch(rng, 0.5)[: int(0.12 * SR)], k * 0.11)
    rn = int(0.3 * SR)
    place(sg, lowpass((sine(440, rn) + sine(480, rn)) * (signal.square(2 * np.pi * 20 * np.arange(rn) / SR) > 0), 3000) * 0.25, 0.65)
    s["special_ghostface"] = sg
    boom_n = int(2.0 * SR)
    boom = sine(40 + 60 * np.exp(-np.arange(boom_n) / SR / 0.08), boom_n) * env_exp(boom_n, 0.6, 0.002)
    s["ko"] = reverb(rng, boom + 0.3 * lowpass(noise(rng, boom_n), 400) * env_exp(boom_n, 0.3), 2.5, 0.4)
    sting = pad(rng, [note_hz("A2"), note_hz("C3"), note_hz("E3"), note_hz("G#3")], 3.0, 1200)
    place(sting, bell(note_hz("A4"), 2.5), 0.2, 0.3)
    s["tombstone"] = reverb(rng, sting, 2.5, 0.4)
    gl = np.zeros(int(0.6 * SR))
    for k in range(10):
        mm = int(rng.uniform(0.02, 0.06) * SR)
        tone = signal.square(2 * np.pi * rng.uniform(200, 2000) * np.arange(mm) / SR) * 0.3 + noise(rng, mm) * 0.2
        place(gl, np.round(tone * 4) / 4, k * 0.055)
    s["gotcha"] = gl
    lv = np.zeros(int(1.2 * SR))
    for k, nm in enumerate(["C5", "E5", "G5", "C6"]):
        place(lv, bell(note_hz(nm), 0.8, ((1, 1), (2, 0.3), (3, 0.1))), k * 0.09, 0.5)
    s["levelup"] = reverb(rng, lv, 1.5, 0.3)
    rf = np.zeros(int(0.5 * SR))
    for k, hz in enumerate((330, 247)):
        mm = int(0.18 * SR)
        place(rf, lowpass(saw(hz, mm), 1500) * env_adsr(mm, 0.01, 0.05, 0.8, 0.04), k * 0.2, 0.5)
    s["refuse"] = rf
    return s


# ---------------------------------------------------------------- output

def write_ogg(x: np.ndarray, dest: Path, bitrate: str) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    x = np.clip(x, -1, 1)
    channels = 1 if x.ndim == 1 else x.shape[1]
    tmp = ROOT / "art-work/audio" / (dest.stem + ".wav")
    tmp.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(tmp), "-c:a", "libopus", "-b:a", bitrate,
                    "-vbr", "on", str(dest)], check=True)


def write_wav(x: np.ndarray, dest: Path) -> None:
    """Write a sound effect as 16-bit PCM mono WAV at SFX_SR (resampled from the synth rate SR)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if x.ndim != 1:
        x = x.mean(axis=1)
    g = np.gcd(SFX_SR, SR)
    y = signal.resample_poly(np.clip(x, -1, 1), SFX_SR // g, SR // g)
    with wave.open(str(dest), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SFX_SR)
        w.writeframes((np.clip(y, -1, 1) * 32767).astype("<i2").tobytes())


MUSIC = {"jason": music_jason, "freddy": music_freddy, "pinhead": music_pinhead,
         "ghostface": music_ghostface, "fight": music_fight, "switcher": music_switcher}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append")
    args = ap.parse_args()
    out = ROOT / "audio-final"
    for name, fn in MUSIC.items():
        if args.only and name not in args.only:
            continue
        x = level_music(fn())
        write_ogg(x, out / "music" / f"{name}.ogg", "96k")
        print(f"music/{name}.ogg {len(x) / SR:.1f}s")
    if not args.only or "sfx" in args.only:
        for name, x in sfx_all().items():
            write_wav(normalize(edge_fade(x), -1.0), out / "sfx" / f"{name}.wav")
            print(f"sfx/{name}.wav {len(x) / SR:.2f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
