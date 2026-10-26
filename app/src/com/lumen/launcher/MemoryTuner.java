package com.lumen.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the 1 GB projector's memory for what you're watching.
 *
 * When a video app starts playing and free memory is low, Lumen clears the leftover background
 * processes of other apps (only ones Android already considers cached), so the player isn't the one
 * that gets squeezed. Never touches the app that's playing, the keyboard, or system apps.
 * The system-side limits (how many apps stay cached, the cached-app freezer) are set by
 * LumenOS-Speed.bat over ADB, because Android only lets ADB change them.
 */
final class MemoryTuner {

    /** Below this much free memory a new player gets help. */
    static final long LOW_MB = 300;
    private static long lastRun;

    private MemoryTuner() {
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("auto_free", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto_free", on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lumen_memory", Context.MODE_PRIVATE);
    }

    static long availMb(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.availMem >> 20;
    }

    /** A player just started in {@code pkg}. Main or binder thread. */
    static void onPlaybackStart(final Context c, final String pkg) {
        if (!enabled(c) || pkg == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRun < 3 * 60 * 1000) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (availMb(c) >= LOW_MB) return;
                lastRun = SystemClock.elapsedRealtime();
                int n = freeBackground(c, pkg, true);
                prefs(c).edit().putLong("last_free_at", System.currentTimeMillis()).putInt("last_free_count", n)
                        .putString("last_free_for", pkg).apply();
            }
        }, "lumen-memory").start();
    }

    /**
     * Asks Android to drop the cached background processes of installed (non-system) apps.
     * Returns how many apps were asked.
     */
    static int freeBackground(Context c, String keep, boolean quiet) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> protect = new HashSet<String>();
        protect.add(c.getPackageName());
        if (keep != null) protect.add(keep);
        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) protect.add(ime.substring(0, ime.indexOf('/')));
        int n = 0;
        try {
            List<ApplicationInfo> apps = c.getPackageManager().getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || protect.contains(ai.packageName)) continue;
                am.killBackgroundProcesses(ai.packageName);
                n++;
            }
        } catch (RuntimeException ignored) {
        }
        return n;
    }

    /** For Lumen settings. */
    static String summary(Context c) {
        return (enabled(c) ? "On" : "Off") + "  ·  " + availMb(c) + " MB free";
    }
}
