package com.lumen.launcher;

/** Where this build of Lumen comes from and where it looks for updates. */
final class LumenConfig {

    /** The open-source project. */
    static final String REPO = "irig-16/lumen";
    static final String PROJECT_URL = "github.com/" + REPO;

    /**
     * The update channel: an update.json kept in the repo (ota/update.json on main), which points at the
     * signed Lumen.apk next to it. Projectors read it at startup and every 6 hours.
     */
    static final String UPDATE_SOURCE = "https://raw.githubusercontent.com/" + REPO + "/main/ota/update.json";

    /**
     * Lumen's key service until the update channel names one: proxy/worker.js on Cloudflare, which adds the
     * YouTube, TMDB and Pixabay keys on its side. Empty = none yet. The channel's "keyProxy" overrides it.
     */
    static final String KEY_PROXY = "";

    private LumenConfig() {
    }
}
