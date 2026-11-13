package com.lumen.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * "Home button opens Lumen" on Google TV and other locked-down launchers.
 *
 * Google TV keeps its own launcher as the Home button's target, whatever app is the default home. Rather
 * than disabling it (which can break setup, Assistant and account prompts), this accessibility service
 * notices when the stock home screen comes up and brings Lumen to the front instead. It reads nothing on
 * screen: it only listens for which app's window opened. Turning it off in Settings → Accessibility puts
 * everything back exactly as it was.
 */
public class HomeKeeper extends AccessibilityService {

    /** The stock home screens Lumen stands in for. */
    static final String[] STOCK_HOMES = {
            "com.google.android.apps.tv.launcherx",      // Google TV
            "com.google.android.tvlauncher",             // Android TV
            "com.google.android.leanbacklauncher",       // older Android TV
    };

    /** Until this time (elapsedRealtime), the stock home is left alone: the user opened it on purpose. */
    private static long passUntil;

    /** Let the stock home screen show for a couple of minutes (Lumen's "Google TV" button). */
    static void allowStockHome() {
        passUntil = SystemClock.elapsedRealtime() + 2 * 60 * 1000;
    }

    private long lastJump;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || !isStockHome(pkg.toString())) return;
        if (!isHomeScreen(cls == null ? "" : cls.toString())) return;
        long now = SystemClock.elapsedRealtime();
        if (now < passUntil || now - lastJump < 800) return;
        if (!enabledInLumen(this)) return;
        lastJump = now;
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    static boolean isStockHome(String pkg) {
        for (String s : STOCK_HOMES) if (s.equals(pkg)) return true;
        return false;
    }

    /**
     * Only the launcher's home screen itself, not its other panels (profile pickers, setup, search).
     * Google TV's home window class contains "home" or "MainActivity"; layouts and dialogs (FrameLayout,
     * Dialog…) aren't activities and are ignored.
     */
    static boolean isHomeScreen(String cls) {
        String c = cls.toLowerCase(java.util.Locale.ROOT);
        if (c.startsWith("android.")) return false;            // a dialog or view, not a screen
        if (c.contains("setup") || c.contains("profile") || c.contains("search") || c.contains("account")) return false;
        return c.contains("home") || c.contains("mainactivity") || c.contains("launcher");
    }

    /** Lumen's own switch for this (on by default once the service is allowed). */
    static boolean enabledInLumen(Context c) {
        return new AppStore(c).getBool("home_keeper", true);
    }

    static boolean serviceOn(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
        return s != null && s.contains(c.getPackageName() + "/");
    }

    static String component(Context c) {
        return c.getPackageName() + "/" + HomeKeeper.class.getName();
    }

    /** Turns the service on by itself when Lumen holds WRITE_SECURE_SETTINGS (LumenOS-Unlock). */
    static boolean enableBySettings(Context c) {
        if (!SelfSetup.hasSecureSettings(c)) return false;
        try {
            String me = component(c);
            String cur = Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
            if (cur == null || !cur.contains(me)) {
                Settings.Secure.putString(c.getContentResolver(), "enabled_accessibility_services",
                        cur == null || cur.length() == 0 ? me : cur + ":" + me);
            }
            Settings.Secure.putInt(c.getContentResolver(), "accessibility_enabled", 1);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True on Google TV / Android TV builds whose own launcher grabs the Home button. */
    static boolean stockHomeInstalled(Context c) {
        for (String p : STOCK_HOMES) {
            try {
                if (c.getPackageManager().getApplicationInfo(p, 0).enabled) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
