package com.lumen.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Continue Watching list.
 *
 * Two sources, merged:
 *  1. Android TV's "Watch Next" list (content://android.media.tv/watch_next_program) — exact
 *     episode links published by Netflix, Prime, YouTube… Readable only where the firmware lets
 *     Lumen see other apps' rows (needs READ_EPG_DATA; often system-only).
 *  2. What Lumen saw playing: MediaWatch records each app's "now playing" info (title, episode,
 *     artwork, position) while you watch.
 */
final class ContinueStore {

    static final class Entry {
        String key = "", pkg = "", title = "", subtitle = "", mediaId = "", uri = "", intentUri = "", art = "";
        long position, duration, updatedAt;
        boolean watchNext;

        int percent() {
            return duration > 0 ? (int) Math.max(0, Math.min(100, position * 100 / duration)) : -1;
        }

        long remainingMin() {
            return duration > 0 ? Math.max(0, (duration - position) / 60000) : -1;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("pkg", pkg).put("title", title).put("subtitle", subtitle)
                    .put("mediaId", mediaId).put("uri", uri).put("intentUri", intentUri).put("art", art)
                    .put("position", position).put("duration", duration).put("updatedAt", updatedAt);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.key = o.optString("key");
            e.pkg = o.optString("pkg");
            e.title = o.optString("title");
            e.subtitle = o.optString("subtitle");
            e.mediaId = o.optString("mediaId");
            e.uri = o.optString("uri");
            e.intentUri = o.optString("intentUri");
            e.art = o.optString("art");
            e.position = o.optLong("position");
            e.duration = o.optLong("duration");
            e.updatedAt = o.optLong("updatedAt");
            return e;
        }
    }

    static final int MAX = 12, PER_APP = 3;
    static volatile Runnable onChange;   // HomeActivity redraws the row (posted to the main thread by the caller)

    private static final Map<String, Entry> entries = new HashMap<String, Entry>();
    private static boolean loaded;

    private ContinueStore() {
    }

