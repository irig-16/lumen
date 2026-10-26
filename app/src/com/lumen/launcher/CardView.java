package com.lumen.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A tvOS-style app card.
 *
 * Focus effect, in layers:
 *  1. the card tilts in from the direction focus came from, then springs flat (overshoot)
 *  2. it lifts: scale 1.12 + a real elevation shadow
 *  3. the icon layer lags behind the plate, giving layered parallax depth
 *  4. a soft specular glare sweeps across once
 *  5. while it stays focused it "breathes" with a slow sub-3° drift
 * Everything animates RenderNode properties (scale/rotation/translation/alpha), so the
 * card's pixels are never redrawn during the motion — the GPU just transforms them.
 */
final class CardView extends LinearLayout {

    private static final OvershootInterpolator SPRING = new OvershootInterpolator(1.25f);
    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator(2f);

    final AppInfo app;
    final boolean fav;
    final FrameLayout face;

    private final ImageView art;
    private final View sheen, glare;
    private final TextView label;
    private final float d;
    private final int faceW, faceH, glareW;
    private final boolean alwaysLabel;
    private boolean motion = true;
    private ValueAnimator idle;

    private final Runnable startIdle = new Runnable() {
        @Override
        public void run() {
            if (!motion || !hasFocus()) return;
            idle = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            idle.setDuration(5600);
            idle.setRepeatCount(ValueAnimator.INFINITE);
            idle.setInterpolator(new LinearInterpolator());
            idle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float t = (Float) a.getAnimatedValue();
                    float s1 = (float) Math.sin(t), s2 = (float) Math.sin(t * 2f);
                    face.setRotationY(2.4f * s1);
                    face.setRotationX(1.3f * s2);
                    art.setTranslationX(-d * 3f * s1);
                    art.setTranslationY(-d * 1.6f * s2);
                }
            });
            idle.start();
        }
    };

    CardView(Context c, AppInfo app, boolean fav, int wDp, int hDp, float density) {
        super(c);
        this.app = app;
        this.fav = fav;
        this.d = density;
        this.alwaysLabel = !fav; // dock shows the name only on focus, like tvOS
        faceW = dp(wDp);
        faceH = dp(hDp);
        glareW = (int) (faceW * 0.55f);

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(true);
        setClickable(true);
        setClipChildren(false);
        setClipToPadding(false);
        setBackground(noFocusHighlight());

        // ---- face: rounded plate, clipped by its outline on the GPU
        face = new FrameLayout(c);
        face.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        face.setClipToOutline(true);
        face.setCameraDistance(7000f * density);

        if (app.banner != null) {
            ImageView bg = new ImageView(c);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setImageBitmap(app.banner);
            // oversize the artwork so the parallax shift never reveals its edges
            bg.setScaleX(1.14f);
            bg.setScaleY(1.14f);
            face.addView(bg, match());
            art = bg;
        } else {
            GradientDrawable plate = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{tone(app.color, 1f, 0.66f), tone(app.color, 1.05f, 0.30f)});
            face.setBackground(plate);
            ImageView icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (app.icon != null) icon.setImageBitmap(app.icon);
            int is = (int) (faceH * 0.56f);
            face.addView(icon, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
            art = icon;
        }

        // static glass layer: brighter top edge, like light on a raised surface
        View glass = new View(c);
        glass.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        face.addView(glass, match());

        // focus-only sheen
        sheen = new View(c);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x30FFFFFF, 0x00FFFFFF, 0x12FFFFFF}));
        sheen.setAlpha(0f);
        face.addView(sheen, match());

        // one-shot specular glare band
        glare = new View(c);
        glare.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x50FFFFFF, 0x00FFFFFF}));
        glare.setAlpha(0f);
        glare.setRotation(18f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(glareW, faceH * 2);
        face.addView(glare, glp);
        glare.setTranslationY(-faceH * 0.5f);
        glare.setTranslationX(-glareW * 1.5f);

        addView(face, new LayoutParams(faceW, faceH));

        // ---- label
        label = new TextView(c);
        label.setText(app.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        LayoutParams llp = new LayoutParams(faceW, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(12);
        addView(label, llp);
        label.setAlpha(alwaysLabel ? 0.55f : 0f);
        label.setTranslationY(alwaysLabel ? 0 : dp(6));
    }

    void setMotion(boolean on) {
        motion = on;
    }

    /** dx/dy: -1, 0 or 1 — the direction focus arrived from. */
    void focus(float dx, float dy) {
        setTranslationZ(dp(2));
        face.animate().cancel();
        art.animate().cancel();
        if (motion) {
            face.setRotationY(dx * 12f);
            face.setRotationX(-dy * 10f);
            art.setTranslationX(-dx * dp(10));
            art.setTranslationY(-dy * dp(6));
        }
        face.animate().scaleX(1.12f).scaleY(1.12f).rotationX(0f).rotationY(0f)
                .translationZ(dp(18)).setDuration(motion ? 430 : 200)
                .setInterpolator(motion ? SPRING : DECEL).start();
        art.animate().translationX(0f).translationY(0f).setDuration(560).setInterpolator(DECEL).start();
        sheen.animate().alpha(1f).setDuration(220).start();
        label.animate().alpha(1f).translationY(0f).setStartDelay(50).setDuration(220).start();

        if (motion) {
            glare.animate().cancel();
            glare.setTranslationX(-glareW * 1.5f);
            glare.setAlpha(1f);
            glare.animate().translationX(faceW + glareW * 0.3f).setStartDelay(90).setDuration(720)
                    .setInterpolator(DECEL).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            glare.setAlpha(0f);
                        }
                    }).start();
            removeCallbacks(startIdle);
            postDelayed(startIdle, 520);
        }
    }

    void blur() {
        removeCallbacks(startIdle);
        if (idle != null) {
            idle.cancel();
            idle = null;
        }
        setTranslationZ(0f);
        face.animate().cancel();
        face.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationZ(0f)
                .setDuration(240).setInterpolator(DECEL).start();
        art.animate().translationX(0f).translationY(0f).setDuration(240).setInterpolator(DECEL).start();
        sheen.animate().alpha(0f).setDuration(200).start();
        glare.animate().cancel();
        glare.setAlpha(0f);
        label.animate().alpha(alwaysLabel ? 0.55f : 0f).translationY(alwaysLabel ? 0 : dp(6))
                .setStartDelay(0).setDuration(180).start();
    }

    /** Stop the idle drift while the launcher is not visible (saves CPU/GPU). */
    void pauseIdle() {
        removeCallbacks(startIdle);
        if (idle != null) {
            idle.cancel();
            idle = null;
        }
        face.setRotationX(0f);
        face.setRotationY(0f);
        art.setTranslationX(0f);
        art.setTranslationY(0f);
    }

    void resumeIdle() {
        if (motion && hasFocus()) {
            removeCallbacks(startIdle);
            postDelayed(startIdle, 300);
        }
    }

    /** Short press feedback before the app opens. */
    void press() {
        face.animate().scaleX(1.04f).scaleY(1.04f).setDuration(90).setInterpolator(DECEL)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        face.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).setInterpolator(SPRING).start();
                    }
                }).start();
    }

    // ---------------------------------------------------------------- helpers

    static int tone(int color, float satMul, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * satMul);
        hsv[2] = value;
        return Color.HSVToColor(hsv);
    }

    /** Stateful transparent background so Android 8+ doesn't draw its default grey focus box. */
    static StateListDrawable noFocusHighlight() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(Color.TRANSPARENT));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
