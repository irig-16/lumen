"""
"First Light" — the score for Lumen's intro (10.5 s, stereo, original), locked to IntroScene's timeline:
  0.6–1.6 s  a warm, breathing drone under the ember
  1.6 s      ignition: a bright electric click, a sparkle burst and a whoosh as the spark darts away
  2.45, 3.15, 3.85, 4.55, 5.25 s
             the spark touches L, U, M, E, N: five rising celesta chimes (E5 G5 A5 C6 E6), each with a
             soft pluck in the bass and a filament buzz-flicker, panned to follow the letter
  5.3–6.0 s  a riser as the spark climbs
  6.0 s      the burst: a warm C-major bloom (strings, soft brass, sub), a shimmer that hangs in the air
  7–10.5 s   the chord breathes and fades with the picture
The file starts with PREROLL seconds of silence: on Bluetooth or a home theatre that silence wakes the
link before the first note (Lumen skips it on the projector's own speaker).
Usage: python3 make_intro_music.py out.wav   (then encode to res/raw/intro.ogg)
"""
import sys
import wave
import numpy as np
from scipy.signal import lfilter

SR = 32000
DUR = 10.5
N = int(SR * DUR)
PREROLL = 1.2
rng = np.random.default_rng(11)
L = np.zeros(N)
R = np.zeros(N)

TOUCH = [2.45, 3.15, 3.85, 4.55, 5.25]
IGNITE, BURST = 1.6, 6.0


def t_arr(sec):
    return np.arange(int(SR * sec)) / SR


def place(sig, at, gain=1.0, pan=0.0):
    """Adds a mono signal at time `at` (s) with equal-power panning (-1 left … 1 right)."""
    i = int(at * SR)
    if i >= N:
        return
    n = min(len(sig), N - i)
    a = (pan + 1) * np.pi / 4
    L[i:i + n] += sig[:n] * gain * np.cos(a)
    R[i:i + n] += sig[:n] * gain * np.sin(a)


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    return lfilter([1 - a], [1, -a], x)


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def hz(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def adsr(t, a, d, s, r, length):
    e = np.ones_like(t) * s
    e[t < a] = t[t < a] / a
    m = (t >= a) & (t < a + d)
    e[m] = 1 - (1 - s) * (t[m] - a) / d
    rel = t > length - r
    e[rel] *= np.clip((length - t[rel]) / r, 0, 1)
    return e


# ---------------------------------------------------------------- instruments

def celesta(f, length=2.2):
    t = t_arr(length)
    tone = (np.sin(2 * np.pi * f * t) + 0.45 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t * 6)
            + 0.22 * np.sin(2 * np.pi * 3.01 * f * t) * np.exp(-t * 9) + 0.12 * np.sin(2 * np.pi * 4.2 * f * t) * np.exp(-t * 14))
    return tone * np.exp(-t * 2.6) * np.minimum(1, t / 0.003)


def pluck(f, length=0.9):
    t = t_arr(length)
    x = np.sin(2 * np.pi * f * t) + 0.5 * np.sin(2 * np.pi * 2 * f * t)
    return lowpass(x * np.exp(-t * 7), 1800) * np.minimum(1, t / 0.004)


def pad(freqs, length, attack=1.2, release=2.5, bright=2200):
    t = t_arr(length)
    out = np.zeros_like(t)
    for f in freqs:
        for det in (-0.12, 0.0, 0.12):
            ff = f * 2 ** (det / 12)
            ph = rng.uniform(0, 2 * np.pi)
            out += (2 * ((ff * t + ph / (2 * np.pi)) % 1) - 1) * 0.33   # soft saw choir
    out = lowpass(out, bright)
    return out * adsr(t, attack, 0.5, 0.85, release, length) / len(freqs)


def brass(freqs, length, attack=0.35, release=2.4):
    t = t_arr(length)
    out = np.zeros_like(t)
    for f in freqs:
        vib = 1 + 0.004 * np.sin(2 * np.pi * 5 * t)
        ph = np.cumsum(2 * np.pi * f * vib / SR)
        out += np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.3 * np.sin(3 * ph) + 0.15 * np.sin(4 * ph)
    out = lowpass(out, 1400)
    return out * adsr(t, attack, 0.6, 0.7, release, length) / len(freqs)


def noise(length):
    return rng.uniform(-1, 1, int(SR * length))


def whoosh(length, f0, f1):
    """A noise sweep from f0 to f1 Hz."""
    n = noise(length)
    out = np.zeros_like(n)
    seg = 64
    for k in range(0, len(n), int(SR * 0.02)):
        p = k / len(n)
        fc = f0 * (f1 / f0) ** p
        out[k:k + int(SR * 0.02)] = highpass(lowpass(n, fc * 1.6), fc * 0.5)[k:k + int(SR * 0.02)]
    t = t_arr(length)
    return out * np.sin(np.pi * np.clip(t / length, 0, 1)) ** 1.5


def click():
    t = t_arr(0.25)
    snap = highpass(noise(0.25), 2500) * np.exp(-t * 60)
    zap = np.sin(2 * np.pi * np.cumsum(3000 * np.exp(-t * 25) + 400) / SR) * np.exp(-t * 18)
    return snap * 0.8 + zap * 0.5


def buzz(length=0.32):
    """A filament warming up: a 100 Hz electric buzz that flickers on/off like the letter."""
    t = t_arr(length)
    b = np.sign(np.sin(2 * np.pi * 100 * t)) * 0.5 + np.sin(2 * np.pi * 200 * t) * 0.5
    gate = np.ones_like(t)
    for a, z in ((0.06, 0.11), (0.16, 0.21)):
        gate[(t >= a) & (t < z)] = 0.15
    return lowpass(b, 900) * gate * np.exp(-t * 5)