    static String keyFor(String pkg, String title) {
        return pkg + "|" + title.trim().toLowerCase(Locale.ROOT);
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("lumen_continue", Context.MODE_PRIVATE).getBoolean("on", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("lumen_continue", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply();
        changed();
    }

    /** Lumen can see what's playing (notification-listener access, granted once by the LumenOS script). */
    static boolean canWatch(Context c) {
        try {
            String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(c.getPackageName() + "/");
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ storage

    private static File file(Context c) {
        return new File(c.getFilesDir(), "continue.json");
    }

    static File artDir(Context c) {
        File d = new File(c.getFilesDir(), "continue_art");
        d.mkdirs();
        return d;
    }

    private static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            File f = file(c);
            if (!f.exists()) return;
            byte[] b = TrendingFeed.readAll(new FileInputStream(f));
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                Entry e = Entry.from(a.getJSONObject(i));
                if (e.key.length() > 0) entries.put(e.key, e);
            }
        } catch (Exception ignored) {
        }
    }

    private static synchronized void save(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : entries.values()) a.put(e.json());
            File tmp = new File(c.getFilesDir(), "continue.json.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(a.toString().getBytes("UTF-8"));
            o.close();
            tmp.renameTo(file(c));
        } catch (Exception ignored) {
        }
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    // ------------------------------------------------------------------ updates (any thread)

    /** Records what's playing. Keeps the newest few per app and drops finished films. */
    static void record(Context c, Entry e, Bitmap art, boolean notify) {
        if (e.title.length() == 0) return;
        load(c);
        e.key = keyFor(e.pkg, e.title);
        synchronized (ContinueStore.class) {
            Entry old = entries.get(e.key);
            if (art != null) e.art = saveArt(c, e.key, art);
            else if (old != null) e.art = old.art;
            if (e.art.length() == 0 && old != null) e.art = old.art;
            entries.put(e.key, e);
            trim(c);
        }
        save(c);
        if (notify) changed();
    }

    static void remove(Context c, String key) {
        load(c);
        synchronized (ContinueStore.class) {
            Entry e = entries.remove(key);
            if (e != null && e.art.length() > 0) new File(e.art).delete();
            hidden(c, key, true);
        }
        save(c);
        changed();
    }

    static void clear(Context c) {
        load(c);
        synchronized (ContinueStore.class) {
            for (Entry e : entries.values()) {
                if (e.art.length() > 0) new File(e.art).delete();
                hidden(c, e.key, true);
            }
            entries.clear();
        }
        save(c);
        changed();
    }

    /** A removed title stays hidden until it plays again. */
    private static void hidden(Context c, String key, boolean hide) {
        android.content.SharedPreferences sp = c.getSharedPreferences("lumen_continue", Context.MODE_PRIVATE);
        if (hide) sp.edit().putLong("hide:" + key, System.currentTimeMillis()).apply();
        else sp.edit().remove("hide:" + key).apply();
    }

    static void unhide(Context c, String key) {
        hidden(c, key, false);
    }

    private static boolean isHidden(Context c, Entry e) {
        long at = c.getSharedPreferences("lumen_continue", Context.MODE_PRIVATE).getLong("hide:" + e.key, 0);
        return at > 0 && e.updatedAt <= at;
    }

    private static void trim(Context c) {
        List<Entry> all = new ArrayList<Entry>(entries.values());
        Collections.sort(all, NEWEST);
        Map<String, Integer> perApp = new HashMap<String, Integer>();
        for (Entry e : all) {
            int n = perApp.containsKey(e.pkg) ? perApp.get(e.pkg) : 0;
            boolean finishedFilm = e.duration > 20 * 60000 && e.duration - e.position < 3 * 60000 && e.subtitle.length() == 0;
            if (n >= PER_APP || finishedFilm || entries.size() > MAX * 2) {
                entries.remove(e.key);
                if (e.art.length() > 0) new File(e.art).delete();
            } else {
                perApp.put(e.pkg, n + 1);
            }
        }
    }

    private static final Comparator<Entry> NEWEST = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            return a.updatedAt < b.updatedAt ? 1 : a.updatedAt > b.updatedAt ? -1 : 0;
        }
    };

    /** Newest first, Watch Next rows merged in. Background thread (reads the TV provider). */
    static List<Entry> list(Context c) {
        load(c);
        Map<String, Entry> merged = new HashMap<String, Entry>();
        for (Entry e : watchNext(c)) merged.put(e.key, e);
        synchronized (ContinueStore.class) {
            for (Entry e : entries.values()) {
                Entry w = merged.get(e.key);
                if (w == null) merged.put(e.key, e);
                else if (w.art.length() == 0) w.art = e.art;
            }
        }
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : merged.values()) if (!isHidden(c, e)) out.add(e);
        Collections.sort(out, NEWEST);
        return out.size() > MAX ? new ArrayList<Entry>(out.subList(0, MAX)) : out;
    }

    static Entry get(Context c, String key) {
        for (Entry e : list(c)) if (e.key.equals(key)) return e;
        return null;
    }

    // ------------------------------------------------------------------ Android TV Watch Next

    private static List<Entry> watchNext(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        if (c.checkSelfPermission("com.android.providers.tv.permission.READ_EPG_DATA") != 0) return out;
        Cursor cur = null;
        try {
            ContentResolver cr = c.getContentResolver();
            cur = cr.query(Uri.parse("content://android.media.tv/watch_next_program"), null, null, null,
                    "last_engagement_time_utc_millis DESC");
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < MAX) {
                String pkg = str(cur, "package_name");
                if (pkg.length() == 0 || pkg.equals(c.getPackageName())) continue;
                Entry e = new Entry();
                e.watchNext = true;
                e.pkg = pkg;
                String show = str(cur, "title"), ep = str(cur, "episode_title");
                String s = str(cur, "season_display_number"), n = str(cur, "episode_display_number");
                e.title = show.length() > 0 ? show : ep;
                StringBuilder sub = new StringBuilder();
                if (s.length() > 0) sub.append('S').append(s);
                if (n.length() > 0) sub.append(sub.length() > 0 ? " E" : "E").append(n);
                if (ep.length() > 0 && !ep.equals(e.title)) sub.append(sub.length() > 0 ? " · " : "").append(ep);
                e.subtitle = sub.toString();
                e.intentUri = str(cur, "intent_uri");
                e.position = lng(cur, "last_playback_position_millis");
                e.duration = lng(cur, "duration_millis");
                e.updatedAt = lng(cur, "last_engagement_time_utc_millis");
                String art = str(cur, "poster_art_uri");
                if (art.length() == 0) art = str(cur, "thumbnail_uri");
                e.key = keyFor(e.pkg, e.title);
                File f = new File(artDir(c), "wn_" + Integer.toHexString(e.key.hashCode()) + ".jpg");
                if (!f.exists() && art.length() > 0) {
                    Bitmap b = fetch(c, art);
                    if (b != null) saveArt(c, "wn_" + e.key, b);
                }
                if (f.exists()) e.art = f.getPath();
                out.add(e);
            }
        } catch (Exception ignored) {
            // the provider refused (no access on this firmware): the "now playing" source still works
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return "";
        String s = c.getString(i);
        return s == null ? "" : s;
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    // ------------------------------------------------------------------ artwork

    /** Saves a 16:9 crop (480×270) of the artwork; returns its path. */
    static String saveArt(Context c, String key, Bitmap src) {
        try {
            String name = (key.startsWith("wn_") ? "wn_" + Integer.toHexString(key.substring(3).hashCode())
                    : Integer.toHexString(key.hashCode())) + ".jpg";
            File f = new File(artDir(c), name);
            Bitmap out = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            int w = src.getWidth(), h = src.getHeight();
            Rect from;
            if (w * 9 > h * 16) { int nw = h * 16 / 9; from = new Rect((w - nw) / 2, 0, (w + nw) / 2, h); }
            else { int nh = w * 9 / 16; from = new Rect(0, (h - nh) / 2, w, (h + nh) / 2); }
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(src, from, new Rect(0, 0, 480, 270), p);
            FileOutputStream o = new FileOutputStream(f);
            out.compress(Bitmap.CompressFormat.JPEG, 86, o);
            o.close();
            out.recycle();
            return f.getPath();
        } catch (Exception e) {
            return "";
        }
    }

    /** Loads artwork from http(s), content: or file: URIs. Background thread. */
    static Bitmap fetch(Context c, String uri) {
        InputStream in = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = 1;
            if (uri.startsWith("http")) {
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(uri).openConnection();
                h.setConnectTimeout(6000);
                h.setReadTimeout(8000);
                byte[] b = TrendingFeed.readAll(h.getInputStream());
                return BitmapFactory.decodeByteArray(b, 0, b.length, o);
            }
            in = c.getContentResolver().openInputStream(Uri.parse(uri));
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** The card image: artwork (or a fallback) with a progress bar along the bottom. */
    static Bitmap cardImage(Entry e, Bitmap fallback, int accent) {
        Bitmap base = null;
        if (e.art.length() > 0 && new File(e.art).exists()) base = BitmapFactory.decodeFile(e.art);
        Bitmap out = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (base != null) {
            cv.drawBitmap(base, null, new Rect(0, 0, 480, 270), p);
            base.recycle();
        } else {
            cv.drawColor(0xFF15181D);
            if (fallback != null) {
                int fw = fallback.getWidth(), fh = fallback.getHeight();
                float s = Math.min(380f / fw, 200f / fh);
                int dw = (int) (fw * s), dh = (int) (fh * s);
                cv.drawBitmap(fallback, null, new Rect((480 - dw) / 2, (270 - dh) / 2 - 8, (480 + dw) / 2, (270 + dh) / 2 - 8), p);
            }
        }
        int pct = e.percent();
        if (pct >= 0) {
            p.setColor(0x99000000);
            cv.drawRect(0, 256, 480, 270, p);
            p.setColor(0x55FFFFFF);
            cv.drawRect(12, 261, 468, 265, p);
            p.setColor(accent);
            cv.drawRect(12, 261, 12 + 456 * Math.max(3, pct) / 100, 265, p);
        }
        return out;
    }
}
