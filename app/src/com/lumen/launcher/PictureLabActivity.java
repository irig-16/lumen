package com.lumen.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Picture Lab: full-screen calibration patterns drawn at the projector's native pixels.
 * Software can't add resolution, but correct focus, geometry and levels are the biggest
 * real-world gains on a small LCD projector.
 */
public class PictureLabActivity extends Activity {

    static final String EXTRA_PATTERN = "pattern";

    private static final String[] TITLES = {
            "Focus", "Geometry & keystone", "Black level", "White level", "Sharpness", "Gradient & colour"};
    private static final String[] TIPS = {
            "Turn the focus wheel slowly until the finest lines in the centre star and the corner patches are crisp. "
                    + "If the centre and corners can't both be sharp, favour the centre.",
            "Move or tilt the projector until the white frame is a true rectangle and the circles look round. "
                    + "Physical alignment beats digital keystone: keystone throws away pixels and softens the picture, so set it to 0 if you can.",
            "In the projector's picture menu, lower Brightness until the 0% bar disappears into the background, "
                    + "then raise it just until the 2% bar is barely visible. Darken the room first.",
            "Raise Contrast until the brightest bars start to merge, then lower it until 96%, 98% and 100% are all separate. "
                    + "Whites stay bright without losing detail in clouds and skin.",
            "Lower Sharpness until there are no bright or dark outlines (halos) next to the lines. "
                    + "On most projectors the best setting is near the bottom of the slider.",
            "The ramps should look smooth, not stepped, and greys should look neutral. If whites look blue, choose "
                    + "Colour temperature Normal or Warm. For films, turn off dynamic contrast and motion smoothing (MEMC)."};

    private PatternView pattern;
    private LinearLayout card;
    private TextView title, tip, page;
    private int index;
    private boolean tipsVisible = true;
    private float d;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d = getResources().getDisplayMetrics().density;
        index = Math.max(0, Math.min(TITLES.length - 1, getIntent().getIntExtra(EXTRA_PATTERN, 0)));

        FrameLayout root = new FrameLayout(this);
        pattern = new PatternView(this);
        root.addView(pattern, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xE60A0A0A);
        bg.setStroke(dp(1), Theme.GLASS_STROKE);
        card.setBackground(bg);
        card.setPadding(dp(22), dp(16), dp(22), dp(16));
        LinearLayout head = new LinearLayout(this);
        title = text(20, 0xFFF4F6FA);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page = text(13, Theme.ROSE);
        head.addView(page);
        card.addView(head);
        tip = text(14, 0xCCE6EAF2);
        tip.setPadding(0, dp(6), 0, dp(8));
        tip.setLineSpacing(0, 1.15f);
        card.addView(tip);
        TextView keys = text(12, 0x80E6EAF2);
        keys.setText("◀ ▶ pattern   ·   OK projector picture menu   ·   ▲ ▼ hide tips   ·   BACK exit");
        card.addView(keys);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = dp(28);
        root.addView(card, clp);

        setContentView(root);
        show();

