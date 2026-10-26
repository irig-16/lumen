package com.lumen.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Sound Studio panel: presets, five sliders and two switches, all driven by the remote's D-pad. */
public class SoundActivity extends Activity implements SoundEngine.Listener {

    private static final int C_TEXT = Theme.TEXT;
    private static final int C_SUB = Theme.SUB;
    private static final int C_ACCENT = Theme.CERULEAN_LIGHT;

    private SoundEngine engine;
    private float d;
    private TextView status, presetLabel;
    private final List<TextView> chips = new ArrayList<TextView>();
    private final List<Slider> sliders = new ArrayList<Slider>();
    private Toggle abToggle, resetRow, outputRow;
    private EqCurveView curve;
    private LinearLayout panel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        engine = SoundEngine.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(null);
        setContentView(root);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(14), 0, dp(14));
        root.addView(sv, new FrameLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Theme.GLASS_TOP, Theme.GLASS_BOTTOM});
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(1), Theme.GLASS_STROKE);
        panel.setBackground(bg);
        panel.setElevation(dp(24));
        panel.setPadding(dp(30), dp(20), dp(30), dp(16));
        sv.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Sound Studio", 24, C_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        presetLabel = text("", 14, Theme.ROSE);
        presetLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(presetLabel);
        panel.addView(head);
        status = text("", 13, C_SUB);
        status.setPadding(0, dp(2), 0, dp(10));
        panel.addView(status);

        // presets
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setPadding(0, dp(4), 0, dp(10));
        for (int i = 0; i < SoundEngine.PRESETS.length; i++) {
            final String key = SoundEngine.PRESETS[i];
            TextView chip = chip(SoundEngine.PRESET_NAMES[i]);
            chip.setTag(key);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    engine.applyPreset(key);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
            chips.add(chip);
        }
        panel.addView(row);

        // the EQ that's being applied right now
        curve = new EqCurveView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        clp.bottomMargin = dp(6);
        panel.addView(curve, clp);

        // where the sound goes: decides the tuning (a home theatre gets very different treatment)
        outputRow = new Toggle(this, "Sound goes to", "");
        outputRow.hideSwitch = true;
        outputRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.cycleOutputMode();
            }
        });
        panel.addView(outputRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        // sliders
        String[] keys = {"bass", "treble", "clarity", "loudness", "surround"};
        String[] names = {"Bass", "Treble", "Dialogue clarity", "Loudness", "Surround"};
        float[] ranges = {6f, 6f, 6f, 4f, 0f};   // ± dB around the preset's own tuning; 0 = percentage
        for (int i = 0; i < names.length; i++) {
            Slider s = new Slider(this, keys[i], names[i], ranges[i]);
            panel.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            sliders.add(s);
        }

        abToggle = new Toggle(this, "A/B compare: hear Off", "Flip between Off and the preset to judge by ear (returns by itself)");
        abToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.setBypass(!engine.bypass);
            }
        });
        resetRow = new Toggle(this, "Reset fine-tuning", "Back to the preset's own tuning");
        resetRow.hideSwitch = true;
        resetRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.applyPreset(engine.preset);
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        tlp.topMargin = dp(6);
        panel.addView(abToggle, tlp);
        panel.addView(resetRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        TextView foot = text("◀ ▶ adjust   ·   OK select   ·   BACK close   ·   say “cinema sound” or “home theatre sound”", 12, 0x73E6EAF2);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.setPadding(0, dp(10), 0, 0);
        panel.addView(foot);

        refresh();

        // entrance
        panel.setAlpha(0f);
        panel.setTranslationY(dp(30));
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.05f)).start();

        int idx = 0;
        for (int i = 0; i < SoundEngine.PRESETS.length; i++) if (SoundEngine.PRESETS[i].equals(engine.preset)) idx = i;
        chips.get(idx).requestFocusFromTouch();
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.addListener(this);
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        engine.removeListener(this);
        if (engine.bypass) engine.setBypass(false);
    }

    @Override
    public void onSoundChanged() {
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Front.paused(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Front.resumed(this);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private void refresh() {
        status.setText(engine.status());
        presetLabel.setText((SoundEngine.presetName(engine.preset) + (engine.tuned() && !"off".equals(engine.preset) ? " · TUNED" : "")
                + "   ·   " + engine.outputName()).toUpperCase());
        for (TextView c : chips) {
            boolean sel = c.getTag().equals(engine.preset);
            c.setSelected(sel);
            c.setTextColor(C_TEXT);
        }
        for (Slider s : sliders) s.invalidate();
        abToggle.setOn(engine.bypass);
        curve.setGains(engine.currentGains(), engine.isOn());
        outputRow.value = engine.outputModeLabel();
        outputRow.sub = outputHint();
        outputRow.invalidate();
    }

    private String outputHint() {
        switch (engine.output) {
            case SoundTuning.HOME_THEATRE:
                return "Near-flat tuning that keeps the film's dynamics. Tip: projector volume high, control it on the receiver";
            case SoundTuning.EXTERNAL:
                return "Tuned for a portable Bluetooth speaker";
            case SoundTuning.HEADPHONES:
                return "Tuned for headphones; Surround works here";
            default:
                return "Tuned for the built-in speaker: deep bass removed so it plays louder and cleaner";
        }
    }

    // ================================================================== views

    private TextView chip(String label) {
        TextView t = text(label, 15, C_TEXT);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(dp(23));
        idle.setColor(Theme.CERULEAN_GLASS);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(dp(23));
        focus.setColor(0x33FFFFFF);
        focus.setStroke(dp(2), Color.WHITE);
        GradientDrawable sel = new GradientDrawable();
        sel.setCornerRadius(dp(23));
        sel.setColor(Theme.ROSE);
        GradientDrawable selFocus = new GradientDrawable();
        selFocus.setCornerRadius(dp(23));
        selFocus.setColor(Theme.ROSE);
        selFocus.setStroke(dp(2), Color.WHITE);
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        s.addState(new int[]{android.R.attr.state_selected, android.R.attr.state_focused}, selFocus);
        s.addState(new int[]{android.R.attr.state_selected}, sel);
        s.addState(new int[]{android.R.attr.state_focused}, focus);
        s.addState(new int[]{}, idle);
        t.setBackground(s);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                float sc = has ? 1.08f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(160).setInterpolator(new DecelerateInterpolator(2f)).start();
            }
        });
        return t;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    private static StateListDrawable rowBackground(float d) {
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(14 * d);
        f.setColor(0x1FFFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    /** One D-pad slider row: label, track with fill and knob, value text. */
    private final class Slider extends View {
        final String key, label;
        final boolean bipolar;
        final float range;
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
                fill = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Slider(Context c, String key, String label, float range) {
            super(c);
            this.key = key;
            this.label = label;
            this.range = range;
            this.bipolar = range > 0;
            setFocusable(true);
            setBackground(rowBackground(d));
            text.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
            track.setColor(0x26FFFFFF);
            fill.setColor(Theme.CERULEAN);
            knob.setColor(Color.WHITE);
        }

        @Override
        protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            invalidate();
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                engine.adjust(key, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : -5);
                return true;
            }
            return super.onKeyDown(code, e);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cy = h / 2f;
            int v = engine.get(key);
            boolean f = isFocused();
            text.setColor(f ? C_TEXT : C_SUB);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(label, 16 * d, cy + text.getTextSize() * 0.35f, text);

            float x0 = 210 * d, x1 = w - 96 * d, th = (f ? 8 : 6) * d;
            r.set(x0, cy - th / 2, x1, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, track);
            float pos = x0 + (x1 - x0) * v / 100f;
            if (bipolar) {
                float mid = (x0 + x1) / 2f;
                r.set(Math.min(mid, pos), cy - th / 2, Math.max(mid, pos), cy + th / 2);
                c.drawRect(mid - d, cy - th, mid + d, cy + th, track);
            } else {
                r.set(x0, cy - th / 2, pos, cy + th / 2);
            }
            c.drawRoundRect(r, th / 2, th / 2, fill);
            knob.setColor(f ? Theme.ROSE : Color.WHITE);
            c.drawCircle(pos, cy, (f ? 11 : 8) * d, knob);

            String val;
            if (bipolar) {
                float db = SoundTuning.offset(v, range);
                val = Math.abs(db) < 0.05f ? "preset" : String.format("%+.1f dB", db);
            } else {
                val = engine.output == SoundTuning.HEADPHONES ? v + "%" : "headphones";
            }
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(f ? C_TEXT : C_SUB);
            c.drawText(val, w - 16 * d, cy + text.getTextSize() * 0.35f, text);
        }
    }

    /** Label + subtitle + pill switch; OK toggles. */
    private final class Toggle extends View {
        final String label;
        String sub, value;
        boolean on, hideSwitch;
        float anim;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Toggle(Context c, String label, String sub) {
            super(c);
            this.label = label;
            this.sub = sub;
            setFocusable(true);
            setClickable(true);
            setBackground(rowBackground(d));
            float sd = getResources().getDisplayMetrics().scaledDensity;
            p.setTextSize(16 * sd);
            small.setTextSize(12 * sd);
        }

        @Override
        protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            invalidate();
        }

        void setOn(boolean v) {
            if (on == v && anim == (v ? 1f : 0f)) return;
            on = v;
            final float from = anim, to = v ? 1f : 0f;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, to);
            a.setDuration(180);
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(android.animation.ValueAnimator va) {
                    anim = (Float) va.getAnimatedValue();
                    invalidate();
                }
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean f = isFocused();
            p.setColor(f ? C_TEXT : C_SUB);
            c.drawText(label, 16 * d, h * 0.45f, p);
            small.setColor(0x80E6EAF2);
            c.drawText(sub, 16 * d, h * 0.45f + 18 * d, small);
            if (value != null) {
                p.setColor(f ? Theme.ROSE : Theme.CERULEAN_LIGHT);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(value + (f ? "  ▸" : ""), w - 16 * d, h * 0.45f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            if (hideSwitch) return;
            float sw = 52 * d, sh = 30 * d, x1 = w - 16 * d, x0 = x1 - sw, cy = h / 2f;
            r.set(x0, cy - sh / 2, x1, cy + sh / 2);
            p.setColor(blend(0x33FFFFFF, Theme.CERULEAN, anim));
            c.drawRoundRect(r, sh / 2, sh / 2, p);
            p.setColor(Color.WHITE);
            float kx = x0 + sh / 2 + (sw - sh) * anim;
            c.drawCircle(kx, cy, sh / 2 - 3 * d, p);
        }
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * f) << 24) | ((int) (ar + (br - ar) * f) << 16)
                | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }
}
