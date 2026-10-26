package com.lumen.launcher;

import java.util.Random;

/**
 * "First Light" — Lumen's startup intro (10.5 s). Lumen means light, so the name is brought to life by light:
 *
 *  0.6–1.6 s  darkness; a small warm ember breathes
 *  1.6 s      it ignites into a living spark (a bright click) and darts away
 *  2.0–5.3 s  the spark swoops back on a playful, bouncing path and touches each letter of L U M E N;
 *             each one flickers on like a warming filament, with a chime, a ring of light and sparkles
 *  6.0 s      the spark shoots up and bursts: light floods out from behind the word, the room appears,
 *             a dark glossy floor reflecting the glowing letters; a light sweep and an anamorphic streak
 *  7.0 s      "CINEMA AT HOME", then the credit; dust drifts through the light
 *  9.5–10.5 s everything dissolves into the home screen
 *
 * Everything is a pure function of time (no stored state), so it stays locked to the score on any device,
 * and the same code draws on the projector (Android Canvas) and renders the preview video (Java2D).
 */
final class IntroScene {

    static final float DURATION = 10.5f;

    interface Gfx {
        void clear(int argb);

        void glow(float cx, float cy, float r, int argb);

        void circle(float cx, float cy, float r, int argb);

        void ring(float cx, float cy, float r, float width, int argb);

        void arc(float cx, float cy, float r, float width, float startDeg, float sweepDeg, int argb);

        void line(float x0, float y0, float x1, float y1, float width, int argb);

        /** weight: 0 light, 1 regular, 2 medium. align: -1 left, 0 centre, 1 right. */
        void text(String s, float x, float y, float size, int argb, int weight, int align, float scale);

        float textWidth(String s, float size, int weight);

        void rect(float l, float t, float r, float b, int argb);

        /** Vertical gradient through evenly spaced colours. */
        void vgradient(float l, float t, float r, float b, int[] colors);

        /** Filled polygon from x,y pairs. */
        void poly(float[] xy, int points, int argb);

        /** Draws everything until {@link #restore()} mirrored upside down around the line y = axisY. */
        void flipY(float axisY);

        void restore();
    }

    private static final int CERULEAN = 0xFF3FB3E0, ROSE = 0xFFFF2D95, WARM = 0xFFFFE9C9, GOLD = 0xFFFFC46B;
    private static final String WORD = "LUMEN";
    /** When the spark touches each letter. */
    private static final float[] TOUCH = {2.45f, 3.15f, 3.85f, 4.55f, 5.25f};
    private static final float IGNITE = 1.6f, BURST = 6.0f;

    private float w, h;
    private float size, baseY, letterY, floorY;
    private final float[] lx = new float[5], lw = new float[5];
    private boolean laidOut;
    private float layoutW;

    // the spark's flight: key times and positions (filled in once the letters are laid out)
    private float[] keyT, keyX, keyY;

    // sparkles shed along the flight, and dust in the air (seeded, so every boot looks the same)
    private static final int SPARKS = 220, DUST = 90;
    private final float[] spT = new float[SPARKS], spVX = new float[SPARKS], spVY = new float[SPARKS], spS = new float[SPARKS];
    private final float[] duX = new float[DUST], duY = new float[DUST], duS = new float[DUST], duP = new float[DUST];
    private final float[] buf = new float[16];

    IntroScene() {
        Random r = new Random(7);
        for (int i = 0; i < SPARKS; i++) {
            spT[i] = 2.0f + (BURST - 2.0f) * i / SPARKS + r.nextFloat() * 0.01f;
            double a = r.nextFloat() * Math.PI * 2;
            float sp = 0.02f + r.nextFloat() * 0.06f;
            spVX[i] = (float) Math.cos(a) * sp;
            spVY[i] = (float) Math.sin(a) * sp - 0.01f;
            spS[i] = 0.5f + r.nextFloat();
        }
        for (int i = 0; i < DUST; i++) {
            duX[i] = r.nextFloat();
            duY[i] = r.nextFloat();
            duS[i] = 0.4f + r.nextFloat() * 1.2f;
            duP[i] = r.nextFloat() * 6.28f;
        }
    }

    float alpha(float t) {
        return t < 9.5f ? 1f : Math.max(0f, 1f - smooth((t - 9.5f) / 1f));
    }

