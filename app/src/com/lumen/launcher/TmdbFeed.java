package com.lumen.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Popular on Netflix / Prime Video in your country", from TMDB's discover API filtered by streaming
 * provider and region (TMDB's availability data comes from JustWatch). Netflix and Prime themselves
 * offer no public API, so this is the closest honest equivalent: not personalised, but country-aware.
 * Pure Java, testable off-device.
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class TmdbFeed {

    static final String NETFLIX = "8";
    static final String PRIME = "9|119";

    static final class Title {
        final String id, name, type, year, backdrop;
        final double rating, popularity;
        int rank;

        Title(String id, String name, String type, String year, String backdrop, double rating, double popularity) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.year = year;
            this.backdrop = backdrop;
            this.rating = rating;
            this.popularity = popularity;
        }

        String meta() {
            StringBuilder b = new StringBuilder("tv".equals(type) ? "Series" : "Movie");
            if (year.length() > 0) b.append("  ·  ").append(year);
            if (rating > 0) b.append("  ·  ★ ").append(String.format(Locale.ROOT, "%.1f", rating));
            return b.toString();
        }

        String imageUrl() {
            return "https://image.tmdb.org/t/p/w300" + backdrop;
        }
    }

    private TmdbFeed() {
    }

    static String url(String type, String providers, String region, String key) throws IOException {
        String u = "https://api.themoviedb.org/3/discover/" + type
                + "?watch_region=" + region
                + "&with_watch_providers=" + java.net.URLEncoder.encode(providers, "UTF-8")
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&language=en-US&page=1";
        if (!bearer(key)) u += "&api_key=" + java.net.URLEncoder.encode(key, "UTF-8");
        return u;
    }

    /** v4 "API Read Access Token" (a JWT) goes in a header; the short v3 "API key" goes in the URL. */
    static boolean bearer(String key) {
        return key.startsWith("eyJ") && key.length() > 60;
    }

    /** Network: movies + series for one provider, merged by popularity. Returns the two raw JSONs joined. */
    static String[] download(String key, String region, String providers) throws IOException {
        return new String[]{get(url("movie", providers, region, key), key), get(url("tv", providers, region, key), key)};
    }

    static String get(String u, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer(key)) c.setRequestProperty("Authorization", "Bearer " + key);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(TrendingFeed.readAll(in), "UTF-8");
        c.disconnect();
        if (code == 401) throw new IOException("That TMDB key isn't valid");
        if (code == 429) throw new IOException("TMDB is busy; it will retry later");
        if (code >= 400) throw new IOException("TMDB returned error " + code);
        return body;
    }

    /** Parses and merges the movie + tv responses; keeps titles that have a 16:9 backdrop image. */
    @SuppressWarnings("unchecked")
    static List<Title> parse(String movieJson, String tvJson, int max) {
        List<Title> movies = new ArrayList<Title>(), shows = new ArrayList<Title>();
        for (int pass = 0; pass < 2; pass++) {
            Object root = TrendingFeed.Json.parse(pass == 0 ? movieJson : tvJson);
            Object results = TrendingFeed.Json.path(root, "results");
            if (!(results instanceof List)) continue;
            for (Object o : (List<Object>) results) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                String backdrop = str(m.get("backdrop_path"));
                if (backdrop.length() == 0) continue;
                String name = str(m.get(pass == 0 ? "title" : "name"));
                if (name.length() == 0) continue;
                String date = str(m.get(pass == 0 ? "release_date" : "first_air_date"));
                Object idObj = m.get("id");
                String id = idObj instanceof Double ? String.valueOf(((Double) idObj).longValue()) : str(idObj);
                Title t = new Title(id, name, pass == 0 ? "movie" : "tv", date.length() >= 4 ? date.substring(0, 4) : "",
                        backdrop, num(m.get("vote_average")), num(m.get("popularity")));
                (pass == 0 ? movies : shows).add(t);
            }
        }
        // interleave so the row mixes films and series, most popular first
        List<Title> out = new ArrayList<Title>();
        int i = 0, j = 0;
        while (out.size() < max && (i < movies.size() || j < shows.size())) {
            boolean takeMovie = j >= shows.size() || (i < movies.size() && movies.get(i).popularity >= shows.get(j).popularity);
            out.add(takeMovie ? movies.get(i++) : shows.get(j++));
        }
        for (int k = 0; k < out.size(); k++) out.get(k).rank = k + 1;
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o) {
        return o instanceof Double ? (Double) o : 0;
    }
}
