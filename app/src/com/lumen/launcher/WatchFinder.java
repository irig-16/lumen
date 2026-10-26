package com.lumen.launcher;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Where can I watch Interstellar?" — finds a film or series on TMDB and which services stream it in the
 * viewer's country (TMDB's watch-provider data comes from JustWatch). Pure Java (tested on a desktop JVM).
 */
final class WatchFinder {

    static final class Match {
        int id;
        String type = "movie";         // movie | tv
        String title = "";
        String year = "";
        double popularity;
        double score;                  // how closely the title matches what was said, 0..1
        final List<String> stream = new ArrayList<String>();   // subscription ("flatrate") and free services
        final List<String> rent = new ArrayList<String>();

        String label() {
            return title + (year.length() > 0 ? " (" + year + ")" : "");
        }
    }

    private WatchFinder() {
    }

    /** Looks the title up and fills in the services for {@code region}. Null if nothing matches. */
    static Match find(String key, String query, String region) throws IOException {
        String q = URLEncoder.encode(query, "UTF-8");
        String u = "https://api.themoviedb.org/3/search/multi?query=" + q + "&include_adult=false&language=en-US&page=1";
        if (!TmdbFeed.bearer(key)) u += "&api_key=" + URLEncoder.encode(key, "UTF-8");
        Match m = best(TmdbFeed.get(u, key), query);
        if (m == null) return null;
        String p = "https://api.themoviedb.org/3/" + m.type + "/" + m.id + "/watch/providers";
        if (!TmdbFeed.bearer(key)) p += "?api_key=" + URLEncoder.encode(key, "UTF-8");
        providers(m, TmdbFeed.get(p, key), region);
        return m;
    }

    /** Picks the result whose title best matches what was said (popularity breaks ties). */
    static Match best(String searchJson, String query) {
        Object list = TrendingFeed.Json.path(TrendingFeed.Json.parse(searchJson), "results");
        if (!(list instanceof List)) return null;
        String want = norm(query);
        Match best = null;
        int rank = 0;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map) || rank++ > 12) continue;
            Map<?, ?> r = (Map<?, ?>) o;
            String type = str(r.get("media_type"));
            if (!"movie".equals(type) && !"tv".equals(type)) continue;
            Match m = new Match();
            m.type = type;
            m.id = (int) num(r.get("id"));
            m.title = str(r.get("movie".equals(type) ? "title" : "name"));
            String date = str(r.get("movie".equals(type) ? "release_date" : "first_air_date"));
            m.year = date.length() >= 4 ? date.substring(0, 4) : "";
            m.popularity = num(r.get("popularity"));
            String orig = str(r.get("movie".equals(type) ? "original_title" : "original_name"));
            m.score = Math.max(similarity(want, norm(m.title)), similarity(want, norm(orig)));
            // a small boost for popular titles, so "dune" finds the film people mean
            double rankScore = m.score + Math.min(0.08, Math.log10(1 + m.popularity) / 40);
            double bestScore = best == null ? -1 : best.score + Math.min(0.08, Math.log10(1 + best.popularity) / 40);
            if (rankScore > bestScore) best = m;
        }
        return best != null && best.score >= 0.55 ? best : null;
    }

    /** Fills in the streaming (subscription or free) and rental services for one country. */
    static void providers(Match m, String json, String region) {
        Object r = TrendingFeed.Json.path(TrendingFeed.Json.parse(json), "results", region);
        if (!(r instanceof Map)) return;
        for (String kind : new String[]{"flatrate", "free", "ads"}) addNames(((Map<?, ?>) r).get(kind), m.stream);
        addNames(((Map<?, ?>) r).get("rent"), m.rent);
        addNames(((Map<?, ?>) r).get("buy"), m.rent);
    }

    private static void addNames(Object list, List<String> into) {
        if (!(list instanceof List)) return;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            String n = cleanProvider(str(((Map<?, ?>) o).get("provider_name")));
            if (n.length() > 0 && !into.contains(n)) into.add(n);
        }
    }

    /** "Netflix Standard with Ads" → "Netflix", "Amazon Prime Video" → "Prime Video", "Disney Plus" → "Disney+". */
    static String cleanProvider(String n) {
        String s = n.trim();
        String l = s.toLowerCase(Locale.ROOT);
        if (l.startsWith("netflix")) return "Netflix";
        if (l.contains("prime video")) return "Prime Video";
        if (l.equals("amazon video")) return "Amazon";
        if (l.startsWith("disney")) return l.contains("hotstar") ? "Disney+ Hotstar" : "Disney+";
        if (l.startsWith("apple tv")) return l.contains("plus") || l.contains("+") ? "Apple TV+" : "Apple TV";
        if (l.startsWith("shahid")) return "Shahid";
        if (l.startsWith("osn")) return "OSN+";
        if (l.startsWith("google play")) return "Google Play";
        if (l.startsWith("youtube")) return "YouTube";
        if (l.contains("channel")) return "";     // "… Amazon Channel" add-ons: the base service is listed too
        return s;
    }

    /** One sentence for the voice reply. {@code only} narrows it to "is it on Netflix?". */
    static String sentence(Match m, String country, String only) {
        String where = country == null || country.length() == 0 ? "" : " in " + country;
        if (only != null && only.length() > 0) {
            for (String s : m.stream) {
                if (norm(s).contains(norm(only)) || norm(only).contains(norm(s))) return "Yes, " + m.label() + " is on " + s + where;
            }
            if (!m.stream.isEmpty()) return "No, but " + m.label() + " is on " + join(m.stream) + where;
            return "No, " + m.label() + " isn't streaming on " + only + where;
        }
        if (!m.stream.isEmpty()) return m.label() + " is streaming on " + join(m.stream) + where;
        if (!m.rent.isEmpty()) return m.label() + " isn't on a subscription" + where + ". You can rent or buy it on " + join(m.rent);
        return "I couldn't find anywhere streaming " + m.label() + where;
    }

    static String join(List<String> l) {
        int n = Math.min(l.size(), 4);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(i == n - 1 ? " and " : ", ");
            b.append(l.get(i));
        }
        return b.toString();
    }

    static String norm(String s) {
        return (s == null ? "" : s).toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("^(?:the|a|an) ", "").replaceAll("\\s+", " ").trim();
    }

    /** 1 for the same title, high for a prefix ("dune" vs "dune part two"), otherwise edit-distance based. */
    static double similarity(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return 0;
        if (a.equals(b)) return 1;
        if (b.startsWith(a + " ") || a.startsWith(b + " ")) return 0.85;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - prev[b.length()] / (double) Math.max(a.length(), b.length());
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
