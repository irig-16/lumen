package com.lumen.launcher;

/**
 * The tuning tables behind Sound Studio — pure Java so they can be tested off-device.
 *
 * Why the old presets sounded worse than "Off": they BOOSTED deep bass that a 3 W projector speaker
 * physically can't reproduce. That energy only eats amplifier headroom and causes distortion, and the
 * level changes made A/B comparisons unfair. The new tables follow how small speakers are tuned:
 *   1. remove sub-bass below ~90 Hz (frees headroom, cleaner and louder)
 *   2. lift 120–300 Hz a little, the lowest range the speaker can actually play ("perceived bass")
 *   3. dip the boxy 300–600 Hz region
 *   4. add presence at 1.2–3 kHz so voices cut through, and a touch of air on top
 *   5. gentle compression + make-up gain so every preset is at least as loud as Off (louder always
 *      sounds "better" in a comparison, so presets are level-matched), and a limiter so nothing clips.
 */
final class SoundTuning {

    /** Upper edge of each precision-EQ band, in Hz. */
    static final float[] CUTOFFS = {90, 160, 300, 600, 1200, 3000, 6000, 20000};

    /** Where the sound goes. HOME_THEATRE = good speakers, soundbar or AV receiver (cable or Bluetooth). */
    static final int SPEAKER = 0, EXTERNAL = 1, HEADPHONES = 2, HOME_THEATRE = 3;
    static final String[] OUTPUT_KEYS = {"speaker", "portable", "headphones", "hometheatre"};
    static final String[] OUTPUT_NAMES = {"Projector speaker", "Bluetooth speaker", "Headphones", "Home theatre"};

    static final String[] PRESETS = {"off", "lumen", "cinema", "dialogue", "music", "night"};
    static final String[] NAMES = {"Off", "Lumen", "Cinema", "Dialogue", "Music", "Night"};

    // ---- built-in projector speaker (dB per band)            90   160  300   600  1.2k  3k   6k   20k
    private static final float[][] SPEAKER_EQ = {
            /* lumen    */ {-9f, 2f, 1f, -2f, 0f, 2f, 1f, 1.5f},
            /* cinema   */ {-9f, 3f, 1.5f, -1.5f, 0f, 2f, 0.5f, 1f},
            /* dialogue */ {-12f, -2f, -1f, -2.5f, 1f, 3.5f, 1.5f, -0.5f},
            /* music    */ {-8f, 2.5f, 1f, -1f, 0f, 1f, 1.5f, 2.5f},
            /* night    */ {-12f, -1f, 0f, -2f, 1f, 3f, 1f, 0f}};

    // ---- Bluetooth speaker / soundbar / headphones: real bass is available
    private static final float[][] EXTERNAL_EQ = {
            /* lumen    */ {0f, 1.5f, 0.5f, -1f, 0f, 1f, 0.5f, 1f},
            /* cinema   */ {3f, 3f, 1f, -1.5f, 0f, 1.5f, 0.5f, 1f},
            /* dialogue */ {-3f, -1f, -0.5f, -2f, 1f, 3f, 1f, 0f},
            /* music    */ {2.5f, 2f, 0.5f, -1f, 0f, 0.5f, 1.5f, 2.5f},
            /* night    */ {-4f, -1f, 0f, -1.5f, 1f, 2.5f, 1f, 0f}};

    // ---- home theatre / good speakers / soundbar: full-range and already tuned, so stay close to flat.
    //      Small, broad moves only; real sub-bass is welcome; nothing that fights the receiver's own processing.
    private static final float[][] THEATRE_EQ = {
            /* lumen    */ {0.5f, 0.5f, 0f, -0.5f, 0f, 0.5f, 0.5f, 0.5f},
            /* cinema   */ {2.5f, 1.5f, 0f, -1f, 0f, 1f, 0.5f, 0.5f},
            /* dialogue */ {-2f, -1f, -0.5f, -1.5f, 1f, 2.5f, 1f, 0f},
            /* music    */ {1.5f, 1f, 0f, -0.5f, 0f, 0f, 1f, 1.5f},
            /* night    */ {-6f, -2f, 0f, -1f, 1f, 2.5f, 0.5f, -0.5f}};

