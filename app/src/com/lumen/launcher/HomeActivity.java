package com.lumen.launcher;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.SearchManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lumen 2 — a tvOS-inspired home screen for low-memory Android projectors.
 *
 * Layers (back to front): ambient aurora light → legibility shade → scrolling content
 * (top bar, top shelf hero, dock row, app grid). Only plain framework views are used.
 */
public class HomeActivity extends Activity {

    private static final String S_AMBIENT = "ambient";
    private static final String S_MOTION = "motion_full";
    private static final String S_24H = "clock_24h";
    private static final String S_STATS = "show_stats";
    private static final String S_SYSTEM = "show_system_apps";
    private static final String S_REMOTE = "phone_remote";
    private static final String S_SPEAK = "spoken_replies";
    private static final int REMOTE_PORT = 8090;
    private static final int REQ_MIC = 71;
    private static final String[] YT_PKGS = {"com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
            "com.google.android.youtube.tv", "com.google.android.youtube"};
    private static final String[] VOICE_HINTS = {
            "Try “open Kodi”, “play lofi music” or “volume 30”",
            "Try “search cat videos on YouTube” or “pause”",
            "Try “go home”, “free up memory” or “what time is it”",
            "Try “add VLC to the top row” or “calm mode”"};

    // voice
    private VoiceOverlay voice;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady, resumed, listening;
    private CommandParser parser = new CommandParser(new ArrayList<CommandParser.AppRef>());
    private RemoteServer remote;
    private String lastPartial = "";
    private RemoteServer secureRemote;
    private SoundEngine sound;
    private SoundPool uiSounds;
    private int sTick, sSelect;
    private long lastTick;
    private static final String S_UI_SOUNDS = "ui_sounds";
    private static final String S_INTRO = "startup_intro";
    // trending
    private static final long TREND_MAX_AGE = 3 * 3600 * 1000L;
    private static final long TMDB_MAX_AGE = 12 * 3600 * 1000L;
    private static final long GEO_MAX_AGE = 6 * 3600 * 1000L;
    private IntroView intro;
    private MediaPlayer introPlayer;
    private List<View> pendingEntrance;
    private static final int SECURE_PORT = 8443;
    private int hintIndex;

    private static final int C_TEXT = Theme.TEXT;
    private static final int C_SUB = Theme.SUB;
    private static final int C_DIM = Theme.DIM;

    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator(2f);

    private final Handler main = new Handler(Looper.getMainLooper());
    private AppStore store;
    private List<AppInfo> allApps = new ArrayList<AppInfo>();
    private boolean loading, reloadQueued, firstRender = true;

    private float density;
    private int wDp, hDp, usableDp, cardW, cardH, columns, heroH;
    private static final int GAP = 28;

    // views
    private FrameLayout root;
    private AuroraView aurora;
    private SmoothScroll scroll;
    private LinearLayout content;
    private FrameLayout hero;
    private LinearLayout heroText;
    private FrameLayout heroArt;
    private TextView heroEyebrow, heroTitle, heroSub, timeView, dateView, ramChip, storageChip, weatherView;
    private View weatherIcon;
    private ImageView heroBanner, heroIcon, heroGlyph;
    private FrameLayout heroPlate;
    private GradientDrawable heroPlateBg;
    private ObjectAnimator scrollAnim;
    private String heroKey = "";
    private HeroSpec pendingHero;

    private final int[] tmpA = new int[2], tmpB = new int[2];

    /** What the top shelf should show. */
    private static final class HeroSpec {
        String key, eyebrow, title, subtitle;
        Bitmap banner, icon;
        int glyph = -1;
        int color;
    }

