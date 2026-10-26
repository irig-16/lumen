package com.lumen.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Over-the-air updates for Lumen itself.
 *  • Cloud: checks the update source (a GitHub repo's latest release or an update.json URL), downloads
 *    the APK, verifies it and hands it to Android's installer.
 *  • Phone: an APK uploaded from the phone remote goes through the same checks.
 * Android then shows its "Install update?" screen once; press OK on the remote.
 */
final class Updater {

    interface Listener {
        void onUpdateState();
    }

    static final long AUTO_EVERY = 6L * 3600 * 1000;
    /** Set once the startup check has run in this process. */
    private static boolean checkedThisStart;
    static final String ADB_ALLOW = "adb shell appops set com.lumen.launcher REQUEST_INSTALL_PACKAGES allow";

    private static Updater instance;

    static synchronized Updater get(Context c) {
        if (instance == null) instance = new Updater(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private volatile boolean busy;
    private String progress = "";
    /** Android's confirm screen, when it couldn't be shown right away (Lumen wasn't in front). */
    static volatile Intent pendingConfirm;
    /** Set by the home screen while it's in front. */
    static volatile boolean homeVisible;

    private Updater(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("lumen_update", Context.MODE_PRIVATE);
        // Lumen 7: follow the official open-source channel (once; a source set later on the phone stays)
        if (!sp.getBoolean("channel_v7", false)) {
            sp.edit().putBoolean("channel_v7", true).remove("source").putLong("checked", 0).apply();
        }
    }

    // ---------------------------------------------------------------- state

    String source() {
        String s = sp.getString("source", "");
        if (s.length() > 0) return s;
        return LumenConfig.UPDATE_SOURCE.contains("/OWNER/") ? "" : LumenConfig.UPDATE_SOURCE;
    }

    void setSource(String raw) {
        String s = UpdateFeed.normalizeSource(raw);
        sp.edit().putString("source", s).remove("avail_name").remove("avail_code").putLong("checked", 0).putString("error", "").apply();
        notifyListeners();
    }

    boolean auto() {
        return sp.getBoolean("auto", true);
    }

    void setAuto(boolean on) {
        sp.edit().putBoolean("auto", on).apply();
        notifyListeners();
    }

    int currentCode() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    String currentName() {
        try {
            String n = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return n == null ? "" : n;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The newer release found by the last check, or null. */
    UpdateFeed.Release available() {
        String name = sp.getString("avail_name", "");
        if (name.length() == 0 && sp.getInt("avail_code", 0) == 0) return null;
        UpdateFeed.Release r = new UpdateFeed.Release();
        r.versionName = name;
        r.versionCode = sp.getInt("avail_code", 0);
        r.url = sp.getString("avail_url", "");
        r.sha256 = sp.getString("avail_sha", "");
        r.notes = sp.getString("avail_notes", "");
        r.size = sp.getLong("avail_size", 0);
        if (!r.newerThan(currentCode(), currentName())) return null; // installed meanwhile
        return r;
    }

    boolean busy() {
        return busy;
    }

    String status() {
        if (busy) return progress;
        String err = sp.getString("error", "");
        if (err.length() > 0) return err;
        if (source().length() == 0) return "Set an update source on the phone remote's Updates card";
        UpdateFeed.Release r = available();
        if (r != null) return "Lumen " + r.versionName + " is available";
        long at = sp.getLong("checked", 0);
        return at == 0 ? "Not checked yet" : "Up to date · checked " + ago(at);
    }

    String json() {
        UpdateFeed.Release r = available();
        return "{\"current\":" + RemoteServer.json(currentName()) + ",\"currentCode\":" + currentCode()
                + ",\"source\":" + RemoteServer.json(source()) + ",\"sourceName\":" + RemoteServer.json(UpdateFeed.describeSource(source()))
                + ",\"auto\":" + auto() + ",\"busy\":" + busy + ",\"status\":" + RemoteServer.json(status())
                + ",\"canInstall\":" + canInstall() + ",\"available\":" + (r == null ? "null" : r.json()) + "}";
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : new ArrayList<Listener>(listeners)) l.onUpdateState();
            }
        });
    }

    private void setProgress(String p) {
        progress = p;
        notifyListeners();
    }

    // ---------------------------------------------------------------- check

    /** The quiet check: once when Lumen starts, then every 6 hours. */
    void checkAtStartupOrDue() {
        if (!auto()) return;
        if (!checkedThisStart) {
            checkedThisStart = true;
            check(false, true);
        } else {
            check(false, false);
        }
    }

    /** Background check; quiet ones only run every 6 h. */
    void check(final boolean manual) {
        check(manual, false);
    }

    private void check(final boolean manual, boolean force) {
        if (busy || source().length() == 0) return;
        if (!manual && !force && (!auto() || System.currentTimeMillis() - sp.getLong("checked", 0) < AUTO_EVERY)) return;
        busy = true;
        setProgress("Checking for updates…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                SharedPreferences.Editor e = sp.edit();
                try {
                    UpdateFeed.Release r = UpdateFeed.check(source());
                    e.putLong("checked", System.currentTimeMillis()).putString("error", "");
                    if (r.newerThan(currentCode(), currentName())) {
                        e.putString("avail_name", r.versionName).putInt("avail_code", r.versionCode)
                                .putString("avail_url", r.url).putString("avail_sha", r.sha256)
                                .putString("avail_notes", r.notes).putLong("avail_size", r.size);
                    } else {
                        e.remove("avail_name").remove("avail_code");
                    }
                } catch (Exception ex) {
                    e.putString("error", manual ? "Couldn't check: " + ex.getMessage() : "");
                    if (!manual) e.putLong("checked", System.currentTimeMillis() - AUTO_EVERY + 3600 * 1000); // retry in 1 h
                }
                e.apply();
                busy = false;
                notifyListeners();
            }
        }, "lumen-update-check").start();
    }

    // ---------------------------------------------------------------- install

    /** Downloads the available update and starts the install. Returns a message for the user. */
    String installAvailable() {
        final UpdateFeed.Release r = available();
        if (r == null) return source().length() == 0 ? "No update source set yet" : "Lumen is up to date";
        if (busy) return progress;
        if (!canInstall()) return "Lumen needs permission to install updates first (Lumen settings → Updates)";
        busy = true;
        setProgress("Downloading Lumen " + r.versionName + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File f = new File(ctx.getCacheDir(), "lumen-update.apk");
                try {
                    OutputStream out = new FileOutputStream(f);
                    try {
                        UpdateFeed.download(r, out);
                    } finally {
                        out.close();
                    }
                    String why = verify(f, false);
                    if (why != null) throw new IOException(why);
                    setProgress("Installing Lumen " + r.versionName + "…");
                    sp.edit().putString("installing_notes", r.notes).apply();
                    commit(f);
                    sp.edit().putString("error", "").apply();
                } catch (Exception ex) {
                    sp.edit().putString("error", "Update failed: " + ex.getMessage()).apply();
                } finally {
                    f.delete();
                    busy = false;
                    notifyListeners();
                }
            }
        }, "lumen-update-install").start();
        return "Downloading Lumen " + r.versionName + ". Then press Install on the projector";
    }

    /** An APK sent from the phone. Returns {ok, message}. */
    String[] installUpload(byte[] apk) {
        if (busy) return new String[]{"false", progress};
        if (apk == null || apk.length < 1000) return new String[]{"false", "That file is empty"};
        if (!canInstall()) return new String[]{"false", "Allow Lumen to install updates first: Lumen settings → Updates on the projector"};
        File f = new File(ctx.getCacheDir(), "lumen-upload.apk");
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(apk);
            } finally {
                out.close();
            }
            String why = verify(f, true);
            if (why != null) return new String[]{"false", why};
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            busy = true;
            setProgress("Installing Lumen " + (pi == null ? "" : pi.versionName) + " from your phone…");
            sp.edit().putString("installing_notes", "").apply();
            commit(f);
            return new String[]{"true", "Sent to the projector. Press Install there to finish (Lumen restarts by itself)"};
        } catch (Exception ex) {
            return new String[]{"false", "Install failed: " + ex.getMessage()};
        } finally {
            f.delete();
            busy = false;
            notifyListeners();
        }
    }

    /** Null if the APK is a genuine Lumen update; otherwise why not. */
    private String verify(File apk, boolean allowSameVersion) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (theirs == null) return "That file isn't an Android app";
        if (!ctx.getPackageName().equals(theirs.packageName)) return "That's not Lumen (it's " + theirs.packageName + ")";
        int cur = currentCode();
        if (theirs.versionCode < cur) return "That's an older Lumen (" + theirs.versionName + ") than the one installed";
        if (theirs.versionCode == cur && !allowSameVersion) return "That's the version already installed";
        try {
            PackageInfo ours = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (theirs.signatures == null || !sameSigners(ours.signatures, theirs.signatures))
                return "That Lumen was signed with a different key, so it can't update this one";
        } catch (PackageManager.NameNotFoundException e) {
            return "Can't read the installed Lumen";
        }
        return null;
    }

    private static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!Arrays.equals(a[i].toByteArray(), b[i].toByteArray())) return false;
        return true;
    }

    private void commit(File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("lumen.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            s.fsync(out);
            out.close();
            sp.edit().putInt("installing_code", currentCode()).apply();
            Intent done = new Intent(ctx, UpdateReceiver.class);
            PendingIntent p = PendingIntent.getBroadcast(ctx, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(p.getIntentSender());
        } catch (IOException e) {
            s.abandon();
            throw e;
        } catch (RuntimeException e) {
            s.abandon();
            throw new IOException(e.getMessage());
        } finally {
            s.close();
        }
    }

    /** Called by {@link UpdateReceiver} when Android reports that the install failed or was cancelled. */
    void failed(String message) {
        sp.edit().putString("error", message).apply();
        notifyListeners();
    }

    /** After an update: the release notes once, then cleared. Null if Lumen wasn't just updated. */
    String justUpdatedNotes() {
        int before = sp.getInt("last_run_code", 0);
        int now = currentCode();
        sp.edit().putInt("last_run_code", now).apply();
        if (before == 0 || now <= before) return null;
        String notes = sp.getString("installing_notes", "");
        sp.edit().remove("installing_notes").remove("avail_name").remove("avail_code").putString("error", "").apply();
        return notes;
    }

    // ---------------------------------------------------------------- permission

    /** Android 8+ asks the user to allow each app that installs apps. */
    boolean canInstall() {
        try {
            Object r = PackageManager.class.getMethod("canRequestPackageInstalls").invoke(ctx.getPackageManager());
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return true; // Android 7 and older: the global "unknown sources" switch decides
        }
    }

    void requestInstallPermission(Activity a) {
        try {
            a.startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + ctx.getPackageName())));
            return;
        } catch (RuntimeException ignored) {
            // some projector firmwares hide this screen
        }
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Allow Lumen to install updates")
                .setMessage("This projector hides the setting. From a computer on the same Wi-Fi, run once:\n\n"
                        + "adb connect <projector-ip>:5555\n" + ADB_ALLOW)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String ago(long at) {
        long m = (System.currentTimeMillis() - at) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        return (m / 1440) + " days ago";
    }
}
