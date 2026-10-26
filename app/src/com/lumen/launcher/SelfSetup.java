package com.lumen.launcher;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Lumen sets itself up as much as Android allows a normally-installed app to.
 *
 * Three tiers, by what the OS permits:
 *   1. Things Lumen applies ITSELF once it holds WRITE_SECURE_SETTINGS (granted once over ADB, then
 *      kept forever): 0.5x animations, system-wide ad-blocking DNS, the screensaver, and enabling its
 *      own "what's playing" access for Continue Watching. Re-applied on every launch, so they survive
 *      updates and factory tweaks with no PC.
 *   2. Things the USER grants with a tap, no PC: make Lumen the home screen, allow updates, draw over
 *      apps, Continue Watching (notification access), change system settings (brightness), the mic.
 *   3. Things Android reserves for a computer: the one-time WRITE_SECURE_SETTINGS / DUMP grant, and the
 *      privileged package operations (silent debloat, forcing 720p). Debloat is offered in-app as
 *      per-app uninstall prompts the user confirms.
 *
 * Everything here is idempotent and safe to call on every start.
 */
final class SelfSetup {

    static final String ADGUARD = "dns.adguard-dns.com";
    // Bloatware Lumen offers to remove (the user confirms each): factory/aging tools, vendor OTA
    // updaters (they can flash stock back), the ad store, the office suite.
    static final String[] BLOAT = {
            "com.softwinner.dragonatt", "com.softwinner.dragonbox", "com.softwinner.agingdragonbox",
            "com.htc.hyk_test", "com.htc.htcotaupdate", "android.rockchip.update.service",
            "com.htc.storeos", "com.mobisystems.office", "com.android.egg",
    };

    private SelfSetup() {
    }

    // ---------------------------------------------------------------- permission checks

