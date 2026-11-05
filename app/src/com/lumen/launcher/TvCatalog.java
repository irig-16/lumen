package com.lumen.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live TV's channel store, shared by the TV screen and the phone remote.
 *
 * Fast: each section (a country or a category) is its own small iptv-org playlist, fetched only when it's
 * opened and cached for a day, so nothing waits for the full 10,000-channel list. That full list is only
 * fetched for searching by name, in the background.
 *
 * Clean: every channel is checked in the background (does its stream answer?), working links are
 * remembered, and channels that don't work are hidden.
 */
final class TvCatalog {

    static final String BASE = "https://iptv-org.github.io/iptv/";
    static final long SECTION_AGE = 24L * 3600 * 1000, OK_AGE = 12L * 3600 * 1000, BAD_AGE = 6L * 3600 * 1000;

    /** iptv-org's categories, most useful first (display name; the playlist is categories/<lower>.m3u). */
    static final String[] CATEGORIES = {"News", "Movies", "Entertainment", "Music", "Sports", "Kids", "Documentary",
            "Series", "Comedy", "Lifestyle", "Cooking", "Travel", "Business", "Animation", "Classic", "Education",
            "Culture", "Family", "Science", "Outdoor", "Religious", "General"};
    static final String FAV = "Favourites", RECENT = "Recently watched";

    interface Listener {
        /** The section's list changed (loaded, or channels found working / not working). Any thread. */
        void onSection(String section);
    }

    private static TvCatalog instance;