        // look for the projector's picture menu in the background
        new Thread(new Runnable() {
            @Override
            public void run() {
                PictureSettings.find(getApplicationContext());
            }
        }, "lumen-pq-scan").start();
    }

    private void show() {
        pattern.setPattern(index);
        title.setText(TITLES[index]);
        tip.setText(TIPS[index]);
        page.setText((index + 1) + " / " + TITLES.length);
        // keep the card away from the area being judged
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.gravity = (index == 2 || index == 3 ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(28);
        card.setLayoutParams(lp);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                index = (index + 1) % TITLES.length;
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                index = (index + TITLES.length - 1) % TITLES.length;
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                tipsVisible = !tipsVisible;
                card.animate().alpha(tipsVisible ? 1f : 0f).setDuration(200).start();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                PictureSettings.open(this);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    /** Draws the patterns in real pixels: hairlines are exactly one projector pixel wide. */
    static final class PatternView extends View {
        private int which;
        private final Paint p = new Paint();
        private final Paint aa = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();

        PatternView(Context c) {
            super(c);
            p.setAntiAlias(false);
            p.setStrokeWidth(0); // hairline = 1 physical pixel
        }

        void setPattern(int i) {
            which = i;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            switch (which) {
                case 0:
                    focus(c, w, h);
                    break;
                case 1:
                    geometry(c, w, h);
                    break;
                case 2:
                    black(c, w, h);
                    break;
                case 3:
                    white(c, w, h);
                    break;
                case 4:
                    sharpness(c, w, h);
                    break;
                default:
                    gradient(c, w, h);
                    break;
            }
        }

        private static int grey(float pct) {
            int v = Math.round(pct / 100f * 255f);
            return Color.rgb(v, v, v);
        }

        private void focus(Canvas c, int w, int h) {
            c.drawColor(Color.BLACK);
            // Siemens star
            float cx = w / 2f, cy = h / 2f, r = h * 0.28f;
            aa.setColor(Color.WHITE);
            aa.setStyle(Paint.Style.FILL);
            int spokes = 72;
            for (int i = 0; i < spokes; i += 2) {
                double a0 = Math.PI * 2 * i / spokes, a1 = Math.PI * 2 * (i + 1) / spokes;
                path.reset();
                path.moveTo(cx, cy);
                path.lineTo(cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r);
                path.lineTo(cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r);
                path.close();
                c.drawPath(path, aa);
            }
            // 1-pixel gratings in the four corners and beside the star
            int s = Math.max(60, h / 7);
            int m = Math.max(12, h / 30);
            grating(c, m, m, s);
            grating(c, w - m - s, m, s);
            grating(c, m, h - m - s, s);
            grating(c, w - m - s, h - m - s, s);
            grating(c, (int) (cx - r - s * 1.3f), (int) (cy - s / 2f), s);
            grating(c, (int) (cx + r + s * 0.3f), (int) (cy - s / 2f), s);
            // small text
            aa.setTypeface(Typeface.DEFAULT);
            float[] sizes = {9, 11, 13, 16};
            float y = h * 0.82f;
            for (float size : sizes) {
                aa.setTextSize(size * getResources().getDisplayMetrics().density);
                aa.setTextAlign(Paint.Align.CENTER);
                c.drawText("The quick brown fox jumps over the lazy dog 0123456789", cx, y, aa);
                y += size * getResources().getDisplayMetrics().density * 1.5f;
            }
        }

        /** Square split in two halves: vertical 1-px lines on top, horizontal on the bottom. */
        private void grating(Canvas c, int x, int y, int s) {
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(x, y, x + s, y + s, p);
            p.setColor(Color.WHITE);
            for (int i = 0; i < s; i += 2) c.drawRect(x + i, y, x + i + 1, y + s / 2, p);
            for (int j = s / 2; j < s; j += 2) c.drawRect(x, y + j, x + s, y + j + 1, p);
        }

        private void geometry(Canvas c, int w, int h) {
            c.drawColor(Color.BLACK);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF505050);
            for (int i = 1; i < 16; i++) c.drawRect(w * i / 16, 0, w * i / 16 + 1, h, p);
            for (int j = 1; j < 9; j++) c.drawRect(0, h * j / 9, w, h * j / 9 + 1, p);
            p.setColor(Color.WHITE);
            c.drawRect(0, 0, w, 1, p);
            c.drawRect(0, h - 1, w, h, p);
            c.drawRect(0, 0, 1, h, p);
            c.drawRect(w - 1, 0, w, h, p);
            c.drawRect(w / 2, 0, w / 2 + 1, h, p);
            c.drawRect(0, h / 2, w, h / 2 + 1, p);
            aa.setStyle(Paint.Style.STROKE);
            aa.setStrokeWidth(2);
            aa.setColor(Color.WHITE);
            c.drawCircle(w / 2f, h / 2f, h * 0.42f, aa);
            float rr = h * 0.13f;
            c.drawCircle(rr + h * 0.04f, rr + h * 0.04f, rr, aa);
            c.drawCircle(w - rr - h * 0.04f, rr + h * 0.04f, rr, aa);
            c.drawCircle(rr + h * 0.04f, h - rr - h * 0.04f, rr, aa);
            c.drawCircle(w - rr - h * 0.04f, h - rr - h * 0.04f, rr, aa);
            aa.setStyle(Paint.Style.FILL);
        }

        private void black(Canvas c, int w, int h) {
            c.drawColor(Color.BLACK);
            float[] lv = {0, 1, 2, 3, 4, 5, 6, 8, 10, 15};
            barRow(c, w, h, lv, 0xFF5A5A5A);
        }

        private void white(Canvas c, int w, int h) {
            c.drawColor(grey(50));
            float[] lv = {84, 86, 88, 90, 92, 94, 96, 98, 100};
            barRow(c, w, h, lv, Color.BLACK);
        }

        private void barRow(Canvas c, int w, int h, float[] levels, int labelColor) {
            int n = levels.length;
            float mx = w * 0.06f, gap = w * 0.008f;
            float bw = (w - 2 * mx - gap * (n - 1)) / n;
            float top = h * 0.40f, bottom = h * 0.80f;
            aa.setTextSize(h * 0.035f);
            aa.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                float x = mx + i * (bw + gap);
                p.setColor(grey(levels[i]));
                p.setStyle(Paint.Style.FILL);
                c.drawRect(x, top, x + bw, bottom, p);
                aa.setColor(labelColor);
                c.drawText(((int) levels[i]) + "%", x + bw / 2, bottom + h * 0.06f, aa);
            }
        }

        private void sharpness(Canvas c, int w, int h) {
            c.drawColor(grey(50));
            p.setStyle(Paint.Style.FILL);
            // thin black and white lines with gaps, plus solid edges
            for (int i = 0; i < 6; i++) {
                int x = (int) (w * (0.15f + i * 0.12f));
                p.setColor(Color.BLACK);
                c.drawRect(x, h * 0.12f, x + 1 + i / 2, h * 0.45f, p);
                p.setColor(Color.WHITE);
                c.drawRect(x + w * 0.05f, h * 0.12f, x + w * 0.05f + 1 + i / 2, h * 0.45f, p);
            }
            p.setColor(Color.BLACK);
            c.drawRect(w * 0.15f, h * 0.55f, w * 0.45f, h * 0.85f, p);
            p.setColor(Color.WHITE);
            c.drawRect(w * 0.55f, h * 0.55f, w * 0.85f, h * 0.85f, p);
            aa.setColor(Color.BLACK);
            aa.setTextAlign(Paint.Align.CENTER);
            aa.setTextSize(h * 0.05f);
            c.drawText("Look for halos at every edge", w / 2f, h * 0.52f, aa);
        }

        private void gradient(Canvas c, int w, int h) {
            c.drawColor(Color.BLACK);
            int[][] ramps = {{Color.BLACK, Color.WHITE}, {Color.BLACK, Color.RED}, {Color.BLACK, Color.GREEN},
                    {Color.BLACK, Color.BLUE}};
            float rh = h * 0.14f, y = h * 0.06f;
            for (int[] r : ramps) {
                aa.setShader(new LinearGradient(w * 0.05f, 0, w * 0.95f, 0, r[0], r[1], Shader.TileMode.CLAMP));
                rf.set(w * 0.05f, y, w * 0.95f, y + rh);
                c.drawRect(rf, aa);
                y += rh + h * 0.02f;
            }
            aa.setShader(null);
            int[] bars = {0xFFBFBFBF, 0xFFBFBF00, 0xFF00BFBF, 0xFF00BF00, 0xFFBF00BF, 0xFFBF0000, 0xFF0000BF};
            float bw = w * 0.9f / bars.length;
            for (int i = 0; i < bars.length; i++) {
                p.setColor(bars[i]);
                p.setStyle(Paint.Style.FILL);
                c.drawRect(w * 0.05f + i * bw, y, w * 0.05f + (i + 1) * bw, h * 0.96f, p);
            }
        }
    }    @Override
    protected void onPause() {
        super.onPause();
        Front.paused(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Front.resumed(this);
    }


}
