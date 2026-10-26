package com.lumen.launcher;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import java.util.Locale;

/**
 * Lumen Imaging: picture profiles, the Detail engine and Auto Picture.
 *
 * With the projector's picture processor reachable (PictureEngine), a profile sets the real
 * picture: mode, contrast, colour, sharpness, gamma, dynamic contrast (DCI), black extension,
 * colour temperature and noise reduction, and the Detail engine drives the hardware edge
 * enhancer (SSR peaking) and colour-edge sharpening (CTI). Every value Lumen touches is first
 * saved, so Reset and Compare can always bring back the projector's own picture.
 *
 * Without the processor (other firmware), profiles fall back to what any app can do: screen
 * brightness and Android's warmth filter, plus the values to set in the projector's menu.
 */
final class ScreenEngine {

    static final String[] PRESETS = {"standard", "cinema", "hdr", "vivid", "game", "bright"};
    static final String[] NAMES = {"Natural", "Cinema", "HDR-style", "Vivid", "Game", "Bright room"};
    static final String[] BLURBS = {"Everyday: clean, true colour with a little pop",
            "Films in a dark room: most accurate, gamma 2.4, warm",
            "Films: deeper blacks, brighter mid-tones, punchy contrast",
            "YouTube and animation: bold colour, crisp edges",
            "Games: fastest response, no noise filtering",
            "Daytime with lights on: lifted shadows, brighter"};

    /** Vendor picture mode each profile builds on. */
    private static final String[] MODE = {"standard", "cinema", "hdr", "vivid", "game", "standard"};
    /**
     * Per profile: brightness, contrast, colour, tint, sharpness (0–100); TNR, SNR, DCI, black
     * extension (0 off … 3 high); gamma index (0=1.8 1=2.0 2=2.1 3=2.2 4=2.4); colour temp
     * (0 standard, 1 cool, 2 warm). Stays inside the ranges the vendor's own modes use.
     */
    private static final int[][] PQ = {
            {50, 54, 58, 50, 45, 1, 1, 2, 1, 3, 0},
            {49, 50, 52, 50, 35, 2, 1, 1, 1, 4, 2},
            {50, 57, 60, 50, 48, 1, 1, 3, 1, 2, 0},
            {52, 58, 62, 50, 52, 1, 1, 3, 1, 3, 1},
            {50, 54, 56, 50, 45, 0, 0, 2, 1, 3, 0},
            {56, 60, 58, 50, 50, 1, 1, 3, 0, 1, 1}};
    private static final int[] PQ_IDS = {PictureEngine.BRIGHTNESS, PictureEngine.CONTRAST, PictureEngine.COLOR,
            PictureEngine.TINT, PictureEngine.SHARPNESS, PictureEngine.TNR, PictureEngine.SNR, PictureEngine.DCI,
            PictureEngine.BLACK_EXT, PictureEngine.GAMMA, PictureEngine.COLOR_TEMP};

    /** Screen brightness (0–255) per profile when the processor isn't reachable; -1 = the user's own. */
    private static final int[] BRIGHTNESS = {-1, 190, 255, 255, -1, 255};
    /** Night-light warmth (kelvin) per profile in fallback mode; 0 = off. */
    private static final int[] WARMTH_K = {0, 4082, 0, 0, 0, 0};

    // ------------------------------------------------------------------ Detail engine
    static final String[] DETAIL_NAMES = {"Soft", "Natural", "Crisp", "Detail+", "Max"};
    static final String[] DETAIL_BLURBS = {"Smooth, for old or low-quality video",
            "The projector's own tuning", "Sharper edges, no halos: the everyday pick",
            "More texture in faces, hair and fabric", "Strongest; best for clean HD sources"};
    private static final int[] SSR_ITEMS = {PictureEngine.SSR_POS_GAIN, PictureEngine.SSR_NEG_GAIN,
            PictureEngine.PEAK_H2, PictureEngine.PEAK_H4, PictureEngine.PEAK_H6, PictureEngine.PEAK_H8,
            PictureEngine.PEAK_V2, PictureEngine.PEAK_V4, PictureEngine.PEAK_V6, PictureEngine.PEAK_S2, PictureEngine.PEAK_S4};
    /** Edge-enhancer values per level, within the vendor's own range (gains 80–150, bands 4–12). */
    private static final int[][] SSR = {
            {64, 64, 6, 6, 3, 3, 6, 6, 3, 4, 4},
            {80, 80, 8, 8, 4, 4, 8, 8, 4, 6, 6},
            {104, 104, 10, 9, 6, 6, 10, 9, 5, 8, 6},
            {128, 128, 12, 10, 8, 8, 12, 10, 6, 10, 6},
            {150, 150, 12, 10, 12, 12, 12, 10, 6, 10, 6}};
    private static final int[] CTI_ITEMS = {PictureEngine.CTI_PRE_GAIN, PictureEngine.CTI_PRE_STEP,
            PictureEngine.CTI_POST_GAIN, PictureEngine.CTI_POST_STEP};
    /** Colour-edge sharpening per level (vendor default: 6/2/8/1). */
    private static final int[][] CTI = {{4, 2, 6, 1}, {6, 2, 8, 1}, {7, 2, 9, 1}, {8, 2, 10, 1}, {8, 2, 10, 1}};
    private static final int[] ENABLES = {PictureEngine.EN_SSR, PictureEngine.EN_PEAKING,
            PictureEngine.EN_PRE_DCTI, PictureEngine.EN_POST_DCTI};

