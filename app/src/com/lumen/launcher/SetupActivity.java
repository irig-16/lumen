package com.lumen.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * "Setting up LumenOS": the first-run screen. It walks the user through the taps that make Lumen the
 * home screen and switch on its features, applies everything Lumen is allowed to apply itself, and
 * says plainly which one optional step needs a computer. Reachable later from Lumen settings.
 */
public class SetupActivity extends Activity {

    private LinearLayout list;
    private Button action;
    private TextView footer;
    private float d;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        d = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.JET);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(64), dp(44), dp(64), dp(40));
        scroll.addView(root);

        TextView title = label("Setting up LumenOS", 30, Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        root.addView(title);
        root.addView(space(6));
        root.addView(label("A few taps and Lumen takes over as your projector's home.", 15, Theme.SUB));
        root.addView(space(22));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        root.addView(space(18));
        footer = label("", 13, Theme.SUB);
        footer.setLineSpacing(0, 1.3f);
        root.addView(footer);

        root.addView(space(26));
        action = new Button(this);
        action.setAllCaps(false);
        action.setTextColor(Color.WHITE);
        action.setTextSize(17);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.CERULEAN);
        bg.setCornerRadius(26 * d);
        action.setBackground(bg);
        action.setPadding(dp(40), dp(12), dp(40), dp(12));
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAction();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(action, alp);

        TextView skip = label("Skip for now", 14, Theme.DIM);
        skip.setPadding(0, dp(18), 0, 0);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishSetup();
            }
        });
        root.addView(skip);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SelfSetup.applyHeld(this);     // apply anything we're now allowed to
        rebuild();
    }

    private void rebuild() {
        list.removeAllViews();
        List<SelfSetup.Item> items = SelfSetup.checklist(this);
        SelfSetup.Item next = null;
        int doneCount = 0;
        for (SelfSetup.Item it : items) {
            if (it.done) doneCount++;
            list.addView(row(it));
            if (next == null && !it.done && it.userGrantable) next = it;
        }
        int bloat = SelfSetup.bloatInstalled(this);

        if (next != null) {
            action.setText(actionLabel(next.id));
            action.setTag(next.id);
            action.setVisibility(View.VISIBLE);
        } else if (bloat > 0) {
            action.setText("Remove " + bloat + " unwanted app" + (bloat == 1 ? "" : "s"));
            action.setTag("bloat");
            action.setVisibility(View.VISIBLE);
        } else {
            action.setText("Done");
            action.setTag("done");
            action.setVisibility(View.VISIBLE);
        }

        boolean sec = SelfSetup.hasSecureSettings(this);
        StringBuilder f = new StringBuilder();
        if (!sec) {
            f.append("Optional, once, on a PC (about 5 seconds): run LumenOS-Unlock to let Lumen keep ")
                    .append("0.5× animations, system-wide ad blocking and exact temperatures applied by itself. ")
                    .append("Everything above works without it.");
        } else {
            f.append("Smooth animations and ad blocking are on, and Lumen keeps them that way after every update.");
            if (bloat > 0) f.append(" The unwanted apps above open one by one so you can remove them.");
        }
        footer.setText(f.toString());
    }

    private String actionLabel(String id) {
        if (id.equals("home")) return "Make Lumen the home screen";
        if (id.equals("updates")) return "Allow Lumen to update itself";
        if (id.equals("overlay")) return "Allow the overheating warning";
        if (id.equals("continue")) return "Turn on Continue Watching";
        if (id.equals("brightness")) return "Allow brightness control";
        if (id.equals("mic")) return "Turn on voice commands";
        return "Continue";
    }

    private void onAction() {
        String id = String.valueOf(action.getTag());
        if ("done".equals(id)) {
            finishSetup();
            return;
        }
        if ("bloat".equals(id)) {
            removeBloat();
            return;
        }
        if ("home".equals(id)) SelfSetup.askHome(this);
        else if ("updates".equals(id)) SelfSetup.askInstall(this);
        else if ("overlay".equals(id)) SelfSetup.askOverlay(this);
        else if ("continue".equals(id)) SelfSetup.askContinueWatching(this);
        else if ("brightness".equals(id)) SelfSetup.askWriteSettings(this);
        else if ("mic".equals(id)) SelfSetup.askMic(this);
    }

    private int bloatIndex;
    private List<String> bloatList;

    private void removeBloat() {
        bloatList = SelfSetup.bloatToRemove(this);
        bloatIndex = 0;
        nextBloat();
    }

    private void nextBloat() {
        if (bloatList == null || bloatIndex >= bloatList.size()) {
            bloatList = null;
            rebuild();
            return;
        }
        String pkg = bloatList.get(bloatIndex++);
        if (!SelfSetup.askUninstall(this, pkg)) nextBloat();   // not removable: skip to the next
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (bloatList != null) nextBloat();
    }

    private void finishSetup() {
        new AppStore(this).setBool("setup_done", true);
        SelfSetup.applyHeld(this);
        try {
            startActivity(new android.content.Intent(this, HomeActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException ignored) {
        }
        finish();
    }

    // ---------------------------------------------------------------- views

    private View row(SelfSetup.Item it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(9), 0, dp(9));

        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (it.done) g.setColor(0xFF34C759);
        else {
            g.setColor(0x00000000);
            g.setStroke((int) (2 * d), it.userGrantable ? Theme.CERULEAN_LIGHT : Theme.DIM);
        }
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dlp.rightMargin = dp(16);
        r.addView(dot, dlp);

        TextView t = label(it.label, 16, it.done ? Theme.TEXT : Theme.SUB);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!it.done && !it.userGrantable) {
            TextView tag = label("needs PC once", 12, Theme.DIM);
            r.addView(tag);
        } else if (it.done) {
            TextView tag = label("✓", 16, 0xFF34C759);
            r.addView(tag);
        }
        return r;
    }

    private TextView label(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setMinimumHeight(dp(h));
        return v;
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