    static boolean hasSecureSettings(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasDump(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canInstall(Context c) {
        try {
            Object r = PackageManager.class.getMethod("canRequestPackageInstalls").invoke(c.getPackageManager());
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return true;    // Android 7 and older: the global switch decides
        }
    }

    static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static boolean canWriteSettings(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(c);
    }

    static boolean hasMic(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean continueWatchingOn(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName() + "/");
    }

    static boolean isDefaultHome(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && c.getPackageName().equals(ri.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static int bloatInstalled(Context c) {
        int n = 0;
        for (String p : BLOAT) if (isInstalled(c, p)) n++;
        return n;
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- tier 1: self-apply

    /**
     * Applies everything Lumen is allowed to apply right now. Call on every launch; it only writes a
     * setting when it's not already the way Lumen wants it. Returns a short note, or "" if nothing was
     * holding it back.
     */
    static void applyHeld(final Context c) {
        if (!hasSecureSettings(c)) return;    // tier-1 settings live in Secure/Global
        ContentResolver cr = c.getContentResolver();
        try {
            setGlobalFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 0.5f);
            setGlobalFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 0.5f);
            setGlobalFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f);
        } catch (Throwable ignored) {
        }
        try {
            // Lumen Window as the screensaver, if nothing else is set.
            String comp = Settings.Secure.getString(cr, "screensaver_components");
            if (comp == null || comp.length() == 0 || comp.contains(c.getPackageName())) {
                Settings.Secure.putString(cr, "screensaver_components", c.getPackageName() + "/.WindowDream");
                Settings.Secure.putInt(cr, "screensaver_enabled", 1);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (!continueWatchingOn(c)) enableContinueWatching(c);
        } catch (Throwable ignored) {
        }
        applyAdBlock(c, false);
    }

    private static void setGlobalFloat(ContentResolver cr, String key, float want) {
        if (Math.abs(Settings.Global.getFloat(cr, key, -1f) - want) > 0.01f) {
            Settings.Global.putFloat(cr, key, want);
        }
    }

    /** Turns on Lumen's own "what's playing" access (needs WRITE_SECURE_SETTINGS). */
    static boolean enableContinueWatching(Context c) {
        if (!hasSecureSettings(c)) return false;
        try {
            ContentResolver cr = c.getContentResolver();
            String me = c.getPackageName() + "/" + c.getPackageName() + ".MediaWatch";
            String cur = Settings.Secure.getString(cr, "enabled_notification_listeners");
            if (cur != null && cur.contains(me)) return true;
            Settings.Secure.putString(cr, "enabled_notification_listeners",
                    cur == null || cur.length() == 0 ? me : cur + ":" + me);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * System-wide ad blocking through AdGuard DNS. Checks the network actually allows encrypted DNS and
     * reverts if it doesn't, so the internet never ends up broken. Background thread.
     */
    static void applyAdBlock(final Context c, final boolean force) {
        if (!hasSecureSettings(c)) return;
        final ContentResolver cr = c.getContentResolver();
        final String mode = Settings.Global.getString(cr, "private_dns_mode");
        final boolean alreadyOurs = "hostname".equals(mode)
                && ADGUARD.equals(Settings.Global.getString(cr, "private_dns_specifier"));
        if (alreadyOurs && !force) return;
        if (mode != null && mode.length() > 0 && !alreadyOurs && !force) return;  // the user picked their own DNS: leave it
        new Thread(new Runnable() {
            @Override
            public void run() {
                String prevMode = Settings.Global.getString(cr, "private_dns_mode");
                String prevSpec = Settings.Global.getString(cr, "private_dns_specifier");
                try {
                    Settings.Global.putString(cr, "private_dns_specifier", ADGUARD);
                    Settings.Global.putString(cr, "private_dns_mode", "hostname");
                    Thread.sleep(6000);
                    boolean ok = resolves("dns.adguard-dns.com") && resolves("example.com");
                    if (!ok) {
                        if (prevMode == null || prevMode.length() == 0) Settings.Global.putString(cr, "private_dns_mode", "opportunistic");
                        else Settings.Global.putString(cr, "private_dns_mode", prevMode);
                        if (prevSpec != null) Settings.Global.putString(cr, "private_dns_specifier", prevSpec);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "lumen-dns").start();
    }

    static void clearAdBlock(Context c) {
        if (!hasSecureSettings(c)) return;
        try {
            Settings.Global.putString(c.getContentResolver(), "private_dns_mode", "opportunistic");
        } catch (Throwable ignored) {
        }
    }

    static boolean adBlockOn(Context c) {
        ContentResolver cr = c.getContentResolver();
        return "hostname".equals(Settings.Global.getString(cr, "private_dns_mode"))
                && ADGUARD.equals(Settings.Global.getString(cr, "private_dns_specifier"));
    }

    private static boolean resolves(String host) {
        try {
            return InetAddress.getByName(host) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- tier 2: ask the user

    static void askInstall(Activity a) {
        open(a, new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + a.getPackageName())));
    }

    static void askOverlay(Activity a) {
        open(a, new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.parse("package:" + a.getPackageName())));
    }

    static void askWriteSettings(Activity a) {
        open(a, new Intent("android.settings.action.MANAGE_WRITE_SETTINGS", Uri.parse("package:" + a.getPackageName())));
    }

    static void askContinueWatching(Activity a) {
        if (enableContinueWatching(a)) return;              // did it ourselves if we hold the grant
        if (!open(a, new Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS"))) {
            open(a, new Intent(Settings.ACTION_SETTINGS));
        }
    }

    static void askMic(Activity a) {
        try {
            a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 42);
        } catch (RuntimeException ignored) {
        }
    }

    /** Make Lumen the home screen: the role prompt on Android 10+, else the Home settings screen. */
    static void askHome(Activity a) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Object rm = a.getSystemService(Class.forName("android.app.role.RoleManager"));
                if (rm != null) {
                    java.lang.reflect.Method mk = rm.getClass().getMethod("createRequestRoleIntent", String.class);
                    Intent i = (Intent) mk.invoke(rm, "android.app.role.HOME");
                    if (i != null && a.getPackageManager().resolveActivity(i, 0) != null) {
                        a.startActivityForResult(i, 43);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (!open(a, new Intent(Settings.ACTION_HOME_SETTINGS))) open(a, new Intent(Settings.ACTION_SETTINGS));
    }

    /** Opens each installed bloat app's uninstall dialog in turn (the user taps Uninstall). */
    static List<String> bloatToRemove(Context c) {
        List<String> out = new ArrayList<String>();
        for (String p : BLOAT) if (isInstalled(c, p)) out.add(p);
        return out;
    }

    static boolean askUninstall(Activity a, String pkg) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
        return open(a, i);
    }

    private static boolean open(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- status

    /** One line per item for the setup checklist. */
    static List<Item> checklist(Context c) {
        List<Item> l = new ArrayList<Item>();
        l.add(new Item("home", "Lumen is your home screen", isDefaultHome(c), true));
        l.add(new Item("updates", "Install Lumen's own updates", canInstall(c), true));
        l.add(new Item("overlay", "Show the overheating warning over apps", canOverlay(c), true));
        l.add(new Item("continue", "Continue Watching (see what's playing)", continueWatchingOn(c), true));
        l.add(new Item("brightness", "Adjust brightness for picture presets", canWriteSettings(c), true));
        l.add(new Item("mic", "Voice commands", hasMic(c), true));
        boolean sec = hasSecureSettings(c);
        l.add(new Item("smooth", "Smooth animations + system-wide ad blocking", sec, false));
        l.add(new Item("temps", "Exact CPU / GPU temperatures", hasDump(c), false));
        return l;
    }

    static final class Item {
        final String id, label;
        final boolean done;
        final boolean userGrantable;   // true = the user can grant it here; false = needs the 5-second PC step

        Item(String id, String label, boolean done, boolean userGrantable) {
            this.id = id;
            this.label = label;
            this.done = done;
            this.userGrantable = userGrantable;
        }
    }
}
