package com.lumen.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live TV channels from the iptv-org project (github.com/iptv-org/iptv): a community list of free,
 * publicly available streams. Lumen hosts nothing; it downloads the public playlist, caches it for a day,
 * and plays the links. Parsing is pure Java (tested on a desktop JVM).
 */
final class IptvFeed {

    /** All channels, grouped by category in group-title; the country is the tvg-id suffix (CNN.us@SD). */
    static final String PLAYLIST = "https://iptv-org.github.io/iptv/index.m3u";
    static final long MAX_AGE = 24L * 3600 * 1000;

    static final class Channel {
        String name = "", id = "", logo = "", url = "", referrer = "", userAgent = "", feed = "";
        String country = "";              // ISO 3166 alpha-2, lower case ("in", "ae", "us"), "" if unknown
        final List<String> groups = new ArrayList<String>();   // categories ("News", "Movies"…)
        boolean geoBlocked, notAlways;    // iptv-org's [Geo-blocked] / [Not 24/7] marks
        String quality = "";              // "1080p", "720p"… when the name says so
        /** Other links for the same channel (iptv-org often has several); tried in turn when one fails. */
        final List<Channel> alts = new ArrayList<Channel>();

        String key() {
            return id.length() > 0 ? id : url;
        }

        String badge() {
            StringBuilder b = new StringBuilder();
            if (quality.length() > 0) b.append(quality);
            if (country.length() > 0) b.append(b.length() > 0 ? "  ·  " : "").append(country.toUpperCase(Locale.ROOT));
            if (geoBlocked) b.append(b.length() > 0 ? "  ·  " : "").append("may be region-locked");
            else if (notAlways) b.append(b.length() > 0 ? "  ·  " : "").append("not 24/7");
            return b.toString();
        }
    }

    private IptvFeed() {
    }

    // ---------------------------------------------------------------- parsing (pure)

