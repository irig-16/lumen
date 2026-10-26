package com.lumen.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Ambient "aurora" light that follows the focused app's colour, plus a blurred copy
 * of its TV banner (like the tvOS top shelf).
 *
 * Performance trick: everything is painted into a tiny 96x54 bitmap on the CPU and the
 * GPU stretches it to full screen with bilinear filtering. The upscale itself is the
 * blur, so the cost per frame is a few thousand pixels — fine for a Mali-G31.
 */
final class AuroraView extends View {

    private static final int SW = 96, SH = 54;
    private static final long MIX_MS = 650;

    private final Bitmap small = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final Canvas sc = new Canvas(small);
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint up = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect smallRect = new Rect(0, 0, SW, SH);
    private final Rect dst = new Rect();
    private final float[] hsv = new float[3];

    private int from = 0xFF0A4D6E, to = 0xFF0A4D6E; // cerulean until an app is focused
    private Bitmap heroOld, heroNew;
    private long mixStart;
    private float mix = 1f;
    private boolean running;
    private final long t0 = SystemClock.uptimeMillis();

    AuroraView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    /** Smoothly shifts the light to a new colour and banner. */
    void setAccent(int color, Bitmap banner) {
        if (color == to && banner == null && heroNew == null) return;
        from = currentColor();
        to = color;
        heroOld = mix < 0.5f ? heroOld : heroNew;
        heroNew = banner == null ? null : Bitmap.createScaledBitmap(banner, 32, 18, true);
        mix = 0f;
        mixStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** true = slow drifting light (~25 fps); false = still image, animates only on colour change. */
    void setRunning(boolean r) {
        if (running == r) return;
        running = r;
        invalidate();
    }

    private int currentColor() {
        return blend(from, to, ease(mix));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        mix = Math.min(1f, (now - mixStart) / (float) MIX_MS);
        float e = ease(mix);
        float t = (now - t0) / 1000f;
        if (!running) t = 12f; // frozen but still a nice composition

        int acc = currentColor();
        int acc2 = shift(acc, 38f, 0.9f);
        int acc3 = shift(acc, -32f, 0.6f);

        sc.drawColor(0xFF0A0A0A, PorterDuff.Mode.SRC);

        // blurred banner, cross-faded
        if (heroOld != null && e < 1f) {
            img.setAlpha((int) (150 * (1f - e)));
            sc.drawBitmap(heroOld, null, smallRect, img);
        }
        if (heroNew != null) {
            img.setAlpha((int) (150 * e));
            sc.drawBitmap(heroNew, null, smallRect, img);
        }

        drawBlob(SW * (0.24f + 0.12f * sin(t * 0.13f)), SH * (0.18f + 0.14f * sin(t * 0.11f + 1f)),
                SW * 0.58f, acc, 205);
        drawBlob(SW * (0.80f + 0.10f * sin(t * 0.09f + 2f)), SH * (0.30f + 0.18f * sin(t * 0.12f + 4f)),
                SW * 0.50f, acc2, 150);
        drawBlob(SW * (0.50f + 0.24f * sin(t * 0.07f + 4f)), SH * (0.98f + 0.08f * sin(t * 0.10f)),
                SW * 0.62f, acc3, 115);

        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(small, smallRect, dst, up);

        if (mix < 1f) postInvalidateOnAnimation();
        else if (running) postInvalidateDelayed(40);
    }

    private void drawBlob(float cx, float cy, float r, int color, int alpha) {
        int c = (color & 0x00FFFFFF) | (alpha << 24);
        blob.setShader(new RadialGradient(cx, cy, r, c, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        sc.drawCircle(cx, cy, r, blob);
    }

    private int shift(int color, float hueDeg, float valMul) {
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + hueDeg + 360f) % 360f;
        hsv[2] = Math.min(1f, hsv[2] * valMul);
        return Color.HSVToColor(hsv);
    }

    private static float sin(float a) {
        return (float) Math.sin(a);
    }

    private static float ease(float x) {
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    private static int blend(int a, int b, float f) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000
                | ((int) (ar + (br - ar) * f) << 16)
                | ((int) (ag + (bg - ag) * f) << 8)
                | (int) (ab + (bb - ab) * f);
    }
}