    /** ScrollView that never scrolls by itself: focus moves freely, we animate the scroll. */
    private static final class SmoothScroll extends ScrollView {
        SmoothScroll(Context c) {
            super(c);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return false; // let the framework move focus; we follow it smoothly
                default:
                    return super.executeKeyEvent(e);
            }
        }
    }

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppStore(this);
        if (!store.has(S_24H)) store.setBool(S_24H, DateFormat.is24HourFormat(this));
        measure();

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.JET);
        setContentView(root);
        showSplash();
        if (shouldPlayIntro()) playIntro();

        root.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                onFocusMoved(oldFocus, newFocus);
            }
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        voice = new VoiceOverlay(this, density);
        voice.setListener(new VoiceOverlay.Listener() {
            @Override
            public void onTyped(String text) {
                handleUtterance(Collections.singletonList(text), false);
            }

            @Override
            public void onDismissed() {
                stopListening();
                View f = findFirstCard(content == null ? root : content);
                if (getCurrentFocus() == null && f != null) f.requestFocusFromTouch();
            }
        });
        initTts();
        sound = SoundEngine.get(this);   // re-applies the saved Sound Studio profile at boot
        ScreenEngine.start(this);        // Lumen Imaging: reconnects to the picture processor, re-applies your picture
        ContinueStore.onChange = new Runnable() {
            @Override
            public void run() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshContinue();
                    }
                });
            }
        };
        initUiSounds();
        dropAdoptedKeys();
        HeatGuard.get(this).start();     // CPU/GPU temperature watch (Lumen settings → Heat guard)
        initUpdates();
        java.io.File castDir = getExternalFilesDir(null);
        MediaInbox.I.init(new java.io.File(castDir != null ? castDir : getFilesDir(), "cast"));
        // find the projector's own picture menu later, so it never competes with the first paint
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        PictureSettings.find(getApplicationContext());
                    }
                }, "lumen-pq-scan").start();
            }
        }, 6000);
        if (store.getBool(S_REMOTE, true)) startRemote();
        loadTrendingCache();
        watchNetwork();

        reloadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Front.resumed(this);
        Updater.homeVisible = true;
        Intent confirm = Updater.pendingConfirm;     // an update sent while something else was on screen
        if (confirm != null && intro == null) {
            Updater.pendingConfirm = null;
            try {
                startActivity(confirm);
            } catch (RuntimeException ignored) {
            }
        }
        lastInputAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, 30000);
        ScreenEngine.onHome(this);       // Auto Picture: back to your everyday picture
        refreshContinue();
        refreshWeather(false);
        if (updater != null) {
            main.removeCallbacks(quietUpdateCheck);
            main.postDelayed(quietUpdateCheck, 20000); // once the network is up; then every 6 h
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    maybePromptUpdate();
                }
            }, intro != null ? 14000 : 1500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        Front.paused(this);
        Updater.homeVisible = false;
        main.removeCallbacks(idleCheck);
        main.removeCallbacks(quietUpdateCheck);
        stopListening();
        if (voice != null && voice.isShowing()) voice.hide();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
        updateStats();
        if (aurora != null && intro == null) aurora.setRunning(store.getBool(S_AMBIENT, true) && motionOn());
        View f = getCurrentFocus();
        if (f instanceof CardView) ((CardView) f).resumeIdle();
        refreshTrending(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(timeReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (aurora != null) aurora.setRunning(false);
        View f = getCurrentFocus();
        if (f instanceof CardView) ((CardView) f).pauseIdle();
        if (intro != null) finishIntro(); // never keep animating or playing music off-screen
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        if (remote != null) remote.shutdown();
        if (secureRemote != null) secureRemote.shutdown();
        if (uiSounds != null) uiSounds.release();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goHome();
    }

    @Override
    public void onBackPressed() {
        if (voice != null && voice.isShowing()) {
            voice.hide();
            return;
        }
        goHome();
    }

    /** Any button skips the intro. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        lastInputAt = SystemClock.elapsedRealtime();
        if (intro != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) intro.skip();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == KeyEvent.KEYCODE_ASSIST) {
            startVoice();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (voice != null && voice.isShowing()) return true;
            View f = getCurrentFocus();
            if (f instanceof CardView) showAppMenu((CardView) f);
            else showLauncherSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
            updateStats();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    // ================================================================== sizing

    private void measure() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        density = m.density;
        wDp = (int) (Math.max(m.widthPixels, m.heightPixels) / density);
        hDp = (int) (Math.min(m.widthPixels, m.heightPixels) / density);
        usableDp = wDp - 96;
        columns = Math.max(3, (usableDp + GAP) / (150 + GAP));
        cardW = (usableDp - (columns - 1) * GAP) / columns;
        cardH = cardW * 10 / 16;
        int dockRow = cardH + 12 + 18 + 32;
        heroH = Math.max(170, hDp - 22 - 50 - dockRow - 22);
    }

    // ================================================================== loading

    private void reloadApps() {
        if (loading) {
            reloadQueued = true;
            return;
        }
        loading = true;
        final int iconPx = dp(96);
        final int bw = dp(300), bh = dp(169);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppInfo> list = store.load(iconPx, bw, bh);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        allApps = list;
                        List<CommandParser.AppRef> refs = new ArrayList<CommandParser.AppRef>();
                        for (AppInfo a : list) refs.add(new CommandParser.AppRef(a.pkg, a.label));
                        parser = new CommandParser(refs);
                        store.ensureDefaults(list);
                        render(null, false);
                        refreshContinue();       // needs the app list (labels, banner fallbacks)
                        if (reloadQueued) {
                            reloadQueued = false;
                            reloadApps();
                        }
                    }
                });
            }
        }, "lumen-load").start();
    }

    private void showSplash() {
        TextView t = text("Lumen", 34, C_SUB);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setLetterSpacing(0.2f);
        t.setAlpha(0f);
        root.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        t.animate().alpha(1f).setDuration(500).start();
    }

    // =================================================================== render

    private boolean motionOn() {
        return store.getBool(S_MOTION, true);
    }

    private void render(String focusPkg, boolean focusFav) {
        boolean motion = motionOn();
        boolean ambient = store.getBool(S_AMBIENT, true);
        root.removeAllViews();
        heroKey = "";

        // ---- background layers
        aurora = new AuroraView(this);
        root.addView(aurora, match());
        aurora.setVisibility(ambient ? View.VISIBLE : View.GONE);
        aurora.setRunning(ambient && motion);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x590A0A0A, 0x000A0A0A, 0x330A0A0A, 0xB30A0A0A, 0xF00A0A0A}));
        root.addView(shade, match());

        // ---- scrolling content
        scroll = new SmoothScroll(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(48), dp(22), dp(48), dp(64));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, match());
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                // top shelf slides slower than the page and fades — depth on scroll
                if (hero == null) return;
                float f = Math.min(1f, y / (heroH * density * 0.75f));
                hero.setAlpha(1f - f);
                hero.setTranslationY(y * 0.4f);
            }
        });

        content.addView(buildTopBar());
        content.addView(buildHero());

        // ---- dock (favourites)
        Map<String, AppInfo> byPkg = new HashMap<String, AppInfo>();
        for (AppInfo a : allApps) byPkg.put(a.pkg, a);
        List<AppInfo> favs = new ArrayList<AppInfo>();
        for (String p : store.favourites()) {
            AppInfo a = byPkg.get(p);
            if (a != null) favs.add(a);
        }

        List<View> entrance = new ArrayList<View>();
        View focusTarget = null, firstFav = null, firstApp = null;

        if (favs.isEmpty()) {
            TextView hint = text("Press MENU on any app (or hold OK) to add it to the top row.", 15, C_DIM);
            hint.setPadding(0, dp(18), 0, dp(26));
            content.addView(hint);
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setSmoothScrollingEnabled(true);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setPadding(0, dp(16), dp(48), dp(16));
            for (int i = 0; i < favs.size(); i++) {
                AppInfo a = favs.get(i);
                CardView c = card(a, true, motion);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.leftMargin = dp(GAP);
                row.addView(c, lp);
                entrance.add(c);
                if (firstFav == null) firstFav = c;
                if (focusFav && a.pkg.equals(focusPkg)) focusTarget = c;
            }
            hs.addView(row);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.rightMargin = -dp(48); // let the row bleed past the edge to hint "more"
            content.addView(hs, hlp);
        }

        // ---- continue watching, then trending (both filled in when their data arrives)
        content.addView(buildContinue(motion));
        for (Shelf sh : shelves) content.addView(buildShelf(sh, motion));

        // ---- all apps grid
        Set<String> hidden = store.hidden();
        boolean showSystem = store.getBool(S_SYSTEM, true);
        List<AppInfo> visible = new ArrayList<AppInfo>();
        for (AppInfo a : allApps) {
            if (hidden.contains(a.pkg)) continue;
            if (!showSystem && a.system) continue;
            visible.add(a);
        }
        TextView gridTitle = text("All Apps", 20, C_TEXT);
        gridTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        gridTitle.setPadding(0, dp(30), 0, dp(4));
        content.addView(gridTitle);

        LinearLayout row = null;
        for (int i = 0; i < visible.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(0, dp(16), 0, dp(10));
                content.addView(row);
            }
            AppInfo a = visible.get(i);
            CardView c = card(a, false, motion);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % columns != 0) lp.leftMargin = dp(GAP);
            row.addView(c, lp);
            if (i < columns * 2) entrance.add(c);
            if (firstApp == null) firstApp = c;
            if (!focusFav && a.pkg.equals(focusPkg)) focusTarget = c;
        }

        TextView footer = text("MENU on an app  ·  add to top row, move, hide, uninstall        MENU elsewhere  ·  Lumen settings",
                12, C_DIM);
        footer.setPadding(0, dp(30), 0, 0);
        content.addView(footer);

        updateClock();
        updateStats();

        if (firstRender && motion) {
            if (intro != null) pendingEntrance = entrance; // play it as the intro hands over
            else playEntrance(entrance);
        }
        firstRender = false;

        // the voice layer survives rebuilds and always sits on top
        if (voice.getParent() != null) ((ViewGroup) voice.getParent()).removeView(voice);
        root.addView(voice, match());
        if (intro != null) {
            if (intro.getParent() != null) ((ViewGroup) intro.getParent()).removeView(intro);
            root.addView(intro, match());
        }

        final View focus = focusTarget != null ? focusTarget : (firstFav != null ? firstFav : firstApp);
        main.post(new Runnable() {
            @Override
            public void run() {
                // requestFocusFromTouch: this projector boots in "touch mode", where a plain
                // requestFocus() is ignored and the top shelf stayed blank
                if (focus != null) focus.requestFocusFromTouch();
                if (heroKey.length() == 0 && focus instanceof CardView) showHeroFor((CardView) focus);
            }
        });
    }

    private CardView card(final AppInfo a, boolean fav, boolean motion) {
        final CardView c = new CardView(this, a, fav, cardW, cardH, density);
        c.setMotion(motion);
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                c.press();
                playUi(true);
                ContentItem item = contentItems.get(a.pkg);
                if (item != null) openContent(item, c.face);
                else launch(a, c.face);
            }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showAppMenu(c);
                return true;
            }
        });
        return c;
    }

    private void playEntrance(List<View> views) {
        heroText.setAlpha(0f);
        heroText.setTranslationY(dp(18));
        heroText.animate().alpha(1f).translationY(0f).setStartDelay(80).setDuration(600).setInterpolator(DECEL).start();
        heroArt.setAlpha(0f);
        heroArt.setScaleX(0.94f);
        heroArt.setScaleY(0.94f);
        heroArt.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(160).setDuration(650).setInterpolator(DECEL).start();
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(36));
            v.animate().alpha(1f).translationY(0f).setStartDelay(180 + i * 45L)
                    .setDuration(520).setInterpolator(DECEL).start();
        }
    }

    // ------------------------------------------------------------- top bar

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setMinimumHeight(dp(50));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        dateView = text("", 15, C_SUB);
        left.addView(dateView);
        weatherIcon = new View(this);
        weatherIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams wilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        wilp.leftMargin = dp(18);
        wilp.rightMargin = dp(7);
        left.addView(weatherIcon, wilp);
        weatherView = text("", 15, C_SUB);
        weatherView.setSingleLine(true);
        weatherView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(weatherView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        showWeather();

        addQuick(bar, Glyph.MIC, "Voice", "Press OK and speak a command", "voice");
        addQuick(bar, Glyph.SPEAKER, "Sound Studio", "Cinema, dialogue, music and night sound", "sound");
        addQuick(bar, Glyph.CAST, "Cast", "Mirror a phone, share a laptop, show photos and videos", "cast");
        addQuick(bar, Glyph.PICTURE, "Screen", "Standard, Cinema, Vivid and Bright room picture presets", "screen");
        addQuick(bar, Glyph.WIFI, "Wi-Fi", "Networks and internet", Settings.ACTION_WIFI_SETTINGS);
        addQuick(bar, Glyph.BLUETOOTH, "Bluetooth", "Speakers, remotes and controllers", Settings.ACTION_BLUETOOTH_SETTINGS);
        addQuick(bar, Glyph.BOOST, "Free RAM", "Stop apps running in the background", null);
        addQuick(bar, Glyph.SETTINGS, "Settings", "All system settings", Settings.ACTION_SETTINGS);
        addQuick(bar, Glyph.TUNE, "Lumen", "Look, motion and hidden apps", "lumen");

        timeView = text("", 26, C_TEXT);
        timeView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(22);
        bar.addView(timeView, tlp);
        return bar;
    }

    private void addQuick(LinearLayout bar, int glyph, String title, String sub, final String action) {
        QuickButton q = new QuickButton(this, glyph, title, sub, density);
        q.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action == null) boost();
                else if ("voice".equals(action)) startVoice();
                else if ("sound".equals(action)) openSoundStudio();
                else if ("picture".equals(action)) openPictureLab(0);
                else if ("screen".equals(action)) openScreen();
                else if ("cast".equals(action)) openCast(null);
                else if ("lumen".equals(action)) showLauncherSettings();
                else openSettings(action);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.leftMargin = dp(11);
        bar.addView(q, lp);
    }

    // ---------------------------------------------------------------- hero

    private View buildHero() {
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        hero.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heroH)));

        heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setGravity(Gravity.BOTTOM | Gravity.START);
        heroEyebrow = text("", 12, Theme.ROSE);
        heroEyebrow.setLetterSpacing(0.16f);
        heroEyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroTitle = text("", 44, C_TEXT);
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setShadowLayer(dp(12), 0, dp(2), 0x66000000);
        heroSub = text("", 16, C_SUB);
        heroSub.setPadding(0, dp(4), 0, 0);
        heroText.addView(heroEyebrow);
        heroText.addView(heroTitle);
        heroText.addView(heroSub);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(14), 0, 0);
        ramChip = chip();
        storageChip = chip();
        chips.addView(ramChip);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(8);
        chips.addView(storageChip, sp);
        chips.setVisibility(store.getBool(S_STATS, true) ? View.VISIBLE : View.GONE);
        heroText.addView(chips);

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                dp(usableDp * 0.56f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        tlp.bottomMargin = dp(10);
        hero.addView(heroText, tlp);

        // artwork on the right
        heroArt = new FrameLayout(this);
        heroArt.setClipChildren(false);
        int bw = Math.min(300, (int) (usableDp * 0.38f));
        int bh = Math.min(heroH - 30, bw * 9 / 16);
        bw = bh * 16 / 9;

        heroBanner = new ImageView(this);
        heroBanner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded(heroBanner, 18);
        heroBanner.setElevation(dp(24));
        heroArt.addView(heroBanner, new FrameLayout.LayoutParams(dp(bw), dp(bh), Gravity.BOTTOM | Gravity.END));

        int ps = Math.min(150, bh);
        heroPlate = new FrameLayout(this);
        heroPlateBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Theme.CERULEAN, Theme.JET});
        heroPlateBg.setCornerRadius(dp(34));
        heroPlate.setBackground(heroPlateBg);
        rounded(heroPlate, 34);
        heroPlate.setElevation(dp(24));
        heroIcon = new ImageView(this);
        heroIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heroPlate.addView(heroIcon, new FrameLayout.LayoutParams(dp(ps * 0.62f), dp(ps * 0.62f), Gravity.CENTER));
        heroGlyph = new ImageView(this);
        heroPlate.addView(heroGlyph, new FrameLayout.LayoutParams(dp(ps * 0.42f), dp(ps * 0.42f), Gravity.CENTER));
        View gloss = new View(this);
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        heroPlate.addView(gloss, match());
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(ps), dp(ps), Gravity.BOTTOM | Gravity.END);
        plp.rightMargin = dp((bw - ps) / 2f);
        heroArt.addView(heroPlate, plp);

        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        alp.bottomMargin = dp(14);
        hero.addView(heroArt, alp);
        return hero;
    }

    private final Runnable applyHero = new Runnable() {
        @Override
        public void run() {
            HeroSpec h = pendingHero;
            if (h == null || hero == null || h.key.equals(heroKey)) return;
            boolean first = heroKey.length() == 0;
            heroKey = h.key;

            heroEyebrow.setText(h.eyebrow);
            heroTitle.setText(h.title);
            heroSub.setText(h.subtitle);
            if (h.banner != null) {
                heroBanner.setImageBitmap(h.banner);
                heroBanner.setVisibility(View.VISIBLE);
                heroPlate.setVisibility(View.GONE);
            } else {
                heroBanner.setVisibility(View.GONE);
                heroPlate.setVisibility(View.VISIBLE);
                if (h.glyph >= 0) {
                    heroIcon.setVisibility(View.GONE);
                    heroGlyph.setVisibility(View.VISIBLE);
                    heroGlyph.setImageDrawable(new Glyph(h.glyph, C_TEXT));
                    heroPlateBg.setColors(new int[]{Theme.CERULEAN, 0xFF06283A});
                } else {
                    heroGlyph.setVisibility(View.GONE);
                    heroIcon.setVisibility(View.VISIBLE);
                    heroIcon.setImageBitmap(h.icon);
                    heroPlateBg.setColors(new int[]{CardView.tone(h.color, 1f, 0.66f), CardView.tone(h.color, 1.05f, 0.28f)});
                }
            }
            if (aurora != null) aurora.setAccent(CardView.tone(h.color, 1.1f, 0.62f), h.banner);

            if (!first && motionOn()) {
                heroText.animate().cancel();
                heroText.setAlpha(0.15f);
                heroText.setTranslationY(dp(10));
                heroText.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(260).setInterpolator(DECEL).start();
                heroArt.animate().cancel();
                heroArt.setAlpha(0.2f);
                heroArt.setScaleX(0.96f);
                heroArt.setScaleY(0.96f);
                heroArt.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(340).setInterpolator(DECEL).start();
            }
        }
    };

    private void showHeroFor(CardView c) {
        HeroSpec h = new HeroSpec();
        h.key = "app:" + c.app.pkg;
        ContentItem item = contentItems.get(c.app.pkg);
        if (item != null) {
            h.eyebrow = item.eyebrow;
            h.subtitle = item.meta;
        } else {
            h.eyebrow = c.fav ? "TOP ROW" : (c.app.system ? "SYSTEM APP" : "APP");
            h.subtitle = "Press OK to open  ·  MENU for options";
        }
        h.title = c.app.label;
        h.banner = c.app.banner;
        h.icon = c.app.icon;
        h.color = c.app.color;
        showHero(h);
    }

    private void showHero(HeroSpec h) {
        pendingHero = h;
        main.removeCallbacks(applyHero);
        main.postDelayed(applyHero, 45); // skip intermediate cards while the user flicks fast
    }

    // ================================================================== focus

    private void onFocusMoved(View oldFocus, View newFocus) {
        float dx = 0, dy = 0;
        if (oldFocus != null && newFocus != null && oldFocus.isAttachedToWindow()) {
            oldFocus.getLocationOnScreen(tmpA);
            newFocus.getLocationOnScreen(tmpB);
            float ax = tmpA[0] + oldFocus.getWidth() / 2f, ay = tmpA[1] + oldFocus.getHeight() / 2f;
            float bx = tmpB[0] + newFocus.getWidth() / 2f, by = tmpB[1] + newFocus.getHeight() / 2f;
            float mx = bx - ax, my = by - ay;
            if (Math.abs(mx) > Math.abs(my)) dx = Math.signum(mx);
            else dy = Math.signum(my);
        }
        if (oldFocus instanceof CardView) ((CardView) oldFocus).blur();
        else if (oldFocus instanceof QuickButton) ((QuickButton) oldFocus).blur();

        if (oldFocus != null && (newFocus instanceof CardView || newFocus instanceof QuickButton)) playUi(false);
        if (newFocus instanceof CardView) {
            CardView c = (CardView) newFocus;
            c.focus(dx, dy);
            showHeroFor(c);
            followFocus(c);
        } else if (newFocus instanceof QuickButton) {
            QuickButton q = (QuickButton) newFocus;
            q.focus();
            HeroSpec h = new HeroSpec();
            h.key = "quick:" + q.title;
            h.eyebrow = "QUICK ACTION";
            h.title = q.title;
            h.subtitle = q.subtitle;
            h.glyph = q.glyphType;
            h.color = Theme.CERULEAN;
            showHero(h);
            animateScrollTo(0);
        }
    }

    /** Keeps the focused grid row at a comfortable height; the top row pins the page to the top. */
    private void followFocus(final CardView c) {
        if (c.fav) {
            animateScrollTo(0);
            return;
        }
        if (!c.isLaidOut() || scroll.getHeight() == 0) {
            // focus was set right after a rebuild: wait for layout, then scroll
            content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    content.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (c.isAttachedToWindow() && c.hasFocus()) followFocus(c);
                    return true;
                }
            });
            return;
        }
        int y = 0;
        for (View v = c; v != null && v != content; v = (View) v.getParent()) y += v.getTop();
        int target = y + c.getHeight() / 2 - (int) (scroll.getHeight() * 0.42f);
        animateScrollTo(target);
    }

    private void animateScrollTo(int target) {
        if (scroll == null || content == null) return;
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        target = Math.max(0, Math.min(max, target));
        if (scrollAnim != null) scrollAnim.cancel();
        if (Math.abs(target - scroll.getScrollY()) < 2) return;
        scrollAnim = ObjectAnimator.ofInt(scroll, "scrollY", scroll.getScrollY(), target);
        scrollAnim.setDuration(motionOn() ? 460 : 220);
        scrollAnim.setInterpolator(DECEL);
        scrollAnim.start();
    }

    private void goHome() {
        if (content == null) return;
        animateScrollTo(0);
        View first = findFirstCard(content);
        if (first != null) first.requestFocusFromTouch();
    }

    private View findFirstCard(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof CardView) return v;
            if (v instanceof ViewGroup) {
                View r = findFirstCard((ViewGroup) v);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ================================================================= updaters

    private void updateClock() {
        if (timeView == null) return;
        Date now = new Date();
        boolean h24 = store.getBool(S_24H, true);
        timeView.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        if (resumed) refreshWeather(false);   // runs at most every 30 minutes
    }

    // ---------------------------------------------------------------- weather

    private static final long WEATHER_EVERY = 30 * 60 * 1000L, PLACE_EVERY = 12 * 3600 * 1000L;
    private boolean weatherBusy;

    private boolean weatherOn() {
        return store.getBool("weather", true);
    }

    /** Draws the last known weather next to the date. */
    private void showWeather() {
        if (weatherView == null) return;
        long at = store.getLong("wx_at", 0);
        boolean fresh = at > 0 && System.currentTimeMillis() - at < 3 * 3600 * 1000L;
        if (!weatherOn() || !fresh) {
            weatherView.setText("");
            weatherIcon.setVisibility(View.GONE);
            return;
        }
        int code = (int) store.getLong("wx_code", 0);
        boolean day = store.getBool("wx_day", true);
        weatherIcon.setBackground(new Glyph(WeatherFeed.glyphFor(code, day), C_SUB));
        weatherIcon.setVisibility(View.VISIBLE);
        String city = store.getString("wx_city", "");
        weatherView.setText(store.getLong("wx_temp", 0) + "°  " + WeatherFeed.describe(code)
                + "   H " + store.getLong("wx_high", 0) + "°  L " + store.getLong("wx_low", 0) + "°"
                + (city.length() > 0 ? "   ·   " + city : ""));
    }

    /** Fetches the weather when it's older than 30 minutes (location: every 12 hours). */
    private void refreshWeather(boolean force) {
        if (!weatherOn() || weatherBusy) return;
        if (!force && System.currentTimeMillis() - store.getLong("wx_at", 0) < WEATHER_EVERY) return;
        if (HeatGuard.get(this).cooling()) return;          // no extra work while it cools down
        weatherBusy = true;
        final boolean newPlace = force || System.currentTimeMillis() - store.getLong("wx_place_at", 0) > PLACE_EVERY
                || store.getString("wx_lat", "").length() == 0;
        final String lat = store.getString("wx_lat", ""), lon = store.getString("wx_lon", ""), city = store.getString("wx_city", "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WeatherFeed.Place p;
                    if (newPlace) {
                        p = WeatherFeed.locate();
                    } else {
                        p = new WeatherFeed.Place();
                        p.lat = Double.parseDouble(lat);
                        p.lon = Double.parseDouble(lon);
                        p.city = city;
                    }
                    final WeatherFeed.Place place = p;
                    final WeatherFeed.Now n = WeatherFeed.forecast(p);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            weatherBusy = false;
                            if (newPlace) {
                                store.setString("wx_lat", String.valueOf(place.lat));
                                store.setString("wx_lon", String.valueOf(place.lon));
                                store.setString("wx_city", place.city);
                                store.setLong("wx_place_at", System.currentTimeMillis());
                            }
                            store.setLong("wx_temp", n.temp);
                            store.setLong("wx_high", n.high);
                            store.setLong("wx_low", n.low);
                            store.setLong("wx_code", n.code);
                            store.setBool("wx_day", n.day);
                            store.setLong("wx_at", System.currentTimeMillis());
                            showWeather();
                        }
                    });
                } catch (Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            weatherBusy = false;
                            store.setLong("wx_at", Math.max(store.getLong("wx_at", 0),
                                    System.currentTimeMillis() - WEATHER_EVERY + 10 * 60 * 1000L)); // retry in 10 min
                            showWeather();
                        }
                    });
                }
            }
        }, "lumen-weather").start();
    }

    private void updateStats() {
        if (ramChip == null) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramChip.setText("RAM  " + (mi.availMem >> 20) + " MB free");
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            storageChip.setText(String.format(Locale.getDefault(), "Storage  %.1f GB free",
                    fs.getAvailableBytes() / (1024.0 * 1024 * 1024)));
        } catch (RuntimeException e) {
            ramChip.setText("RAM  —");
        }
    }

    // ================================================================== actions

    private void launch(AppInfo app, View from) {
        ScreenEngine.onApp(this, app.pkg);
        Bundle opts = null;
        if (from != null && motionOn()) {
            opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        }
        try {
            startActivity(app.launch, opts);
            return;
        } catch (ActivityNotFoundException ignored) {
        } catch (SecurityException ignored) {
        }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(app.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(app.pkg);
        if (i != null) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        toast("Couldn't open " + app.label);
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e2) {
                toast("Settings not available on this device");
            }
        }
    }

    /** Stops background processes of user apps and reports how much RAM came back. */
    private void boost() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final long before = availMb(am);
        String self = getPackageName();
        int n = 0;
        try {
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(self)) continue;
                am.killBackgroundProcesses(ai.packageName);
                n++;
            }
        } catch (RuntimeException ignored) {
        }
        final int count = n;
        toast("Cleaning…");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                long after = availMb(am);
                long freed = after - before;
                if (freed > 4) toast("Freed " + freed + " MB  ·  " + after + " MB free now");
                else toast("Already clean  ·  " + after + " MB free (" + count + " apps checked)");
                updateStats();
            }
        }, 1200);
    }

    private static long availMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem >> 20;
    }

    private void showAppMenu(final CardView card) {
        if (contentItems.containsKey(card.app.pkg)) {
            showContentMenu(contentItems.get(card.app.pkg), card);
            return;
        }
        final AppInfo app = card.app;
        final boolean isFav = store.isFavourite(app.pkg);
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();

        labels.add("Open");
        actions.add(new Runnable() {
            public void run() {
                launch(app, card.face);
            }
        });
        labels.add(isFav ? "Remove from top row" : "Add to top row");
        actions.add(new Runnable() {
            public void run() {
                store.toggleFavourite(app.pkg);
                render(app.pkg, !isFav);
            }
        });
        if (isFav) {
            labels.add("Move left");
            actions.add(new Runnable() {
                public void run() {
                    store.moveFavourite(app.pkg, -1);
                    render(app.pkg, true);
                }
            });
            labels.add("Move right");
            actions.add(new Runnable() {
                public void run() {
                    store.moveFavourite(app.pkg, 1);
                    render(app.pkg, true);
                }
            });
        }
        if (!card.fav) {
            labels.add("Hide from All Apps");
            actions.add(new Runnable() {
                public void run() {
                    store.setHidden(app.pkg, true);
                    toast(app.label + " hidden. Unhide it in Lumen settings.");
                    render(null, false);
                }
            });
        }
        String pc = ScreenEngine.appChoice(this, app.pkg);
        labels.add("Picture for this app:  " + ("auto".equals(pc) ? "Auto" : ScreenEngine.name(pc)));
        actions.add(new Runnable() {
            public void run() {
                choosePictureFor(app);
            }
        });
        labels.add("App info");
        actions.add(new Runnable() {
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", app.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    toast("App info not available");
                }
            }
        });
        if (!app.system) {
            labels.add("Uninstall");
            actions.add(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        toast("Uninstall not available");
                    }
                }
            });
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(app.label)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    private void showLauncherSettings() {
        final boolean ambient = store.getBool(S_AMBIENT, true);
        final boolean motion = motionOn();
        final boolean h24 = store.getBool(S_24H, true);
        final boolean stats = store.getBool(S_STATS, true);
        final boolean sys = store.getBool(S_SYSTEM, true);
        final boolean remoteOn = store.getBool(S_REMOTE, true);
        final boolean speak = store.getBool(S_SPEAK, true);
        final Set<String> hidden = store.hidden();
        String currentHome = currentHomeLabel();

        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();

        labels.add("Voice:  speak a command");
        actions.add(new Runnable() { public void run() { startVoice(); } });
        labels.add("Phone remote:  " + (remoteOn ? remoteAddress() + "   ·   PIN " + remotePin() : "Off"));
        actions.add(new Runnable() { public void run() { showRemoteInfo(); } });
        if (remoteOn && !Settings.canDrawOverlays(this)) {
            labels.add("Let the phone open apps while you watch  (needs permission)");
            actions.add(new Runnable() { public void run() { askOverlayPermission(); } });
        }
        labels.add("Trending (YouTube, Netflix, Prime):  " + trendStatus());
        actions.add(new Runnable() { public void run() { showTrendingInfo(); } });
        final boolean allTrend = trendingOn();
        labels.add("Show trending rows:  " + (allTrend ? "On" : "Off (all trending hidden)"));
        actions.add(new Runnable() { public void run() {
            store.setBool("trending_all", !allTrend);
            if (!allTrend) refreshTrending(false);
            render(null, false);
            showLauncherSettings();
        } });
        labels.add("Sound Studio:  " + SoundEngine.presetName(sound.preset));
        actions.add(new Runnable() { public void run() { openSoundStudio(); } });
        final int idleMins = windowIdleMinutes();
        labels.add("Lumen Window:  living scenes (" + WindowView.library(this).clips().size() + " ready)");
        actions.add(new Runnable() { public void run() { openWindow(null); } });
        labels.add("Window after idle:  " + (idleMins == 0 ? "Off" : idleMins + " min"));
        actions.add(new Runnable() { public void run() {
            int[] steps = {0, 5, 10, 20, 30};
            int next = 0;
            for (int k = 0; k < steps.length; k++) if (steps[k] == idleMins) next = steps[(k + 1) % steps.length];
            WindowView.prefs(HomeActivity.this).edit().putInt("idle", next).apply();
            showLauncherSettings();
        } });
        labels.add("Cast:  mirror a phone, share a laptop, show photos and videos");
        actions.add(new Runnable() { public void run() { openCast(null); } });
        labels.add("Screen:  " + ScreenEngine.name(ScreenEngine.current(this)) + " picture"
                + (ScreenEngine.engine() ? "  ·  Detail " + ScreenEngine.DETAIL_NAMES[ScreenEngine.detail(this)]
                + (ScreenEngine.auto(this) ? "  ·  Auto Picture" : "") : ""));
        actions.add(new Runnable() { public void run() { openScreen(); } });
        final boolean cwOn = ContinueStore.enabled(this);
        labels.add("Continue Watching row:  " + onOff(cwOn) + (cwOn && !ContinueStore.canWatch(this)
                ? "  (needs one-time access: run LumenOS-Update)" : ""));
        actions.add(new Runnable() { public void run() {
            ContinueStore.setEnabled(HomeActivity.this, !cwOn);
            refreshContinue();
            showLauncherSettings();
        } });
        final HeatGuard heat = HeatGuard.get(this);
        labels.add("Heat guard:  " + heat.summary());
        actions.add(new Runnable() { public void run() { showHeatGuard(); } });
        final boolean autoFree = MemoryTuner.enabled(this);
        labels.add("Free memory for films:  " + MemoryTuner.summary(this));
        actions.add(new Runnable() { public void run() {
            MemoryTuner.setEnabled(HomeActivity.this, !autoFree);
            toast(autoFree ? "Lumen won't clear background apps when a film starts"
                    : "When a film starts and memory is low, Lumen clears leftover background apps");
            showLauncherSettings();
        } });
        labels.add("Picture Lab:  focus, keystone and brightness calibration");
        actions.add(new Runnable() { public void run() { openPictureLab(0); } });
        final boolean ui = store.getBool(S_UI_SOUNDS, true);
        final boolean introOn = store.getBool(S_INTRO, true);
        labels.add("Startup intro (every time the projector starts):  " + onOff(introOn));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_INTRO, !introOn);
            showLauncherSettings();
        } });
        labels.add("Play the intro now");
        actions.add(new Runnable() { public void run() { playIntro(); } });
        labels.add("UI sounds:  " + onOff(ui));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_UI_SOUNDS, !ui);
            showLauncherSettings();
        } });
        labels.add("Spoken replies:  " + onOff(speak) + (ttsReady ? "" : "  (no voice engine found)"));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_SPEAK, !speak);
            showLauncherSettings();
        } });
        labels.add("Background:  " + (ambient ? "Ambient light (follows the app)" : "Pure black (max contrast)"));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_AMBIENT, !ambient);
            render(null, false);
            showLauncherSettings();
        } });
        labels.add("Motion:  " + (motion ? "Full (parallax, glare, drifting light)" : "Calm (fastest)"));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_MOTION, !motion);
            render(null, false);
            showLauncherSettings();
        } });
        final boolean wx = weatherOn();
        labels.add("Weather next to the date:  " + onOff(wx));
        actions.add(new Runnable() { public void run() {
            store.setBool("weather", !wx);
            if (!wx) refreshWeather(true);
            showWeather();
            showLauncherSettings();
        } });
        labels.add("24-hour clock:  " + onOff(h24));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_24H, !h24);
            updateClock();
            showLauncherSettings();
        } });
        labels.add("Show RAM & storage:  " + onOff(stats));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_STATS, !stats);
            render(null, false);
            showLauncherSettings();
        } });
        labels.add("Show system apps:  " + onOff(sys));
        actions.add(new Runnable() { public void run() {
            store.setBool(S_SYSTEM, !sys);
            render(null, false);
            showLauncherSettings();
        } });
        labels.add("Unhide apps  (" + hidden.size() + " hidden)");
        actions.add(new Runnable() { public void run() { showUnhide(); } });
        labels.add(currentHome == null ? "Lumen is your default home ✓"
                : "Make Lumen the default home  (now: " + currentHome + ")");
        actions.add(new Runnable() { public void run() { openSettings(Settings.ACTION_HOME_SETTINGS); } });
        labels.add("Reload app list");
        actions.add(new Runnable() { public void run() {
            reloadApps();
            toast("Reloading apps…");
        } });
        UpdateFeed.Release upd = updater.available();
        labels.add(upd != null ? "Update available:  Lumen " + upd.versionName + "  →  Install"
                : "Updates:  Lumen " + updater.currentName() + "  ·  " + updater.status());
        actions.add(new Runnable() { public void run() { showUpdates(); } });
        labels.add("About Lumen  ·  developed with \u2665 by Radirig");
        actions.add(new Runnable() { public void run() { showAbout(); } });

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Lumen settings")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    private void showHeatGuard() {
        final HeatGuard heat = HeatGuard.get(this);
        final boolean on = heat.enabled();
        final int earlier = heat.earlier();
        String[] items = {
                "Heat guard:  " + onOff(on),
                "Step in:  " + (earlier == 0 ? "Standard (CPU 100 / 105 / 110°C)" : "Earlier (CPU 95 / 100 / 105°C)"),
                "OK"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Heat guard  ·  " + heat.reading())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            heat.setEnabled(!on);
                            showHeatGuard();
                        } else if (w == 1) {
                            heat.setEarlier(earlier == 0 ? 5 : 0);
                            showHeatGuard();
                        }
                    }
                })
                .setNeutralButton("Details", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new AlertDialog.Builder(HomeActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("Heat guard")
                                .setMessage(heat.details())
                                .setPositiveButton("OK", null).show();
                    }
                })
                .show();
    }

    private void showUnhide() {
        Set<String> hidden = store.hidden();
        final List<AppInfo> list = new ArrayList<AppInfo>();
        for (AppInfo a : allApps) if (hidden.contains(a.pkg)) list.add(a);
        if (list.isEmpty()) {
            for (String p : hidden) store.setHidden(p, false);
            toast("No hidden apps");
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).label;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Select an app to unhide")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        AppInfo a = list.get(which);
                        store.setHidden(a.pkg, false);
                        render(a.pkg, false);
                        toast(a.label + " is back in All Apps");
                    }
                })
                .show();
    }

    private void showAbout() {
        android.text.SpannableStringBuilder m = new android.text.SpannableStringBuilder(
                "A tvOS-inspired home screen for low-memory projectors.\n\n"
                        + "• Startup intro, parallax cards and ambient light\n"
                        + "• Trending on YouTube in the top shelf\n"
                        + "• Voice: the mic button, or talk from your phone\n"
                        + "• Sound Studio: speaker-aware Lumen, Cinema, Dialogue, Music and Night sound\n"
                        + "• Picture Lab: focus, keystone and level calibration\n"
                        + "• No ads, no tracking; the phone remote stays on your home Wi-Fi\n\n"
                        + "Running slow? Set Motion to Calm and Background to Pure black.\n\n");
        appendCredit(m);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Lumen 6.8")
                .setMessage(m)
                .setPositiveButton("OK", null)
                .show();
    }

    /** "Developed with ♥ by Radirig" with a pink heart. (♥ U+2665 renders on every Android version.) */
    private static void appendCredit(android.text.SpannableStringBuilder b) {
        b.append("Developed with ");
        int s = b.length();
        b.append("\u2665");
        b.setSpan(new android.text.style.ForegroundColorSpan(Theme.ROSE), s, b.length(), 0);
        b.setSpan(new android.text.style.RelativeSizeSpan(1.3f), s, b.length(), 0);
        b.append(" by Radirig");
    }

    private String currentHomeLabel() {
        try {
            PackageManager pm = getPackageManager();
            ResolveInfo ri = pm.resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return "none";
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) return null;
            if ("android".equals(pkg)) return "ask every time";
            CharSequence l = ri.loadLabel(pm);
            return l == null ? pkg : l.toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ==================================================================== voice

    private void initTts() {
        try {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    if (ttsReady) {
                        try {
                            tts.setLanguage(Locale.getDefault());
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            tts = null;
        }
    }

    private void say(String text) {
        if (tts == null || !ttsReady || !store.getBool(S_SPEAK, true)) return;
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lumen");
        } catch (RuntimeException ignored) {
        }
    }

    private void startVoice() {
        if (voice == null) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            boolean on = store.getBool(S_REMOTE, true);
            voice.typing(on ? "This projector has no microphone engine. Scan the code to talk from your phone "
                    + "(tap Advanced → Proceed the first time), or type here."
                    : "Type a command here, or turn on Phone remote in Lumen settings to talk from your phone.");
            voice.setQr(on ? phoneLink() : null);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(recognitionListener);
        }
        if (tts != null && ttsReady) tts.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        lastPartial = "";
        listening = true;
        voice.listening(VOICE_HINTS[hintIndex++ % VOICE_HINTS.length]);
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            listening = false;
            voice.result("", "Couldn't start the microphone", false);
        }
    }

    private void stopListening() {
        if (listening && recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        listening = false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startVoice();
        else voice.typing("Microphone permission was denied. Type a command instead, or use the phone remote.");
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle b) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rms) {
            voice.level(rms);
        }

        @Override
        public void onBufferReceived(byte[] buf) {
        }

        @Override
        public void onEndOfSpeech() {
            voice.thinking(lastPartial.length() > 0 ? lastPartial : "…");
        }

        @Override
        public void onError(int error) {
            listening = false;
            String msg;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    msg = "I didn't catch that. Try “open Kodi”.";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    msg = "The voice engine needs internet. Check Wi-Fi.";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    msg = "Microphone permission is off.";
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    msg = "No microphone found. Pair a voice remote, or use your phone.";
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    msg = "The voice engine is busy. Try again.";
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    return;
                default:
                    msg = "Voice didn't work (error " + error + ").";
            }
            voice.result("", msg, false);
        }

        @Override
        public void onResults(Bundle b) {
            listening = false;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) {
                voice.result("", "I didn't catch that.", false);
                return;
            }
            handleUtterance(r, false);
        }

        @Override
        public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && r.get(0).length() > 0) {
                lastPartial = r.get(0);
                voice.partial(lastPartial);
            }
        }

        @Override
        public void onEvent(int type, Bundle b) {
        }
    };

    /** Runs the first alternative the parser understands; shows and speaks the reply. Returns {ok, reply}. */
    private String[] handleUtterance(List<String> alternatives, boolean fromPhone) {
        CommandParser.Command best = null;
        for (String s : alternatives) {
            CommandParser.Command c = parser.parse(s);
            if (best == null) best = c;
            if (c.type != CommandParser.Type.UNKNOWN && c.type != CommandParser.Type.UNKNOWN_APP) {
                best = c;
                break;
            }
        }
        if (best == null) best = parser.parse("");
        // "cinema sound and more bass", "open kodi then volume 30": run each part in order
        // (only when every part is a real command, so "play tom and jerry" stays one search)
        boolean whole = best.type == CommandParser.Type.SEARCH_IN_APP || best.type == CommandParser.Type.WHERE_TO_WATCH
                || best.type == CommandParser.Type.WATCH;
        if (!whole && best.heard.toLowerCase(Locale.ROOT).matches(".+ (?:and|then|&) .+")) {
            String[] parts = best.heard.split("(?i)\\s+(?:and then|and|then|&)\\s+");
            List<CommandParser.Command> cmds = new ArrayList<CommandParser.Command>();
            for (String part : parts) {
                CommandParser.Command pc = parser.parse(part);
                if (pc.type == CommandParser.Type.UNKNOWN || pc.type == CommandParser.Type.UNKNOWN_APP) {
                    cmds.clear();
                    break;
                }
                cmds.add(pc);
            }
            if (cmds.size() > 1) {
                StringBuilder reply = new StringBuilder();
                boolean quietAll = true;
                for (CommandParser.Command pc : cmds) {
                    String[] pr = execute(pc);
                    if (reply.length() > 0) reply.append("  ·  ");
                    reply.append(pr[1]);
                    quietAll &= isPlaybackCommand(pc.type);
                }
                if (resumed && voice != null) voice.result("“" + best.heard + "”" + (fromPhone ? "   ·   from your phone" : ""),
                        reply.toString(), true);
                if (!quietAll && (resumed || !fromPhone)) say(reply.toString().replace("  ·  ", ". "));
                return ok(reply.toString());
            }
        }
        String[] r = execute(best);
        boolean ok = "true".equals(r[0]);
        boolean quiet = isPlaybackCommand(best.type);
        if (resumed && voice != null) {
            String line = best.heard.length() == 0 ? "" : "“" + best.heard + "”" + (fromPhone ? "   ·   from your phone" : "");
            voice.result(line, r[1], ok);
        }
        if (!quiet && (resumed || !fromPhone)) say(r[1]);
        return r;
    }

    private static boolean isPlaybackCommand(CommandParser.Type t) {
        switch (t) {
            case SEEK:
            case PLAY:
            case PAUSE:
            case PLAY_PAUSE:
            case STOP:
            case NEXT:
            case PREVIOUS:
            case VOLUME_UP:
            case VOLUME_DOWN:
            case VOLUME_SET:
            case MUTE:
            case UNMUTE:
                return true;
            default:
                return false;
        }
    }

    private String[] execute(CommandParser.Command c) {
        AppInfo app = c.app == null ? null : appByPkg(c.app.pkg);
        String label = app == null ? "" : app.label;
        switch (c.type) {
            case OPEN:
                if (app == null) break;
                launch(app, null);
                return ok("Opening " + label);
            case SEARCH_YOUTUBE:
                return searchYouTube(c.query);
            case SEARCH_WEB:
                return searchWeb(c.query);
            case PLAY:
                media(KeyEvent.KEYCODE_MEDIA_PLAY);
                return ok("Playing");
            case PAUSE:
                media(KeyEvent.KEYCODE_MEDIA_PAUSE);
                return ok("Paused");
            case PLAY_PAUSE:
                media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return ok("Play / pause");
            case STOP:
                media(KeyEvent.KEYCODE_MEDIA_STOP);
                return ok("Stopped");
            case NEXT:
                media(KeyEvent.KEYCODE_MEDIA_NEXT);
                return ok("Next");
            case PREVIOUS:
                media(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                return ok("Previous");
            case VOLUME_UP:
                return volumeStep(AudioManager.ADJUST_RAISE);
            case VOLUME_DOWN:
                return volumeStep(AudioManager.ADJUST_LOWER);
            case VOLUME_SET:
                return volumeSet(c.value);
            case MUTE:
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                return ok("Muted");
            case UNMUTE:
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                return ok("Sound on");
            case HOME:
                goHomeFromAnywhere();
                return ok("Going home");
            case SETTINGS:
                openSettings(Settings.ACTION_SETTINGS);
                return ok("Opening settings");
            case WIFI:
                openSettings(Settings.ACTION_WIFI_SETTINGS);
                return ok("Opening Wi-Fi settings");
            case BLUETOOTH:
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
                return ok("Opening Bluetooth");
            case DISPLAY:
                return ok(PictureSettings.open(this) ? "Opening the projector's picture settings" : "Opening display settings");
            case SOUND_PRESET:
                sound.applyPreset(c.query);
                return soundReply("off".equals(c.query) ? "Sound effects off" : SoundEngine.presetName(c.query) + " sound on");
            case SOUND_ADJUST: {
                sound.adjust(c.query, c.value);
                int v = sound.get(c.query);
                String name = "bass".equals(c.query) ? "Bass" : "treble".equals(c.query) ? "Treble"
                        : "clarity".equals(c.query) ? "Dialogue clarity" : "surround".equals(c.query) ? "Surround" : "Loudness";
                float range = "loudness".equals(c.query) ? 4f : 6f;
                String val = "surround".equals(c.query) ? v + "% (headphones only)"
                        : String.format(Locale.ROOT, "%+.1f dB vs preset", SoundTuning.offset(v, range));
                return soundReply(name + " " + val);
            }
            case SOUND_AB:
                sound.setBypass(!sound.bypass);
                return ok(sound.bypass ? "Now hearing Off. Say it again to hear " + SoundEngine.presetName(sound.preset)
                        : "Now hearing " + SoundEngine.presetName(sound.preset));
            case INTRO:
                playIntro();
                return ok("Here's the intro");
            case SEARCH_IN_APP:
                if (c.app == null) return fail(serviceName(c.target) + " isn't installed on this projector");
                return searchIn(c.app.pkg, c.app.label, c.query, c.value == 1);
            case WHERE_TO_WATCH:
            case WATCH:
                return lookUpTitle(c);
            case SEEK: {
                boolean fwd = c.value == 1;
                if (Front.get() instanceof CastActivity) Front.press(fwd ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                else media(fwd ? KeyEvent.KEYCODE_MEDIA_FAST_FORWARD : KeyEvent.KEYCODE_MEDIA_REWIND);
                return ok(fwd ? "Forward" : "Back");
            }
            case TRENDING:
                if ("nf".equals(c.query) || "pv".equals(c.query) || "yt".equals(c.query)) return focusShelf(c.query);
                refreshTrending(true);
                focusTrending();
                if (!trendingOn()) {
                    store.setBool("trending_all", true);
                    fillShelves();
                }
                return ok(anyTrendingCards() ? "Here's what's trending in " + countryName(trendRegion())
                        : (trendKey().length() == 0 && tmdbKey().length() == 0
                        ? "Trending needs free keys; add them on the phone remote's Trending card" : "Fetching what's trending…"));
            case SOUND_STUDIO:
                openSoundStudio();
                return ok("Opening Sound Studio");
            case PICTURE_LAB:
                openPictureLab(Math.max(0, c.value));
                return ok("Opening Picture Lab");
            case SCREEN:
                openScreen();
                return ok("Opening screen presets");
            case DETAIL: {
                if (!ScreenEngine.engine()) {
                    openScreen();
                    return fail("Detail needs direct access to the picture processor, which this projector isn't giving Lumen");
                }
                int lv = ScreenEngine.detail(this);
                int next = c.value >= 0 ? c.value : "down".equals(c.query) ? lv - 1 : lv + 1;
                next = Math.max(0, Math.min(4, next));
                ScreenEngine.setDetail(this, next);
                if (next == lv && c.value < 0) return ok("Detail is already at " + ScreenEngine.DETAIL_NAMES[lv]);
                return ok("Detail: " + ScreenEngine.DETAIL_NAMES[next]);
            }
            case AUTO_PICTURE:
                ScreenEngine.setAuto(this, c.value != 0);
                return ok(c.value != 0 ? "Auto Picture is on: the picture now follows what you watch"
                        : "Auto Picture is off: " + ScreenEngine.name(ScreenEngine.current(this)) + " everywhere");
            case COMPARE_PICTURE:
                if (ScreenEngine.compare(this, 5)) return ok("Here's the projector's original picture for 5 seconds");
                return fail("Pick a picture profile first, then you can compare");
            case CONTINUE_WATCHING: {
                if (!ContinueStore.enabled(this)) ContinueStore.setEnabled(this, true);
                ContinueStore.Entry latest = null;
                for (ContinueStore.Entry e : cwEntries.values()) if (latest == null || e.updatedAt > latest.updatedAt) latest = e;
                if (latest != null && resumeContinue(latest.key, null)) return ok("Resuming " + latest.title);
                return fail(ContinueStore.canWatch(this) ? "Nothing to continue yet. Start something in Netflix, Prime or YouTube"
                        : "Continue Watching needs one-time access. Run LumenOS-Update from your PC");
            }
            case SCREEN_PRESET: {
                String done = ScreenEngine.apply(this, c.query);
                String name = ScreenEngine.name(c.query);
                if (done.length() == 0) {
                    openScreen();
                    return ok(name + " picture: here are the projector settings for it");
                }
                return ok(name + " picture: " + done);
            }
            case WINDOW: {
                SceneLibrary lib = WindowView.library(this);
                if (lib.clips().isEmpty()) {
                    openWindow(null);  // shows how to get the scenes
                    return ok("No scenes yet. Download them on the phone remote: More → Window");
                }
                String scene = c.query.length() > 0 ? c.query : null;
                if (scene != null && lib.clipFor(scene) == null) {
                    openWindow(null);
                    return ok("That scene isn't downloaded yet, here's the window");
                }
                openWindow(scene);
                SceneLibrary.Scene sc = scene == null ? null : SceneLibrary.scene(scene);
                return ok(sc != null ? sc.name : "Lumen Window");
            }
            case CAST:
                openCast(null);
                return ok("Cast: mirror a phone, share a laptop, or send photos and videos");
            case MIRROR:
                return openMirror();
            case UPDATE: {
                if (updater.source().length() == 0) {
                    showUpdateHelp();
                    return ok("Set an update source first. I've opened the steps");
                }
                if (!updater.canInstall()) {
                    showUpdates();
                    return ok("Lumen needs permission to install updates first");
                }
                if (updater.available() != null) return ok(updater.installAvailable());
                updater.check(true);
                return ok("Checking for a newer Lumen…");
            }
            case SOUND_OUTPUT:
                sound.setOutputMode(c.query);
                return ok("auto".equals(c.query) ? "Sound output: " + sound.outputModeLabel()
                        : "Sound Studio is now tuned for " + sound.outputName().toLowerCase(Locale.ROOT));
            case FREE_RAM:
                boost();
                return ok("Freeing up memory");
            case TIME:
                return ok("It's " + new SimpleDateFormat(store.getBool(S_24H, true) ? "HH:mm" : "h:mm a",
                        Locale.getDefault()).format(new Date()));
            case DATE:
                return ok("Today is " + new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()));
            case MEMORY:
                return ok(availMb((ActivityManager) getSystemService(ACTIVITY_SERVICE)) + " MB of memory free");
            case TEMPERATURE: {
                HeatGuard h = HeatGuard.get(this);
                return ok("Projector temperature: " + h.reading()
                        + (h.level > HeatGuard.NORMAL ? " · " + HeatGuard.LEVEL_NAMES[h.level] : ""));
            }
            case PIN_APP:
                if (app == null) break;
                if (!store.isFavourite(app.pkg)) store.toggleFavourite(app.pkg);
                render(app.pkg, true);
                return ok(label + " is in your top row");
            case UNPIN_APP:
                if (app == null) break;
                if (store.isFavourite(app.pkg)) store.toggleFavourite(app.pkg);
                render(null, false);
                return ok("Removed " + label + " from the top row");
            case HIDE_APP:
                if (app == null) break;
                store.setHidden(app.pkg, true);
                render(null, false);
                return ok("Hid " + label + ". Unhide it in Lumen settings.");
            case UNINSTALL_APP:
                if (app == null) break;
                if (app.system) return fail(label + " is a system app and can't be uninstalled");
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return ok("Confirm on screen to uninstall " + label);
                } catch (RuntimeException e) {
                    return fail("Uninstall isn't available here");
                }
            case MOTION_CALM:
                store.setBool(S_MOTION, false);
                render(null, false);
                return ok("Calm motion on");
            case MOTION_FULL:
                store.setBool(S_MOTION, true);
                render(null, false);
                return ok("Full motion on");
            case BG_BLACK:
                store.setBool(S_AMBIENT, false);
                render(null, false);
                return ok("Pure black background");
            case BG_AMBIENT:
                store.setBool(S_AMBIENT, true);
                render(null, false);
                return ok("Ambient light on");
            case HELP:
                return ok("Try “open Kodi”, “play lofi music”, “volume 30”, “cinema sound”, “search Stranger Things on Netflix”, “where can I watch Dune”, “continue watching”, “HDR picture”, “sharper”, “show me the beach” or “go home”");
            case UNKNOWN_APP:
                return fail("I couldn't find an app called “" + c.query + "”");
            default:
                break;
        }
        if (c.heard.length() == 0) return fail("I didn't hear anything");
        return fail("Sorry, I didn't get “" + c.heard + "”. Say “help” for ideas.");
    }

    private static String[] ok(String reply) {
        return new String[]{"true", reply};
    }

    private static String[] fail(String reply) {
        return new String[]{"false", reply};
    }

    private AppInfo appByPkg(String pkg) {
        for (AppInfo a : allApps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private AudioManager audio() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    /** Media keys reach whichever app is playing (SmartTube, Kodi, VLC…), even while Lumen is in the background. */
    private void media(int keyCode) {
        AudioManager am = audio();
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private String[] volumeStep(int direction) {
        AudioManager am = audio();
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        return ok("Volume " + volumePercent() + "%");
    }

    private String[] volumeSet(int pct) {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), AudioManager.FLAG_SHOW_UI);
        return ok("Volume " + volumePercent() + "%");
    }

    private int volumePercent() {
        AudioManager am = audio();
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private void goHomeFromAnywhere() {
        if (resumed) {
            goHome();
            return;
        }
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- search inside apps

    /** Where a service puts its search, when it has a link for it (the generic search intents come after). */
    private static String[] searchLinks(String pkg, String q) {
        String e = Uri.encode(q);
        if (pkg.startsWith("com.netflix")) return new String[]{"https://www.netflix.com/search?q=" + e};
        if (pkg.startsWith("com.amazon.amazonvideo") || pkg.startsWith("com.amazon.avod"))
            return new String[]{"https://app.primevideo.com/search?phrase=" + e, "https://www.primevideo.com/search?phrase=" + e};
        if (pkg.startsWith("com.disney.disneyplus")) return new String[]{"https://www.disneyplus.com/search?q=" + e};
        if (pkg.startsWith("in.startv.hotstar")) return new String[]{"https://www.hotstar.com/in/explore?search_query=" + e};
        if (pkg.startsWith("com.spotify")) return new String[]{"spotify:search:" + e};
        if (pkg.equals("com.google.android.apps.youtube.music")) return new String[]{"https://music.youtube.com/search?q=" + e};
        if (pkg.startsWith("com.anghami")) return new String[]{"https://play.anghami.com/search/" + e};
        return new String[0];
    }

    /**
     * Searches inside an app: its own search link if it has one, then the standard Android / Assistant search
     * intents (most TV apps answer one of them), then "play from search" for music apps; otherwise opens the
     * app and says what to look for.
     */
    private String[] searchIn(String pkg, String label, String q, boolean play) {
        for (String y : YT_PKGS) if (y.equals(pkg)) return searchYouTube(q);
        PackageManager pm = getPackageManager();
        List<Intent> tries = new ArrayList<Intent>();
        Intent fromSearch = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").putExtra(android.app.SearchManager.QUERY, q);
        if (play) tries.add(fromSearch);            // music apps start playing straight away
        for (String u : searchLinks(pkg, q)) tries.add(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        tries.add(new Intent("com.google.android.gms.actions.SEARCH_ACTION").putExtra(android.app.SearchManager.QUERY, q));
        tries.add(new Intent(Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, q));
        if (!play) tries.add(fromSearch);
        for (Intent i : tries) {
            i.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i);
                boolean playing = "android.media.action.MEDIA_PLAY_FROM_SEARCH".equals(i.getAction());
                return ok(playing ? "Playing “" + q + "” on " + label : "Searching " + label + " for “" + q + "”");
            } catch (RuntimeException ignored) {
            }
        }
        Intent open = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (open == null) open = pm.getLaunchIntentForPackage(pkg);
        if (open == null) return fail(label + " can't be opened");
        try {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            return fail(label + " can't be opened");
        }
        return ok("Opened " + label + ". Search for “" + q + "” there");
    }

    private static String serviceName(String spoken) {
        String s = spoken == null ? "" : spoken.trim();
        if (s.matches("prime|prime video|amazon prime|amazon")) return "Prime Video";
        if (s.matches("disney|disney plus|disney hotstar")) return "Disney+";
        if (s.matches("apple tv|apple tv plus")) return "Apple TV";
        if (s.matches("osn|osn plus")) return "OSN+";
        if (s.matches("you tube|youtube")) return "YouTube";
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) if (w.length() > 0) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.length() == 0 ? "That app" : b.toString();
    }

    /** An installed app for a streaming service name from TMDB ("Prime Video", "Disney+"), or null. */
    private CommandParser.AppRef appFor(String service) {
        String spoken = service.toLowerCase(Locale.ROOT).replace("+", " plus").replaceAll("\\s+", " ").trim();
        return parser.findApp(spoken, 0.8);
    }

    private static String theCountry(String name) {
        if (name == null || name.length() == 0) return "";
        return name.matches("(?i)(united .*|.*emirates|netherlands|philippines|czech republic|dominican republic|bahamas|maldives)") ? "the " + name : name;
    }

    // ---------------------------------------------------------------- answers that need the internet

    /** An answer still being looked up; the phone remote waits on it, the projector shows it when ready. */
    private static final class Pending {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile String[] result;

        void finish(String[] r) {
            result = r;
            done.countDown();
        }

        String[] await(long ms) {
            try {
                done.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            return result;
        }
    }

    private Pending lastPending;

    private synchronized Pending takePending() {
        Pending p = lastPending;
        lastPending = null;
        return p;
    }

    /** "Where can I watch X", "is X on Netflix", "watch X": looks X up on TMDB, then answers or opens it. */
    private String[] lookUpTitle(final CommandParser.Command c) {
        final String key = tmdbKey(), region = trendRegion(), country = theCountry(countryName(region));
        final boolean watch = c.type == CommandParser.Type.WATCH;
        final String q = c.query;
        if (key.length() == 0) return watch ? searchYouTube(q) : fail("Where-to-watch needs a TMDB key (More → Trending on the phone)");
        final Pending p = new Pending();
        synchronized (this) {
            lastPending = p;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                WatchFinder.Match m = null;
                String error = null;
                try {
                    m = WatchFinder.find(key, q, region);
                } catch (java.io.IOException e) {
                    error = e.getMessage();
                }
                final WatchFinder.Match found = m;
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        String[] r = answer(found, err, q, watch, c.target, country);
                        if (resumed && voice != null) voice.result("“" + c.heard + "”", r[1], "true".equals(r[0]));
                        else toast(r[1]);
                        say(r[1]);
                        p.finish(r);
                    }
                });
            }
        }, "lumen-where").start();
        return ok(watch ? "Finding “" + q + "”…" : "Looking up where to watch “" + q + "”…");
    }

    /** Main thread: turns a lookup into a reply, and for "watch X" opens it in the right app. */
    private String[] answer(WatchFinder.Match m, String error, String q, boolean watch, String only, String country) {
        if (watch) {
            if (m != null && m.score >= 0.75) {
                for (String svc : m.stream) {
                    CommandParser.AppRef app = appFor(svc);
                    if (app == null) continue;
                    String[] r = searchIn(app.pkg, app.label, m.title, true);
                    if ("true".equals(r[0])) return ok(r[1].startsWith("Searching") ? "Opening " + m.label() + " on " + app.label : r[1]);
                }
            }
            String[] r = searchYouTube(q);  // not on any installed service (or not a film/series): YouTube
            return m != null && !m.stream.isEmpty() && m.score >= 0.75
                    ? ok(m.label() + " is on " + WatchFinder.join(m.stream) + ", which isn't installed here. " + r[1]) : r;
        }
        if (error != null) return fail("Couldn't look that up: " + error);
        if (m == null) return fail("I couldn't find a film or series called “" + q + "”");
        String line = WatchFinder.sentence(m, country, only);
        for (String svc : m.stream) {
            CommandParser.AppRef app = appFor(svc);
            if (app != null) return ok(line + ". Say “watch " + m.title + " on " + svc + "”");
        }
        return ok(line);
    }

    /** "What's popular on Netflix": turns that row on if needed and jumps to it. */
    private String[] focusShelf(String key) {
        Shelf target = null;
        for (Shelf sh : shelves) if (sh.key.equals(key)) target = sh;
        if (target == null) return fail("That row isn't available");
        if (!trendingOn()) store.setBool("trending_all", true);
        if (!target.enabled()) store.setBool(target.onPref, true);
        fillShelves();
        refreshTrending(false);
        if (target.row != null && target.row.getChildCount() > 0 && target.section.getVisibility() == View.VISIBLE) {
            target.row.getChildAt(0).requestFocusFromTouch();
            return ok(target.label + " in " + theCountry(countryName(trendRegion())));
        }
        return ok("Fetching " + target.label.toLowerCase(Locale.ROOT) + "…");
    }

    private String[] searchYouTube(String q) {
        PackageManager pm = getPackageManager();
        for (String pkg : YT_PKGS) {
            if (pm.getLaunchIntentForPackage(pkg) == null && pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
            if (q.length() == 0) {
                AppInfo a = appByPkg(pkg);
                if (a != null) {
                    launch(a, null);
                    return ok("Opening " + a.label);
                }
            }
            Intent v = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
                    .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent s = new Intent(Intent.ACTION_SEARCH).setPackage(pkg)
                    .putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            for (Intent i : new Intent[]{v, s}) {
                if (i.resolveActivity(pm) == null) continue;
                try {
                    startActivity(i);
                    return ok("Searching YouTube for “" + q + "”");
                } catch (RuntimeException ignored) {
                }
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return ok("Searching YouTube for “" + q + "”");
        } catch (RuntimeException e) {
            return fail("Install SmartTube or YouTube to search videos");
        }
    }

    private String[] searchWeb(String q) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return ok("Searching the web for “" + q + "”");
        } catch (RuntimeException e) {
            return fail("No web browser is installed");
        }
    }

    // ================================================================ trending

    /** A trending card that isn't an app: a YouTube video or a Netflix / Prime Video title. */
    private static final class ContentItem {
        static final int YT = 0, NF = 1, PV = 2, CW = 3;
        final int kind;
        final String id, title, eyebrow, meta;

        ContentItem(int kind, String id, String title, String eyebrow, String meta) {
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.eyebrow = eyebrow;
            this.meta = meta;
        }
    }

    /** One horizontal row of trending cards. */
    private final class Shelf {
        final String key, label, onPref;
        final int kind;
        LinearLayout section, row;
        TextView title;
        List<AppInfo> cards = new ArrayList<AppInfo>();
        long fetchedAt;
        String error = "", region = "";
        boolean loading;

        Shelf(String key, int kind, String label, String onPref) {
            this.key = key;
            this.kind = kind;
            this.label = label;
            this.onPref = onPref;
        }

        boolean enabled() {
            return trendingOn() && store.getBool(onPref, true);
        }

        boolean hasKey() {
            return (kind == ContentItem.YT ? trendKey() : tmdbKey()).length() > 0;
        }
    }

    private final Shelf ytShelf = new Shelf("yt", ContentItem.YT, "Trending on YouTube", "yt_on");
    private final Shelf nfShelf = new Shelf("nf", ContentItem.NF, "Popular on Netflix", "nf_on");
    private final Shelf pvShelf = new Shelf("pv", ContentItem.PV, "Popular on Prime Video", "pv_on");
    private final Shelf[] shelves = {ytShelf, nfShelf, pvShelf};
    private final Map<String, ContentItem> contentItems = new HashMap<String, ContentItem>();

    private boolean trendingOn() {
        return store.getBool("trending_all", true);
    }

    private String trendKey() {
        String k = store.getString("yt_key", "").trim();
        return k.length() > 0 ? k : keyService();
    }

    private String tmdbKey() {
        String k = store.getString("tmdb_key", "").trim();
        return k.length() > 0 ? k : keyService();
    }

    /** Manual country if the user set one; otherwise the country of the current Wi-Fi connection. */
    private String trendRegion() {
        String r = store.getString("yt_region", "");
        if (r.length() == 2) return r;
        String geo = store.getString("geo_country", "");
        if (geo.length() == 2) return geo;
        return fallbackRegion();
    }

    private boolean regionAuto() {
        return store.getString("yt_region", "").length() != 2;
    }

    private static String countryName(String code) {
        String n = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        return n == null || n.length() == 0 || n.equals(code) ? code : n;
    }

    /** Before the first Wi-Fi lookup succeeds: guess from the time zone, then the system language. */
    private String fallbackRegion() {
        String tz = java.util.TimeZone.getDefault().getID();
        String[][] map = {{"Asia/Dubai", "AE"}, {"Asia/Kolkata", "IN"}, {"Asia/Calcutta", "IN"}, {"Asia/Riyadh", "SA"},
                {"Asia/Qatar", "QA"}, {"Asia/Muscat", "OM"}, {"Asia/Bahrain", "BH"}, {"Asia/Kuwait", "KW"},
                {"Europe/London", "GB"}, {"Asia/Singapore", "SG"}, {"Australia/Sydney", "AU"}};
        for (String[] m : map) if (m[0].equals(tz)) return m[1];
        String c = Locale.getDefault().getCountry();
        return c.length() == 2 ? c : "US";
    }

    private View buildShelf(Shelf sh, boolean motion) {
        sh.section = new LinearLayout(this);
        sh.section.setOrientation(LinearLayout.VERTICAL);
        sh.section.setClipChildren(false);
        sh.section.setClipToPadding(false);
        sh.title = text("", 20, C_TEXT);
        sh.title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sh.title.setPadding(0, dp(22), 0, dp(2));
        sh.section.addView(sh.title);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        sh.row = new LinearLayout(this);
        sh.row.setOrientation(LinearLayout.HORIZONTAL);
        sh.row.setClipChildren(false);
        sh.row.setClipToPadding(false);
        sh.row.setPadding(0, dp(16), dp(48), dp(10));
        hs.addView(sh.row);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = -dp(48);
        sh.section.addView(hs, hlp);
        fillShelf(sh, motion);
        return sh.section;
    }

    private void fillShelves() {
        for (Shelf sh : shelves) fillShelf(sh, motionOn());
    }

    private void fillShelf(Shelf sh, boolean motion) {
        if (sh.row == null) return;
        View focused = getCurrentFocus();
        String focusedPkg = focused instanceof CardView ? ((CardView) focused).app.pkg : null;
        sh.row.removeAllViews();
        if (sh.cards.isEmpty() || !sh.enabled()) {
            sh.section.setVisibility(View.GONE);
            return;
        }
        sh.section.setVisibility(View.VISIBLE);
        String region = sh.region.length() == 2 ? sh.region : trendRegion();
        sh.title.setText(sh.label + "  ·  " + countryName(region) + (sh.fetchedAt > 0 ? "  ·  " + ago(sh.fetchedAt) : ""));
        for (int i = 0; i < sh.cards.size(); i++) {
            CardView c = card(sh.cards.get(i), false, motion);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(GAP);
            sh.row.addView(c, lp);
            if (sh.cards.get(i).pkg.equals(focusedPkg)) c.requestFocusFromTouch();
        }
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        return (m / 1440) + " days ago";
    }

    private void focusTrending() {
        for (Shelf sh : shelves) {
            if (sh.row != null && sh.row.getChildCount() > 0 && sh.section.getVisibility() == View.VISIBLE) {
                sh.row.getChildAt(0).requestFocusFromTouch();
                return;
            }
        }
    }

    private boolean anyTrendingCards() {
        for (Shelf sh : shelves) if (!sh.cards.isEmpty() && sh.enabled()) return true;
        return false;
    }

    /** Loads each row's last feed + images from disk, so the rows appear instantly after a reboot. */
    private void loadTrendingCache() {
        for (final Shelf sh : shelves) {
            final java.io.File dir = new java.io.File(getCacheDir(), sh.key);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.io.File f = new java.io.File(dir, "feed.json");
                        if (!f.exists()) return;
                        String json = new String(TrendingFeed.readAll(new java.io.FileInputStream(f)), "UTF-8");
                        final long at = f.lastModified();
                        final List<AppInfo> cards = buildCards(sh, json, dir, false);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!sh.cards.isEmpty()) return; // a fresh fetch won the race
                                sh.fetchedAt = at;
                                sh.region = store.getString(sh.key + "_last_region", "");
                                sh.cards = cards;
                                fillShelf(sh, motionOn());
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }
            }, "lumen-cache-" + sh.key).start();
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshTrending(false);
            }
        }, 2500);
    }

    private boolean refreshing;

    /**
     * Refreshes every enabled row that has its key. The country comes from the Wi-Fi connection
     * (looked up once per refresh), and a row refetches when it's stale or the country changed.
     */
    private void refreshTrending(final boolean force) {
        if (refreshing || !trendingOn()) return;
        boolean anyWork = false;
        final long now = System.currentTimeMillis();
        final boolean geoStale = now - store.getLong("geo_at", 0) > GEO_MAX_AGE;
        for (Shelf sh : shelves) {
            if (!sh.enabled() || !sh.hasKey()) continue;
            long maxAge = sh.kind == ContentItem.YT ? TREND_MAX_AGE : TMDB_MAX_AGE;
            if (force || now - sh.fetchedAt > maxAge || (regionAuto() && geoStale)) anyWork = true;
        }
        if (!anyWork) return;
        refreshing = true;
        for (Shelf sh : shelves) if (sh.enabled() && sh.hasKey()) sh.loading = true;
        final boolean auto = regionAuto();
        final String manual = store.getString("yt_region", "");
        final String fallback = trendRegion();
        final String ytKey = trendKey(), tmKey = tmdbKey();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String region = auto ? null : manual;
                if (auto && (force || geoStale || store.getString("geo_country", "").length() != 2)) {
                    final String found = TrendingFeed.detectCountry();
                    if (found != null) {
                        region = found;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                store.setString("geo_country", found);
                                store.setLong("geo_at", System.currentTimeMillis());
                            }
                        });
                    }
                }
                if (region == null) region = fallback;
                for (final Shelf sh : shelves) {
                    if (!sh.enabled() || !sh.hasKey()) continue;
                    long maxAge = sh.kind == ContentItem.YT ? TREND_MAX_AGE : TMDB_MAX_AGE;
                    boolean fresh = now - sh.fetchedAt < maxAge;
                    if (!force && fresh && region.equals(sh.region)) {
                        postShelf(sh, null, "", region);
                        continue;
                    }
                    java.io.File dir = new java.io.File(getCacheDir(), sh.key);
                    dir.mkdirs();
                    try {
                        String json;
                        if (sh.kind == ContentItem.YT) {
                            json = TrendingFeed.download(ytKey, region, 15);
                        } else {
                            String[] two = TmdbFeed.download(tmKey, region, sh.kind == ContentItem.NF ? TmdbFeed.NETFLIX : TmdbFeed.PRIME);
                            json = "{\"movie\":" + two[0] + ",\"tv\":" + two[1] + "}";
                        }
                        List<AppInfo> cards = buildCards(sh, json, dir, true);
                        java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "feed.json"));
                        out.write(json.getBytes("UTF-8"));
                        out.close();
                        postShelf(sh, cards, "", region);
                    } catch (Exception e) {
                        String err = e.getMessage() == null ? "Couldn't reach the service" : e.getMessage();
                        if (e instanceof java.net.UnknownHostException) err = "No internet connection";
                        postShelf(sh, null, err, region);
                    }
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                    }
                });
            }
        }, "lumen-trending").start();
    }

    private void postShelf(final Shelf sh, final List<AppInfo> cards, final String error, final String region) {
        main.post(new Runnable() {
            @Override
            public void run() {
                sh.loading = false;
                sh.error = error;
                if (cards != null) {
                    sh.cards = cards;
                    sh.fetchedAt = System.currentTimeMillis();
                    sh.region = region;
                    store.setString(sh.key + "_last_region", region);
                    fillShelf(sh, motionOn());
                }
            }
        });
    }

    /** Background thread: parse a feed, fetch/cached images, and make cards that reuse the parallax CardView. */
    private List<AppInfo> buildCards(Shelf sh, String json, java.io.File dir, boolean download) {
        List<String[]> items = new ArrayList<String[]>(); // {id, title, imageUrl, eyebrow, meta}
        if (sh.kind == ContentItem.YT) {
            for (TrendingFeed.Video v : TrendingFeed.parse(json)) {
                items.add(new String[]{v.id, v.title, v.thumb, "TRENDING ON YOUTUBE  ·  #" + v.rank, v.meta()});
            }
        } else {
            Object root = TrendingFeed.Json.parse(json);
            String movie = jsonOf(TrendingFeed.Json.path(root, "movie")), tv = jsonOf(TrendingFeed.Json.path(root, "tv"));
            String service = sh.kind == ContentItem.NF ? "NETFLIX" : "PRIME VIDEO";
            for (TmdbFeed.Title t : TmdbFeed.parse(movie, tv, 12)) {
                items.add(new String[]{t.type + t.id, t.name, t.imageUrl(), "POPULAR ON " + service + "  ·  #" + t.rank, t.meta()});
            }
        }
        List<AppInfo> out = new ArrayList<AppInfo>();
        java.util.Set<String> keep = new java.util.HashSet<String>();
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        for (String[] it : items) {
            java.io.File f = new java.io.File(dir, it[0] + ".jpg");
            keep.add(f.getName());
            Bitmap img = null;
            try {
                if (!f.exists() && download) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(it[2]).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(8000);
                    byte[] bytes = TrendingFeed.readAll(c.getInputStream());
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(bytes);
                    fo.close();
                }
                if (f.exists()) img = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            } catch (Exception ignored) {
            }
            if (img == null) continue;
            String pkg = sh.key + ":" + it[0];
            synchronized (contentItems) {
                contentItems.put(pkg, new ContentItem(sh.kind, it[0], it[1], it[3], it[4]));
            }
            out.add(new AppInfo(pkg, it[1], null, img, AppStore.dominant(img), null, false));
        }
        if (download) { // keep the cache small: drop images that left the list
            java.io.File[] files = dir.listFiles();
            if (files != null) for (java.io.File f : files) if (f.getName().endsWith(".jpg") && !keep.contains(f.getName())) f.delete();
        }
        return out;
    }

    /** Re-serialises a parsed JSON subtree just enough for TmdbFeed.parse (it only needs "results"). */
    @SuppressWarnings("unchecked")
    private static String jsonOf(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return RemoteServer.json((String) o);
        if (o instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (b.length() > 1) b.append(',');
                b.append(RemoteServer.json(e.getKey())).append(':').append(jsonOf(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (o instanceof List) {
            StringBuilder b = new StringBuilder("[");
            for (Object v : (List<Object>) o) {
                if (b.length() > 1) b.append(',');
                b.append(jsonOf(v));
            }
            return b.append(']').toString();
        }
        return String.valueOf(o);
    }

    private void openContent(ContentItem it, View from) {
        if (it.kind == ContentItem.CW) {
            resumeContinue(it.id, from);
            return;
        }
        if (it.kind == ContentItem.YT) {
            playVideo(it.id, from);
            return;
        }
        String q = Uri.encode(it.title);
        String[] pkgs = it.kind == ContentItem.NF
                ? new String[]{"com.netflix.ninja", "com.netflix.mediaclient"}
                : new String[]{"com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"};
        String[] urls = it.kind == ContentItem.NF
                ? new String[]{"https://www.netflix.com/search?q=" + q}
                : new String[]{"https://app.primevideo.com/search?phrase=" + q, "https://www.primevideo.com/search?phrase=" + q};
        String app = it.kind == ContentItem.NF ? "Netflix" : "Prime Video";
        PackageManager pm = getPackageManager();
        for (String pkg : pkgs) {
            if (isInstalled(pkg)) {
                ScreenEngine.onApp(this, pkg);
                break;
            }
        }
        for (String pkg : pkgs) {
            for (String u : urls) { // a search deep link, when the app supports it
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(pm) == null) continue;
                try {
                    startActivity(i);
                    return;
                } catch (RuntimeException ignored) {
                }
            }
        }
        for (String pkg : pkgs) { // otherwise open the app and say what to search for
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast("Search for “" + it.title + "” in " + app);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        toast(app + " isn't installed on this projector");
    }

    private void playVideo(String id, View from) {
        playVideoAt(id, 0, from);
    }

    private void playVideoAt(String id, long startSec, View from) {
        Uri u = Uri.parse("https://www.youtube.com/watch?v=" + id + (startSec > 5 ? "&t=" + startSec + "s" : ""));
        for (String p : YT_PKGS) {
            if (isInstalled(p)) {
                ScreenEngine.onApp(this, p);
                break;
            }
        }
        Bundle opts = from != null && motionOn()
                ? ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle() : null;
        PackageManager pm = getPackageManager();
        for (String pkg : YT_PKGS) {
            Intent i = new Intent(Intent.ACTION_VIEW, u).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i, opts);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
        } catch (RuntimeException e) {
            toast("Install SmartTube or YouTube to play videos");
        }
    }

    // ================================================================== Continue Watching

    private LinearLayout cwSection, cwRow;
    private TextView cwTitle;
    private List<AppInfo> cwCards = new ArrayList<AppInfo>();
    private final Map<String, ContinueStore.Entry> cwEntries = new HashMap<String, ContinueStore.Entry>();
    private boolean cwLoading, cwAgain;

    private View buildContinue(boolean motion) {
        cwSection = new LinearLayout(this);
        cwSection.setOrientation(LinearLayout.VERTICAL);
        cwSection.setClipChildren(false);
        cwSection.setClipToPadding(false);
        cwTitle = text("Continue Watching", 20, C_TEXT);
        cwTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cwTitle.setPadding(0, dp(22), 0, dp(2));
        cwSection.addView(cwTitle);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        cwRow = new LinearLayout(this);
        cwRow.setOrientation(LinearLayout.HORIZONTAL);
        cwRow.setClipChildren(false);
        cwRow.setClipToPadding(false);
        cwRow.setPadding(0, dp(16), dp(48), dp(10));
        hs.addView(cwRow);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = -dp(48);
        cwSection.addView(hs, hlp);
        fillContinue(motion);
        return cwSection;
    }

    private void fillContinue(boolean motion) {
        if (cwRow == null) return;
        View focused = getCurrentFocus();
        String focusedPkg = focused instanceof CardView ? ((CardView) focused).app.pkg : null;
        cwRow.removeAllViews();
        if (cwCards.isEmpty() || !ContinueStore.enabled(this)) {
            cwSection.setVisibility(View.GONE);
            return;
        }
        cwSection.setVisibility(View.VISIBLE);
        for (int i = 0; i < cwCards.size(); i++) {
            CardView c = card(cwCards.get(i), false, motion);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(GAP);
            cwRow.addView(c, lp);
            if (cwCards.get(i).pkg.equals(focusedPkg)) c.requestFocusFromTouch();
        }
    }

    /** Rebuilds the Continue Watching cards in the background (artwork + progress bars). */
    private void refreshContinue() {
        if (!ContinueStore.enabled(this)) {
            cwCards = new ArrayList<AppInfo>();
            fillContinue(motionOn());
            return;
        }
        if (cwLoading) {
            cwAgain = true;
            return;
        }
        cwLoading = true;
        final Map<String, AppInfo> byPkg = new HashMap<String, AppInfo>();
        for (AppInfo a : allApps) byPkg.put(a.pkg, a);
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ContinueStore.Entry> list = ContinueStore.list(ctx);
                final List<AppInfo> cards = new ArrayList<AppInfo>();
                for (ContinueStore.Entry e : list) {
                    AppInfo owner = byPkg.get(e.pkg);
                    if (owner == null) continue; // app uninstalled
                    Bitmap img = ContinueStore.cardImage(e, owner.banner != null ? owner.banner : owner.icon, Theme.ROSE);
                    String id = "cw:" + e.key;
                    StringBuilder meta = new StringBuilder();
                    if (e.subtitle.length() > 0) meta.append(e.subtitle).append("  ·  ");
                    long left = e.remainingMin();
                    if (left > 0) meta.append(left).append(" min left  ·  ");
                    meta.append("OK to resume  ·  MENU for options");
                    synchronized (contentItems) {
                        contentItems.put(id, new ContentItem(ContentItem.CW, e.key, e.title,
                                "CONTINUE WATCHING  ·  " + owner.label.toUpperCase(Locale.ROOT), meta.toString()));
                    }
                    boolean episode = e.subtitle.matches("S\\d+( E\\d+)?|E\\d+");
                    cards.add(new AppInfo(id, episode ? e.title + "  ·  " + e.subtitle : e.title, null, img, owner.color, null, false));
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cwLoading = false;
                        cwEntries.clear();
                        for (ContinueStore.Entry e : list) cwEntries.put(e.key, e);
                        cwCards = cards;
                        fillContinue(motionOn());
                        if (cwAgain) {
                            cwAgain = false;
                            refreshContinue();
                        }
                    }
                });
            }
        }, "lumen-continue").start();
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean launchPkg(String pkg, View from) {
        for (AppInfo a : allApps) {
            if (a.pkg.equals(pkg)) {
                launch(a, from);
                return true;
            }
        }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        ScreenEngine.onApp(this, pkg);
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Resumes a title, most exact way first: the app's own Watch Next link; its live session
     * (it still has the video loaded); YouTube at the saved second; Netflix / Prime opened on the
     * title (they resume the episode themselves); otherwise the app.
     */
    private boolean resumeContinue(String key, View from) {
        final ContinueStore.Entry e = cwEntries.get(key);
        if (e == null) return false;
        ScreenEngine.onApp(this, e.pkg);
        if (e.intentUri.length() > 0) {
            try {
                Intent i = Intent.parseUri(e.intentUri, Intent.URI_INTENT_SCHEME);
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {
            }
        }
        final android.media.session.MediaController mc = MediaWatch.controllerFor(e.pkg);
        if (mc != null) {
            android.media.MediaMetadata m = null;
            try {
                m = mc.getMetadata();
            } catch (RuntimeException ignored) {
            }
            CharSequence t = m == null ? null : m.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
            CharSequence dt = m == null ? null : m.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            boolean same = (t != null && e.title.equalsIgnoreCase(t.toString().trim()))
                    || (dt != null && e.title.equalsIgnoreCase(dt.toString().trim()));
            if (same) {
                boolean opened = false;
                try {
                    android.app.PendingIntent pi = mc.getSessionActivity();
                    if (pi != null) {
                        pi.send();
                        opened = true;
                    }
                } catch (Exception ignored) {
                }
                if (!opened) opened = launchPkg(e.pkg, from);
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mc.getTransportControls().play();
                        } catch (RuntimeException ignored) {
                        }
                    }
                }, 1500);
                if (opened) return true;
            }
        }
        boolean youTube = e.pkg.startsWith("com.google.android.youtube") || e.pkg.startsWith("com.teamsmart.videomanager")
                || e.pkg.startsWith("com.liskovsoft");
        if (youTube) {
            String id = e.mediaId.matches("[A-Za-z0-9_-]{11}") ? e.mediaId : "";
            if (id.length() == 0 && e.uri.contains("v=")) {
                String u = e.uri.substring(e.uri.indexOf("v=") + 2);
                if (u.length() >= 11 && u.substring(0, 11).matches("[A-Za-z0-9_-]{11}")) id = u.substring(0, 11);
            }
            if (id.length() > 0) {
                playVideoAt(id, e.position / 1000, from);
                return true;
            }
        }
        if (e.pkg.startsWith("com.netflix")) {
            openContent(new ContentItem(ContentItem.NF, "", e.title, "", ""), from);
            return true;
        }
        if (e.pkg.startsWith("com.amazon.amazonvideo") || e.pkg.startsWith("com.amazon.avod")) {
            openContent(new ContentItem(ContentItem.PV, "", e.title, "", ""), from);
            return true;
        }
        if (e.uri.length() > 0) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.uri)).setPackage(e.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        if (launchPkg(e.pkg, from)) return true;
        toast("Couldn't open that app");
        return false;
    }

    private void showContinueMenu(final ContentItem it, final CardView card) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(it.title)
                .setItems(new String[]{"Resume", "Remove from Continue Watching", "Clear the whole row",
                        "Turn off Continue Watching"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) resumeContinue(it.id, card.face);
                        else if (which == 1) {
                            ContinueStore.remove(HomeActivity.this, it.id);
                            toast("Removed. It comes back if you play it again.");
                        } else if (which == 2) {
                            ContinueStore.clear(HomeActivity.this);
                            toast("Continue Watching cleared");
                        } else {
                            ContinueStore.setEnabled(HomeActivity.this, false);
                            toast("Continue Watching is off. Turn it back on in Lumen settings.");
                        }
                    }
                })
                .show();
    }

    /** MENU on an app: which picture it gets (Auto Picture, or a fixed profile). */
    private void choosePictureFor(final AppInfo app) {
        final String guess = ScreenEngine.category(this, app.pkg);
        String autoName = "Auto (" + ScreenEngine.name(guess != null ? guess : ScreenEngine.current(this)) + ")";
        final String[] keys = new String[ScreenEngine.PRESETS.length + 1];
        String[] names = new String[keys.length];
        keys[0] = "auto";
        names[0] = autoName;
        for (int i = 0; i < ScreenEngine.PRESETS.length; i++) {
            keys[i + 1] = ScreenEngine.PRESETS[i];
            names[i + 1] = ScreenEngine.NAMES[i];
        }
        String cur = ScreenEngine.appChoice(this, app.pkg);
        int sel = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(cur)) sel = i;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Picture for " + app.label)
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        ScreenEngine.setAppChoice(HomeActivity.this, app.pkg, keys[which]);
                        d.dismiss();
                        toast(app.label + " will use " + (which == 0 ? "Auto Picture" : ScreenEngine.NAMES[which - 1])
                                + (ScreenEngine.auto(HomeActivity.this) ? "" : " (turn Auto Picture on in Screen)"));
                    }
                })
                .show();
    }

    private void showContentMenu(final ContentItem it, final CardView card) {
        if (it.kind == ContentItem.CW) {
            showContinueMenu(it, card);
            return;
        }
        final Shelf sh = it.kind == ContentItem.YT ? ytShelf : it.kind == ContentItem.NF ? nfShelf : pvShelf;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(it.title)
                .setItems(new String[]{it.kind == ContentItem.YT ? "Play" : "Open", "Refresh trending", "Hide this row",
                        "Turn off all trending"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) openContent(it, card.face);
                        else if (which == 1) {
                            refreshTrending(true);
                            toast("Refreshing…");
                        } else if (which == 2) {
                            store.setBool(sh.onPref, false);
                            fillShelf(sh, motionOn());
                            toast(sh.label + " hidden. Turn it back on in Lumen settings.");
                        } else {
                            store.setBool("trending_all", false);
                            fillShelves();
                            toast("All trending rows are off. Turn them back on in Lumen settings.");
                        }
                    }
                })
                .show();
    }

    private String shelfStatus(Shelf sh) {
        if (!trendingOn()) return "Off (all trending is off)";
        if (!store.getBool(sh.onPref, true)) return "Off";
        if (!sh.hasKey()) return sh.kind == ContentItem.YT ? "needs a free YouTube API key" : "needs a free TMDB key";
        if (sh.loading) return "updating…";
        if (sh.error.length() > 0) return sh.error;
        if (!sh.cards.isEmpty()) return sh.cards.size() + " titles · " + ago(sh.fetchedAt);
        return "waiting for the first update";
    }

    private String trendStatus() {
        if (!trendingOn()) return "Off";
        int on = 0;
        for (Shelf sh : shelves) if (!sh.cards.isEmpty() && sh.enabled()) on++;
        String where = countryName(trendRegion()) + (regionAuto() ? " (from your Wi-Fi)" : "");
        return on == 0 ? "On · " + where + " · set up keys on the phone remote" : on + " rows · " + where;
    }

    private String settingsState() {
        StringBuilder b = new StringBuilder("{");
        b.append("\"ytKeySet\":").append(trendKey().length() > 0);
        b.append(",\"tmdbKeySet\":").append(tmdbKey().length() > 0);
        b.append(",\"region\":\"").append(regionAuto() ? "" : trendRegion()).append('"');
        b.append(",\"auto\":").append(regionAuto());
        b.append(",\"country\":").append(RemoteServer.json(countryName(trendRegion())));
        b.append(",\"allOn\":").append(trendingOn());
        b.append(",\"ytOn\":").append(store.getBool("yt_on", true));
        b.append(",\"nfOn\":").append(store.getBool("nf_on", true));
        b.append(",\"pvOn\":").append(store.getBool("pv_on", true));
        b.append(",\"yt\":").append(RemoteServer.json(shelfStatus(ytShelf)));
        b.append(",\"nf\":").append(RemoteServer.json(shelfStatus(nfShelf)));
        b.append(",\"pv\":").append(RemoteServer.json(shelfStatus(pvShelf)));
        b.append(",\"trending\":").append(RemoteServer.json(trendStatus()));
        b.append(",\"screen\":\"").append(ScreenEngine.current(this)).append('"');
        b.append(",\"screenAuto\":").append(ScreenEngine.canBrightness(this) || ScreenEngine.canWarmth(this));
        b.append(",\"engine\":").append(ScreenEngine.engine());
        b.append(",\"detail\":").append(ScreenEngine.detail(this));
        b.append(",\"autoPic\":").append(ScreenEngine.auto(this));
        b.append(",\"cwOn\":").append(ContinueStore.enabled(this));
        b.append(",\"cwAccess\":").append(ContinueStore.canWatch(this));
        b.append(",\"window\":").append(windowJson());
        return b.append('}').toString();
    }

    /** A different Wi-Fi (e.g. the projector travelled): look up the country again and refresh. */
    private void watchNetwork() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cm.registerNetworkCallback(req, new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!regionAuto()) return;
                            if (System.currentTimeMillis() - store.getLong("geo_at", 0) < 10 * 60 * 1000L) return;
                            store.setLong("geo_at", 0);
                            refreshTrending(false);
                        }
                    }, 4000);
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void showTrendingInfo() {
        final boolean on = trendingOn();
        String msg = "Rows of what's popular where your Wi-Fi is (" + countryName(trendRegion()) + "):\n\n"
                + "• YouTube: " + shelfStatus(ytShelf) + "\n"
                + "• Netflix: " + shelfStatus(nfShelf) + "\n"
                + "• Prime Video: " + shelfStatus(pvShelf) + "\n\n"
                + (keyService().length() > 0 ? "These rows work out of the box through Lumen's key service. "
                + "You can also use your own free keys on the phone remote's Trending card:\n"
                : "Set up on the phone remote's Trending card (free keys, a couple of minutes each):\n")
                + "YouTube: console.cloud.google.com → enable YouTube Data API v3 → Credentials → API key\n"
                + "Netflix & Prime: themoviedb.org → Settings → API → request a free API key\n\n"
                + "Netflix and Prime don't publish trending data or share your account, so these rows show what's "
                + "popular on each service in your country (TMDB data, powered by JustWatch). "
                + "This product uses the TMDB API but is not endorsed or certified by TMDB.";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Trending")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Refresh now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        refreshTrending(true);
                    }
                })
                .setNegativeButton(on ? "Turn all off" : "Turn on", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        store.setBool("trending_all", !on);
                        fillShelves();
                        if (!on) refreshTrending(true);
                    }
                })
                .show();
    }

    // ==================================================================== intro

    /** True once per boot (and on first install), unless turned off in Lumen settings. */
    private boolean shouldPlayIntro() {
        return store.getBool(S_INTRO, true) && !bootKey().equals(store.getString("intro_boot", ""));
    }

    private String bootKey() {
        try {
            int bc = Settings.Global.getInt(getContentResolver(), "boot_count", -1);
            if (bc >= 0) return "bc" + bc;
        } catch (RuntimeException ignored) {
        }
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return "t" + (bootAt / 120000L);
    }

    private String greeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 12) return "Good morning";
        if (h >= 12 && h < 17) return "Good afternoon";
        if (h >= 17 && h < 22) return "Good evening";
        return "Good night";
    }

    private void playIntro() {
        if (intro != null) return;
        store.setString("intro_boot", bootKey());
        if (voice != null && voice.isShowing()) voice.hide();
        introReady = false;
        intro = new IntroView(this);
        intro.setListener(new IntroView.Listener() {
            @Override
            public void onIntroReady() {
                introReady = true;
                maybeStartIntro();
            }

            @Override
            public void onIntroFinished(boolean skipped) {
                finishIntro();
            }
        });
        root.addView(intro, match());
        if (aurora != null) aurora.setRunning(false);
        try {
            introPlayer = MediaPlayer.create(this, R.raw.intro); // prepared now, started only with the picture
            if (introPlayer != null) {
                introPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        releaseIntroPlayer();
                    }
                });
            }
        } catch (RuntimeException e) {
            introPlayer = null;
        }
    }

    private boolean introReady, introStarted;

    /** Silence at the head of intro.ogg: wakes a Bluetooth link / speaker before the first note. */
    private static final int INTRO_PREROLL_MS = 1200;
    /** After boot, how long to wait for the usual Bluetooth speaker to reconnect. */
    private static final long BT_WAIT_MS = 12000;
    private long introWaitUntil;

    /**
     * Music and picture start together, and only once the intro's first frame has been drawn AND our
     * window has focus — at boot the projector's display takes a few seconds to wake, which used to
     * swallow the opening of the intro. A short settle delay covers the panel's own fade-in.
     */
    private void maybeStartIntro() {
        if (intro == null || introStarted || !introReady || !hasWindowFocus()) return;
        introStarted = true;
        boolean boot = SystemClock.elapsedRealtime() < 180000;
        // Right after boot a Bluetooth soundbar/speaker takes a few seconds to reconnect; without waiting, the
        // music would start on the projector's own speaker and jump across halfway through.
        if (boot && sound.expectsBluetooth() && !sound.bluetoothConnected) {
            introWaitUntil = SystemClock.uptimeMillis() + BT_WAIT_MS;
            intro.setWaiting("Connecting to your speaker…");
            waitForSpeaker();
            return;
        }
        main.postDelayed(startIntroNow, boot ? 1200 : 350); // longer settle right after boot
    }

    private void waitForSpeaker() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (intro == null) return;
                if (sound.bluetoothConnected) {
                    main.postDelayed(startIntroNow, 1500); // let the audio route settle on the speaker
                } else if (SystemClock.uptimeMillis() >= introWaitUntil) {
                    main.post(startIntroNow);              // not coming: play on what we have
                } else {
                    waitForSpeaker();
                }
            }
        }, 400);
    }

    private final Runnable startIntroNow = new Runnable() {
        @Override
        public void run() {
            if (intro == null) return;
            intro.setWaiting(null);
            boolean external = sound.output != SoundTuning.SPEAKER;
            try {
                if (introPlayer != null) {
                    // the lead-in silence only matters for external sound; skip it on the built-in speaker
                    if (!external) introPlayer.seekTo(INTRO_PREROLL_MS);
                    introPlayer.start();
                }
            } catch (RuntimeException ignored) {
            }
            intro.begin(introPlayer == null ? null : new AudioClock(external ? INTRO_PREROLL_MS : 0));
        }
    };

    /**
     * The intro's clock follows the music as it actually leaves the speaker (Android's playback timestamp
     * includes the output's delay, ~150–250 ms on Bluetooth), so the hit at 6 s lands on the flare.
     * Falls back to the wall clock on firmware that doesn't report timestamps.
     */
    private final class AudioClock implements IntroView.Clock {
        private final float lead;           // lead-in silence still to come, in seconds
        private final long begun = SystemClock.uptimeMillis();
        private int mode;                   // 0 = deciding, 1 = audio timestamps, 2 = wall clock
        private float lastMedia, last = -1f;
        private long lastAt;

        AudioClock(int leadMs) {
            lead = leadMs / 1000f;
        }

        @Override
        public float seconds() {
            long now = SystemClock.uptimeMillis();
            float t;
            if (mode != 2) {
                float media = mediaSeconds();
                if (media >= 0) {
                    mode = 1;
                    lastMedia = media;
                    lastAt = now;
                    t = media - INTRO_PREROLL_MS / 1000f;
                } else if (mode == 1) {         // player finished or was released: keep counting from there
                    t = lastMedia + (now - lastAt) / 1000f - INTRO_PREROLL_MS / 1000f;
                } else if (now - begun > 900 + lead * 1000) {
                    mode = 2;                   // no timestamps on this firmware
                    t = 0;
                    lastAt = now;
                } else {
                    return -1f;                 // still waiting for the first sound to come out
                }
            } else {
                t = (now - lastAt) / 1000f;
            }
            if (t < last) t = last;             // never run backwards
            last = t;
            return t;
        }

        private float mediaSeconds() {
            MediaPlayer mp = introPlayer;
            if (mp == null) return -1f;
            try {
                android.media.MediaTimestamp ts = mp.getTimestamp();
                if (ts == null || ts.getMediaClockRate() <= 0f) return -1f;
                long us = ts.getAnchorMediaTimeUs()
                        + (long) ((System.nanoTime() - ts.getAnchorSytemNanoTime()) / 1000L * ts.getMediaClockRate());
                return us / 1e6f;
            } catch (RuntimeException e) {
                return -1f;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) maybeStartIntro();
    }

    private void finishIntro() {
        if (intro == null) return;
        root.removeView(intro);
        intro = null;
        introStarted = false;
        main.removeCallbacks(startIntroNow);
        fadeOutIntroMusic();
        if (aurora != null) aurora.setRunning(store.getBool(S_AMBIENT, true) && motionOn());
        if (pendingEntrance != null) {
            playEntrance(pendingEntrance);
            pendingEntrance = null;
        }
        View first = content == null ? null : findFirstCard(content);
        if (first != null) first.requestFocusFromTouch();
    }

    private void fadeOutIntroMusic() {
        final MediaPlayer mp = introPlayer;
        if (mp == null) return;
        try {
            if (!mp.isPlaying()) {
                releaseIntroPlayer();
                return;
            }
        } catch (RuntimeException e) {
            releaseIntroPlayer();
            return;
        }
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(500);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                float v = (Float) va.getAnimatedValue();
                try {
                    mp.setVolume(v, v);
                } catch (RuntimeException ignored) {
                }
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (introPlayer == mp) releaseIntroPlayer();
            }
        });
        a.start();
    }

    private void releaseIntroPlayer() {
        if (introPlayer == null) return;
        try {
            introPlayer.stop();
        } catch (RuntimeException ignored) {
        }
        introPlayer.release();
        introPlayer = null;
    }

    // ============================================================ studio & lab

    private void openSoundStudio() {
        try {
            startActivity(new Intent(this, SoundActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast("Couldn't open Sound Studio");
        }
    }

    /** The Cast screen; {@code mode} null = the "how to connect" page. */
    private void openCast(String mode) {
        if (remote == null) startRemote(); // the laptop and the phone send over the remote server
        String pin = remotePin();
        String share = (secureRemote != null ? secureAddress() : remoteAddress()) + "/share#pin=" + pin;
        CastActivity.open(this, mode, share, phoneLink());
        overridePendingTransition(android.R.anim.fade_in, 0);
    }

    /** Opens the projector's own mirroring receiver, so a phone can mirror with Cast / Smart View / AirPlay. */
    private String[] openMirror() {
        MirrorApps.App app = MirrorApps.best(this);
        if (app == null) {
            openCast(null);
            return ok("No mirroring app found on this projector. Here are the other ways to share");
        }
        try {
            startActivity(app.launch);
            return ok("Opening " + app.label + ". Now mirror from your phone");
        } catch (RuntimeException e) {
            openCast(null);
            return fail("Couldn't open " + app.label);
        }
    }

    private long castOpenedAt;

    /** Something was sent from a laptop or the phone: bring the Cast screen up (at most every few seconds). */
    private void castIncoming(final String mode) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (CastActivity.isShowing() || SystemClock.elapsedRealtime() - castOpenedAt < 3000) return;
                castOpenedAt = SystemClock.elapsedRealtime();
                if (intro != null) finishIntro();
                openCast(mode);
            }
        });
    }

    // ---------------------------------------------------------------- Lumen Window

    private long lastInputAt = SystemClock.elapsedRealtime();

    /** Minutes of no buttons on the home screen before the window view starts (0 = never). */
    private int windowIdleMinutes() {
        return WindowView.prefs(this).getInt("idle", 10);
    }

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            int mins = windowIdleMinutes();
            boolean quiet = intro == null && (voice == null || !voice.isShowing()) && resumed;
            if (mins > 0 && quiet && SystemClock.elapsedRealtime() - lastInputAt >= mins * 60000L
                    && !WindowView.library(HomeActivity.this).clips().isEmpty()) {
                openWindow(null);
                return;
            }
            main.postDelayed(this, 30000);
        }
    };

    /** A video from the phone that becomes a Lumen Window scene (kept, plays offline). */
    private String[] receiveWindowVideo(String name, String type, long length, InputStream in) throws java.io.IOException {
        SceneLibrary lib = WindowView.library(this);
        if (MediaInbox.kindOf(name, type) != MediaInbox.VIDEO || length > 400L * 1024 * 1024) {
            byte[] buf = new byte[65536];
            long left = length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                left -= n;
            }
            return fail(length > 400L * 1024 * 1024 ? "That video is too big for a scene (400 MB max)" : "Scenes have to be videos");
        }
        if (lib.dir().getUsableSpace() < length + 300L * 1024 * 1024) return fail("Not enough space on the projector");
        String safe = MediaInbox.safeName(name);
        String file = "own-" + System.currentTimeMillis() + "-" + safe;
        java.io.File part = new java.io.File(lib.dir(), file + ".part");
        java.io.OutputStream out = new java.io.FileOutputStream(part);
        long done = 0;
        try {
            byte[] buf = new byte[65536];
            while (done < length) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, length - done));
                if (n < 0) break;
                out.write(buf, 0, n);
                done += n;
            }
        } finally {
            out.close();
        }
        if (done < length || !part.renameTo(new java.io.File(lib.dir(), file))) {
            part.delete();
            return fail("The transfer stopped before the end");
        }
        String title = safe.replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ');
        lib.addOwn(file, title.length() > 0 ? title : "Your video");
        return ok("Added “" + title + "” to Lumen Window");
    }

    private void openWindow(String scene) {
        WindowActivity.open(this, scene);
        overridePendingTransition(android.R.anim.fade_in, 0);
    }

    private String pixabayKey() {
        String k = WindowView.prefs(this).getString("pix_key", "").trim();
        return k.length() > 0 ? k : keyService();
    }

    private volatile String windowStatus = "";

    /** Downloads the scenes (once; {@code fresh} = new clips for all ten) on a background thread. */
    private String[] downloadScenes(final boolean fresh) {
        final String key = pixabayKey();
        if (key.length() == 0) return fail("Add a free Pixabay key first (phone remote: More → Window)");
        final SceneLibrary lib = WindowView.library(this);
        if (lib.busy()) return ok(lib.status());
        windowStatus = "Starting download…";
        new Thread(new Runnable() {
            @Override
            public void run() {
                lib.downloadAll(key, fresh, new SceneLibrary.Progress() {
                    @Override
                    public void onProgress(String text) {
                        windowStatus = text;
                    }
                });
                windowStatus = lib.status();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("Lumen Window: " + windowStatus);
                    }
                });
            }
        }, "lumen-scenes").start();
        return ok(fresh ? "Downloading new scenes…" : "Downloading the scenes. They'll play offline after this");
    }

    private String windowJson() {
        SceneLibrary lib = WindowView.library(this);
        String status = lib.busy() ? windowStatus : (windowStatus.length() > 0 ? windowStatus
                : lib.clips().isEmpty() ? "No scenes yet" : lib.clips().size() + " scenes ready · they play offline");
        return "{\"scenes\":" + lib.clips().size() + ",\"busy\":" + lib.busy() + ",\"status\":" + RemoteServer.json(status)
                + ",\"keySet\":" + (pixabayKey().length() > 0)
                + ",\"minutes\":" + WindowView.minutes(this) + ",\"idle\":" + windowIdleMinutes() + ",\"list\":" + lib.json() + "}";
    }

    private void openScreen() {
        startActivity(new Intent(this, ScreenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        overridePendingTransition(android.R.anim.fade_in, 0);
    }

    private void openPictureLab(int pattern) {
        try {
            startActivity(new Intent(this, PictureLabActivity.class)
                    .putExtra(PictureLabActivity.EXTRA_PATTERN, pattern)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast("Couldn't open Picture Lab");
        }
    }

    /** Adds a hint when the firmware blocks system-wide effects, so the reply never over-promises. */
    private String[] soundReply(String msg) {
        String st = sound.status();
        if (st.startsWith("Applied")) return ok(msg);
        return ok(msg + ". Note: " + st);
    }

    private void initUiSounds() {
        try {
            uiSounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            sTick = uiSounds.load(this, R.raw.tick, 1);
            sSelect = uiSounds.load(this, R.raw.select, 1);
        } catch (RuntimeException e) {
            uiSounds = null;
        }
    }

    /** Soft tvOS-style feedback: a tick when focus moves, a "thock" when an app opens. */
    private void playUi(boolean select) {
        if (uiSounds == null || !store.getBool(S_UI_SOUNDS, true)) return;
        long now = SystemClock.uptimeMillis();
        if (!select && now - lastTick < 45) return; // holding the D-pad shouldn't buzz
        lastTick = now;
        try {
            uiSounds.play(select ? sSelect : sTick, select ? 0.55f : 0.32f, select ? 0.55f : 0.32f, 1, 0, 1f);
        } catch (RuntimeException ignored) {
        }
    }

    /** Runs work on the UI thread and waits (max 5 s) — used by the phone remote's server threads. */
    private String onMain(final java.util.concurrent.Callable<String> work, String timeoutJson) {
        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = work.call();
                } catch (Exception e) {
                    out[0] = null;
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) return timeoutJson;
        } catch (InterruptedException e) {
            return timeoutJson;
        }
        return out[0] == null ? timeoutJson : out[0];
    }

    // ============================================================ phone remote

    private String remotePin() {
        String p = store.getString("remote_pin", "");
        if (p.length() != 4) p = newPin();
        return p;
    }

    private String newPin() {
        String p = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
        store.setString("remote_pin", p);
        return p;
    }

    // ---------------------------------------------------------------- updates

    private Updater updater;

    private final Runnable quietUpdateCheck = new Runnable() {
        @Override
        public void run() {
            if (updater != null) updater.checkAtStartupOrDue();
        }
    };

    /**
     * No API keys ship with Lumen. Without your own keys, Lumen goes through its key service (a small proxy
     * that holds the keys privately); its address comes with the update channel.
     */
    private String keyService() {
        return ApiProxy.key(Updater.get(this).keyProxy());
    }

    /**
     * Lumen 7.0 private builds copied their built-in keys into the settings. Those keys are retired:
     * clear them once, so the key service takes over.
     */
    private void dropAdoptedKeys() {
        if (!store.getBool("keys_adopted", false)) return;
        store.setString("yt_key", "");
        store.setString("tmdb_key", "");
        WindowView.prefs(this).edit().remove("pix_key").apply();
        store.setBool("keys_adopted", false);
    }

    private void initUpdates() {
        updater = Updater.get(this);
        final String notes = updater.justUpdatedNotes();
        if (notes != null) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    toast("Updated to Lumen " + updater.currentName() + (notes.length() > 0 ? ": " + firstLine(notes) : ""));
                }
            }, intro != null ? 13000 : 2500);
        }
        final String heat = HeatGuard.get(this).bootNotice();
        if (heat != null) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing()) return;
                    promptOpen = true;
                    new AlertDialog.Builder(HomeActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Your projector overheated")
                            .setMessage(heat)
                            .setPositiveButton("OK", null)
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface d) {
                                    promptOpen = false;
                                }
                            })
                            .show();
                }
            }, intro != null ? 12500 : 2000);
        }
        updater.addListener(new Updater.Listener() {
            @Override
            public void onUpdateState() {
                maybePromptUpdate();
                String ks = keyService();
                if (!ks.equals(lastKeyService)) {          // the key service appeared or moved
                    boolean first = lastKeyService != null;
                    lastKeyService = ks;
                    if (first && ks.length() > 0 && trendingOn()) refreshTrending(false);
                }
            }
        });
    }

    private String lastKeyService;

    /** True while an automatic prompt (update, overheating) is on screen. */
    private boolean promptOpen;

    /**
     * A new Lumen is out: ask right away on the home screen (not during the intro, not over other apps).
     * "Later" waits a day before asking again about the same version.
     */
    private void maybePromptUpdate() {
        UpdateFeed.Release r = updater == null ? null : updater.available();
        if (r == null || !resumed || intro != null || promptOpen || updater.busy() || isFinishing()) return;
        if (r.versionName.equals(store.getString("update_snooze_name", ""))
                && System.currentTimeMillis() < store.getLong("update_snooze_until", 0)) return;
        promptOpen = true;
        showUpdates(true);
    }

    private void snoozeUpdate(UpdateFeed.Release r) {
        if (r == null) return;
        store.setString("update_snooze_name", r.versionName);
        store.setLong("update_snooze_until", System.currentTimeMillis() + 24L * 3600 * 1000);
    }

    private static String firstLine(String s) {
        String l = s.replace("\r", "").trim().split("\n")[0].replaceFirst("^[-*#•\\s]+", "");
        return l.length() > 90 ? l.substring(0, 90) + "…" : l;
    }

    private void showUpdates() {
        showUpdates(false);
    }

    private void showUpdates(final boolean prompt) {
        final UpdateFeed.Release r = updater.available();
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        b.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                promptOpen = false;
            }
        });
        if (r != null && !updater.canInstall()) {
            b.setTitle("Allow Lumen to install updates")
                    .setMessage("Android asks once before an app can install updates. Choose Allow, then come back here.")
                    .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            updater.requestInstallPermission(HomeActivity.this);
                        }
                    })
                    .setNegativeButton("Later", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            snoozeUpdate(r);
                        }
                    }).show();
            return;
        }
        if (r != null) {
            b.setTitle((prompt ? "Update ready: " : "") + "Lumen " + r.versionName + " is available")
                    .setMessage((r.notes.length() > 0 ? r.notes + "\n\n" : "") + "You have Lumen " + updater.currentName()
                            + ". Lumen downloads the update, checks it, and Android asks you to press Install. "
                            + "Your apps, settings and keys stay as they are.")
                    .setPositiveButton("Update now", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            toast(updater.installAvailable());
                        }
                    })
                    .setNegativeButton("Later", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            snoozeUpdate(r);
                        }
                    }).show();
            return;
        }
        if (prompt) {
            promptOpen = false;
            return;
        }
        final boolean auto = updater.auto();
        final boolean hasSource = updater.source().length() > 0;
        String[] items = {
                "Check now",
                "Automatic checks (at startup and every 6 hours):  " + onOff(auto),
                "Update source:  " + UpdateFeed.describeSource(updater.source()),
                "Install an update from your phone"};
        b.setTitle("Lumen " + updater.currentName() + "  ·  " + updater.status())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            if (!hasSource) showUpdateHelp();
                            else {
                                updater.check(true);
                                toast("Checking for updates…");
                            }
                        } else if (w == 1) {
                            updater.setAuto(!auto);
                            showUpdates();
                        } else {
                            showUpdateHelp();
                        }
                    }
                }).show();
    }

    private void showUpdateHelp() {
        String phone = store.getBool(S_REMOTE, true) ? remoteAddress() : "the phone remote (turn it on in Lumen settings)";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Updating Lumen")
                .setMessage("Lumen is open source and updates itself from " + LumenConfig.PROJECT_URL + ". "
                        + "It checks at startup and every 6 hours, and asks you on the home screen when a new version is out.\n\n"
                        + "To follow a different source: on your phone open " + phone + ", go to the Updates card and enter "
                        + "a GitHub repo like yourname/lumen or a link to an update.json.\n\n"
                        + "From your phone: on the same Updates card, tap \u201cSend an APK\u201d and pick Lumen.apk from your phone. "
                        + "Then press Install on the projector.\n\n"
                        + "Only Lumen builds signed with the same key are accepted.")
                .setPositiveButton("OK", null).show();
    }

    /** A button pressed on the phone remote. Main thread. */
    private String[] remoteKey(String k) {
        boolean cast = Front.get() instanceof CastActivity;   // photos/videos sent from the phone
        if ("playpause".equals(k)) { if (cast) Front.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); else media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return ok(""); }
        if ("next".equals(k)) { if (cast) Front.press(KeyEvent.KEYCODE_MEDIA_NEXT); else media(KeyEvent.KEYCODE_MEDIA_NEXT); return ok(""); }
        if ("prev".equals(k)) { if (cast) Front.press(KeyEvent.KEYCODE_MEDIA_PREVIOUS); else media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return ok(""); }
        if ("volup".equals(k)) return volumeStep(AudioManager.ADJUST_RAISE);
        if ("voldown".equals(k)) return volumeStep(AudioManager.ADJUST_LOWER);
        if ("mute".equals(k)) {
            audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
            return ok(audio().isStreamMute(AudioManager.STREAM_MUSIC) ? "Muted" : "Sound on");
        }
        if ("home".equals(k)) {
            goHomeFromAnywhere();
            return ok("");
        }
        int code = "up".equals(k) ? KeyEvent.KEYCODE_DPAD_UP : "down".equals(k) ? KeyEvent.KEYCODE_DPAD_DOWN
                : "left".equals(k) ? KeyEvent.KEYCODE_DPAD_LEFT : "right".equals(k) ? KeyEvent.KEYCODE_DPAD_RIGHT
                : "ok".equals(k) ? KeyEvent.KEYCODE_DPAD_CENTER : "back".equals(k) ? KeyEvent.KEYCODE_BACK
                : "menu".equals(k) ? KeyEvent.KEYCODE_MENU : 0;
        if (code == 0) return fail("Unknown button");
        if (Front.press(code)) return ok("");
        return fail("Arrows, OK and Back work on Lumen's screens. In other apps use the projector's remote, or Home to come back");
    }

    private String[] onMainArr(final java.util.concurrent.Callable<String[]> c) {
        final String[][] out = new String[1][];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = c.call();
                } catch (Exception e) {
                    out[0] = fail("Something went wrong");
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            return null;
        }
        return out[0];
    }

    private void startRemote() {
        if (remote != null) return;
        byte[] page;
        try {
            InputStream in = getAssets().open("remote.html");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            page = b.toByteArray();
        } catch (java.io.IOException e) {
            page = "<h1>Lumen remote</h1>".getBytes();
        }
        final RemoteServer.Bridge bridge = new RemoteServer.Bridge() {
            @Override
            public String[] command(final String text) {
                final String[][] out = new String[1][];
                final CountDownLatch latch = new CountDownLatch(1);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            out[0] = handleUtterance(Collections.singletonList(text), true);
                        } catch (RuntimeException e) {
                            out[0] = fail("Something went wrong");
                        } finally {
                            latch.countDown();
                        }
                    }
                });
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) return fail("The projector is busy, try again");
                } catch (InterruptedException e) {
                    return fail("Interrupted");
                }
                // "where can I watch …": the phone waits a little for the real answer instead of "Looking…"
                Pending p = takePending();
                if (p != null) {
                    String[] answer = p.await(9000);
                    if (answer != null) return answer;
                }
                return out[0];
            }

            @Override
            public String soundJson() {
                return onMain(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        return sound.json();
                    }
                }, "{}");
            }

            @Override
            public String soundSet(final String form) {
                return onMain(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        Map<String, String> f = RemoteServer.form(form);
                        for (Map.Entry<String, String> e : f.entrySet()) {
                            String k = e.getKey(), v = e.getValue();
                            if ("preset".equals(k)) sound.applyPreset(v);
                            else if ("ab".equals(k)) sound.setBypass("true".equals(v));
                            else if ("output".equals(k)) sound.setOutputMode(v);
                            else if ("reset".equals(k)) sound.applyPreset(sound.preset);
                            else {
                                try {
                                    sound.set(k, Integer.parseInt(v));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        return sound.json();
                    }
                }, "{}");
            }

            @Override
            public String settingsJson() {
                return onMain(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        return settingsState();
                    }
                }, "{}");
            }

            @Override
            public String settingsSet(final String form) {
                return onMain(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        Map<String, String> f = RemoteServer.form(form);
                        if (f.containsKey("ytkey")) store.setString("yt_key", f.get("ytkey").trim());
                        if (f.containsKey("region")) {
                            String r = f.get("region").trim().toUpperCase(Locale.ROOT);
                            if (r.matches("[A-Z]{2}")) store.setString("yt_region", r);
                            else if (r.length() == 0 || "AUTO".equals(r)) store.setString("yt_region", "");
                        }
                        if (f.containsKey("tmdbkey")) store.setString("tmdb_key", f.get("tmdbkey").trim());
                        if (f.containsKey("yton")) store.setBool("yt_on", "true".equals(f.get("yton")));
                        if (f.containsKey("nfon")) store.setBool("nf_on", "true".equals(f.get("nfon")));
                        if (f.containsKey("pvon")) store.setBool("pv_on", "true".equals(f.get("pvon")));
                        if (f.containsKey("allon")) store.setBool("trending_all", "true".equals(f.get("allon")));
                        if (f.containsKey("screen")) {
                            ScreenEngine.apply(HomeActivity.this, f.get("screen"));
                            if (f.size() == 1) return settingsState(); // no need to refetch trending
                        }
                        if (f.containsKey("detail") || f.containsKey("autopic") || f.containsKey("compare") || f.containsKey("cwon")) {
                            try {
                                if (f.containsKey("detail")) ScreenEngine.setDetail(HomeActivity.this, Integer.parseInt(f.get("detail")));
                            } catch (NumberFormatException ignored) {
                            }
                            if (f.containsKey("autopic")) ScreenEngine.setAuto(HomeActivity.this, "true".equals(f.get("autopic")));
                            if (f.containsKey("compare")) ScreenEngine.compare(HomeActivity.this, 5);
                            if (f.containsKey("cwon")) {
                                ContinueStore.setEnabled(HomeActivity.this, "true".equals(f.get("cwon")));
                                refreshContinue();
                            }
                            return settingsState();
                        }
                        boolean windowOnly = false;
                        android.content.SharedPreferences.Editor we = WindowView.prefs(HomeActivity.this).edit();
                        if (f.containsKey("pixkey")) { we.putString("pix_key", f.get("pixkey").trim()); windowOnly = true; }
                        try {
                            if (f.containsKey("winmins")) { we.putInt("minutes", Math.max(1, Math.min(30, Integer.parseInt(f.get("winmins"))))); windowOnly = true; }
                            if (f.containsKey("winidle")) { we.putInt("idle", Math.max(0, Math.min(120, Integer.parseInt(f.get("winidle"))))); windowOnly = true; }
                        } catch (NumberFormatException ignored) {
                        }
                        we.apply();
                        if (f.containsKey("windl")) { downloadScenes("fresh".equals(f.get("windl"))); windowOnly = true; }
                        if (f.containsKey("winremove")) { WindowView.library(HomeActivity.this).remove(f.get("winremove")); windowOnly = true; }
                        if (windowOnly) return settingsState();
                        for (Shelf sh : shelves) sh.error = "";
                        refreshTrending(true);
                        fillShelves();
                        return settingsState();
                    }
                }, "{}");
            }

            @Override
            public String frame(String session, byte[] jpeg) {
                boolean ok = ShareHub.I.offer(session, jpeg);
                if (ok && !CastActivity.isShowing()) castIncoming(CastActivity.MODE_SCREEN);
                return ShareHub.I.json(ok);
            }

            @Override
            public void shareStopped(String session) {
                ShareHub.I.stopBySender(session);
            }

            @Override
            public String[] receiveFile(String name, String type, long length, InputStream in, String dest) throws java.io.IOException {
                if ("window".equals(dest)) return receiveWindowVideo(name, type, length, in);
                if (!CastActivity.isShowing() && MediaInbox.kindOf(name, type) >= 0) castIncoming(CastActivity.MODE_MEDIA);
                return MediaInbox.I.receive(name, type, length, in);
            }

            @Override
            public String[] key(final String k) {
                String[] r = onMainArr(new java.util.concurrent.Callable<String[]>() {
                    @Override
                    public String[] call() {
                        return remoteKey(k);
                    }
                });
                return r == null ? fail("The projector is busy, try again") : r;
            }

            @Override
            public String[] text(final String t) {
                String[] r = onMainArr(new java.util.concurrent.Callable<String[]>() {
                    @Override
                    public String[] call() {
                        String clean = t.replaceAll("[\\r\\n]+", " ");
                        if (clean.trim().length() == 0) return fail("Nothing to type");
                        if (Front.type(clean)) return ok("Typed on the projector");
                        // no text box open on a Lumen screen: treat it as a command or a search
                        return handleUtterance(Collections.singletonList(clean), true);
                    }
                });
                return r == null ? fail("The projector is busy, try again") : r;
            }

            @Override
            public String updateJson() {
                return updater.json();
            }

            @Override
            public String updateSet(final String form) {
                Map<String, String> f = RemoteServer.form(form);
                if (f.containsKey("source")) updater.setSource(f.get("source"));
                if (f.containsKey("auto")) updater.setAuto("true".equals(f.get("auto")));
                if (f.containsKey("check")) updater.check(true);
                if (f.containsKey("install")) {
                    final String msg = updater.installAvailable();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(msg);
                        }
                    });
                }
                if (f.containsKey("source") && updater.source().length() > 0) updater.check(true);
                return updater.json();
            }

            @Override
            public String[] installApk(byte[] apk) {
                final String[] r = updater.installUpload(apk);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(r[1]);
                    }
                });
                return r;
            }

            @Override
            public String appsJson() {
                List<AppInfo> snapshot = allApps;
                Set<String> hidden = store.hidden();
                StringBuilder sb = new StringBuilder("[");
                for (AppInfo a : snapshot) {
                    if (hidden.contains(a.pkg)) continue;
                    if (sb.length() > 1) sb.append(',');
                    sb.append(RemoteServer.json(a.label));
                }
                return sb.append(']').toString();
            }
        };
        final byte[] sharePage = asset("share.html", "<h1>Lumen screen share</h1>");
        remote = new RemoteServer(REMOTE_PORT, remotePin(), page, bridge);
        remote.addPage("/share", sharePage);
        remote.start();

        // HTTPS twin: same page and API, but phone browsers allow the microphone on it.
        // The certificate is made once per projector (RSA-2048 takes a few seconds on this CPU).
        final byte[] securePage = page;
        final String pin = remotePin();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    javax.net.ssl.SSLContext ctx = CertUtil.serverContext(
                            new java.io.File(getFilesDir(), "remote-tls.ks"), "lumen-remote".toCharArray());
                    final RemoteServer s = new RemoteServer(SECURE_PORT, pin, securePage, bridge,
                            ctx.getServerSocketFactory());
                    s.addPage("/share", sharePage);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (remote == null) return; // turned off meanwhile
                            secureRemote = s;
                            s.start();
                        }
                    });
                } catch (Exception ignored) {
                    // phone still works over plain HTTP with the keyboard mic
                }
            }
        }, "lumen-tls").start();
    }

    private byte[] asset(String name, String fallback) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            return b.toByteArray();
        } catch (java.io.IOException e) {
            return fallback.getBytes();
        }
    }

    private void stopRemote() {
        if (remote != null) remote.shutdown();
        if (secureRemote != null) secureRemote.shutdown();
        remote = null;
        secureRemote = null;
    }

    /** First private IPv4 address on Wi-Fi/Ethernet, as the URL to type on the phone. */
    private String remoteAddress() {
        return address("http", REMOTE_PORT);
    }

    private String secureAddress() {
        return address("https", SECURE_PORT);
    }

    private String address(String scheme, int port) {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return scheme + "://" + a.getHostAddress() + ":" + port;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return scheme + "://<projector-ip>:" + port;
    }

    /** What the QR code opens: the secure page (mic allowed) with the PIN pre-filled. */
    private String phoneLink() {
        String host = secureAddress();
        return (secureRemote != null ? host : remoteAddress()) + "/#pin=" + remotePin();
    }

    private void showRemoteInfo() {
        final boolean on = store.getBool(S_REMOTE, true);
        String msg = on
                ? "1.  Connect your phone to the same Wi-Fi as the projector.\n\n"
                + "2.  Open this address in your phone's browser:\n\n        " + remoteAddress() + "\n\n"
                + "3.  Enter the PIN:   " + remotePin() + "\n\n"
                + "Want the big mic button? Open the secure page instead:\n\n        " + secureAddress() + "\n\n"
                + "Your browser warns that the certificate is self-made; tap Advanced → Proceed (it's your projector). "
                + "Media, volume and Sound Studio controls work while you watch."
                : "Phone remote is off. Turn it on to control Lumen by voice from your phone.";
        View qrPanel = null;
        if (on) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setPadding(dp(24), dp(16), dp(24), 0);
            box.setGravity(Gravity.CENTER_VERTICAL);
            QrView q = new QrView(this);
            q.setText(phoneLink());
            box.addView(q, new LinearLayout.LayoutParams(dp(170), dp(170)));
            TextView tv = text("Scan with your phone's camera.\nIt opens the remote with the PIN already filled in.\n\n"
                    + "First time only: the browser warns about the certificate; tap Advanced → Proceed.", 14, C_SUB);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = dp(18);
            box.addView(tv, tlp);
            qrPanel = box;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Phone remote")
                .setView(qrPanel)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNegativeButton(on ? "Turn off" : "Turn on", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        store.setBool(S_REMOTE, !on);
                        if (on) stopRemote();
                        else startRemote();
                        showRemoteInfo();
                    }
                })
                .setNeutralButton(on ? "New PIN" : null, on ? new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        newPin();
                        stopRemote();
                        startRemote();
                        showRemoteInfo();
                    }
                } : null)
                .show();
    }

    private void askOverlayPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("One-time permission")
                    .setMessage("This projector hides that setting. From a computer, run:\n\n"
                            + "adb shell appops set com.lumen.launcher SYSTEM_ALERT_WINDOW allow\n\n"
                            + "Then “open Kodi” from your phone works even while a video is playing.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    // ================================================================== helpers

    private TextView chip() {
        TextView t = text("", 13, 0xE6FFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(Theme.CERULEAN_GLASS);
        g.setStroke(dp(1), Theme.GLASS_STROKE);
        t.setBackground(g);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        return t;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private void rounded(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static String onOff(boolean b) {
        return b ? "On" : "Off";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