    static List<Channel> parse(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in, 65536);
        List<Channel> out = new ArrayList<Channel>();
        Map<String, Channel> seen = new HashMap<String, Channel>();
        Channel cur = null;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#EXTINF")) {
                cur = new Channel();
                cur.id = attr(line, "tvg-id");
                cur.logo = attr(line, "tvg-logo");
                String groups = attr(line, "group-title");
                for (String g : groups.split(";")) {
                    g = g.trim();
                    if (g.length() > 0 && !cur.groups.contains(g)) cur.groups.add(g);
                }
                int comma = lastTopComma(line);
                cur.name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                tidyName(cur);
                cur.country = countryOf(cur.id);
                int at = cur.id.indexOf('@');
                if (at > 0) cur.feed = cur.id.substring(at + 1);
            } else if (line.startsWith("#EXTVLCOPT:") && cur != null) {
                String o = line.substring(11).trim();
                if (o.startsWith("http-referrer=")) cur.referrer = o.substring(14).trim();
                else if (o.startsWith("http-user-agent=")) cur.userAgent = o.substring(16).trim();
            } else if (line.startsWith("#")) {
                // other directives (#EXTM3U, #KODIPROP…) aren't needed
            } else if (cur != null) {
                cur.url = line;
                if (isPlayable(cur) && !adult(cur)) {
                    String k = cur.id.toLowerCase(Locale.ROOT) + "|" + norm(cur.name);
                    Channel first = seen.get(k);
                    if (first == null) {
                        seen.put(k, cur);
                        out.add(cur);
                    } else {
                        first.alts.add(cur);                         // same channel, another link
                        if (first.logo.length() == 0) first.logo = cur.logo;
                        for (String g : cur.groups) if (!first.groups.contains(g)) first.groups.add(g);
                    }
                }
                cur = null;
            }
        }
        return out;
    }

    static List<Channel> parse(String m3u) throws IOException {
        return parse(new StringReader(m3u));
    }

    /** The attribute value inside an #EXTINF line ("" if absent). */
    static String attr(String line, String name) {
        String key = name + "=\"";
        int i = line.indexOf(key);
        if (i < 0) return "";
        int s = i + key.length(), e = line.indexOf('"', s);
        return e < 0 ? "" : line.substring(s, e).trim();
    }

    /** The comma that starts the display name: the first one after the last attribute (names may hold commas). */
    private static int lastTopComma(String line) {
        int q = line.lastIndexOf('"');
        return line.indexOf(',', q < 0 ? 0 : q);
    }

    /** "Zee News (1080p) [Geo-blocked]" → name "Zee News", quality "1080p", geoBlocked. */
    static void tidyName(Channel c) {
        String n = c.name;
        if (n.contains("[Geo-blocked]")) c.geoBlocked = true;
        if (n.contains("[Not 24/7]")) c.notAlways = true;
        n = n.replaceAll("\\s*\\[[^\\]]*\\]", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d{3,4}[pi])\\)").matcher(n);
        if (m.find()) c.quality = m.group(1);
        n = n.replaceAll("\\s*\\((\\d{3,4}[pi])\\)", "").trim();
        c.name = n.length() > 0 ? n : c.id;
    }

    /** "CNN.us@SD" → "us"; "" when there's no country part. */
    static String countryOf(String id) {
        String s = id;
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        int dot = s.lastIndexOf('.');
        if (dot < 0 || dot == s.length() - 1) return "";
        String cc = s.substring(dot + 1).toLowerCase(Locale.ROOT);
        return cc.matches("[a-z]{2}") ? cc : "";
    }

    private static boolean isPlayable(Channel c) {
        String u = c.url.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("rtmp://") || u.startsWith("rtsp://");
    }

    /** Lumen never lists adult channels. */
    private static boolean adult(Channel c) {
        for (String g : c.groups) if (g.equalsIgnoreCase("XXX") || g.equalsIgnoreCase("Adult")) return true;
        return false;
    }

    // ---------------------------------------------------------------- grouping (pure)

    /** Categories by how many channels they have (biggest first); "Undefined" goes last. */
    static List<String> categories(List<Channel> all) {
        final Map<String, Integer> n = new HashMap<String, Integer>();
        for (Channel c : all) for (String g : c.groups) n.put(g, n.containsKey(g) ? n.get(g) + 1 : 1);
        List<String> out = new ArrayList<String>(n.keySet());
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                boolean ua = a.equalsIgnoreCase("Undefined"), ub = b.equalsIgnoreCase("Undefined");
                if (ua != ub) return ua ? 1 : -1;
                return n.get(b) - n.get(a);
            }
        });
        return out;
    }

    /** Countries by channel count, as {code → count}, biggest first. */
    static LinkedHashMap<String, Integer> countries(List<Channel> all) {
        final Map<String, Integer> n = new HashMap<String, Integer>();
        for (Channel c : all) if (c.country.length() == 2) n.put(c.country, n.containsKey(c.country) ? n.get(c.country) + 1 : 1);
        List<String> keys = new ArrayList<String>(n.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return n.get(b) - n.get(a);
            }
        });
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String k : keys) out.put(k, n.get(k));
        return out;
    }

    static List<Channel> inCategory(List<Channel> all, String cat) {
        List<Channel> out = new ArrayList<Channel>();
        for (Channel c : all) if (c.groups.contains(cat)) out.add(c);
        return out;
    }

    static List<Channel> inCountry(List<Channel> all, String cc) {
        List<Channel> out = new ArrayList<Channel>();
        for (Channel c : all) if (c.country.equals(cc)) out.add(c);
        return out;
    }

    /** Channels whose name matches what was typed or said, best first ("cnn", "zee news", "sun tv"). */
    static List<Channel> search(List<Channel> all, String q) {
        final String want = norm(q);
        List<Channel> out = new ArrayList<Channel>();
        if (want.length() == 0) return out;
        final Map<Channel, Integer> score = new HashMap<Channel, Integer>();
        for (Channel c : all) {
            String n = norm(c.name);
            int s;
            if (n.equals(want)) s = 0;
            else if (n.startsWith(want + " ") || n.startsWith(want)) s = 1;
            else if ((" " + n + " ").contains(" " + want + " ")) s = 2;
            else if (n.contains(want)) s = 3;
            else continue;
            score.put(c, s);
            out.add(c);
        }
        Collections.sort(out, new Comparator<Channel>() {
            @Override
            public int compare(Channel a, Channel b) {
                int d = score.get(a) - score.get(b);
                if (d != 0) return d;
                if (a.geoBlocked != b.geoBlocked) return a.geoBlocked ? 1 : -1;
                return a.name.length() - b.name.length();
            }
        });
        return out;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- download + cache (background thread)

    /** The cached playlist, refreshed when older than a day (or forced). Falls back to the cache when offline. */
    static List<Channel> load(File cacheDir, boolean force) throws IOException {
        File f = new File(cacheDir, "iptv-index.m3u");
        boolean stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE;
        if (stale || force) {
            try {
                download(f);
            } catch (IOException e) {
                if (!f.exists()) throw e;   // offline but we have yesterday's list: use it
            }
        }
        InputStream in = new FileInputStream(f);
        try {
            return parse(new InputStreamReader(in, "UTF-8"));
        } finally {
            in.close();
        }
    }

    private static void download(File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(PLAYLIST).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Lumen-Launcher");
        try {
            if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
            File tmp = new File(to.getPath() + ".tmp");
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
            if (tmp.length() < 1000) throw new IOException("The channel list came back empty");
            if (!tmp.renameTo(to)) throw new IOException("Couldn't save the channel list");
        } finally {
            c.disconnect();
        }
    }
}
