package com.lumen.launcher;

/**
 * Lumen's key service: when you haven't entered your own API keys, trending, "where to watch" and
 * Lumen Window go through a small proxy (proxy/worker.js in the repo, on Cloudflare) that adds the
 * keys on its side. The keys never ship in the APK, the repo or the projector.
 *
 * Inside Lumen the proxy travels as a pseudo-key "proxy:https://…", so the feeds need no context.
 * Pure Java (tested on a desktop JVM).
 */
final class ApiProxy {

    static final String PREFIX = "proxy:";
    private static final String[][] ROUTES = {
            {"https://www.googleapis.com/youtube/v3/", "/yt/"},
            {"https://api.themoviedb.org/3/", "/tmdb/"},
            {"https://pixabay.com/api/", "/pixabay/"},
    };

    private ApiProxy() {
    }

    /** The pseudo-key for a proxy URL ("" when there's no proxy). */
    static String key(String proxyUrl) {
        if (proxyUrl == null) return "";
        String u = proxyUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.startsWith("https://") ? PREFIX + u : "";
    }

    static boolean is(String key) {
        return key != null && key.startsWith(PREFIX + "https://");
    }

    /** An official API URL (without any key in it) rewritten to go through the proxy. */
    static String route(String key, String officialUrl) {
        String base = key.substring(PREFIX.length());
        for (String[] r : ROUTES) {
            if (officialUrl.startsWith(r[0])) return base + r[1] + officialUrl.substring(r[0].length());
        }
        return officialUrl;
    }
}
