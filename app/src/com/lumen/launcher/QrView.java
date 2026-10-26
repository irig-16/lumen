package com.lumen.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Draws a QR code on a white card (with the quiet zone scanners need). */
final class QrView extends View {

    private QrCode code;
    private final Paint dark = new Paint();
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    QrView(Context c) {
        super(c);
        dark.setColor(0xFF0A0A0A);
        card.setColor(0xFFFFFFFF);
    }

    void setText(String text) {
        try {
            code = QrCode.encode(text);
        } catch (RuntimeException e) {
            code = null;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        float ox = (w - s) / 2f, oy = (h - s) / 2f;
        rf.set(ox, oy, ox + s, oy + s);
        c.drawRoundRect(rf, s * 0.06f, s * 0.06f, card);
        if (code == null) return;
        int n = code.size + 8; // 4-module quiet zone on each side
        float m = s / n;
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (!code.modules[y][x]) continue;
                float l = ox + (x + 4) * m, t = oy + (y + 4) * m;
                c.drawRect((float) Math.floor(l), (float) Math.floor(t), (float) Math.ceil(l + m), (float) Math.ceil(t + m), dark);
            }
        }
    }
}
