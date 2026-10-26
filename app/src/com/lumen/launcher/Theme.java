package com.lumen.launcher;

/**
 * Lumen's design language: Jet Black, Cerulean Blue and Neon Rose.
 * App cards keep each app's own colour; everything else (buttons, focus, sliders, panels, text accents)
 * is drawn from this palette.
 */
final class Theme {
    static final int JET = 0xFF0A0A0A;          // #0A0A0A  base
    static final int CERULEAN = 0xFF007BA7;     // #007BA7  primary: focus, fills, sliders
    static final int CERULEAN_LIGHT = 0xFF3FB3E0; // cerulean tint for thin lines/text on black (projectors wash out dark blues)
    static final int ROSE = 0xFFFF2D95;         // #FF2D95  accent: selection, highlights, the heart

    static final int TEXT = 0xFFF5F5F7;
    static final int SUB = 0xB3F5F5F7;
    static final int DIM = 0x73F5F5F7;

    static final int GLASS_TOP = 0xF2151515;    // panels: jet glass
    static final int GLASS_BOTTOM = 0xF70A0A0A;
    static final int GLASS_STROKE = 0x66007BA7; // hairline cerulean edge
    static final int CERULEAN_GLASS = 0x40007BA7;

    private Theme() {
    }
}