    // ------------------------------------------------------------------ Auto Picture
    private static final String[] FILM_APPS = {"com.netflix", "com.amazon.amazonvideo", "com.amazon.avod", "com.disney",
            "in.startv.hotstar", "com.apple.atve", "net.mbc.shahid", "com.osn", "com.plexapp", "org.xbmc.kodi",
            "org.videolan.vlc", "com.mxtech", "com.jio.media", "com.graymatrix.did", "com.sonyliv", "com.hbo",
            "com.wbd", "com.mubi", "com.crunchyroll", "com.starz", "com.starzplay", "com.tod", "tv.twitch",
            "com.google.android.videos", "com.google.android.apps.mediashell", "is.xyz.mpv", "com.zte", "com.softwinner.TvdVideo",
            "com.softwinner.tvdvideo", "com.android.gallery3d"};
    private static final String[] YT_APPS = {"com.google.android.youtube", "com.teamsmart.videomanager", "com.liskovsoft",
            "org.schabi.newpipe", "com.google.android.apps.youtube"};
    private static final String[] GAME_APPS = {"com.retroarch", "org.ppsspp", "org.dolphinemu", "com.explusalpha",
            "com.github.stenzek", "com.antutu", "com.gameloft", "com.nvidia", "com.valvesoftware", "com.microsoft.xcloud",
            "com.playstation", "com.steam"};

    private static HandlerThread thread;
    private static Handler worker;
    private static volatile int generation;
    private static volatile boolean engineUp;

    private ScreenEngine() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lumen_screen", Context.MODE_PRIVATE);
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            thread = new HandlerThread("lumen-picture");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    /** Connects to the picture processor in the background and re-applies the saved picture. */
    static void start(final Context c) {
        final Context app = c.getApplicationContext();
        worker().post(new Runnable() {
            @Override
            public void run() {
                engineUp = PictureEngine.available();
                if (engineUp) applyNow(app, active(app), detail(app));
            }
        });
    }

    static boolean engine() {
        return engineUp;
    }

    // ------------------------------------------------------------------ state

    /** The profile the user picked (used on the home screen and for apps without their own). */
    static String current(Context c) {
        String p = prefs(c).getString("preset", "standard");
        return index(p) >= 0 ? p : "standard";
    }

    /** The profile on screen right now (differs from current() while Auto Picture is at work). */
    static String active(Context c) {
        String p = prefs(c).getString("active", current(c));
        return index(p) >= 0 ? p : current(c);
    }

    static String name(String key) {
        int i = index(key);
        return i >= 0 ? NAMES[i] : NAMES[0];
    }