def sub_drop(length=3.5, f0=80, f1=34):
    t = t_arr(length)
    f = f1 + (f0 - f1) * np.exp(-t * 4)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 1.0) * np.minimum(1, t / 0.01)


def shimmer(length, base=2093):
    t = t_arr(length)
    out = np.zeros_like(t)
    for k, f in enumerate([base, base * 1.5, base * 2, base * 2.52, base * 3]):
        trem = 0.5 + 0.5 * np.sin(2 * np.pi * (5 + k * 1.3) * t + k)
        out += np.sin(2 * np.pi * f * t) * trem
    return out * adsr(t, 0.4, 0.4, 0.8, 2.5, length) / 5


def reverb(x, mix=0.32):
    """Small Schroeder reverb: four combs into two all-passes."""
    out = np.zeros_like(x)
    for d, g in ((1557, 0.84), (1617, 0.83), (1491, 0.85), (1422, 0.86)):
        out += lfilter([1], np.r_[1, np.zeros(d - 1), -g], x)
    out /= 4
    for d, g in ((225, 0.5), (556, 0.5)):
        out = lfilter(np.r_[-g, np.zeros(d - 1), 1], np.r_[1, np.zeros(d - 1), -g], out)
    return x * (1 - mix) + out * mix


# ---------------------------------------------------------------- the score

# the ember: a warm, breathing drone (C2 + G2) with a faint high air
t = t_arr(5.6)
drone = (np.sin(2 * np.pi * hz(36) * t) + 0.6 * np.sin(2 * np.pi * hz(43) * t)) * (0.6 + 0.4 * np.sin(2 * np.pi * 1.0 * t - np.pi / 2))
drone *= adsr(t, 1.0, 0.3, 0.8, 1.2, 5.6)
place(lowpass(drone, 400), 0.6, 0.22)
place(lowpass(highpass(noise(1.2), 4000), 9000) * np.sin(np.pi * t_arr(1.2) / 1.2), 0.5, 0.02)

# ignition
place(click(), IGNITE, 0.55)
place(celesta(hz(88), 1.5), IGNITE + 0.01, 0.12, 0.3)
place(celesta(hz(91), 1.5), IGNITE + 0.05, 0.08, -0.3)
place(whoosh(0.5, 900, 6000), IGNITE + 0.05, 0.22, -0.8)            # darts off to the left

# the dance: whooshes between the letters, a chime + pluck + buzz on each touch
notes = [76, 79, 81, 84, 88]            # E5 G5 A5 C6 E6
bass = [48, 52, 55, 57, 60]             # C3 E3 G3 A3 C4
pans = [-0.7, -0.35, 0.0, 0.35, 0.7]    # follows the letter across the screen
prev = 2.0
for i, tt in enumerate(TOUCH):
    place(whoosh(tt - prev + 0.05, 500, 2400), prev, 0.10, pans[i] - 0.3)
    place(celesta(hz(notes[i])), tt, 0.30, pans[i])
    place(celesta(hz(notes[i] + 12), 1.2), tt + 0.01, 0.07, pans[i])
    place(pluck(hz(bass[i])), tt, 0.28, pans[i] * 0.5)
    place(buzz(), tt, 0.06, pans[i])
    prev = tt

# the climb to the burst
rt = t_arr(0.8)
riser = whoosh(0.8, 400, 9000) * (rt / 0.8) ** 2
place(riser, 5.25, 0.32)
place(pad([hz(55), hz(60), hz(64)], 1.2, attack=0.7, release=0.3, bright=1500) * (t_arr(1.2) / 1.2), 4.9, 0.22)

# the burst: a warm C-major bloom
chord = [hz(36), hz(48), hz(55), hz(60), hz(64), hz(67), hz(74)]   # C2 C3 G3 C4 E4 G4 D5 (add9)
place(sub_drop(), BURST, 0.55)
place(highpass(noise(0.6), 3000) * np.exp(-t_arr(0.6) * 7), BURST, 0.18)
place(pad(chord[1:], 4.5, attack=0.25, release=2.6, bright=3000), BURST, 0.55, -0.15)
place(pad(chord[1:], 4.5, attack=0.3, release=2.6, bright=3000), BURST + 0.012, 0.55, 0.15)
place(brass([hz(48), hz(55), hz(64)], 3.6, attack=0.18), BURST, 0.35)
place(celesta(hz(84), 2.8), BURST + 0.02, 0.18, -0.2)
place(celesta(hz(91), 2.8), BURST + 0.18, 0.12, 0.25)
place(shimmer(4.4), BURST + 0.15, 0.06)
place(celesta(hz(96), 2.5), 7.0, 0.07, 0.4)      # the tagline appears

# mix
L = reverb(L)
R = reverb(np.roll(R, 41))
mixd = np.stack([L, R], axis=1)
mixd = np.tanh(mixd * 1.1)
mixd /= np.max(np.abs(mixd)) + 1e-9
mixd *= 0.9
fade = np.ones(N)
fs = int(SR * 1.3)
fade[-fs:] = np.linspace(1, 0, fs) ** 1.5
mixd *= fade[:, None]
mixd = np.concatenate([np.zeros((int(SR * PREROLL), 2)), mixd])

with wave.open(sys.argv[1], "wb") as wf:
    wf.setnchannels(2)
    wf.setsampwidth(2)
    wf.setframerate(SR)
    wf.writeframes((mixd * 32767).astype("<i2").tobytes())
print("ok", sys.argv[1])
