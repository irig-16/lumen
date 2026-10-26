package com.lumen.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small line icons drawn in code (no image files, crisp at any density). */
final class Glyph extends Drawable {

    static final int WIFI = 0, BLUETOOTH = 1, DISPLAY = 2, SETTINGS = 3, BOOST = 4, TUNE = 5, FILES = 6, MIC = 7, PHONE = 8, SPEAKER = 9, PICTURE = 10, GAMEPAD = 11, CAST = 12;

    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();

    Glyph(int type, int color) {
        this.type = type;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        c.scale(s / 24f, s / 24f);
        stroke(2f);
        switch (type) {
            case WIFI:
                for (float r = 5f; r <= 13f; r += 4f) {
                    rf.set(12 - r, 19 - r, 12 + r, 19 + r);
                    c.drawArc(rf, 225, 90, false, p);
                }
                fill();
                c.drawCircle(12, 19, 1.6f, p);
                break;
            case BLUETOOTH:
                path.reset();
                path.moveTo(7, 7);
                path.lineTo(17, 17);
                path.lineTo(12, 22);
                path.lineTo(12, 2);
                path.lineTo(17, 7);
                path.lineTo(7, 17);
                c.drawPath(path, p);
                break;
            case DISPLAY:
                c.drawCircle(12, 12, 4f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                    c.drawLine(12 + cx * 7f, 12 + sy * 7f, 12 + cx * 9.5f, 12 + sy * 9.5f, p);
                }
                break;
            case SETTINGS:
                c.drawCircle(12, 12, 3f, p);
                c.drawCircle(12, 12, 7f, p);
                stroke(3f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i + Math.PI / 8;
                    float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                    c.drawLine(12 + cx * 8f, 12 + sy * 8f, 12 + cx * 10f, 12 + sy * 10f, p);
                }
                break;
            case BOOST:
                fill();
                path.reset();
                path.moveTo(13, 2);
                path.lineTo(4, 14);
                path.lineTo(11, 14);
                path.lineTo(10, 22);
                path.lineTo(20, 9.5f);
                path.lineTo(13, 9.5f);
                path.close();
                c.drawPath(path, p);
                break;
            case TUNE:
                c.drawLine(3, 6, 21, 6, p);
                c.drawLine(3, 12, 21, 12, p);
                c.drawLine(3, 18, 21, 18, p);
                fill();
                c.drawCircle(8, 6, 2.6f, p);
                c.drawCircle(16, 12, 2.6f, p);
                c.drawCircle(10, 18, 2.6f, p);
                break;
            case FILES:
                path.reset();
                path.moveTo(3, 7);
                path.lineTo(3, 19);
                path.lineTo(21, 19);
                path.lineTo(21, 9);
                path.lineTo(11.5f, 9);
                path.lineTo(9.5f, 6);
                path.lineTo(3, 6);
                path.close();
                c.drawPath(path, p);
                break;
            case MIC:
                rf.set(9, 2, 15, 14);
                c.drawRoundRect(rf, 3, 3, p);
                rf.set(5, 4, 19, 18);
                c.drawArc(rf, 0, 180, false, p);
                c.drawLine(12, 18, 12, 22, p);
                c.drawLine(8.5f, 22, 15.5f, 22, p);
                break;
            case SPEAKER:
                path.reset();
                path.moveTo(3, 9);
                path.lineTo(7, 9);
                path.lineTo(12, 4.5f);
                path.lineTo(12, 19.5f);
                path.lineTo(7, 15);
                path.lineTo(3, 15);
                path.close();
                c.drawPath(path, p);
                rf.set(10, 8, 18, 16);
                c.drawArc(rf, -55, 110, false, p);
                rf.set(9, 4.5f, 22, 19.5f);
                c.drawArc(rf, -55, 110, false, p);
                break;
            case PICTURE:
                rf.set(2.5f, 5, 21.5f, 19);
                c.drawRoundRect(rf, 2.5f, 2.5f, p);
                c.drawLine(12, 8.5f, 12, 15.5f, p);
                c.drawLine(8.5f, 12, 15.5f, 12, p);
                c.drawCircle(12, 12, 4.5f, p);
                break;
            case CAST:
                // a screen with cast waves rising from its lower-left corner
                path.reset();
                path.moveTo(2.5f, 9);
                path.lineTo(2.5f, 6.5f);
                path.quadTo(2.5f, 4.5f, 4.5f, 4.5f);
                path.lineTo(19.5f, 4.5f);
                path.quadTo(21.5f, 4.5f, 21.5f, 6.5f);
                path.lineTo(21.5f, 17.5f);
                path.quadTo(21.5f, 19.5f, 19.5f, 19.5f);
                path.lineTo(14, 19.5f);
                c.drawPath(path, p);
                rf.set(-6.5f, 10.5f, 11.5f, 28.5f);
                c.drawArc(rf, -90, 90, false, p);
                rf.set(-3, 14, 8, 25);
                c.drawArc(rf, -90, 90, false, p);
                c.drawCircle(2.8f, 19.2f, 0.8f, p);
                break;
            case GAMEPAD:
                path.reset();
                path.moveTo(7, 7);
                path.lineTo(17, 7);
                path.cubicTo(21, 7, 22.5f, 12, 22, 16);
                path.cubicTo(21.6f, 19, 19, 19.5f, 17.5f, 17);
                path.lineTo(16, 15);
                path.lineTo(8, 15);
                path.lineTo(6.5f, 17);
                path.cubicTo(5, 19.5f, 2.4f, 19, 2, 16);
                path.cubicTo(1.5f, 12, 3, 7, 7, 7);
                path.close();
                c.drawPath(path, p);
                c.drawLine(5.5f, 11, 9.5f, 11, p);
                c.drawLine(7.5f, 9, 7.5f, 13, p);
                c.drawCircle(15.5f, 10, 0.6f, p);
                c.drawCircle(17.5f, 12, 0.6f, p);
                break;
            case PHONE:
                rf.set(7, 2, 17, 22);
                c.drawRoundRect(rf, 2.5f, 2.5f, p);
                c.drawLine(11, 18.5f, 13, 18.5f, p);
                break;
            default:
                break;
        }
        c.restore();
    }

    private void stroke(float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
    }

    private void fill() {
        p.setStyle(Paint.Style.FILL);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
