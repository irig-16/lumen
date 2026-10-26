package com.lumen.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Lumen Imaging: picture profiles, the Detail engine, Auto Picture, Compare and Reset. */
public class ScreenActivity extends Activity {

    private float d;
    private final Handler main = new Handler();
    private final List<TextView> profileChips = new ArrayList<TextView>();
    private final List<TextView> detailChips = new ArrayList<TextView>();
    private LinearLayout guide;
    private TextView status, presetLabel, profileBlurb, detailBlurb, guideHead, autoBtn, compareBtn, resetBtn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        if (!ScreenEngine.engine()) ScreenEngine.start(this);

        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(14), 0, dp(14));
        root.addView(sv, new FrameLayout.LayoutParams(dp(800), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Theme.GLASS_TOP, Theme.GLASS_BOTTOM});
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(1), Theme.GLASS_STROKE);
        panel.setBackground(bg);
        panel.setElevation(dp(24));
        panel.setPadding(dp(30), dp(20), dp(30), dp(18));
        sv.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Screen  ·  Lumen Imaging", 24, Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        presetLabel = text("", 14, Theme.ROSE);
        head.addView(presetLabel);
        panel.addView(head);
        status = text("", 13, Theme.SUB);
        status.setPadding(0, dp(2), 0, dp(10));
        panel.addView(status);

        // ---- profiles (two rows of three)
        panel.addView(label("PICTURE"));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(4), 0, dp(4));
            row.setClipChildren(false);
            for (int k = 0; k < 3; k++) {
                final int i = r * 3 + k;
                final String key = ScreenEngine.PRESETS[i];
                TextView chip = chip(ScreenEngine.NAMES[i]);
                chip.setTag(key);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String done = ScreenEngine.apply(ScreenActivity.this, key);
                        refresh();
                        if (done.length() > 0) Toast.makeText(ScreenActivity.this, ScreenEngine.NAMES[i] + ": " + done, Toast.LENGTH_SHORT).show();
                    }
                });
                chip.setOnFocusChangeListener(focusBlurb(chip, ScreenEngine.BLURBS[i], true));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (k > 0) lp.leftMargin = dp(8);
                row.addView(chip, lp);
                profileChips.add(chip);
            }
            panel.addView(row);
        }
        profileBlurb = text("", 13, Theme.SUB);
        profileBlurb.setPadding(dp(4), dp(2), 0, dp(12));
        panel.addView(profileBlurb);

        // ---- Detail engine
        panel.addView(label("DETAIL  ·  edge enhancer and colour-edge sharpening, with noise control"));
        LinearLayout drow = new LinearLayout(this);
        drow.setPadding(0, dp(4), 0, dp(4));
        drow.setClipChildren(false);
        for (int i = 0; i < ScreenEngine.DETAIL_NAMES.length; i++) {
            final int level = i;
            TextView chip = chip(ScreenEngine.DETAIL_NAMES[i]);
            chip.setTag(i);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ScreenEngine.setDetail(ScreenActivity.this, level);
                    refresh();
                }
            });
            chip.setOnFocusChangeListener(focusBlurb(chip, ScreenEngine.DETAIL_BLURBS[i], false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            drow.addView(chip, lp);
            detailChips.add(chip);
        }
        panel.addView(drow);
        detailBlurb = text("", 13, Theme.SUB);
        detailBlurb.setPadding(dp(4), dp(2), 0, dp(12));
        panel.addView(detailBlurb);

        // ---- Auto Picture / Compare / Reset
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(0, dp(2), 0, dp(4));
        autoBtn = chip("");
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ScreenEngine.setAuto(ScreenActivity.this, !ScreenEngine.auto(ScreenActivity.this));
                refresh();
            }
        });
        compareBtn = chip("Compare with original");
        compareBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ScreenEngine.compare(ScreenActivity.this, 5)) {
                    Toast.makeText(ScreenActivity.this, "The projector's original picture for 5 seconds…", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(ScreenActivity.this, "Pick a profile first; then you can compare", Toast.LENGTH_SHORT).show();
                }
            }
        });
        resetBtn = chip("Reset to projector's own");
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        tools.addView(autoBtn, new LinearLayout.LayoutParams(0, dp(46), 1.15f));
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, dp(46), 1.15f);
        t2.leftMargin = dp(8);
        tools.addView(compareBtn, t2);
        LinearLayout.LayoutParams t3 = new LinearLayout.LayoutParams(0, dp(46), 1.15f);
        t3.leftMargin = dp(8);
        tools.addView(resetBtn, t3);
        panel.addView(tools);
        TextView autoNote = text("Auto Picture follows what's playing: films (Netflix, Prime, Disney+…) get HDR-style, "
                + "YouTube gets Vivid, games get Game, and the home screen returns to your pick. "
                + "Choose a fixed picture for any app with MENU on its card.", 12, Theme.DIM);
        autoNote.setPadding(dp(4), dp(6), 0, dp(10));
        panel.addView(autoNote);

        // ---- fallback guide (only when the processor isn't reachable)
        guideHead = label("SET IN THE PROJECTOR'S PICTURE MENU");
        panel.addView(guideHead);
        guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.VERTICAL);
        panel.addView(guide);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(12), 0, 0);
        TextView open = chip("Open projector picture menu");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PictureSettings.open(ScreenActivity.this);
            }
        });
        TextView lab = chip("Picture Lab");
        lab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ScreenActivity.this, PictureLabActivity.class));
            }
        });
        buttons.addView(open, new LinearLayout.LayoutParams(0, dp(46), 1.6f));
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        b2.leftMargin = dp(8);
        buttons.addView(lab, b2);
        panel.addView(buttons);

        TextView note = text("A 720p LCD projector can't show true HDR or Dolby Vision. HDR-style deepens blacks and lifts "
                + "mid-tones for the most HDR-like look this panel can give. Detail sharpens real edges and keeps "
                + "streaming noise down, so it looks clearer without the halo effect of the plain sharpness slider.", 12, Theme.DIM);
        note.setPadding(dp(4), dp(12), 0, 0);
        panel.addView(note);

        refresh();
        panel.setAlpha(0f);
        panel.setTranslationY(dp(30));
        panel.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(new OvershootInterpolator(1.05f)).start();
        int pi = Math.max(0, ScreenEngine.index(ScreenEngine.current(this)));
        profileChips.get(pi).requestFocusFromTouch();
        main.postDelayed(new Runnable() { // the processor connects in the background
            @Override
            public void run() {
                if (!isFinishing()) refresh();
            }
        }, 900);
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
        refresh();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private void refresh() {
        boolean eng = ScreenEngine.engine();
        String cur = ScreenEngine.current(this);
        int ci = Math.max(0, ScreenEngine.index(cur));
        int lv = ScreenEngine.detail(this);
        presetLabel.setText((ScreenEngine.NAMES[ci] + (eng ? "  ·  " + ScreenEngine.DETAIL_NAMES[lv] : "")).toUpperCase());
        status.setText(ScreenEngine.status(this));
        for (TextView c : profileChips) c.setSelected(cur.equals(c.getTag()));
        for (TextView c : detailChips) {
            c.setSelected(((Integer) c.getTag()) == lv);
            c.setEnabled(eng);
            c.setAlpha(eng ? 1f : 0.4f);
        }
        if (!profileBlurb.hasFocus()) profileBlurb.setText(ScreenEngine.BLURBS[ci]);
        detailBlurb.setText(eng ? ScreenEngine.DETAIL_BLURBS[lv] : "Detail needs direct access to the picture processor");
        autoBtn.setText("Auto Picture:  " + (ScreenEngine.auto(this) ? "On" : "Off"));
        autoBtn.setSelected(ScreenEngine.auto(this));
        compareBtn.setVisibility(eng ? View.VISIBLE : View.GONE);
        resetBtn.setVisibility(eng ? View.VISIBLE : View.GONE);
        guideHead.setVisibility(eng ? View.GONE : View.VISIBLE);
        guide.setVisibility(eng ? View.GONE : View.VISIBLE);
        guide.removeAllViews();
        if (!eng) {
            for (String[] kv : ScreenEngine.guide(cur)) {
                LinearLayout r = new LinearLayout(this);
                r.setPadding(dp(4), dp(3), 0, dp(3));
                r.addView(text(kv[0], 15, Theme.SUB), new LinearLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT));
                r.addView(text(kv[1], 15, Theme.TEXT));
                guide.addView(r);
            }
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reset the picture?")
                .setMessage("Puts back every picture value Lumen changed, the projector's own detail settings and "
                        + "its original picture mode, and turns Auto Picture off.")
                .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        ScreenEngine.resetAll(ScreenActivity.this);
                        refresh();
                        Toast.makeText(ScreenActivity.this, "Projector picture restored", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View.OnFocusChangeListener focusBlurb(final TextView chip, final String blurb, final boolean profile) {
        return new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                float sc = has ? 1.06f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(160).setInterpolator(new DecelerateInterpolator(2f)).start();
                if (has) (profile ? profileBlurb : detailBlurb).setText(blurb);
            }
        };
    }

    private TextView label(String s) {
        TextView t = text(s, 12, Theme.CERULEAN_LIGHT);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(4), 0, dp(4));
        return t;
    }

    private TextView chip(String label) {
        TextView t = text(label, 14, Theme.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, dp(8), 0);
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        s.addState(new int[]{android.R.attr.state_selected, android.R.attr.state_focused}, pill(Theme.ROSE, Color.WHITE));
        s.addState(new int[]{android.R.attr.state_selected}, pill(Theme.ROSE, 0));
        s.addState(new int[]{android.R.attr.state_focused}, pill(Theme.CERULEAN, Color.WHITE));
        s.addState(new int[]{}, pill(Theme.CERULEAN_GLASS, 0));
        t.setBackground(s);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                float sc = has ? 1.06f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(160).setInterpolator(new DecelerateInterpolator(2f)).start();
            }
        });
        return t;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(23));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
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
}
