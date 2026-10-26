package com.lumen.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * A living orb: three coloured light blobs orbiting a bright core, blended additively.
 * It swells with the microphone level, spins fast while "thinking" and settles when done.
 * Small (≈80dp) and only animating while visible, so it costs very little.
 */
final class VoiceOrb extends View {

    static final int IDLE = 0, LISTENING = 1, THINKING = 2, DONE = 3, ERROR = 4;

    private static final int[] COLORS = {Theme.CERULEAN_LIGHT, Theme.ROSE, Theme.CERULEAN};
    private static final int[] ERR = {0xFFFF453A, 0xFFFF6B3A, 0xFFB0303A};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = SystemClock.uptimeMillis();
    private int state = IDLE;
    private float level, shown, spin;
    private long last = SystemClock.uptimeMillis();

    VoiceOrb(Context c) {
        super(c);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        setLayerType(LAYER_TYPE_HARDWARE, null); // additive blending needs its own layer
    }

    void setState(int s) {
        state = s;
        invalidate();
    }

    /** rmsdB from the recognizer, roughly -2..10. */
    void setLevel(float rmsDb) {
        level = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - last) / 1000f);
        last = now;
        float t = (now - t0) / 1000f;

        float target = state == LISTENING ? level : state == THINKING ? 0.35f + 0.25f * (float) Math.sin(t * 6) : 0.1f;
        shown += (target - shown) * Math.min(1f, dt * 12f);
        float speed = state == THINKING ? 5.5f : state == LISTENING ? 1.6f + shown * 2f : 0.6f;
        spin += speed * dt;

        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float base = Math.min(w, h) * 0.30f;
        float swell = base * (1f + shown * 0.45f);
        int[] cols = state == ERROR ? ERR : COLORS;

        for (int i = 0; i < 3; i++) {
            double a = spin * (1f + i * 0.35f) + i * 2.094;
            float orbit = base * (0.22f + 0.18f * shown);
            float bx = cx + (float) Math.cos(a) * orbit;
            float by = cy + (float) Math.sin(a * 1.1) * orbit;
            float r = swell * (0.95f + 0.08f * (float) Math.sin(t * 2.3 + i));
            int col = cols[i];
            p.setShader(new RadialGradient(bx, by, r, new int[]{col, (col & 0x00FFFFFF) | 0x66000000, col & 0x00FFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, r, p);
        }
        float cr = base * (0.42f + 0.12f * shown);
        core.setShader(new RadialGradient(cx, cy, cr, new int[]{0xF2FFFFFF, 0x80FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, cr, core);

        if (getVisibility() == VISIBLE && getAlpha() > 0f && isAttachedToWindow()) postInvalidateOnAnimation();
    }
}
