package com.lumen.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Shows Sound Studio's 8-band EQ as a smooth curve, animating whenever the tuning changes. */
final class EqCurveView extends View {

    private static final String[] BANDS = {"Sub", "Bass", "Warmth", "Body", "Voice", "Presence", "Detail", "Air"};
    private static final float RANGE = 8f; // ±dB shown

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), area = new Path();
    private float[] from = new float[8], to = new float[8];
    private long changedAt;
    private boolean active = true;

    EqCurveView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        grid.setColor(0x1AFFFFFF);
        grid.setStrokeWidth(Math.max(1f, d));
        label.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(0x80F5F5F7);
    }

    /** New gains in dB; {@code on} false draws the flat "Off" line dimmed. */
    void setGains(float[] gains, boolean on) {
        float[] now = current();
        from = now;
        to = gains == null ? new float[8] : gains.clone();
        active = on;
        changedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private float[] current() {
        float k = progress();
        float[] g = new float[8];
        for (int i = 0; i < 8; i++) g[i] = from[i] + (to[i] - from[i]) * k;
        return g;
    }

    private float progress() {
        float k = Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / 350f);
        return 1f - (1f - k) * (1f - k);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float top = 6 * d, bottom = h - 20 * d, mid = (top + bottom) / 2f;
        float left = 8 * d, right = w - 8 * d;
        c.drawLine(left, mid, right, mid, grid);
        c.drawLine(left, top, right, top, grid);
        c.drawLine(left, bottom, right, bottom, grid);

        float[] g = current();
        float step = (right - left) / 7f;
        path.reset();
        float px = 0, py = 0;
        for (int i = 0; i < 8; i++) {
            float x = left + step * i;
            float y = mid - Math.max(-RANGE, Math.min(RANGE, g[i])) / RANGE * (mid - top);
            if (i == 0) path.moveTo(x, y);
            else {
                float cx = (px + x) / 2f;
                path.cubicTo(cx, py, cx, y, x, y);
            }
            px = x;
            py = y;
            c.drawText(BANDS[i], x, h - 4 * d, label);
        }
        area.set(path);
        area.lineTo(right, mid);
        area.lineTo(left, mid);
        area.close();
        int col = active ? Theme.ROSE : 0x66F5F5F7;
        fill.setShader(new LinearGradient(0, top, 0, bottom, (col & 0x00FFFFFF) | 0x55000000, (col & 0x00FFFFFF) | 0x10000000, Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        line.setColor(col);
        c.drawPath(path, line);
        if (progress() < 1f) postInvalidateOnAnimation();
    }
}