    // ---- compressor: ratio, threshold dB, make-up gain dB (level-matching vs Off)
    private static final float[][] COMP = {
            /* lumen    */ {1.6f, -22f, 2.5f},
            /* cinema   */ {1.8f, -24f, 3f},
            /* dialogue */ {2.0f, -26f, 4f},
            /* music    */ {1.5f, -20f, 2f},
            /* night    */ {4.0f, -32f, 7f}};

    // ---- home theatre: keep the film's dynamics (that's the point of a big system); only Night squeezes
    private static final float[][] THEATRE_COMP = {
            /* lumen    */ {1.2f, -18f, 0.5f},
            /* cinema   */ {1.0f, -12f, 0f},
            /* dialogue */ {1.6f, -24f, 2f},
            /* music    */ {1.1f, -16f, 0.5f},
            /* night    */ {3.5f, -32f, 6f}};

    private SoundTuning() {
    }

    /** Index into the tables for an active preset, or -1 for off/unknown. */
    static int index(String preset) {
        for (int i = 1; i < PRESETS.length; i++) if (PRESETS[i].equals(preset)) return i - 1;
        return -1;
    }

    static boolean known(String preset) {
        for (String p : PRESETS) if (p.equals(preset)) return true;
        return false;
    }

    static String name(String preset) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i].equals(preset)) return NAMES[i];
        return "Lumen";
    }

    /** Slider value 0..100 (50 = preset default) to an offset of ±range dB. */
    static float offset(int slider, float range) {
        return (Math.max(0, Math.min(100, slider)) - 50) / 50f * range;
    }

    /** Final dB gain for each of the 8 bands, with the fine-tune sliders applied on top of the preset. */
    static float[] gains(int presetIdx, int output, int bass, int treble, int clarity) {
        float[] base = (output == SPEAKER ? SPEAKER_EQ : output == HOME_THEATRE ? THEATRE_EQ : EXTERNAL_EQ)[Math.max(0, presetIdx)];
        float[] g = base.clone();
        float b = offset(bass, 6f), t = offset(treble, 6f), c = offset(clarity, 6f);
        if (output == HOME_THEATRE) g[0] += b;      // a subwoofer can take it
        else if (output != SPEAKER) g[0] += b * 0.7f; // deep bass only where it can be reproduced
        g[1] += b;
        g[2] += b * 0.6f;
        g[3] -= c * 0.3f;                           // clarity also trims mud
        g[4] += c * 0.5f;
        g[5] += c + t * 0.3f;
        g[6] += t + c * 0.4f;
        g[7] += t;
        for (int i = 0; i < g.length; i++) g[i] = Math.max(-15f, Math.min(9f, g[i]));
        return g;
    }

    /** {ratio, threshold, make-up} with the Loudness slider (±4 dB) applied to the make-up gain. */
    static float[] compressor(int presetIdx, int loudness) {
        return compressor(presetIdx, SPEAKER, loudness);
    }

    static float[] compressor(int presetIdx, int output, int loudness) {
        float[] c = (output == HOME_THEATRE ? THEATRE_COMP : COMP)[Math.max(0, presetIdx)].clone();
        c[2] = Math.max(0f, c[2] + offset(loudness, 4f));
        return c;
    }

    static int outputIndex(String key) {
        for (int i = 0; i < OUTPUT_KEYS.length; i++) if (OUTPUT_KEYS[i].equals(key)) return i;
        return -1;
    }

    /**
     * What "Auto" means. Android reports a cable in the 3.5 mm jack exactly like headphones, and can't tell a
     * Bluetooth soundbar from a pocket speaker, so anything external is treated as a proper sound system;
     * the Output setting overrides it (Bluetooth speaker, Headphones).
     */
    static int autoOutput(boolean bluetooth, boolean cable) {
        return bluetooth || cable ? HOME_THEATRE : SPEAKER;
    }

    /** Gain for a fixed-band Equalizer band centred at hz (fallback when the precision engine is missing). */
    static float gainAt(float[] gains, double hz) {
        for (int i = 0; i < CUTOFFS.length; i++) if (hz <= CUTOFFS[i]) return gains[i];
        return gains[gains.length - 1];
    }
}