    static synchronized TvCatalog get(Context c) {
        if (instance == null) instance = new TvCatalog(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final File dir;
    private final Map<String, List<IptvFeed.Channel>> sections = new ConcurrentHashMap<String, List<IptvFeed.Channel>>();
    private final Map<String, IptvFeed.Channel> byKey = new ConcurrentHashMap<String, IptvFeed.Channel>();
    private final Map<String, long[]> health = new ConcurrentHashMap<String, long[]>();   // key → {ok 1/0, time, alt}
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final ExecutorService prober = Executors.newFixedThreadPool(8);
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<String, AtomicInteger>();
    private volatile List<IptvFeed.Channel> index;      // the full list, only for search
    private volatile boolean indexLoading;
    private volatile String error = "";

    private TvCatalog(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("lumen_tv", Context.MODE_PRIVATE);
        dir = new File(c.getCacheDir(), "tv");
        dir.mkdirs();
        loadHealth();
    }

    void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void changed(String section) {
        List<Listener> ls;
        synchronized (listeners) {
            ls = new ArrayList<Listener>(listeners);
        }
        for (Listener l : ls) l.onSection(section);
    }

    String error() {
        return error;
    }

    // ---------------------------------------------------------------- sections

    /** Home country first, pinned countries, then categories. Favourites and recent at the top. */
    List<String> sectionNames() {
        List<String> out = new ArrayList<String>();
        out.add(FAV);
        out.add(RECENT);
        String home = homeCountry();
        if (home.length() == 2) out.add(countryLabel(home));
        for (String cc : pinned()) if (!out.contains(countryLabel(cc))) out.add(countryLabel(cc));
        Collections.addAll(out, CATEGORIES);
        return out;
    }

    String homeCountry() {
        String h = new AppStore(ctx).getString("geo_country", "").toLowerCase(Locale.ROOT);
        if (h.length() != 2) h = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);
        return h;
    }

    static String countryName(String cc) {
        String code = cc.equals("uk") ? "GB" : cc.toUpperCase(Locale.ROOT);
        String n = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        return n.length() > 0 ? n : code;
    }

    static String countryLabel(String cc) {
        return countryName(cc);
    }

    /** The country code behind a section name, or null for a category / list. */
    String countryOf(String section) {
        if (section == null) return null;
        for (String cc : Locale.getISOCountries()) {
            if (countryName(cc.toLowerCase(Locale.ROOT)).equals(section)) return cc.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Every country, sorted by name, for the picker. */
    static List<String[]> allCountries() {
        List<String[]> out = new ArrayList<String[]>();
        for (String cc : Locale.getISOCountries()) {
            String l = cc.toLowerCase(Locale.ROOT);
            out.add(new String[]{l, countryName(l)});
        }
        Collections.sort(out, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[1].compareTo(b[1]);
            }
        });
        return out;
    }

    List<String> pinned() {
        return list("countries");
    }

    void pin(String cc) {
        List<String> l = pinned();
        l.remove(cc);
        l.add(0, cc);
        while (l.size() > 4) l.remove(l.size() - 1);
        putList("countries", l);
    }

    /**
     * The channels to show for a section, working ones first, broken ones hidden (unless showAll).
     * Returns what's known right now (possibly empty) and loads / checks in the background.
     */
    List<IptvFeed.Channel> section(final String name, boolean showAll) {
        if (FAV.equals(name)) return visible(byKeys(list("favs")), true);
        if (RECENT.equals(name)) return visible(byKeys(list("recent")), true);
        List<IptvFeed.Channel> l = sections.get(name);
        if (l == null) {
            load(name, false);
            return new ArrayList<IptvFeed.Channel>();
        }
        return visible(l, showAll);
    }

    boolean loaded(String name) {
        return FAV.equals(name) || RECENT.equals(name) || sections.containsKey(name);
    }

    /** True while channels of this section are still being checked. */
    boolean checking(String name) {
        AtomicInteger p = pending.get(name);
        return p != null && p.get() > 0;
    }

    int hiddenCount(String name) {
        List<IptvFeed.Channel> l = sections.get(name);
        if (l == null) return 0;
        int n = 0;
        for (IptvFeed.Channel c : l) if (state(c) == 0) n++;
        return n;
    }

    private List<IptvFeed.Channel> visible(List<IptvFeed.Channel> l, boolean showAll) {
        List<IptvFeed.Channel> ok = new ArrayList<IptvFeed.Channel>(), unknown = new ArrayList<IptvFeed.Channel>(),
                bad = new ArrayList<IptvFeed.Channel>();
        for (IptvFeed.Channel c : l) {
            int s = state(c);
            (s == 1 ? ok : s == 0 ? bad : unknown).add(c);
        }
        ok.addAll(unknown);
        if (showAll) ok.addAll(bad);
        return ok;
    }

    /** 1 working, 0 not working, -1 not checked yet. */
    int state(IptvFeed.Channel c) {
        long[] h = health.get(c.key());
        if (h == null) return -1;
        long age = System.currentTimeMillis() - h[1];
        if (h[0] == 1 && age < OK_AGE) return 1;
        if (h[0] == 0 && age < BAD_AGE) return 0;
        return -1;
    }

    /** The link that worked last time (0 = the channel's own, n = alternate n). */
    int workingAlt(IptvFeed.Channel c) {
        long[] h = health.get(c.key());
        return h == null ? 0 : (int) Math.min(h[2], c.alts.size());
    }

    void load(final String name, final boolean force) {
        loader.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String cc = countryOf(name);
                    String file = "gb".equals(cc) ? "uk" : cc;   // iptv-org names the UK "uk"
                    String path = cc != null ? "countries/" + file + ".m3u" : "categories/" + name.toLowerCase(Locale.ROOT) + ".m3u";
                    File f = new File(dir, path.replace('/', '_'));
                    boolean stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > SECTION_AGE;
                    if (f.exists() && !sections.containsKey(name)) put(name, read(f));    // yesterday's list, instantly
                    if (stale || force) {
                        try {
                            download(BASE + path, f);
                            put(name, read(f));
                        } catch (IOException e) {
                            if (!f.exists()) {
                                error = "Couldn't get the channels (" + e.getMessage() + "). Check the internet connection.";
                                sections.put(name, new ArrayList<IptvFeed.Channel>());
                                changed(name);
                                return;
                            }
                        }
                    }
                    error = "";
                    check(name);
                } catch (IOException e) {
                    error = "Couldn't read the channel list: " + e.getMessage();
                    changed(name);
                }
            }
        });
    }

    private void put(String name, List<IptvFeed.Channel> l) {
        for (IptvFeed.Channel c : l) {
            IptvFeed.Channel known = byKey.get(c.key());
            if (known == null) byKey.put(c.key(), c);
        }
        List<IptvFeed.Channel> canonical = new ArrayList<IptvFeed.Channel>();
        for (IptvFeed.Channel c : l) canonical.add(byKey.get(c.key()));
        sections.put(name, canonical);
        changed(name);
    }

    private static List<IptvFeed.Channel> read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return IptvFeed.parse(new InputStreamReader(in, "UTF-8"));
        } finally {
            in.close();
        }
    }

    static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Lumen-Launcher");
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new IOException("no channels listed");
            if (code >= 400) throw new IOException("HTTP " + code);
            File tmp = new File(to.getPath() + ".tmp");
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
            if (!tmp.renameTo(to)) throw new IOException("couldn't save");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- health checks

    /** Checks the section's unchecked channels, top of the list first. */
    void check(final String name) {
        List<IptvFeed.Channel> l = sections.get(name);
        if (l == null) return;
        final AtomicInteger left = new AtomicInteger();
        pending.put(name, left);
        final long[] lastNotify = {0};
        for (final IptvFeed.Channel c : l) {
            if (state(c) != -1) continue;
            left.incrementAndGet();
            prober.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (state(c) == -1) probeChannel(c);
                    } finally {
                        int n = left.decrementAndGet();
                        long now = System.currentTimeMillis();
                        if (n == 0 || now - lastNotify[0] > 1500) {
                            lastNotify[0] = now;
                            changed(name);
                        }
                        if (n == 0) saveHealth();
                    }
                }
            });
        }
        if (left.get() == 0) changed(name);
    }

    private void probeChannel(IptvFeed.Channel c) {
        for (int i = 0; i <= c.alts.size(); i++) {
            IptvFeed.Channel l = i == 0 ? c : c.alts.get(i - 1);
            if (probe(l)) {
                health.put(c.key(), new long[]{1, System.currentTimeMillis(), i});
                return;
            }
        }
        health.put(c.key(), new long[]{0, System.currentTimeMillis(), 0});
    }

    /** Does the stream answer with a playlist or video data? Follows up to three redirects. */
    static boolean probe(IptvFeed.Channel l) {
        String url = l.url;
        if (!url.startsWith("http")) return true;     // rtmp/rtsp can't be probed cheaply: let the player try
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(4000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", l.userAgent.length() > 0 ? l.userAgent : "Mozilla/5.0 (Linux; Android 11) Lumen");
                if (l.referrer.length() > 0) c.setRequestProperty("Referer", l.referrer);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return false;
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) return false;
                InputStream in = c.getInputStream();
                byte[] buf = new byte[2048];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                in.close();
                if (n == 0) return false;
                String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
                boolean playlist = url.toLowerCase(Locale.ROOT).contains(".m3u8") || type.contains("mpegurl");
                if (!playlist) return true;
                String head = new String(buf, 0, n, "UTF-8");
                return head.contains("#EXTM3U") || head.contains("#EXT-X-");
            } catch (Exception e) {
                return false;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    /** The player found the channel broken (all links failed): hide it until it's re-checked. */
    void markBad(IptvFeed.Channel c) {
        health.put(c.key(), new long[]{0, System.currentTimeMillis(), 0});
        saveHealth();
    }

    void markGood(IptvFeed.Channel c, int alt) {
        health.put(c.key(), new long[]{1, System.currentTimeMillis(), alt});
    }

    private void loadHealth() {
        File f = new File(dir, "health.txt");
        if (!f.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length == 4) health.put(p[0], new long[]{Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3])});
            }
            r.close();
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveHealth() {
        try {
            File tmp = new File(dir, "health.txt.tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            long now = System.currentTimeMillis();
            for (Map.Entry<String, long[]> e : health.entrySet()) {
                long[] h = e.getValue();
                if (now - h[1] > OK_AGE) continue;
                w.write(e.getKey() + "\t" + h[0] + "\t" + h[1] + "\t" + h[2] + "\n");
            }
            w.close();
            tmp.renameTo(new File(dir, "health.txt"));
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- search (full list, background)

    /** Search by name. Uses the full list once it's loaded; until then, every section seen so far. */
    List<IptvFeed.Channel> search(String q) {
        List<IptvFeed.Channel> pool = index != null ? index : new ArrayList<IptvFeed.Channel>(byKey.values());
        List<IptvFeed.Channel> hits = IptvFeed.search(pool, q);
        List<IptvFeed.Channel> out = new ArrayList<IptvFeed.Channel>();
        for (IptvFeed.Channel c : hits) if (state(c) != 0) out.add(c);
        return out.size() > 300 ? new ArrayList<IptvFeed.Channel>(out.subList(0, 300)) : out;
    }

    boolean searchReady() {
        return index != null;
    }

    /** Loads the full list for searching (once a day), then tells listeners with section "search". */
    void loadIndex() {
        if (index != null || indexLoading) return;
        indexLoading = true;
        loader.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<IptvFeed.Channel> l = IptvFeed.load(dir, false);
                    for (IptvFeed.Channel c : l) {
                        IptvFeed.Channel known = byKey.get(c.key());
                        if (known == null) byKey.put(c.key(), c);
                    }
                    List<IptvFeed.Channel> canon = new ArrayList<IptvFeed.Channel>(l.size());
                    for (IptvFeed.Channel c : l) canon.add(byKey.get(c.key()));
                    index = canon;
                } catch (IOException e) {
                    error = "Search needs the full channel list, which couldn't be downloaded (" + e.getMessage() + ")";
                } finally {
                    indexLoading = false;
                    changed("search");
                }
            }
        });
    }

    /** Checks search results too, so broken ones drop out. */
    void checkList(final String tag, List<IptvFeed.Channel> l) {
        String key = "list:" + tag;
        sections.put(key, l);
        check(key);
    }

    // ---------------------------------------------------------------- favourites & recent

    IptvFeed.Channel find(String key) {
        return byKey.get(key);
    }

    boolean isFavourite(IptvFeed.Channel c) {
        return list("favs").contains(c.key());
    }

    void toggleFavourite(IptvFeed.Channel c) {
        List<String> f = list("favs");
        if (!f.remove(c.key())) f.add(c.key());
        putList("favs", f);
        remember(c, false);
    }

    void remember(IptvFeed.Channel c, boolean recent) {
        // keep a copy of the channel itself, so favourites and recent work before any section is loaded
        sp.edit().putString("ch:" + c.key(), encode(c)).apply();
        if (!recent) return;
        List<String> r = list("recent");
        r.remove(c.key());
        r.add(0, c.key());
        while (r.size() > 30) {
            String gone = r.remove(r.size() - 1);
            if (!list("favs").contains(gone)) sp.edit().remove("ch:" + gone).apply();
        }
        putList("recent", r);
    }

    private List<IptvFeed.Channel> byKeys(List<String> keys) {
        List<IptvFeed.Channel> out = new ArrayList<IptvFeed.Channel>();
        for (String k : keys) {
            IptvFeed.Channel c = byKey.get(k);
            if (c == null) {
                c = decode(sp.getString("ch:" + k, ""));
                if (c != null) byKey.put(k, c);
            }
            if (c != null) out.add(c);
        }
        return out;
    }

    /** A channel saved as one line of its playlist entry (so it survives without the playlist). */
    static String encode(IptvFeed.Channel c) {
        StringBuilder b = new StringBuilder();
        b.append("#EXTINF:-1 tvg-id=\"").append(c.id).append("\" tvg-logo=\"").append(c.logo)
                .append("\" group-title=\"").append(join(c.groups)).append("\",").append(c.name);
        if (c.quality.length() > 0) b.append(" (").append(c.quality).append(")");
        b.append('\n');
        appendLink(b, c);
        for (IptvFeed.Channel a : c.alts) {
            b.append("#EXTINF:-1 tvg-id=\"").append(c.id).append("\",").append(c.name).append('\n');
            appendLink(b, a);
        }
        return b.toString();
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() > 0 ? ";" : "").append(s);
        return b.toString();
    }

    private static void appendLink(StringBuilder b, IptvFeed.Channel l) {
        if (l.referrer.length() > 0) b.append("#EXTVLCOPT:http-referrer=").append(l.referrer).append('\n');
        if (l.userAgent.length() > 0) b.append("#EXTVLCOPT:http-user-agent=").append(l.userAgent).append('\n');
        b.append(l.url).append('\n');
    }

    static IptvFeed.Channel decode(String s) {
        if (s == null || s.length() == 0) return null;
        try {
            List<IptvFeed.Channel> l = IptvFeed.parse(s);
            return l.isEmpty() ? null : l.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> list(String k) {
        List<String> out = new ArrayList<String>();
        for (String s : sp.getString(k, "").split("\n")) if (s.length() > 0) out.add(s);
        return out;
    }

    private void putList(String k, List<String> l) {
        sp.edit().putString(k, TextUtils.join("\n", l)).apply();
    }

    // ---------------------------------------------------------------- phone remote

    /** JSON for the phone's TV tab: sections, the chosen section's channels, what's playing. */
    String json(String section, String query) {
        StringBuilder b = new StringBuilder("{\"sections\":[");
        List<String> names = sectionNames();
        for (int i = 0; i < names.size(); i++) b.append(i > 0 ? "," : "").append(RemoteServer.json(names.get(i)));
        b.append("],\"section\":").append(RemoteServer.json(section == null ? "" : section));
        List<IptvFeed.Channel> l;
        boolean busy;
        if (query != null && query.trim().length() > 0) {
            loadIndex();
            l = search(query);
            busy = !searchReady();
        } else {
            String s = section == null || section.length() == 0 ? (names.size() > 2 ? names.get(2) : FAV) : section;
            l = section(s, false);
            busy = !loaded(s) || checking(s);
        }
        b.append(",\"busy\":").append(busy).append(",\"channels\":[");
        int n = 0;
        for (IptvFeed.Channel c : l) {
            if (n >= 200) break;
            b.append(n++ > 0 ? "," : "").append("{\"k\":").append(RemoteServer.json(c.key()))
                    .append(",\"n\":").append(RemoteServer.json(c.name))
                    .append(",\"l\":").append(RemoteServer.json(c.logo))
                    .append(",\"b\":").append(RemoteServer.json(c.badge()))
                    .append(",\"f\":").append(isFavourite(c))
                    .append(",\"s\":").append(state(c)).append('}');
        }
        b.append("],\"playing\":");
        IptvFeed.Channel p = LiveTvActivity.nowPlaying();
        b.append(p == null ? "null" : "{\"k\":" + RemoteServer.json(p.key()) + ",\"n\":" + RemoteServer.json(p.name) + "}");
        b.append(",\"error\":").append(RemoteServer.json(error)).append('}');
        return b.toString();
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<String, String>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }
}
