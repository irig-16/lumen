"""Generates the soft UI sounds (mono 16-bit 44.1 kHz WAV)."""
import math, struct, sys, wave, random

RATE = 44100

def write(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, int(s * 32767)))) for s in samples))

def tick():
    # 28 ms: a short pitched "tock" (2.2 kHz sine + soft noise) with a fast exponential decay
    n = int(RATE * 0.028); out = []
    rnd = random.Random(7)
    for i in range(n):
        t = i / RATE
        env = math.exp(-t * 220) * min(1, i / 40)
        s = 0.55 * math.sin(2 * math.pi * 2200 * t) + 0.25 * math.sin(2 * math.pi * 3300 * t) + 0.12 * (rnd.random() * 2 - 1)
        out.append(0.35 * env * s)
    return out

def select():
    # 140 ms: a rounded "thock" — pitch glides 660→440 Hz with a soft harmonic and a gentle tail
    n = int(RATE * 0.14); out = []; ph = 0.0
    for i in range(n):
        t = i / RATE
        f = 440 + 220 * math.exp(-t * 40)
        ph += 2 * math.pi * f / RATE
        env = math.exp(-t * 28) * min(1, i / 60)
        out.append(0.45 * env * (math.sin(ph) + 0.3 * math.sin(2 * ph)))
    return out

write(sys.argv[1] + "/tick.wav", tick())
write(sys.argv[1] + "/select.wav", select())
print("ok")