    static int index(String key) {
        if ("bright room".equals(key)) key = "bright";
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i].equals(key)) return i;
        return -1;
    }

    static int detail(Context c) {
        return Math.max(0, Math.min(4, prefs(c).getInt("detail", 2)));
    }

    static boolean auto(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    static void setAuto(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
        if (!on) switchTo(c, current(c));
    }

    static boolean canBrightness(Context c) {
        try {
            return Settings.System.canWrite(c);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean canWarmth(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ actions

    /** The user picks a profile. Returns a short summary for a toast or a voice reply. */
    static String apply(Context c, String key) {
        int i = index(key);
        if (i < 0) i = 0;
        prefs(c).edit().putString("preset", PRESETS[i]).apply();
        return switchTo(c, PRESETS[i]);
    }

    static String setDetail(Context c, int level) {
        level = Math.max(0, Math.min(4, level));
        prefs(c).edit().putInt("detail", level).apply();
        if (!engineUp) return "";
        final Context app = c.getApplicationContext();
        final int gen = ++generation, lv = level;
        final String profile = active(c);
        worker().post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) applyDetail(app, lv, index(profile));
            }
        });
        return "detail " + DETAIL_NAMES[level];
    }

    private static String switchTo(Context c, String key) {
        int i = index(key);
        prefs(c).edit().putString("active", PRESETS[i]).apply();
        if (engineUp) {
            final Context app = c.getApplicationContext();
            final int gen = ++generation;
            final String k = PRESETS[i];
            final int lv = detail(c);
            worker().post(new Runnable() {
                @Override
                public void run() {
                    if (gen == generation) applyNow(app, k, lv);
                }
            });
            return "contrast, colour, gamma and detail tuned";
        }
        return fallback(c, i);
    }

    /** Auto Picture: an app came to the front (launched from Lumen, or started playing). */
    static void onApp(Context c, String pkg) {
        if (pkg == null || !auto(c) || pkg.equals(c.getPackageName())) return;
        String want = profileFor(c, pkg);
        if (!want.equals(active(c))) switchTo(c, want);
    }

    /** Auto Picture: back on Lumen's home screen. */
    static void onHome(Context c) {
        if (auto(c) && !current(c).equals(active(c))) switchTo(c, current(c));
    }

    /** "auto" or a profile key the user chose for this app. */
    static String appChoice(Context c, String pkg) {
        String v = prefs(c).getString("app:" + pkg, "auto");
        return index(v) >= 0 ? v : "auto";
    }

    static void setAppChoice(Context c, String pkg, String key) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (key == null || "auto".equals(key)) e.remove("app:" + pkg);
        else e.putString("app:" + pkg, key);
        e.apply();
    }

    /** The profile Auto Picture uses for an app. */
    static String profileFor(Context c, String pkg) {
        String own = appChoice(c, pkg);
        if (!"auto".equals(own)) return own;
        String guess = category(c, pkg);
        return guess != null ? guess : current(c);
    }

    /** What Auto Picture would pick on its own, or null for "your everyday profile". */
    static String category(Context c, String pkg) {
        if (startsWith(pkg, YT_APPS)) return "vivid";
        if (startsWith(pkg, FILM_APPS)) return "hdr";
        if (startsWith(pkg, GAME_APPS)) return "game";
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return "game";
            // ApplicationInfo.category (API 26): 0 = game, 2 = video
            int cat = ApplicationInfo.class.getField("category").getInt(ai);
            if (cat == 0) return "game";
            if (cat == 2) return "hdr";
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean startsWith(String pkg, String[] prefixes) {
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String s : prefixes) if (p.startsWith(s.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /**
     * Compare: shows the projector's original picture for a few seconds, then Lumen's again.
     * Returns false when there is nothing saved to compare with yet.
     */
    static boolean compare(Context c, final int seconds) {
        if (!engineUp || !prefs(c).contains("base_mode")) return false;
        final Context app = c.getApplicationContext();
        final int gen = ++generation;
        worker().post(new Runnable() {
            @Override
            public void run() {
                restoreBaseline(app, false);
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException ignored) {
                }
                if (gen == generation) applyNow(app, active(app), detail(app));
            }
        });
        return true;
    }

    /** Puts every value Lumen changed back to the projector's own, and turns Auto Picture off. */
    static void resetAll(Context c) {
        final Context app = c.getApplicationContext();
        ++generation;
        prefs(c).edit().putBoolean("auto", false).putString("preset", "standard").putString("active", "standard")
                .putInt("detail", 1).apply();
        worker().post(new Runnable() {
            @Override
            public void run() {
                restoreBaseline(app, true);
            }
        });
    }

    // ------------------------------------------------------------------ the work (worker thread)

    private static void applyNow(Context c, String key, int level) {
        int i = Math.max(0, index(key));
        SharedPreferences sp = prefs(c);
        if (!sp.contains("base_mode")) {
            String m = PictureEngine.getMode();
            sp.edit().putString("base_mode", m.length() > 0 ? m : "standard").commit();
        }
        PictureEngine.setMode(MODE[i]);
        saveBaseline(sp, MODE[i]);
        for (int k = 0; k < PQ_IDS.length; k++) PictureEngine.set(PQ_IDS[k], PQ[i][k]);
        applyDetail(c, level, i);
    }

    private static void applyDetail(Context c, int level, int profile) {
        if (profile == index("game")) level = Math.min(level, 1); // edge processing adds a little lag
        SharedPreferences sp = prefs(c);
        for (int item : ENABLES) param(sp, PictureEngine.P_ENABLE, item, 1);
        for (int k = 0; k < SSR_ITEMS.length; k++) param(sp, PictureEngine.P_SSR, SSR_ITEMS[k], SSR[level][k]);
        for (int k = 0; k < CTI_ITEMS.length; k++) param(sp, PictureEngine.P_CTI, CTI_ITEMS[k], CTI[level][k]);
        // Strong sharpening also sharpens compression noise: keep the noise filter on with it.
        if (level >= 3 && profile >= 0 && PQ[profile][6] < 1) PictureEngine.set(PictureEngine.SNR, 1);
    }

    private static void param(SharedPreferences sp, int type, int item, int value) {
        String k = "basep:" + type + ":" + item;
        if (!sp.contains(k)) {
            int was = PictureEngine.getParam(type, item);
            if (was >= 0) sp.edit().putInt(k, was).commit();
        }
        PictureEngine.setParam(type, item, value);
    }

    private static void saveBaseline(SharedPreferences sp, String mode) {
        if (sp.contains("base:" + mode + ":done")) return;
        SharedPreferences.Editor e = sp.edit();
        for (int id : PictureEngine.ALL_PQ) {
            int v = PictureEngine.get(id);
            if (v >= 0) e.putInt("base:" + mode + ":" + id, v);
        }
        String modes = sp.getString("base_modes", "");
        e.putString("base_modes", modes.contains("," + mode + ",") ? modes : (modes.length() == 0 ? "," : modes) + mode + ",");
        e.putBoolean("base:" + mode + ":done", true).commit();
    }

    private static void restoreBaseline(Context c, boolean forget) {
        SharedPreferences sp = prefs(c);
        for (String mode : sp.getString("base_modes", "").split(",")) {
            if (mode.length() == 0) continue;
            PictureEngine.setMode(mode);
            for (int id : PictureEngine.ALL_PQ) {
                int v = sp.getInt("base:" + mode + ":" + id, -1);
                if (v >= 0) PictureEngine.set(id, v);
            }
        }
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (!k.startsWith("basep:")) continue;
            String[] p = k.split(":");
            try {
                PictureEngine.setParam(Integer.parseInt(p[1]), Integer.parseInt(p[2]), sp.getInt(k, 0));
            } catch (RuntimeException ignored) {
            }
            if (forget) e.remove(k);
        }
        PictureEngine.setMode(sp.getString("base_mode", "standard"));
        if (forget) {
            for (String k : sp.getAll().keySet()) if (k.startsWith("base")) e.remove(k);
        }
        e.commit();
    }

    // ------------------------------------------------------------------ fallback (no processor access)

    private static String fallback(Context c, int i) {
        SharedPreferences sp = prefs(c);
        ContentResolver cr = c.getContentResolver();
        StringBuilder done = new StringBuilder();
        if (canBrightness(c)) {
            try {
                if (!sp.contains("user_brightness")) {
                    sp.edit().putInt("user_brightness", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 200))
                            .putInt("user_brightness_mode", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)).apply();
                }
                int b = BRIGHTNESS[i] < 0 ? sp.getInt("user_brightness", 200) : BRIGHTNESS[i];
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, b);
                if (BRIGHTNESS[i] < 0) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, sp.getInt("user_brightness_mode", 0));
                }
                done.append("brightness ").append(Math.round(b * 100f / 255f)).append('%');
            } catch (RuntimeException ignored) {
            }
        }
        if (canWarmth(c)) {
            try {
                int k = WARMTH_K[i];
                if (k > 0) Settings.Secure.putInt(cr, "night_display_color_temperature", k);
                Settings.Secure.putInt(cr, "night_display_activated", k > 0 ? 1 : 0);
                if (done.length() > 0) done.append(", ");
                done.append(k > 0 ? "warm colour" : "neutral colour");
            } catch (RuntimeException ignored) {
            }
        }
        return done.toString();
    }

    /** Projector-menu values per profile, shown when Lumen can't set the picture itself. */
    static String[][] guide(String key) {
        int i = Math.max(0, index(key));
        String[] g = {"1.8", "2.0", "2.1", "2.2", "2.4"};
        String[] lvl = {"Off", "Low", "Middle", "High"};
        String[] ct = {"Standard", "Cool", "Warm"};
        int[] p = PQ[i];
        return new String[][]{{"Picture mode", MODE[i].substring(0, 1).toUpperCase(Locale.ROOT) + MODE[i].substring(1)},
                {"Brightness", String.valueOf(p[0])}, {"Contrast", String.valueOf(p[1])}, {"Colour", String.valueOf(p[2])},
                {"Sharpness", String.valueOf(p[4])}, {"Gamma", g[p[9]]}, {"Colour temperature", ct[p[10]]},
                {"DCI (dynamic contrast)", lvl[p[7]]}, {"Black extension", lvl[p[8]]},
                {"Noise reduction (TNR / SNR)", lvl[p[5]] + " / " + lvl[p[6]]}};
    }

    static String status(Context c) {
        if (engineUp) {
            return "Lumen Imaging is driving the projector's picture processor" + (auto(c) ? ". Auto Picture is on" : "");
        }
        boolean b = canBrightness(c), w = canWarmth(c);
        String why = PictureEngine.whyNot();
        String head = "Picture processor not reachable (" + why + "). ";
        if (b && w) return head + "Brightness and warmth change automatically; set the rest in the projector's menu";
        return head + "Set these values in the projector's picture menu";
    }
}
