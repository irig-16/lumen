package com.lumen.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.List;
import java.util.Locale;

/**
 * Finds the projector's own picture menu (brightness, contrast, sharpness, colour temperature).
 * Every vendor names it differently, so we scan exported activities of system apps for names
 * like "PictureSettings" / "PqSetting" / "ImageMode" and fall back to Android's display settings.
 */
final class PictureSettings {

    private static ComponentName found;
    private static boolean scanned;

    private PictureSettings() {
    }

    /** Slow (scans packages) — call from a background thread once. */
    static synchronized ComponentName find(Context c) {
        if (scanned) return found;
        scanned = true;
        PackageManager pm = c.getPackageManager();
        int best = 0;
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                PackageInfo pi;
                try {
                    pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_ACTIVITIES);
                } catch (Exception e) {
                    continue;
                }
                if (pi.activities == null) continue;
                for (ActivityInfo a : pi.activities) {
                    if (!a.exported || !a.enabled || a.permission != null) continue;
                    int s = score(a.name);
                    if (s > best) {
                        best = s;
                        found = new ComponentName(a.packageName, a.name);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return found;
    }

    private static int score(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String cls = dot >= 0 ? n.substring(dot + 1) : n;
        if (cls.contains("picturesetting") || cls.contains("picturemode") || cls.contains("pictureactivity")) return 10;
        if (cls.contains("pqsetting") || cls.contains("pqactivity") || cls.equals("pq")) return 9;
        if (cls.contains("imagesetting") || cls.contains("imagemode")) return 8;
        if (cls.contains("picture") && !cls.contains("inpicture") && !cls.contains("pip")) return 6;
        if (cls.contains("projection") || cls.contains("keystone")) return 3;
        return 0;
    }

    static boolean hasVendorMenu() {
        return found != null;
    }

    /** Opens the vendor picture menu if found, otherwise Android's display settings. Returns true for vendor. */
    static boolean open(Context c) {
        if (found != null) {
            try {
                c.startActivity(new Intent().setComponent(found).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        try {
            c.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }
}
