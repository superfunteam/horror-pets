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


def piano_note(rng: np.random.Generator, freq: float, dur: float = 2.5, bright: float = 1.0) -> np.ndarray:
    """Struck-string tone: slightly stretched partials, faster decay for higher partials, a soft hammer thump."""
    n = int(dur * SR)
    out = np.zeros(n)
    for k, amp in enumerate((1.0, 0.5, 0.3, 0.15, 0.08, 0.05), start=1):
        fk = k * freq * np.sqrt(1 + 0.0004 * k * k)
        if fk > SR / 2 - 1000:
            break
        out += amp * bright ** (k - 1) * sine(fk, n) * env_exp(n, dur / (2.2 + 0.6 * k), 0.003)
    hammer = lowpass(noise(rng, n), 1800) * env_exp(n, 0.012, 0.001) * 0.08
    return (out + hammer) * 0.4


def toy_piano_note(rng: np.random.Generator, freq: float, dur: float = 1.0) -> np.ndarray:
    """Bright, clangy toy-piano tine: strong inharmonic partials and a quick decay."""
    n = int(dur * SR)
    tone = sine(freq, n) + 0.5 * sine(freq * 3.01, n) + 0.25 * sine(freq * 5.4, n)
    click = highpass(noise(rng, n), 4000) * env_exp(n, 0.003)
    return (tone * env_exp(n, 0.32, 0.001) + 0.2 * click) * 0.45


CHUCKY_MELODY = [  # original minor-key toy waltz, (note or None for rest, beats); 3 beats per bar
    ("E5", 1), ("D#5", 1), ("E5", 1), ("C5", 2), ("A4", 1), ("B4", 1), ("A4", 1), ("G#4", 1), ("A4", 3),
    ("E5", 1), ("F5", 1), ("E5", 1), ("D5", 2), ("B4", 1), ("C5", 1), ("B4", 1), ("G#4", 1), ("A4", 2), (None, 1),
    ("A5", 1), ("G5", 1), ("F5", 1), ("E5", 2), ("C5", 1), ("D5", 1), ("C5", 1), ("B4", 1), ("C5", 3),
    ("F5", 1), ("E5", 1), ("D#5", 1), ("E5", 2), ("B4", 1), ("C5", 1), ("B4", 1), ("G#4", 1), ("A4", 3),
]
CHUCKY_BARS = [["A2", "C4", "E4"], ["A2", "C4", "E4"], ["E2", "B3", "G#4"], ["A2", "C4", "E4"],
               ["A2", "C4", "E4"], ["D2", "F3", "A3"], ["E2", "B3", "G#3"], ["A2", "C4", "E4"],
               ["F2", "A3", "C4"], ["C2", "E3", "G3"], ["G2", "B3", "D4"], ["C2", "E3", "G3"],
               ["D2", "F3", "A3"], ["A2", "C4", "E4"], ["E2", "B3", "G#3"], ["A2", "C4", "E4"]]


def music_chucky() -> np.ndarray:
    rng = rng_for("chucky")
    bpm = 112
    beat = 60 / bpm
    bars = 40  # the 16-bar tune two and a half times: A B A B A
    L = bars * 3 * beat
    F = 2.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    tune_beats = sum(b for _, b in CHUCKY_MELODY)  # 48 = 16 bars
    t, i = 0.0, 0
    while t < L + F:
        name, b = CHUCKY_MELODY[i % len(CHUCKY_MELODY)]
        if name:
            place(mix, toy_piano_note(rng, note_hz(name)), t, 0.6)
        t += b * beat
        i += 1
        if i % len(CHUCKY_MELODY) == 0:
            t = round(t / (tune_beats * beat)) * tune_beats * beat
    for bar in range(int((L + F) / (3 * beat)) + 1):
        root, *upper = CHUCKY_BARS[bar % len(CHUCKY_BARS)]
        t0 = bar * 3 * beat
        place(mix, pluck(rng, note_hz(root), 0.6, 0.15), t0, 0.5)
        for k in (1, 2):
            for u in upper:
                place(mix, pluck(rng, note_hz(u), 0.3, 0.3), t0 + k * beat, 0.12)
        if bar % 8 == 7:  # a wind-up ratchet before each new phrase
            for c in range(12):
                m = int(0.012 * SR)
                place(mix, bandpass(noise(rng, m), 1500, 5000) * env_exp(m, 0.004), t0 + 1.4 + 0.05 * c * (1 - c / 30), 0.12)
    drone = lowpass(saw(note_hz("A1"), n) + saw(note_hz("A1") * 1.006, n), 220) * 0.05
    mix += drone
    mix = reverb(rng, mix, 1.6, 0.25)
    return widen(loopify(mix, L, F), 9)


MICHAEL_CHORDS = [["B2", "F#3", "B3", "C#4", "D4"], ["G2", "D3", "B3", "D4", "F#4"],
                  ["E2", "B2", "G3", "B3", "E4"], ["F#2", "C#3", "A#3", "C#4", "F#4"]]


