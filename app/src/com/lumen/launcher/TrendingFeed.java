package com.lumen.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What's trending on YouTube in your country, from the official YouTube Data API v3
 * (videos.list chart=mostPopular — 1 quota unit per refresh; the free daily quota is 10,000).
 * Pure Java, including a tiny JSON reader, so it can be tested off-device.
 */
final class TrendingFeed {

    static final class Video {
        final String id, title, channel, thumb;
        final long views;
        final int seconds;
        int rank;

        Video(String id, String title, String channel, String thumb, long views, int seconds) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.thumb = thumb;
            this.views = views;
            this.seconds = seconds;
        }

        String meta() {
            StringBuilder b = new StringBuilder(channel);
            if (views > 0) b.append("  ·  ").append(compact(views)).append(" views");
            if (seconds > 0) b.append("  ·  ").append(duration(seconds));
            return b.toString();
        }
    }

    private TrendingFeed() {
    }

    static String url(String key, String region, int max) throws IOException {
        return "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails,statistics"
                + "&chart=mostPopular&maxResults=" + max
                + "&regionCode=" + URLEncoder.encode(region, "UTF-8")
                + "&key=" + URLEncoder.encode(key, "UTF-8");
    }

    /** Network call: returns the raw JSON (throws with YouTube's own error message on failure). */
    static String download(String key, String region, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url(key, region, max)).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(readAll(in), "UTF-8");
        c.disconnect();
        if (code >= 400) throw new IOException(errorMessage(body, code));
        return body;
    }

    /**
     * Country of the network the projector is on, from the public IP of its Wi-Fi connection.
     * Uses Cloudflare's lightweight trace endpoint (no key, HTTPS), with ipapi.co as a backup.
     * Returns a 2-letter code, or null if it can't be determined (e.g. offline).
     */
    static String detectCountry() {
        try {
            String c = parseTrace(get("https://www.cloudflare.com/cdn-cgi/trace"));
            if (c != null) return c;
        } catch (IOException ignored) {
        }
        try {
            String c = get("https://ipapi.co/country/").trim().toUpperCase(Locale.ROOT);
            if (validCountry(c)) return c;
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Picks "loc=AE" out of a Cloudflare trace response. */
    static String parseTrace(String body) {
        if (body == null) return null;
        for (String line : body.split("\n")) {
            line = line.trim();
            if (line.startsWith("loc=")) {
                String c = line.substring(4).trim().toUpperCase(Locale.ROOT);
                return validCountry(c) ? c : null;
            }
        }
        return null;
    }

    /** Real ISO country codes only (not Cloudflare's XX = unknown or T1 = Tor). */
    static boolean validCountry(String c) {
        return c != null && c.matches("[A-Z]{2}") && !"XX".equals(c) && !"T1".equals(c);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent", "Lumen-Launcher");
        try {
            if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static String errorMessage(String body, int code) {
        try {
            Object root = Json.parse(body);
            Object msg = Json.path(root, "error", "message");
            if (msg instanceof String) {
                String m = ((String) msg).replaceAll("<[^>]+>", "");
                if (m.contains("API key not valid")) return "That API key isn't valid";
                if (m.contains("has not been used") || m.contains("disabled")) return "Enable \"YouTube Data API v3\" for this key's project";
                if (m.contains("quota")) return "Today's YouTube quota is used up; it resets at midnight Pacific time";
                return m;
            }
        } catch (RuntimeException ignored) {
        }
        return "YouTube returned error " + code;
    }

    /** Parses a videos.list response. */
    @SuppressWarnings("unchecked")
    static List<Video> parse(String json) {
        List<Video> out = new ArrayList<Video>();
        Object items = Json.path(Json.parse(json), "items");
        if (!(items instanceof List)) return out;
        int rank = 1;
        for (Object it : (List<Object>) items) {
            String id = str(Json.path(it, "id"));
            String title = str(Json.path(it, "snippet", "title"));
            if (id.length() == 0 || title.length() == 0) continue;
            String thumb = str(Json.path(it, "snippet", "thumbnails", "medium", "url"));
            if (thumb.length() == 0) thumb = "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
            long views = 0;
            try {
                views = Long.parseLong(str(Json.path(it, "statistics", "viewCount")));
            } catch (NumberFormatException ignored) {
            }
            Video v = new Video(id, title, str(Json.path(it, "snippet", "channelTitle")), thumb, views,
                    isoSeconds(str(Json.path(it, "contentDetails", "duration"))));
            v.rank = rank++;
            out.add(v);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static final Pattern ISO = Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");

    static int isoSeconds(String d) {
        Matcher m = ISO.matcher(d == null ? "" : d);
        if (!m.matches()) return 0;
        int s = 0;
        if (m.group(1) != null) s += Integer.parseInt(m.group(1)) * 86400;
        if (m.group(2) != null) s += Integer.parseInt(m.group(2)) * 3600;
        if (m.group(3) != null) s += Integer.parseInt(m.group(3)) * 60;
        if (m.group(4) != null) s += Integer.parseInt(m.group(4));
        return s;
    }

    static String duration(int s) {
        if (s >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static String compact(long n) {
        if (n >= 1_000_000_000L) return trim(n / 1e9) + "B";
        if (n >= 1_000_000L) return trim(n / 1e6) + "M";
        if (n >= 1_000L) return trim(n / 1e3) + "K";
        return String.valueOf(n);
    }

    private static String trim(double v) {
        return v >= 100 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v).replace(".0", "");
    }

    /** Minimal JSON reader: objects → Map, arrays → List, numbers → Double, plus String/Boolean/null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json j = new Json(s == null ? "" : s);
            j.ws();
            return j.value();
        }

        @SuppressWarnings("unchecked")
        static Object path(Object o, String... keys) {
            for (String k : keys) {
                if (!(o instanceof Map)) return null;
                o = ((Map<String, Object>) o).get(k);
            }
            return o;
        }

        private Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("end of json");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("bad json at " + i);
            return Double.valueOf(s.substring(st, i));
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new HashMap<String, Object>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n':
                            b.append('\n');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 'b':
                            b.append('\b');
                            break;
                        case 'f':
                            b.append('\f');
                            break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