    boolean done(float t) {
        return t >= DURATION;
    }

    void step(float t, float width, float height) {
        if (width != w || height != h) laidOut = false;
        w = width;
        h = height;
    }

    // ================================================================ maths

    private static float clamp01(float x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }

    private static float ease(float x) {
        x = clamp01(x);
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    private static float smooth(float x) {
        x = clamp01(x);
        return x * x * (3 - 2 * x);
    }

    private static int withAlpha(int argb, float a) {
        int al = (int) (((argb >>> 24) & 255) * clamp01(a));
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    private static int mix(int a, int b, float f) {
        f = clamp01(f);
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }

    /** A cheap smooth flicker in 0..1. */
    private static float wobble(float t, float seed) {
        return 0.5f + 0.25f * (float) Math.sin(t * 7.1f + seed) + 0.25f * (float) Math.sin(t * 13.7f + seed * 2.3f);
    }

    // ================================================================ layout and the spark's path

    private void layout(Gfx g) {
        if (laidOut && layoutW == w) return;
        size = h * 0.17f;
        float track = size * 0.30f;
        float total = 0;
        for (int i = 0; i < 5; i++) {
            lw[i] = g.textWidth(WORD.substring(i, i + 1), size, 0);
            total += lw[i] + (i > 0 ? track : 0);
        }
        float x = w / 2f - total / 2f;
        for (int i = 0; i < 5; i++) {
            lx[i] = x + lw[i] / 2f;
            x += lw[i] + track;
        }
        baseY = h * 0.53f;
        letterY = baseY - size * 0.36f;
        floorY = baseY + size * 0.06f;

        float up = size * 0.95f, down = size * 0.78f;
        keyT = new float[]{1.6f, 2.0f, 2.25f, TOUCH[0], 2.8f, TOUCH[1], 3.5f, TOUCH[2], 4.2f, TOUCH[3], 4.9f, TOUCH[4], 5.6f, 6.0f};
        keyX = new float[]{w * 0.5f, -w * 0.08f, lx[0] - size * 0.7f, lx[0], (lx[0] + lx[1]) / 2f, lx[1], (lx[1] + lx[2]) / 2f, lx[2],
                (lx[2] + lx[3]) / 2f, lx[3], (lx[3] + lx[4]) / 2f, lx[4], lx[4] + size * 0.6f, w * 0.5f};
        keyY = new float[]{h * 0.5f, h * 0.22f, letterY - up, letterY, letterY + down, letterY, letterY - up, letterY,
                letterY + down, letterY, letterY - up, letterY, letterY - size * 0.6f, h * 0.13f};
        laidOut = true;
        layoutW = w;
    }

    /** The spark's position at time t (Catmull-Rom through the key points). */
    private void spark(float t, float[] out) {
        if (t <= keyT[0]) {
            out[0] = keyX[0];
            out[1] = keyY[0];
            return;
        }
        int n = keyT.length;
        int i = 0;
        while (i < n - 2 && t > keyT[i + 1]) i++;
        float u = clamp01((t - keyT[i]) / (keyT[i + 1] - keyT[i]));
        int i0 = Math.max(0, i - 1), i2 = Math.min(n - 1, i + 1), i3 = Math.min(n - 1, i + 2);
        out[0] = catmull(keyX[i0], keyX[i], keyX[i2], keyX[i3], u);
        out[1] = catmull(keyY[i0], keyY[i], keyY[i2], keyY[i3], u);
    }

    private static float catmull(float p0, float p1, float p2, float p3, float u) {
        float u2 = u * u, u3 = u2 * u;
        return 0.5f * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
    }

    /** How lit letter i is at time t: a warming filament that flickers on, then glows steadily. */
    private static float lit(int i, float t) {
        float tau = t - TOUCH[i];
        if (tau < 0) return 0f;
        if (tau < 0.06f) return 0.85f;
        if (tau < 0.11f) return 0.15f;
        if (tau < 0.16f) return 0.7f;
        if (tau < 0.21f) return 0.3f;
        return 0.55f + 0.45f * smooth((tau - 0.21f) / 0.5f);
    }

    // ================================================================ draw

    void draw(Gfx g, float t) {
        layout(g);
        float flood = smooth((t - BURST) / 1.1f);     // the room lights up after the burst
        g.clear(0xFF000000);

        // ---- the room: deep blue space above a dark glossy floor, revealed by the burst
        if (flood > 0) {
            g.vgradient(0, 0, w, floorY, new int[]{withAlpha(0xFF050B14, flood), withAlpha(0xFF0A1A2C, flood), withAlpha(0xFF14304A, flood)});
            g.vgradient(0, floorY, w, h, new int[]{withAlpha(0xFF0C1C2C, flood), withAlpha(0xFF04080D, flood)});
            g.glow(w / 2f, floorY, w * 0.55f, withAlpha(0x553FB3E0, flood));
            g.glow(w / 2f, floorY, w * 0.25f, withAlpha(0x40FFE9C9, flood));
            g.rect(0, floorY - 1, w, floorY + 1, withAlpha(0x40FFE9C9, flood));
        }

        // ---- rays of light flooding out from behind the word
        if (t > BURST - 0.1f) rays(g, t);

        // ---- glow behind each lit letter
        for (int i = 0; i < 5; i++) {
            float k = lit(i, t);
            if (k <= 0) continue;
            int halo = i % 2 == 0 ? CERULEAN : ROSE;
            float breathe = 0.9f + 0.1f * wobble(t, i);
            g.glow(lx[i], letterY, size * 1.25f, withAlpha(halo, 0.28f * k * breathe));
            g.glow(lx[i], letterY, size * 0.7f, withAlpha(WARM, 0.32f * k * breathe));
        }

        // ---- the floor reflection of the letters
        if (flood > 0) {
            g.flipY(floorY);
            drawLetters(g, t, 0.28f * flood);
            g.restore();
            g.vgradient(0, floorY, w, h, new int[]{0x00050A10, withAlpha(0x99050A10, flood), withAlpha(0xD0050A10, flood), withAlpha(0xD0050A10, flood)});
        }

        // ---- the letters themselves (dark glass tubes until lit)
        drawLetters(g, t, 1f);

        // ---- the spark: ember, ignition, flight with a comet trail, burst
        drawSpark(g, t);

        // ---- touches: a ring of light where the spark meets each letter
        for (int i = 0; i < 5; i++) {
            float tau = t - TOUCH[i];
            if (tau < 0 || tau > 0.7f) continue;
            float k = ease(tau / 0.7f);
            g.ring(lx[i], letterY, size * (0.2f + 0.9f * k), size * 0.03f * (1 - k), withAlpha(WARM, 0.8f * (1 - k)));
        }

        // ---- the light sweep across the word and an anamorphic streak
        if (t > BURST && t < BURST + 1.8f) {
            float s = (t - BURST - 0.4f) / 1.1f;
            for (int i = 0; i < 5; i++) {
                float pos = (lx[i] - lx[0]) / Math.max(1f, lx[4] - lx[0]);
                float shine = Math.max(0f, 1f - Math.abs(s - pos) * 5f);
                if (shine > 0) g.glow(lx[i], letterY, size * 0.9f, withAlpha(0xFFFFFFFF, 0.45f * shine));
            }
            float sk = Math.max(0f, 1f - Math.abs(t - BURST - 0.25f) / 0.9f);
            g.glow(w / 2f, letterY, w * 0.5f * sk, withAlpha(0x22BFE6FF, sk));
            g.rect(w * (0.5f - 0.48f * sk), letterY - h * 0.0018f, w * (0.5f + 0.48f * sk), letterY + h * 0.0018f, withAlpha(0xCCDFF4FF, sk));
        }

        // ---- dust in the light
        dust(g, t, flood);

        // ---- tagline and credit
        if (t > 7.0f) {
            float k = ease((t - 7.0f) / 0.9f);
            float y = floorY + size * 1.18f;
            g.text("C I N E M A   A T   H O M E", w / 2f, y, h * 0.03f, withAlpha(0xFFF5F5F7, k * 0.92f), 1, 0, 1f);
            float half = w * 0.05f * k;
            g.rect(w / 2f - half, y + h * 0.02f, w / 2f + half, y + h * 0.02f + Math.max(2f, h * 0.0028f), withAlpha(ROSE, k));
        }
        if (t > 7.6f) {
            float k = ease((t - 7.6f) / 0.9f);
            String a = "Developed with ", b = " by Radirig";
            float sz = h * 0.024f;
            float wa = g.textWidth(a, sz, 0), wh = g.textWidth("♥", sz * 1.1f, 1), wb = g.textWidth(b, sz, 0);
            float x0 = w / 2f - (wa + wh + wb) / 2f, y = h * 0.93f;
            g.text(a, x0, y, sz, withAlpha(0x99F5F5F7, k), 0, -1, 1f);
            g.text("♥", x0 + wa + wh / 2f, y, sz * 1.1f, withAlpha(ROSE, k), 1, 0, 1f);
            g.text(b, x0 + wa + wh, y, sz, withAlpha(0x99F5F5F7, k), 0, -1, 1f);
        }
    }

    private void drawLetters(Gfx g, float t, float alpha) {
        for (int i = 0; i < 5; i++) {
            String c = WORD.substring(i, i + 1);
            float k = lit(i, t);
            // unlit: a barely visible glass tube, caught by the spark's light as it passes
            float near = 0;
            if (t > IGNITE && t < BURST) {
                spark(t, buf);
                float dx = buf[0] - lx[i], dy = buf[1] - letterY;
                near = Math.max(0f, 1f - (float) Math.sqrt(dx * dx + dy * dy) / (size * 1.6f));
            }
            int base = mix(0xFF1A2430, 0xFF5A6A7A, near);
            int color = k > 0 ? mix(base, mix(WARM, 0xFFFFFFFF, k), Math.min(1f, k * 1.2f)) : base;
            float a = alpha * (k > 0 ? 1f : 0.35f + 0.5f * near) * (t < IGNITE ? smooth((t - 0.8f) / 0.8f) * 0.5f : 1f);
            if (k > 0.3f) {   // a soft bloom around a lit letter
                int halo = i % 2 == 0 ? CERULEAN : ROSE;
                // scaled around the letter's middle, not its baseline, so the bloom sits evenly around it
                g.text(c, lx[i], baseY + size * 0.36f * 0.06f, size, withAlpha(halo, a * 0.2f * k), 2, 0, 1.06f);
                g.text(c, lx[i], baseY + size * 0.36f * 0.025f, size, withAlpha(WARM, a * 0.28f * k), 1, 0, 1.025f);
            }
            g.text(c, lx[i], baseY, size, withAlpha(color, a), 0, 0, 1f);
        }
    }

    private void drawSpark(Gfx g, float t) {
        if (t < 0.6f || t > BURST + 1.2f) return;
        if (t < IGNITE) {   // the ember breathes
            float k = smooth((t - 0.6f) / 0.6f);
            float b = 0.55f + 0.45f * (float) Math.sin((t - 0.6f) * 6.3f);
            g.glow(w / 2f, h / 2f, h * (0.05f + 0.03f * b) * k, withAlpha(GOLD, 0.6f * k));
            g.circle(w / 2f, h / 2f, h * 0.005f * k, withAlpha(WARM, k));
            return;
        }
        float ig = t - IGNITE;
        if (ig < 0.5f) {   // ignition flash
            float f = 1f - ease(ig / 0.5f);
            g.glow(w / 2f, h / 2f, h * 0.55f * (0.4f + 0.6f * f), withAlpha(GOLD, 0.55f * f));
            g.rect(w * 0.5f - w * 0.4f * f, h / 2f - 1, w * 0.5f + w * 0.4f * f, h / 2f + 1, withAlpha(0xFFFFF4E0, f));
        }
        if (t > BURST) {   // the burst
            float b = (t - BURST) / 1.2f;
            float f = 1f - ease(b);
            float bx = keyX[keyX.length - 1], by = keyY[keyY.length - 1];
            g.glow(bx, by, h * (0.2f + 0.9f * ease(b)), withAlpha(0xFFFFF4E0, 0.6f * f));
            g.glow(bx, by, h * 0.08f * f, withAlpha(0xFFFFFFFF, f));
            g.ring(bx, by, h * 0.45f * ease(b), h * 0.004f * f, withAlpha(0xFFBFE6FF, 0.35f * f));
            return;
        }
        // sparkles shed along the way: they drift, fall a little and twinkle out
        for (int i = 0; i < SPARKS; i++) {
            float age = t - spT[i];
            if (age < 0 || age > 0.9f) continue;
            spark(spT[i], buf);
            float x = buf[0] + spVX[i] * h * age * 3f, y = buf[1] + spVY[i] * h * age * 3f + h * 0.06f * age * age;
            float life = 1f - age / 0.9f;
            float tw = 0.6f + 0.4f * (float) Math.sin(age * 40f + i);
            g.circle(x, y, h * 0.0028f * spS[i] * life, withAlpha(i % 3 == 0 ? CERULEAN : i % 3 == 1 ? ROSE : WARM, life * tw));
        }
        // comet trail
        for (int j = 22; j >= 1; j--) {
            float tt = t - j * 0.011f;
            if (tt < IGNITE) continue;
            spark(tt, buf);
            float k = 1f - j / 23f;
            g.circle(buf[0], buf[1], h * 0.009f * k, withAlpha(mix(GOLD, CERULEAN, j / 22f), 0.55f * k));
        }
        spark(t, buf);
        float pulse = 0.85f + 0.15f * (float) Math.sin(t * 30f);
        g.glow(buf[0], buf[1], h * 0.11f * pulse, withAlpha(GOLD, 0.45f));
        g.glow(buf[0], buf[1], h * 0.035f, withAlpha(0xFFFFF4E0, 0.9f));
        g.circle(buf[0], buf[1], h * 0.008f, 0xFFFFFFFF);
        // a tiny cross-shaped glint, like light on a lens
        g.rect(buf[0] - h * 0.04f * pulse, buf[1] - 0.6f, buf[0] + h * 0.04f * pulse, buf[1] + 0.6f, 0xAAFFFFFF);
        g.rect(buf[0] - 0.6f, buf[1] - h * 0.025f * pulse, buf[0] + 0.6f, buf[1] + h * 0.025f * pulse, 0x88FFFFFF);
    }

    private void rays(Gfx g, float t) {
        float k = smooth((t - BURST + 0.1f) / 0.7f) * (t > 8.6f ? 1f - 0.6f * smooth((t - 8.6f) / 1.5f) : 1f);
        float cx = w / 2f, cy = letterY;
        float spin = (t - BURST) * 0.06f;
        float len = w * 0.9f;
        for (int i = 0; i < 14; i++) {
            double a = Math.PI * 2 * i / 14 + spin + (i % 2) * 0.09;
            double half = (i % 3 == 0 ? 0.05 : 0.03);
            buf[0] = cx;
            buf[1] = cy;
            buf[2] = cx + (float) Math.cos(a - half) * len;
            buf[3] = cy + (float) Math.sin(a - half) * len;
            buf[4] = cx + (float) Math.cos(a + half) * len;
            buf[5] = cy + (float) Math.sin(a + half) * len;
            if (buf[3] > floorY && buf[5] > floorY) continue;   // rays stay above the floor
            g.poly(buf, 3, withAlpha(i % 2 == 0 ? 0xFFBFE6FF : 0xFFFFD9EC, 0.07f * k));
        }
        g.glow(cx, cy, w * 0.32f, withAlpha(0xFFFFF0DA, 0.18f * k));
    }

    private void dust(Gfx g, float t, float flood) {
        float sparkK = t > IGNITE && t < BURST ? 1f : 0f;
        if (sparkK > 0) spark(t, buf);
        for (int i = 0; i < DUST; i++) {
            float x = duX[i] * w + (float) Math.sin(t * 0.3f + duP[i]) * h * 0.02f;
            float y = ((duY[i] - t * 0.012f * duS[i]) % 1f + 1f) % 1f * h;
            // a mote shows where light falls on it: near the spark, or in the room's light after the burst
            float light = 0;
            if (sparkK > 0) {
                float dx = x - buf[0], dy = y - buf[1];
                light = Math.max(0f, 1f - (float) Math.sqrt(dx * dx + dy * dy) / (h * 0.25f));
            }
            if (flood > 0) {
                float dx = (x - w / 2f) / w, dy = (y - letterY) / h;
                light = Math.max(light, flood * Math.max(0f, 1f - (float) Math.sqrt(dx * dx + dy * dy) * 1.8f));
            }
            if (light <= 0.02f) continue;
            float tw = 0.6f + 0.4f * (float) Math.sin(t * 2f + duP[i] * 3f);
            g.circle(x, y, h * 0.0016f * duS[i], withAlpha(WARM, 0.55f * light * tw));
        }
    }
}
