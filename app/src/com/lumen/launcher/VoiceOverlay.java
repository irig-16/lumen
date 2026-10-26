package com.lumen.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen voice layer: dim scrim + a floating glass bar with the orb and live transcript. */
final class VoiceOverlay extends FrameLayout {

    interface Listener {
        void onTyped(String text);

        void onDismissed();
    }

    private final float d;
    private final View scrim;
    private final LinearLayout bar;
    private final VoiceOrb orb;
    private final TextView title, hint;
    private final EditText input;
    private final LinearLayout qrBox;
    private final QrView qr;
    private Listener listener;
    private boolean shown;

    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    VoiceOverlay(Context c, float density) {
        super(c);
        d = density;
        setVisibility(GONE);
        setClickable(true);
        setTranslationZ(density * 48); // always above lifted cards

        scrim = new View(c);
        scrim.setBackgroundColor(0x990A0A0A);
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Theme.GLASS_TOP, Theme.GLASS_BOTTOM});
        bg.setCornerRadius(dp(32));
        bg.setStroke(dp(1), Theme.GLASS_STROKE);
        bar.setBackground(bg);
        bar.setElevation(dp(30));
        bar.setPadding(dp(18), dp(16), dp(30), dp(16));

        orb = new VoiceOrb(c);
        bar.addView(orb, new LinearLayout.LayoutParams(dp(92), dp(92)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        hint = new TextView(c);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTextColor(Theme.SUB);
        hint.setPadding(0, dp(6), 0, 0);
        hint.setMaxLines(3);

        input = new EditText(c);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x80FFFFFF);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        input.setHint("Type a command…");
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setCornerRadius(dp(14));
        ibg.setColor(Theme.CERULEAN_GLASS);
        ibg.setStroke(dp(1), Theme.CERULEAN);
        input.setBackground(ibg);
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        input.setVisibility(GONE);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                String s = input.getText().toString().trim();
                if (s.length() > 0 && listener != null) {
                    hideKeyboard();
                    input.setVisibility(GONE);
                    listener.onTyped(s);
                }
                return true;
            }
        });

        col.addView(title);
        col.addView(input, new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(hint);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(14);
        bar.addView(col, clp);

        // "scan to talk from your phone"
        qrBox = new LinearLayout(c);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER_HORIZONTAL);
        qr = new QrView(c);
        qrBox.addView(qr, new LinearLayout.LayoutParams(dp(132), dp(132)));
        TextView cap = new TextView(c);
        cap.setText("Scan with your phone");
        cap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        cap.setTextColor(Theme.ROSE);
        cap.setPadding(0, dp(6), 0, 0);
        qrBox.addView(cap);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.leftMargin = dp(18);
        qrBox.setVisibility(GONE);
        bar.addView(qrBox, qlp);

        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(40);
        addView(bar, blp);
    }

    /** Shows a QR code beside the text (null hides it). */
    void setQr(String text) {
        if (text == null) {
            qrBox.setVisibility(GONE);
        } else {
            qr.setText(text);
            qrBox.setVisibility(VISIBLE);
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isShowing() {
        return shown;
    }

    void listening(String hintText) {
        reveal();
        setQr(null);
        input.setVisibility(GONE);
        orb.setState(VoiceOrb.LISTENING);
        title.setText("Listening…");
        hint.setText(hintText);
    }

    void partial(String text) {
        if (text != null && text.length() > 0) title.setText(text);
    }

    void level(float rms) {
        orb.setLevel(rms);
    }

    void thinking(String heard) {
        reveal();
        setQr(null);
        orb.setState(VoiceOrb.THINKING);
        title.setText(quote(heard));
        hint.setText("");
    }

    /** line = the small text under the reply (usually what was heard), already formatted. */
    void result(String line, String reply, boolean ok) {
        reveal();
        setQr(null);
        input.setVisibility(GONE);
        orb.setState(ok ? VoiceOrb.DONE : VoiceOrb.ERROR);
        title.setText(reply);
        hint.setText(line == null ? "" : line);
        title.setAlpha(0f);
        title.setTranslationY(dp(8));
        title.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
        removeCallbacks(autoHide);
        postDelayed(autoHide, ok ? 2200 : 3800);
    }

    /** Message that stays until dismissed (e.g. no microphone). */
    void message(String head, String body) {
        reveal();
        input.setVisibility(GONE);
        orb.setState(VoiceOrb.IDLE);
        title.setText(head);
        hint.setText(body);
        removeCallbacks(autoHide);
    }

    void typing(String hintText) {
        reveal();
        orb.setState(VoiceOrb.IDLE);
        title.setText("What should I do?");
        hint.setText(hintText);
        input.setText("");
        input.setVisibility(VISIBLE);
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    void hide() {
        if (!shown) return;
        shown = false;
        removeCallbacks(autoHide);
        hideKeyboard();
        bar.animate().alpha(0f).translationY(dp(30)).setDuration(220).start();
        scrim.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
            @Override
            public void run() {
                setVisibility(GONE);
            }
        }).start();
        if (listener != null) listener.onDismissed();
    }

    private void reveal() {
        removeCallbacks(autoHide);
        if (shown) {
            orb.invalidate();
            return;
        }
        shown = true;
        setVisibility(VISIBLE);
        bringToFront();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(240).start();
        bar.setAlpha(0f);
        bar.setTranslationY(dp(40));
        bar.setScaleX(0.94f);
        bar.setScaleY(0.94f);
        bar.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();
        orb.invalidate();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private static String quote(String s) {
        return "“" + s + "”";
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
