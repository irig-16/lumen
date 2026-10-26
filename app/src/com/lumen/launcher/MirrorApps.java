package com.lumen.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds the projector's own screen-mirroring receiver (Miracast / AirPlay / Google Cast), which is what
 * phones mirror to with Cast, Smart View or AirPlay. Projector firmwares ship different ones, so this
 * matches known packages first, then names that look like a mirroring app.
 */
final class MirrorApps {

    /** Receivers seen on Allwinner projectors and Android TV boxes, best first. */
    private static final String[] KNOWN = {
            "com.softwinner.miracastReceiver", "com.softwinner.miracast", "com.allwinner.miracast", "com.aw.miracast",
            "com.ecloud.eshare.server", "com.eshare.server", "com.hpplay.happyplay.aw", "com.hpplay.happycast",
            "com.hpplay.happyplay", "com.waxrain.airplaydmr", "com.waxrain.airplaydmr2", "com.google.android.apps.mediashell"};

    private static final Pattern LOOKS_LIKE = Pattern.compile(
            "(?i)(miracast|airplay|air ?pin|airscreen|eshare|happy ?cast|lebo|screen ?cast|screen ?mirror|mirroring|"
                    + "\\bmirror\\b|screen ?share|wireless display|wi-?fi display|cast receiver|^cast$|\\bcast\\b|投屏)");
    private static final Pattern NOT = Pattern.compile("(?i)(podcast|broadcast|forecast)");

    static final class App {
        final String pkg, label;
        final Intent launch;

        App(String pkg, String label, Intent launch) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
        }
    }

    private MirrorApps() {
    }

    /** Every installed app that looks like a mirroring receiver, best guess first. */
    static List<App> find(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> all = new ArrayList<ResolveInfo>();
        for (String cat : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            try {
                all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0));
            } catch (RuntimeException ignored) {
            }
        }
        List<App> known = new ArrayList<App>(), guessed = new ArrayList<App>();
        List<String> seen = new ArrayList<String>();
        for (ResolveInfo r : all) {
            String pkg = r.activityInfo.packageName;
            if (seen.contains(pkg) || c.getPackageName().equals(pkg)) continue;
            String label = String.valueOf(r.loadLabel(pm));
            int k = indexOf(pkg);
            boolean looks = (LOOKS_LIKE.matcher(label).find() || LOOKS_LIKE.matcher(pkg.replace('.', ' ')).find())
                    && !NOT.matcher(label).find();
            if (k < 0 && !looks) continue;
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) continue;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            seen.add(pkg);
            (k >= 0 ? known : guessed).add(new App(pkg, label, i));
        }
        // known receivers in KNOWN order, then the guesses
        List<App> out = new ArrayList<App>();
        for (String p : KNOWN) for (App a : known) if (a.pkg.equals(p)) out.add(a);
        out.addAll(guessed);
        return out;
    }

    static App best(Context c) {
        List<App> l = find(c);
        return l.isEmpty() ? null : l.get(0);
    }

    private static int indexOf(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        for (int i = 0; i < KNOWN.length; i++) if (KNOWN[i].toLowerCase(Locale.ROOT).equals(p)) return i;
        return -1;
    }
}
