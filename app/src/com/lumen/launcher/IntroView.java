package com.lumen.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** Plays {@link IntroScene} full-screen on the projector, drawing through Android's Canvas. */
final class IntroView extends View implements IntroScene.Gfx {

    /** Where the intro's time comes from: the music's real playback position when available. */
    interface Clock {
        /** Seconds into the intro, or a negative value to keep holding the first frame. */
        float seconds();
    }

    interface Listener {
        /** The first (black) frame is on screen: the projector is ready to show the intro. */
        void onIntroReady();

        void onIntroFinished(boolean skipped);
    }

    private final IntroScene scene;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final RectF rf = new RectF();
    private final Path path = new Path();
    private Canvas cv;
    private long start = -1;
    private boolean finished, readySent;
    private Listener listener;
    private Clock clock;
    private String waiting;

    IntroView(Context c) {
        super(c);
        scene = new IntroScene();
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true); // swallow touches
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Small caption on the black hold screen (e.g. while a Bluetooth speaker reconnects); null hides it. */
    void setWaiting(String text) {
        waiting = text;
        invalidate();
    }

    /** Starts the clock; call right after the music starts so both stay in sync. */
    void begin() {
        begin(null);
    }

    void begin(Clock c) {
        clock = c;
        waiting = null;
        start = SystemClock.uptimeMillis();
        invalidate();
    }

    boolean isFinished() {
        return finished;
    }

    void skip() {
        if (finished) return;
        finished = true;
        animate().alpha(0f).setDuration(450).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onIntroFinished(true);
            }
        }).start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (start < 0) {
            // hold black until the activity tells us to start (after the display is really showing us)
            c.drawColor(0xFF000000);
            if (waiting != null) {
                text.setShader(null);
                text.setTypeface(light);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(getHeight() * 0.026f);
                text.setColor(0x80F5F5F7);
                c.drawText(waiting, getWidth() / 2f, getHeight() * 0.92f, text);
                postInvalidateDelayed(250);
            }
            if (!readySent) {
                readySent = true;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onIntroReady();
                    }
                });
            }
            return;
        }
        float t = clock != null ? clock.seconds() : (SystemClock.uptimeMillis() - start) / 1000f;
        if (t < 0) { // the music hasn't reached the speaker yet: hold the opening frame
            c.drawColor(0xFF000000);
            postInvalidateOnAnimation();
            return;
        }
        scene.step(t, getWidth(), getHeight());
        cv = c;
        scene.draw(this, t);
        cv = null;
        if (finished) return;
        float a = scene.alpha(t);
        if (a < 1f) setAlpha(a);
        if (scene.done(t)) {
            finished = true;
            post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) listener.onIntroFinished(false);
                }
            });
        } else {
            postInvalidateOnAnimation();
        }
    }

    // ============================================================== IntroScene.Gfx

    @Override
    public void flipY(float axisY) {
        cv.save();
        cv.scale(1f, -1f, 0f, axisY);
    }

    @Override
    public void restore() {
        cv.restore();
    }

    @Override
    public void clear(int argb) {
        cv.drawColor(argb);
    }

    @Override
    public void glow(float cx, float cy, float r, int argb) {
        if (r <= 1 || (argb >>> 24) == 0) return;
        fill.setShader(new RadialGradient(cx, cy, r, argb, argb & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, fill);
        fill.setShader(null);
    }

    @Override
    public void circle(float cx, float cy, float r, int argb) {
        if (r <= 0) return;
        fill.setColor(argb);
        cv.drawCircle(cx, cy, r, fill);
    }

    @Override
    public void ring(float cx, float cy, float r, float width, int argb) {
        if (r <= 0) return;
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        cv.drawCircle(cx, cy, r, stroke);
    }

    @Override
    public void arc(float cx, float cy, float r, float width, float startDeg, float sweepDeg, int argb) {
        if (sweepDeg <= 0) return;
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        rf.set(cx - r, cy - r, cx + r, cy + r);
        cv.drawArc(rf, startDeg, sweepDeg, false, stroke);
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float width, int argb) {
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        cv.drawLine(x0, y0, x1, y1, stroke);
    }

    @Override
    public void text(String s, float x, float y, float size, int argb, int weight, int align, float scale) {
        if (scale <= 0) return;
        setupText(size, weight);
        text.setColor(argb);
        text.setTextAlign(align == 0 ? Paint.Align.CENTER : align == 1 ? Paint.Align.RIGHT : Paint.Align.LEFT);
        if (scale != 1f) {
            cv.save();
            cv.scale(scale, scale, x, y);
            cv.drawText(s, x, y, text);
            cv.restore();
        } else {
            cv.drawText(s, x, y, text);
        }
    }

    @Override
    public float textWidth(String s, float size, int weight) {
        setupText(size, weight);
        return text.measureText(s);
    }

    @Override
    public void rect(float l, float t, float r, float b, int argb) {
        fill.setColor(argb);
        cv.drawRect(l, t, r, b, fill);
    }

    @Override
    public void vgradient(float l, float t, float r, float b, int[] colors) {
        fill.setShader(new LinearGradient(0, t, 0, b, colors, null, Shader.TileMode.CLAMP));
        cv.drawRect(l, t, r, b, fill);
        fill.setShader(null);
    }

    @Override
    public void poly(float[] xy, int points, int argb) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 1; i < points; i++) path.lineTo(xy[2 * i], xy[2 * i + 1]);
        path.close();
        fill.setColor(argb);
        cv.drawPath(path, fill);
    }

    private void setupText(float size, int weight) {
        text.setTextSize(size);
        text.setTypeface(weight == 0 ? light : weight == 2 ? medium : regular);
    }
}
