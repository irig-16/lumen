package com.lumen.launcher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Round glass button for the top bar; turns solid white with a dark glyph when focused (tvOS style). */
final class QuickButton extends FrameLayout {

    final String title;
    final String subtitle;
    final int glyphType;
    private final Glyph glyph;

    QuickButton(Context c, int glyphType, String title, String subtitle, float density) {
        super(c);
        this.title = title;
        this.subtitle = subtitle;
        this.glyphType = glyphType;
        setFocusable(true);
        setClickable(true);

        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(Theme.CERULEAN_GLASS);
        idle.setStroke((int) (density + 0.5f), Theme.GLASS_STROKE);
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(Theme.CERULEAN);
        on.setStroke((int) (2 * density + 0.5f), Theme.ROSE);
        StateListDrawable bg = new StateListDrawable();
        bg.setEnterFadeDuration(140);
        bg.setExitFadeDuration(140);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{android.R.attr.state_pressed}, on);
        bg.addState(new int[]{}, idle);
        setBackground(bg);

        glyph = new Glyph(glyphType, Theme.TEXT);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(glyph);
        int g = (int) (20 * density + 0.5f);
        addView(iv, new LayoutParams(g, g, Gravity.CENTER));
    }

    void focus() {
        glyph.setColor(0xFFFFFFFF);
        animate().scaleX(1.18f).scaleY(1.18f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
    }

    void blur() {
        glyph.setColor(Theme.TEXT);
        animate().scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }
}