def music_michael() -> np.ndarray:
    rng = rng_for("michael")
    eighth = 1 / 3  # 6/8 at 60 dotted-quarter beats per minute
    bar = 6 * eighth
    bars = 36
    L = bars * bar
    F = 3.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    shape = [0, 2, 3, 4, 3, 2]  # rising and falling arpeggio over each chord
    for b in range(int((L + F) / bar) + 1):
        chord = MICHAEL_CHORDS[(b // 2) % len(MICHAEL_CHORDS)]
        for k, idx in enumerate(shape):
            place(mix, piano_note(rng, note_hz(chord[idx]) * 2, 1.8, 0.8), b * bar + k * eighth, 0.32)
        place(mix, piano_note(rng, note_hz(chord[0]) / 2, 4.0, 0.6), b * bar, 0.35)
        for beat_at, gain in ((0.0, 0.5), (0.28, 0.35)):  # a slow heartbeat under every bar
            m = int(0.3 * SR)
            thump = sine(45 + 30 * np.exp(-np.arange(m) / SR / 0.03), m) * env_exp(m, 0.09, 0.002)
            place(mix, thump, b * bar + bar / 2 + beat_at, gain * 0.5)
    tt = t_axis(L + F)
    drone = lowpass(saw(note_hz("B1"), n) + saw(note_hz("B1") * 1.004, n), 300) * (0.04 + 0.02 * np.sin(2 * np.pi * tt / 18))
    mix += drone
    for at in np.arange(9.0, L + F - 4, 12.0):  # a high, distant note now and then
        place(mix, piano_note(rng, note_hz("F#6"), 3.0, 0.5), at, 0.12)
    mix = reverb(rng, mix, 3.2, 0.38, 5000)
    return widen(loopify(mix, L, F), 15)


def chainsaw(rng: np.random.Generator, rpm_hz: np.ndarray) -> np.ndarray:
    """Two-stroke engine buzz: a clipped sawtooth at the firing rate plus throaty noise, given a per-sample rate curve."""
    n = len(rpm_hz)
    engine = np.tanh(3.0 * (saw(rpm_hz, n) + 0.5 * saw(rpm_hz * 2.01, n)))
    chain = bandpass(noise(rng, n), 1800, 6000) * (0.5 + 0.5 * np.sin(2 * np.pi * np.cumsum(rpm_hz * 4) / SR))
    return lowpass(engine, 3500) * 0.7 + chain * 0.25


def music_leatherface() -> np.ndarray:
    rng = rng_for("leatherface")
    bpm = 96
    beat = 60 / bpm
    L = 104 * beat  # 26 bars of 4
    F = 2.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    tt = t_axis(L + F)
    drone = np.tanh(2.5 * (saw(note_hz("E1"), n) + saw(note_hz("E1") * 1.007, n) + 0.5 * saw(note_hz("B1"), n)))
    mix += lowpass(drone, 420) * (0.07 + 0.03 * np.sin(2 * np.pi * tt / 13))
    pattern = [0, 0.75, 1.5, 2.5, 3.0, 3.5]  # lopsided junk-percussion figure, one bar
    for bar in range(int((L + F) / (4 * beat)) + 1):
        for k, b in enumerate(pattern):
            t = bar * 4 * beat + b * beat
            hz = rng.uniform(300, 900) if k % 2 else rng.uniform(140, 260)
            place(mix, bell(hz, 0.35, ((1, 1), (2.27, 0.6), (3.91, 0.4), (6.3, 0.2))), t, 0.18)
            place(mix, highpass(noise(rng, int(0.05 * SR)), 2500) * env_exp(int(0.05 * SR), 0.01), t, 0.12)
        place(mix, drum_kick(), bar * 4 * beat, 0.6)
        if bar % 2:
            m = int(0.6 * SR)
            scrape = bandpass(noise(rng, m), 600, 2400) * env_adsr(m, 0.05, 0.2, 0.5, 0.25)
            place(mix, scrape, bar * 4 * beat + 2 * beat, 0.08)
    for at in np.arange(6.0, L + F - 4, 16.0):  # a chainsaw idling somewhere out in the yard
        m = int(2.8 * SR)
        rpm = 48 + 6 * np.sin(2 * np.pi * np.arange(m) / SR * 3.1)
        idle = lowpass(chainsaw(rng, rpm), 900) * env_adsr(m, 0.6, 0.2, 1.0, 0.9)
        place(mix, idle, at, 0.10)
    mix = reverb(rng, mix, 1.4, 0.22)
    return widen(loopify(mix, L, F), 11)


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


def drum_clap(rng: np.random.Generator) -> np.ndarray:
    """A clap to double the snare: a second burst of noise, brighter and shorter than the snare's."""
    m = int(0.18 * SR)
    return bandpass(noise(rng, m), 2500, 11000) * env_exp(m, 0.05, 0.001)


def noise_riser(rng: np.random.Generator, dur: float) -> np.ndarray:
    """Noise that swells and brightens over `dur`, stopping dead on the beat after it."""
    m = int(dur * SR)
    p = np.linspace(0, 1, m)
    x = noise(rng, m)
    swept = (1 - p) * bandpass(x, 300, 1800) + p * bandpass(x, 2500, 12000)
    return edge_fade(swept * p ** 2, 5)


# The fight music's bass line (one note a beat) and its stabs (one chord a bar), shared by its intense take.
FIGHT_BASS = ["E2", "E2", "G2", "E2", "D2", "E2", "B1", "D2"]
FIGHT_STABS = [["E3", "B3", "E4", "G4"], ["C3", "G3", "C4", "E4"], ["D3", "A3", "D4", "F#4"], ["B2", "F#3", "B3", "D#4"]]


def fight_stab(chord: list[str]) -> np.ndarray:
    """One of the fight music's stabs: a detuned saw chord with a filter blip."""
    m = int(0.4 * SR)
    st = sum(saw(note_hz(c) * (1 + d), m) for c in chord for d in (-0.003, 0.003))
    cutoff_env = 400 + 3000 * np.exp(-np.arange(m) / SR / 0.08)
    st = signal.lfilter(*signal.butter(2, 2000, fs=SR), st)  # gentle tame
    return st * env_exp(m, 0.18, 0.004) * (cutoff_env / cutoff_env.max())


def music_fight() -> np.ndarray:
    rng = rng_for("fight")
    bpm = 120
    beat = 60 / bpm
    bars = 32  # 32 bars x 4 beats at 120 BPM = 64 s
    L = bars * 4 * beat
    F = 1.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
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
        bass = lowpass(saw(note_hz(FIGHT_BASS[b % 8]), m) + 0.5 * sine(note_hz(FIGHT_BASS[b % 8]), m), 600)
        place(mix, bass * env_adsr(m, 0.005, 0.1, 0.6, 0.05), t, 0.28)
    for bar in range(bars + 1):
        place(mix, fight_stab(FIGHT_STABS[bar % 4]), bar * 4 * beat + 2.5 * beat, 0.08)
    mix = reverb(rng, mix, 1.2, 0.15)
    return widen(loopify(mix, L, F), 7)


def music_fight_intense() -> np.ndarray:
    """The fight music hardened, for when a fighter is nearly done. Same tempo, length, bass line and stabs as
    `music_fight`, so the app can crossfade into it from the same place without the beat skipping; on top, a kick on
    every beat, a clap on the snare, a saw lead on the off-beats, louder stabs and a riser into every 16 bars. The
    toms fill every 4 bars, as in `music_fight`."""
    rng = rng_for("fight_intense")
    bpm = 120
    beat = 60 / bpm
    bars = 32  # 32 bars x 4 beats at 120 BPM = 64 s, as music_fight
    L = bars * 4 * beat
    F = 1.0
    n = int((L + F) * SR)
    mix = np.zeros(n)
    for b in range(int((L + F) / beat)):
        t = b * beat
        place(mix, drum_kick(), t, 0.9)
        if b % 4 in (1, 3):
            place(mix, drum_snare(rng), t, 0.45)
            place(mix, drum_clap(rng), t, 0.35)
        for h in (0, 0.5):
            place(mix, drum_hat(rng), t + h * beat, 0.18)
        if b % 16 == 15:
            for k, hz in enumerate((140, 110, 85)):
                place(mix, drum_tom(hz), t + k * beat / 3, 0.5)
        hz = note_hz(FIGHT_BASS[b % 8])
        m = int(beat * 0.9 * SR)
        bass = lowpass(saw(hz, m) + 0.5 * sine(hz, m), 600)
        place(mix, bass * env_adsr(m, 0.005, 0.1, 0.6, 0.05), t, 0.28)
        # the lead: the bass note an octave up, on the off-beat
        m = int(beat * 0.45 * SR)
        lead = lowpass(saw(2 * hz, m) + saw(2 * hz * 1.005, m), 2400)
        place(mix, lead * env_adsr(m, 0.003, 0.06, 0.5, 0.04), t + beat / 2, 0.12)
    for bar in range(bars + 1):
        place(mix, fight_stab(FIGHT_STABS[bar % 4]), bar * 4 * beat + 2.5 * beat, 0.14)
        if bar % 16 == 15:  # a riser over the last bar of every 16, landing on the next downbeat
            place(mix, noise_riser(rng, 4 * beat), bar * 4 * beat, 0.3)
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


JAWS_CHORDS = [  # (bass root, cello voicing), one per 12 s; six chords make the 72 s loop. No two-note ostinato anywhere.
    ("D1", ["D2", "A2", "F3"]),        # D minor
    ("Bb1", ["F2", "Bb2", "Db3"]),     # B-flat minor
    ("F#1", ["F#2", "A2", "C3"]),      # F-sharp diminished: the tritone leans on you
    ("A1", ["A2", "C3", "Eb3"]),       # A diminished
    ("G#1", ["G#2", "B2", "D#3"]),     # G-sharp minor
    ("A1", ["D2", "F2", "A2"]),        # D minor over its dominant pedal: pulls back to the top of the loop
]


def loop_bump(tt: np.ndarray, centre: float, half: float, length: float) -> np.ndarray:
    """A smooth 0..1 hill centred on `centre`, measured around the loop so it wraps seamlessly."""
    d = (tt - centre + length / 2) % length - length / 2
    return np.where(np.abs(d) < half, np.cos(np.pi * d / (2 * half)) ** 2, 0.0)


def bowed_string(rng: np.random.Generator, hz: float, dur: float, attack: float, release: float) -> np.ndarray:
    """A section of low strings on one note: four detuned players, slow bow swell, a vibrato that blooms late."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    vib = 1 + 0.003 * np.clip((t - 1.0) / 2.0, 0, 1) * np.sin(2 * np.pi * rng.uniform(4.6, 5.4) * t + rng.uniform(0, 6.28))
    out = sum(saw(hz * vib * (1 + d), n, rng.random()) for d in (-0.006, -0.002, 0.003, 0.007)) / 4
    out += 0.4 * lowpass(noise(rng, n), 1800) * 0.05  # rosin
    env = np.clip(t / attack, 0, 1) ** 1.6 * np.clip((dur - t) / release, 0, 1)
    return out * env * (0.88 + 0.12 * np.sin(2 * np.pi * t / rng.uniform(3.5, 5.5) + rng.uniform(0, 6.28)))


def jaws_thud(rng: np.random.Generator, hz: float) -> np.ndarray:
    """One beat of the creeping pulse: a fat low string struck with the wood of the bow."""
    n = int(0.55 * SR)
    t = np.arange(n) / SR
    body = sine(hz * (1 + 0.5 * np.exp(-t / 0.02)), n) * env_exp(n, 0.17, 0.003)
    string = lowpass(saw(hz, n), 650) * env_exp(n, 0.22, 0.004) * 0.6
    snap = bandpass(noise(rng, n), 250, 1400) * env_exp(n, 0.012, 0.0005) * 0.3
    return body + string + snap


def music_jaws() -> np.ndarray:
    rng = rng_for("jaws")
    L, F = 72.0, 4.0
    n = int((L + F) * SR)
    tt = t_axis(L + F)
    # tension: quiet creep, a long climb, a hard cut, then the sea closes over it and the loop starts again
    tension = np.interp(tt % L, [0, 14, 56, 60, 68, 72], [0.0, 0.05, 1.0, 0.8, 0.0, 0.0])
    cello = np.zeros(n)
    bass = np.zeros(n)
    for k in range(7):  # the 7th is the first chord again, so the fold-over at the loop point is a real chord change
        root, voicing = JAWS_CHORDS[k % 6]
        for name in voicing:
            place(cello, bowed_string(rng, note_hz(name), 16.0, 3.0, 4.0), k * 12.0, 1 / len(voicing))
        place(bass, bowed_string(rng, note_hz(root), 17.0, 3.5, 4.5), k * 12.0, 1.0)
        sub = sine(note_hz(root), int(17.0 * SR)) * np.clip(np.arange(int(17.0 * SR)) / SR / 3.5, 0, 1) \
            * np.clip((17.0 - np.arange(int(17.0 * SR)) / SR) / 4.5, 0, 1)
        place(bass, sub, k * 12.0, 0.5)
    # the cellos get brighter, and start to shiver, as the tension climbs
    shiver = 4 + 9 * tension
    trem = 1 + 0.45 * tension * np.sin(2 * np.pi * np.cumsum(shiver) / SR)
    cello = (lowpass(cello, 420) * (1 - tension) + lowpass(cello, 1700, 3) * tension) * trem * (0.55 + 0.45 * tension)
    bass = lowpass(bass, 200, 3) * (0.8 + 0.5 * tension)
    # sub-bass swells breathing under everything
    swell = 0.35 + 0.65 * np.maximum.reduce([loop_bump(tt, 30, 7, L), loop_bump(tt, 46, 6, L), loop_bump(tt, 64, 9, L)])
    sub_swell = sine(note_hz("D1"), n) * swell * 0.1
    # the sea: a low rumble, waves sliding up the shore, and three big swells (the last one swallows the loop point)
    waves = 0.6 * (0.5 + 0.5 * np.sin(2 * np.pi * tt / (L / 8) - np.pi / 2)) ** 2 \
        + 0.4 * (0.5 + 0.5 * np.sin(2 * np.pi * tt / (L / 11))) ** 2
    ocean = highpass(lowpass(brown(rng, n), 380), 45) * (0.35 + 0.4 * waves) * 0.5
    ocean += bandpass(noise(rng, n), 500, 3200) * waves * 0.05
    big = loop_bump(tt, 28, 5, L) + loop_bump(tt, 44, 5, L) + 1.5 * loop_bump(tt, 66, 8, L)
    ocean += (lowpass(noise(rng, n), 260) * 0.2 + bandpass(noise(rng, n), 300, 2600) * 0.18 * big) * big
    # hull groans: slow, sagging notes from the cellos' lowest register
    groans = np.zeros(n)
    for at, hz in ((21.0, 150.0), (37.0, 128.0), (53.0, 140.0)):
        m = int(5.0 * SR)
        fr = np.linspace(hz, hz * 0.84, m)
        g = lowpass(saw(fr, m) + saw(fr * 1.004, m), 700) * env_adsr(m, 1.8, 0.4, 0.7, 2.0)
        place(groans, g, at, 0.08)
    # the creeping pulse: one low string note per beat, on the chord's root, speeding up, until the cut
    pulse = np.zeros(n)
    t0, t1, ioi0, ioi1 = 3.2, 58.0, 2.4, 0.30
    t = t0
    while t < t1:
        prog = (t - t0) / (t1 - t0)
        root = note_hz(JAWS_CHORDS[int(t // 12) % 6][0]) * 2
        place(pulse, jaws_thud(rng, root), t, 0.18 + 0.82 * prog ** 1.4)
        t += ioi0 * (ioi1 / ioi0) ** prog
    place(pulse, lowpass(drum_tom(42), 200), t1 + 0.35, 1.6)  # the last beat lands heavy, then nothing but sea
    mix = 0.3 * cello + 0.14 * bass + sub_swell + ocean + groans + 0.3 * pulse
    mix = reverb(rng, mix, 3.5, 0.3, 4500)
    return widen(loopify(mix, L, F), 13)


SPECIAL_RIFF = ["E2", "E2", "G2", "E2", "A2", "E2", "Bb2", "A2"]  # one bar of eighths, E minor like the fight


def music_special_riff() -> np.ndarray:
    """A special's riff: 4 bars at 120 BPM that loop over the ducked fight music while a special plays."""
    rng = rng_for("special_riff")
    bpm = 120
    beat = 60 / bpm
    bar = 4 * beat
    L = 4 * bar  # 8 s
    F = 0.5
    n = int((L + F) * SR)
    mix = np.zeros(n)
    tt = t_axis(L + F)
    # a saturated saw bass riff in eighths
    eighth = beat / 2
    for k in range(int((L + F) / eighth) + 1):  # +1: the next bar's first notes play into the crossfade tail
        hz = note_hz(SPECIAL_RIFF[k % 8])
        m = int(eighth * 0.92 * SR)
        raw = saw(hz, m) + saw(hz * 1.006, m)
        note = lowpass(np.tanh(3.5 * raw), 1300) * env_adsr(m, 0.003, 0.08, 0.6, 0.03)
        place(mix, note, k * eighth, 0.25)
    # a tom roll down on every fourth beat
    for b in range(int((L + F) / beat) + 1):
        if b % 4 == 3:
            for k, hz in enumerate((180, 150, 120, 95)):
                place(mix, drum_tom(hz), b * beat + k * beat / 4, 0.45)
    # hats on sixteenths
    for k in range(int((L + F) / (beat / 4)) + 1):
        place(mix, drum_hat(rng), k * beat / 4, 0.2 if k % 2 == 0 else 0.13)
    # a siren: a sine sweeping 400 -> 900 Hz over each bar, faded at the ends so the drop back down is silent
    phase = (tt % bar) / bar
    siren = sine(400 + 500 * phase, n) * np.clip(phase / 0.1, 0, 1) * np.clip((1 - phase) / 0.08, 0, 1)
    mix += siren * 0.06
    mix = reverb(rng, mix, 0.8, 0.12)
    return widen(loopify(mix, L, F), 7)


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


def explosion_crackle(rng: np.random.Generator, dur: float, density: float) -> np.ndarray:
    """Burning debris: sparse random pops, thinning out over `dur`."""
    m = int(dur * SR)
    t = np.arange(m) / SR
    pops = (rng.random(m) < density * np.exp(-t / (dur * 0.4))) * rng.standard_normal(m) * rng.uniform(0.3, 1.0, m)
    return bandpass(pops, 1200, 9000) * 6


def jaws_special() -> np.ndarray:
    """A scuba tank spat out (pop, hiss, a whoosh that builds as it flies), then it blows up on impact."""
    jr = rng_for("special_jaws")  # its own stream, so the sounds before it are untouched
    hit = 0.78  # where the tank lands
    out = np.zeros(int(2.4 * SR))
    pn = int(0.07 * SR)
    place(out, lowpass(noise(jr, pn), 900) * env_exp(pn, 0.015, 0.001) * 0.7
          + sine(260 * np.exp(-np.arange(pn) / SR / 0.03) + 90, pn) * env_exp(pn, 0.03, 0.001) * 0.5, 0.0, 0.5)
    hn = int(0.8 * SR)
    ht = np.arange(hn) / SR
    hiss = highpass(noise(jr, hn), 3200) * (1 + 0.35 * np.sin(2 * np.pi * 11 * ht)) * env_exp(hn, 0.4, 0.01)
    place(out, hiss, 0.02, 0.07)
    place(out, swoosh(jr, 1.4, 250, 3500, down=False)[: int(hit * SR)], 0.03, 0.35)  # the first half of a swoosh: builds all the way in
    for k, at in enumerate((0.20, 0.34, 0.47, 0.58, 0.67)):  # the tank tumbles: little clanks on the way
        place(out, bell(jr.uniform(900, 1500), 0.18, ((1, 1), (2.41, 0.6), (3.87, 0.4))) * 0.5, at, 0.1 + 0.03 * k)
    n = int(1.6 * SR)
    t = np.arange(n) / SR
    boom = sine(32 + 95 * np.exp(-t / 0.1), n) * env_exp(n, 0.5, 0.002)
    boom += 0.5 * lowpass(noise(jr, n), 260) * env_exp(n, 0.45, 0.002)
    crack = bandpass(noise(jr, n), 180, 7000) * env_exp(n, 0.06, 0.0005)
    roar = bandpass(noise(jr, n), 120, 1800) * env_adsr(n, 0.03, 0.25, 0.25, 1.1) * 0.5  # the fireball
    blast = 1.0 * boom + 0.9 * crack + 0.6 * roar
    blast += explosion_crackle(jr, 1.6, 0.006) * 0.12
    place(out, blast, hit, 1.0)
    for k in range(34):  # debris raining down: bits of metal, then rubble, thinning out
        at = hit + 0.1 + jr.exponential(0.45)
        if at > 2.05:
            continue
        if k % 3 == 0:
            piece = bell(jr.uniform(1600, 5200), 0.22, ((1, 1), (2.76, 0.5), (5.4, 0.3))) * 0.5
            piece += highpass(noise(jr, len(piece)), 4000) * env_exp(len(piece), 0.004) * 0.4
        else:
            ln = int(jr.uniform(0.02, 0.07) * SR)
            piece = bandpass(noise(jr, ln), jr.uniform(150, 600), jr.uniform(900, 2500)) * env_exp(ln, 0.02) * 1.4
        place(out, piece, at, jr.uniform(0.1, 0.3) * np.exp(-(at - hit) / 0.8))
    out = reverb(jr, out, 1.4, 0.22, 5000)
    out = np.tanh(2.2 * out / np.abs(out).max()) / np.tanh(2.2)  # glue: a hot, crushed bang rather than a polite one
    return lowpass(out, 15000, 4)  # the crushed top would ring past -1 dB when it is resampled to 44.1 kHz


def challenger_hit() -> np.ndarray:
    """\"Here comes a new challenger\": two huge low notes, a short one and a long one a tritone below."""
    cr = rng_for("challenger_hit")
    out = np.zeros(int(2.0 * SR))

    def note(hz: float, dur: float, decay: float) -> np.ndarray:
        n = int(dur * SR)
        t = np.arange(n) / SR
        slam = hz * (1 + 0.6 * np.exp(-t / 0.03))
        low = sine(slam, n) * env_exp(n, decay, 0.002)
        growl = np.tanh(3.0 * lowpass(saw(slam, n) + saw(slam * 1.006, n), 520)) * env_exp(n, decay * 0.8, 0.003) * 0.6
        grit = lowpass(signal.square(2 * np.pi * np.cumsum(slam * 2) / SR), 1100) * env_exp(n, decay * 0.35, 0.002) * 0.2
        thump = bandpass(noise(cr, n), 90, 1400) * env_exp(n, 0.03, 0.0005) * 0.6
        return (low + growl + grit + thump) * np.clip((dur - t) / 0.05, 0, 1)

    place(out, note(note_hz("Bb1"), 0.42, 0.11), 0.0, 0.8)
    place(out, note(note_hz("E1"), 1.7, 0.55), 0.36, 1.0)
    return reverb(cr, out, 1.6, 0.25, 3500)


def ko_boom() -> np.ndarray:
    """The knockout blow's low boom: a sub sine dropping 90 -> 32 Hz under a short thud and a saturated low saw."""
    kr = rng_for("ko_boom")  # its own stream, so the sounds before it are untouched
    n = int(0.9 * SR)
    t = np.arange(n) / SR
    hz = 90 * (32 / 90) ** np.minimum(t / 0.5, 1.0)  # the drop: 90 Hz down to 32 over 0.5 s, then held
    sub = sine(hz, n)
    growl = np.tanh(3.0 * lowpass(saw(hz, n) + saw(hz * 1.01, n), 400)) * 0.35
    bn = int(0.03 * SR)
    thud = np.zeros(n)
    thud[:bn] = bandpass(noise(kr, bn), 60, 900) * env_exp(bn, 0.008, 0.0005)
    out = (sub + growl) * env_exp(n, 0.35, 0.002) + 0.7 * thud
    out[-int(0.1 * SR):] *= np.linspace(1, 0, int(0.1 * SR))  # a soft tail, not a cut
    return out


def snore() -> np.ndarray:
    """A sleeper's snore, 4 s and looping: a breath in (brown noise swelling under a 300 Hz lowpass), 0.4 s of quiet,
    and a breath out (a soft reed buzz: a 70 Hz saw through a 400 Hz lowpass, with a slow 6 Hz tremolo)."""
    sr = rng_for("snore")  # its own stream, so the sounds before it are untouched
    length, fade = 4.0, 0.3
    out = np.zeros(int((length + fade) * SR))  # rendered `fade` longer, for loopify to fold the tail over the head
    n_in = int(1.4 * SR)
    t_in = np.arange(n_in) / SR
    breath_in = lowpass(brown(sr, n_in), 300) * (t_in / 1.4) ** 1.6  # it swells...
    breath_in[-int(0.08 * SR):] *= np.linspace(1, 0, int(0.08 * SR))  # ...and is caught short
    n_out = int(1.2 * SR)
    t_out = np.arange(n_out) / SR
    tremolo = 0.65 + 0.35 * np.sin(2 * np.pi * 6 * t_out)
    breath_out = lowpass(saw(70, n_out), 400) * tremolo * env_adsr(n_out, 0.15, 0.25, 0.55, 0.6) * 0.5
    place(out, breath_in, 0.35)
    place(out, breath_out, 0.35 + 1.4 + 0.4)  # the breath out starts 0.4 s of quiet after the breath in ends
    return loopify(out, length, fade)


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
    sc = np.zeros(int(1.2 * SR))
    chant = pad(rng, [note_hz("D2"), note_hz("A2"), note_hz("D3")], 0.9, 700)
    chant = bandpass(chant * (1 + 0.3 * np.sin(2 * np.pi * 6 * np.arange(len(chant)) / SR)), 200, 1200) * 3
    place(sc, chant, 0.0, 0.6)
    for k in range(3):
        place(sc, swoosh(rng, 0.1, 2500, 8000) + 0.5 * punch(rng, 0.6)[: int(0.1 * SR)], 0.55 + k * 0.13)
    s["special_chucky"] = reverb(rng, sc, 1.2, 0.3)
    sm = np.zeros(int(1.6 * SR))
    place(sm, swoosh(rng, 0.45, 300, 3500), 0.0, 0.9)
    place(sm, punch(rng, 2.6), 0.42, 1.0)
    m = int(0.7 * SR)
    place(sm, bandpass(noise(rng, m), 350, 1100) * env_adsr(m, 0.15, 0.1, 0.6, 0.35), 0.8, 0.35)
    s["special_michael"] = reverb(rng, sm, 1.8, 0.3)
    m = int(1.5 * SR)
    t = np.arange(m) / SR
    rpm = np.where(t < 0.25, 30 + 40 * t / 0.25, np.minimum(165, 70 + 380 * (t - 0.25)))  # pull, catch, rev
    rpm = rpm * (1 + 0.04 * np.sin(2 * np.pi * 7 * t))
    lf = rng_for("special_leatherface")  # its own stream, so adding it leaves the rest of the bank unchanged
    saw_fx = chainsaw(lf, rpm) * env_adsr(m, 0.02, 0.1, 1.0, 0.25)
    pull = bandpass(noise(lf, int(0.2 * SR)), 300, 1500) * env_exp(int(0.2 * SR), 0.06)
    place(saw_fx, pull, 0.0, 0.6)
    s["special_leatherface"] = saw_fx
    fd = rng_for("food")  # its own stream too, so the food sounds leave the rest of the bank unchanged
    m = int(0.6 * SR)
    t = np.arange(m) / SR
    hz = 1900 * (480 / 1900) ** (t / t[-1]) * (1 + 0.025 * np.sin(2 * np.pi * 6 * t))  # a falling slide whistle
    whistle = (sine(hz, m) + 0.15 * sine(2 * hz, m)) * env_adsr(m, 0.03, 0.1, 0.8, 0.05) * 0.6
    mm = int(0.25 * SR)
    plop = sine(70 + 220 * np.exp(-np.arange(mm) / SR / 0.05), mm) * env_exp(mm, 0.07)
    plop += 0.35 * bandpass(noise(fd, mm), 150, 900) * env_exp(mm, 0.03)
    drop = np.zeros(int(0.9 * SR))
    place(drop, whistle, 0.0)
    place(drop, plop, 0.6, 0.9)
    s["food_drop"] = drop  # lands (the plop) 0.6 s in: FoodDrop's fall takes exactly that long
    mm = int(0.16 * SR)
    s["bite"] = bandpass(noise(fd, mm), 300, 2200) * env_exp(mm, 0.035) + 0.6 * punch(fd, 0.35)[:mm]
    pl = rng_for("play")  # the Play hand's gags, on their own stream as well
    s["hand_whoosh"] = swoosh(pl, 0.32, 500, 3500, down=False)
    mm = int(0.18 * SR)
    t = np.arange(mm) / SR
    s["boop"] = sine(300 + 700 * (1 - np.exp(-t / 0.03)), mm) * env_exp(mm, 0.06) + 0.4 * punch(pl, 0.25)[:mm]
    tk = np.zeros(int(0.55 * SR))
    for k in range(9):  # a squeaky warble, like a giggling toy
        mm = int(0.045 * SR)
        hz = pl.uniform(1500, 2600) * (1 + 0.25 * np.sin(2 * np.pi * 30 * np.arange(mm) / SR))
        place(tk, sine(hz, mm) * env_adsr(mm, 0.005, 0.01, 0.7, 0.015), k * 0.058, pl.uniform(0.5, 0.9))
    s["tickle"] = tk
    jg = np.zeros(int(0.9 * SR))
    for k in range(11):  # keys knocking together on their ring
        clink = bell(pl.uniform(2200, 4200), 0.35, ((1, 1), (2.76, 0.6), (5.4, 0.35), (8.9, 0.2)))
        place(jg, clink + 0.3 * highpass(noise(pl, len(clink)), 5000) * env_exp(len(clink), 0.004), pl.uniform(0, 0.6),
              pl.uniform(0.25, 0.6))
    s["jingle"] = jg
    mm = int(0.7 * SR)
    t = np.arange(mm) / SR
    ul = rng_for("unlock")  # the unlock fanfare, on its own stream
    fan = np.zeros(int(1.6 * SR))
    for k, nm in enumerate(["G4", "C5", "E5", "G5", "C6"]):
        place(fan, bell(note_hz(nm), 1.0, ((1, 1), (2, 0.35), (3, 0.12))), k * 0.07, 0.45)
    shimmer = highpass(noise(ul, int(1.0 * SR)), 6000) * env_adsr(int(1.0 * SR), 0.2, 0.3, 0.4, 0.5) * 0.15
    place(fan, shimmer, 0.3)
    s["unlock"] = reverb(ul, fan, 1.8, 0.35)
    pt = rng_for("potion")  # the fight's potion, on its own stream
    pot = np.zeros(int(0.9 * SR))
    place(pot, clang(pt, 1900, 0.35) * 0.35, 0.0)  # the glass flask lands
    poof = bandpass(noise(pt, int(0.45 * SR)), 300, 2500) * env_adsr(int(0.45 * SR), 0.01, 0.08, 0.5, 0.3)
    place(pot, poof * 0.8, 0.03)
    for k in range(7):  # it bubbles, then sparkles
        bm = int(0.06 * SR)
        hz = pt.uniform(500, 1100) * np.linspace(1.0, 1.6, bm)
        place(pot, sine(hz, bm) * env_exp(bm, 0.02) * 0.5, 0.08 + k * 0.045)
    for k, nm in enumerate(["E6", "G#6", "B6", "E7"]):
        place(pot, bell(note_hz(nm), 0.5, ((1, 1), (2.0, 0.25))) * 0.22, 0.32 + k * 0.06)
    s["potion"] = reverb(pt, pot, 1.2, 0.25)
    s["boing"] = sine(180 * (1 + 0.35 * np.sin(2 * np.pi * 9 * t) * np.exp(-t / 0.25)) + 120 * np.exp(-t / 0.05), mm) \
        * env_exp(mm, 0.25)
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
    # Slash Says' eight tones, one per ring slot: the C-major pentatonic from C5, soft bells. Appended last and built
    # from no random stream, so the sounds above stay byte for byte what they were.
    for k, nm in enumerate(["C5", "D5", "E5", "G5", "A5", "C6", "D6", "E6"]):
        tone = bell(note_hz(nm), 0.45, ((1, 1), (2, 0.3), (3, 0.1), (4.2, 0.04)))
        tone[-int(0.1 * SR):] *= np.linspace(1, 0, int(0.1 * SR))  # a soft tail, not a cut
        s[f"tone_{k + 1}"] = tone
    # The secret killer's two (appended last, each on its own random stream, so nothing above changes).
    s["special_jaws"] = jaws_special()
    s["challenger_hit"] = challenger_hit()
    # The knockout's boom (appended last, on its own random stream, so nothing above changes).
    s["ko_boom"] = ko_boom()
    # A sleeper's snore (appended last, on its own random stream, so nothing above changes).
    s["snore"] = snore()
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
         "ghostface": music_ghostface, "chucky": music_chucky, "michael": music_michael, "leatherface": music_leatherface, "fight": music_fight, "switcher": music_switcher,
         "jaws": music_jaws, "special_riff": music_special_riff, "fight_intense": music_fight_intense}  # new tracks go at the end


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
