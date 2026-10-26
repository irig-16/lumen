package com.lumen.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Loads installed apps and keeps the user's favourites / hidden apps / settings. */
final class AppStore {

    private static final String PREFS = "lumen";
    private static final String K_FAVS = "favs";
    private static final String K_HIDDEN = "hidden";
    private static final String K_INIT = "init";

    /** Common media apps pinned automatically on first run, if installed. */
    private static final String[] DEFAULT_FAVS = {
            "com.teamsmart.videomanager.tv",          // SmartTube
            "com.google.android.youtube.tv",          // YouTube for TV
            "com.google.android.youtube",             // YouTube
            "org.xbmc.kodi",                          // Kodi
            "org.videolan.vlc",                       // VLC
            "com.netflix.mediaclient",                // Netflix
            "com.amazon.amazonvideo.livingroom",      // Prime Video
            "com.android.vending",                    // Play Store
    };

    private final Context ctx;
    private final SharedPreferences sp;

    AppStore(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- loading

    /** Runs on a background thread. */
    List<AppInfo> load(int iconPx, int bannerW, int bannerH) {
        PackageManager pm = ctx.getPackageManager();
        LinkedHashMap<String, AppInfo> map = new LinkedHashMap<String, AppInfo>();
        addFrom(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, map, iconPx, bannerW, bannerH);
        addFrom(pm, Intent.CATEGORY_LAUNCHER, map, iconPx, bannerW, bannerH);
        map.remove(ctx.getPackageName());

        List<AppInfo> list = new ArrayList<AppInfo>(map.values());
        final Collator col = Collator.getInstance();
        Collections.sort(list, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return col.compare(a.label, b.label);
            }
        });
        return list;
    }

    private void addFrom(PackageManager pm, String category,
                         LinkedHashMap<String, AppInfo> map, int iconPx, int bannerW, int bannerH) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(q, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (map.containsKey(pkg)) continue;

            String label;
            try {
                CharSequence cs = ri.loadLabel(pm);
                label = cs == null ? pkg : cs.toString().trim();
            } catch (RuntimeException e) {
                label = pkg;
            }
            if (label.length() == 0) label = pkg;

            Bitmap icon = null;
            try {
                icon = toBitmap(ri.loadIcon(pm), iconPx, iconPx);
            } catch (Throwable ignored) {
                // Some vendor icons are broken; card falls back to a plain plate.
            }

            Bitmap banner = null;
            try {
                Drawable b = ri.activityInfo.loadBanner(pm);
                if (b == null && ri.activityInfo.applicationInfo != null) {
                    b = ri.activityInfo.applicationInfo.loadBanner(pm);
                }
                if (b != null) banner = toBitmap(b, bannerW, bannerH);
            } catch (Throwable ignored) {
            }

            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            boolean system = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            int color = dominant(banner != null ? banner : icon);
            map.put(pkg, new AppInfo(pkg, label, icon, banner, color, launch, system));
        }
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /**
     * Picks a vivid representative colour: averages opaque pixels weighted by
     * saturation x brightness, then normalises it to a pleasant mid tone.
     */
    static int dominant(Bitmap b) {
        final int fallback = 0xFF46557A;
        if (b == null) return fallback;
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(1, Math.min(w, h) / 24);
        double r = 0, g = 0, bl = 0, wt = 0, satSum = 0;
        int n = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = b.getPixel(x, y);
                if ((c >>> 24) < 160) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[2] < 0.12f) continue;
                double weight = 0.04 + hsv[1] * hsv[1] * hsv[2];
                r += Color.red(c) * weight;
                g += Color.green(c) * weight;
                bl += Color.blue(c) * weight;
                wt += weight;
                satSum += hsv[1];
                n++;
            }
        }
        if (n == 0 || wt < 1e-4) return fallback;
        if (satSum / n < 0.08) return fallback; // greyscale icon → calm slate
        Color.colorToHSV(Color.rgb((int) (r / wt), (int) (g / wt), (int) (bl / wt)), hsv);
        hsv[1] = Math.max(0.35f, Math.min(0.85f, hsv[1] * 1.2f));
        hsv[2] = 0.78f;
        return Color.HSVToColor(hsv);
    }

    // ------------------------------------------------------------- favourites

    List<String> favourites() {
        String raw = sp.getString(K_FAVS, "");
        List<String> out = new ArrayList<String>();
        if (raw.length() == 0) return out;
        for (String s : raw.split("\\|")) if (s.length() > 0) out.add(s);
        return out;
    }

    void setFavourites(List<String> favs) {
        sp.edit().putString(K_FAVS, TextUtils.join("|", favs)).apply();
    }

    boolean isFavourite(String pkg) {
        return favourites().contains(pkg);
    }

    void toggleFavourite(String pkg) {
        List<String> f = favourites();
        if (f.contains(pkg)) f.remove(pkg); else f.add(pkg);
        setFavourites(f);
    }

    /** dir = -1 moves left, +1 moves right. */
    void moveFavourite(String pkg, int dir) {
        List<String> f = favourites();
        int i = f.indexOf(pkg);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= f.size()) return;
        Collections.swap(f, i, j);
        setFavourites(f);
    }

    void ensureDefaults(List<AppInfo> apps) {
        if (sp.getBoolean(K_INIT, false)) return;
        Set<String> installed = new HashSet<String>();
        for (AppInfo a : apps) installed.add(a.pkg);
        List<String> f = new ArrayList<String>();
        for (String p : DEFAULT_FAVS) {
            if (installed.contains(p) && f.size() < 6) f.add(p);
        }
        // If YouTube for TV and phone YouTube are both present, keep only one.
        if (f.contains("com.google.android.youtube.tv")) f.remove("com.google.android.youtube");
        setFavourites(f);
        sp.edit().putBoolean(K_INIT, true).apply();
    }

    // ----------------------------------------------------------------- hidden

    Set<String> hidden() {
        return new HashSet<String>(sp.getStringSet(K_HIDDEN, new HashSet<String>()));
    }

    void setHidden(String pkg, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(pkg); else h.remove(pkg);
        sp.edit().putStringSet(K_HIDDEN, h).apply();
    }

    // --------------------------------------------------------------- settings

    boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    void setBool(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
    }

    String getString(String key, String def) {
        return sp.getString(key, def);
    }

    void setString(String key, String v) {
        sp.edit().putString(key, v).apply();
    }

    long getLong(String key, long def) {
        return sp.getLong(key, def);
    }

    void setLong(String key, long v) {
        sp.edit().putLong(key, v).apply();
    }

    boolean has(String key) {
        return sp.contains(key);
    }
}
