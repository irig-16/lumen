package com.lumen.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lumen Window's scenes: real outdoor video clips, downloaded once from Pixabay (free stock video) and kept on
 * the projector, so they play offline. People can also add their own videos. Pure Java (tested on a desktop JVM).
 */
final class SceneLibrary {

    static final class Scene {
        final String id, name, query;

        Scene(String id, String name, String query) {
            this.id = id;
            this.name = name;
            this.query = query;
        }
    }

    /** The ten scenes, each a Pixabay search for real footage. */
    static final Scene[] SCENES = {
            new Scene("ocean", "Ocean waves", "ocean waves beach"),
            new Scene("mountains", "Snowy mountains", "snow mountains"),
            new Scene("rain", "Rain in the forest", "rain forest"),
            new Scene("city", "City at night", "city night skyline"),
            new Scene("waterfall", "Waterfall", "waterfall"),
            new Scene("aurora", "Northern lights", "aurora borealis"),
            new Scene("desert", "Desert dunes", "desert dunes"),
            new Scene("autumn", "Autumn forest", "autumn forest"),
            new Scene("reef", "Coral reef", "coral reef underwater"),
            new Scene("lake", "Lake at sunrise", "lake sunrise")};

    /** A clip on disk. */
    static final class Clip {
        String sceneId = "", name = "", file = "", credit = "";
        int sourceId;           // Pixabay id (0 for the user's own videos)

        String json() {
            return "{\"scene\":" + RemoteServer.json(sceneId) + ",\"name\":" + RemoteServer.json(name) + ",\"file\":" + RemoteServer.json(file)
                    + ",\"credit\":" + RemoteServer.json(credit) + ",\"source\":" + sourceId + "}";
        }
    }

    /** One downloadable Pixabay video. */
    static final class Found {
        int id;
        String url = "", user = "";
        int width, height;
        long size;
        double duration;
    }

    static final long MAX_CLIP = 45L * 1024 * 1024;

    private final File dir;
    private final List<Clip> clips = new ArrayList<Clip>();
    private volatile String status = "";
    private volatile boolean busy;

    SceneLibrary(File dir) {
        this.dir = dir;
        dir.mkdirs();
        load();
    }

    File dir() {
        return dir;
    }

    synchronized List<Clip> clips() {
        List<Clip> out = new ArrayList<Clip>();
        for (Clip c : clips) if (new File(dir, c.file).isFile()) out.add(c);
        return out;
    }

    synchronized Clip clipFor(String sceneId) {
        for (Clip c : clips) if (c.sceneId.equals(sceneId) && new File(dir, c.file).isFile()) return c;
        return null;
    }

    static Scene scene(String id) {
        for (Scene s : SCENES) if (s.id.equals(id)) return s;
        return null;
    }

    /** "beach", "the ocean", "snow" → a scene id, or null. */
    static String sceneFor(String spoken) {
        String s = spoken == null ? "" : spoken.toLowerCase(Locale.ROOT);
        String[][] words = {
                {"ocean", "ocean", "sea", "beach", "waves", "coast"}, {"mountains", "mountain", "snow", "alps", "winter"},
                {"rain", "rain", "rainy", "storm"}, {"city", "city", "skyline", "night city", "new york", "dubai"},
                {"waterfall", "waterfall", "falls", "river"}, {"aurora", "aurora", "northern lights", "lights", "stars", "night sky"},
                {"desert", "desert", "dunes", "sand"}, {"autumn", "autumn", "fall", "leaves", "forest"},
                {"reef", "reef", "coral", "underwater", "fish", "aquarium", "porthole"}, {"lake", "lake", "sunrise", "sunset", "calm"}};
        for (String[] w : words) for (int i = 1; i < w.length; i++) if (s.matches(".*\\b" + w[i] + "s?\\b.*")) return w[0];
        return null;
    }

    String status() {
        return status;
    }

    boolean busy() {
        return busy;
    }

    // ------------------------------------------------------------------ Pixabay

    /** Pixabay's video search (tests point this at a local server). */
    static String apiBase = "https://pixabay.com/api/videos/";

    static String searchUrl(String key, String query) throws IOException {
        String q = "q=" + URLEncoder.encode(query, "UTF-8")
                + "&video_type=film&min_width=1280&safesearch=true&order=popular&per_page=30";
        if (ApiProxy.is(key)) return ApiProxy.route(key, apiBase + "?" + q);
        return apiBase + "?key=" + URLEncoder.encode(key, "UTF-8") + "&" + q;
    }

    /**
     * Picks the best clip from a Pixabay search: landscape, long enough to loop calmly (12 s to 3 min), and the
     * smallest rendition that is at least 1280 wide (the projector is 720p), skipping ids already used.
     */
    static Found pick(String json, List<Integer> avoid) {
        Object hits = TrendingFeed.Json.path(TrendingFeed.Json.parse(json), "hits");
        if (!(hits instanceof List)) return null;
        for (Object o : (List<?>) hits) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> h = (Map<?, ?>) o;
            int id = (int) num(h.get("id"));
            double dur = num(h.get("duration"));
            if (avoid != null && avoid.contains(id)) continue;
            if (dur < 12 || dur > 180) continue;
            Object vids = h.get("videos");
            if (!(vids instanceof Map)) continue;
            Found best = null;
            for (String size : new String[]{"small", "medium", "large"}) {   // smallest that is still 720p or more
                Object v = ((Map<?, ?>) vids).get(size);
                if (!(v instanceof Map)) continue;
                Map<?, ?> vm = (Map<?, ?>) v;
                String url = str(vm.get("url"));
                int w = (int) num(vm.get("width")), hgt = (int) num(vm.get("height"));
                long bytes = (long) num(vm.get("size"));
                if (url.length() == 0 || w < 1280 || hgt <= 0 || w < hgt * 1.3) continue;    // landscape only
                if (bytes > MAX_CLIP) continue;
                best = new Found();
                best.id = id;
                best.url = url;
                best.width = w;
                best.height = hgt;
                best.size = bytes;
                best.duration = dur;
                best.user = str(h.get("user"));
                break;
            }
            if (best != null) return best;
        }
        return null;
    }

    interface Progress {
        void onProgress(String text);
    }

    /**
     * Downloads every scene that's missing (or all of them again when {@code fresh}). Runs on the caller's
     * thread; reports progress. Returns how many scenes are ready.
     */
    int downloadAll(String key, boolean fresh, Progress p) {
        if (busy) return clips().size();
        busy = true;
        int ready = 0, failed = 0;
        String lastError = "";
        try {
            for (int i = 0; i < SCENES.length; i++) {
                Scene s = SCENES[i];
                Clip have = clipFor(s.id);
                if (have != null && !fresh) {
                    ready++;
                    continue;
                }
                report(p, "Downloading " + s.name + " (" + (i + 1) + " of " + SCENES.length + ")…");
                try {
                    List<Integer> avoid = new ArrayList<Integer>();
                    if (have != null) avoid.add(have.sourceId);
                    Found f = pick(get(searchUrl(key, s.query)), avoid);
                    if (f == null) throw new IOException("no suitable clip");
                    String file = s.id + "-" + f.id + ".mp4";
                    download(f.url, new File(dir, file), p, s.name, i + 1);
                    Clip c = new Clip();
                    c.sceneId = s.id;
                    c.name = s.name;
                    c.file = file;
                    c.credit = f.user.length() > 0 ? "Video by " + f.user + " on Pixabay" : "Video from Pixabay";
                    c.sourceId = f.id;
                    synchronized (this) {
                        if (have != null) {
                            clips.remove(have);
                            new File(dir, have.file).delete();
                        }
                        clips.add(c);
                        save();
                    }
                    ready++;
                } catch (IOException e) {
                    failed++;
                    lastError = e.getMessage();
                    if (have != null) ready++;
                    if ("That Pixabay key isn't valid".equals(lastError)) break;
                }
            }
        } finally {
            busy = false;
        }
        status = failed == 0 ? ready + " scenes ready · they play offline"
                : ready + " scenes ready, " + failed + " couldn't download" + (lastError.length() > 0 ? " (" + lastError + ")" : "");
        if (p != null) p.onProgress(status);
        return ready;
    }

    /** Adds the user's own video (already saved in the scenes folder) as a scene. */
    synchronized Clip addOwn(String file, String name) {
        Clip c = new Clip();
        c.sceneId = "own-" + file.hashCode();
        c.name = name;
        c.file = file;
        c.credit = "Your video";
        clips.add(c);
        save();
        return c;
    }

    synchronized boolean remove(String file) {
        for (Clip c : clips) {
            if (c.file.equals(file)) {
                clips.remove(c);
                new File(dir, c.file).delete();
                save();
                return true;
            }
        }
        return false;
    }

    synchronized String json() {
        StringBuilder b = new StringBuilder("[");
        for (Clip c : clips()) b.append(b.length() > 1 ? "," : "").append(c.json());
        return b.append(']').toString();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        File f = new File(dir, "scenes.json");
        if (!f.isFile()) return;
        try {
            InputStream in = new java.io.FileInputStream(f);
            String s = new String(TrendingFeed.readAll(in), "UTF-8");
            Object list = TrendingFeed.Json.parse(s);
            if (!(list instanceof List)) return;
            for (Object o : (List<?>) list) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                Clip c = new Clip();
                c.sceneId = str(m.get("scene"));
                c.name = str(m.get("name"));
                c.file = str(m.get("file"));
                c.credit = str(m.get("credit"));
                c.sourceId = (int) num(m.get("source"));
                if (c.file.length() > 0 && !c.file.contains("/") && new File(dir, c.file).isFile()) clips.add(c);
            }
        } catch (Exception ignored) {
            // a damaged list: start empty; the files are re-listed on the next download
        }
    }

    private void save() {
        try {
            File tmp = new File(dir, "scenes.json.tmp");
            OutputStream out = new FileOutputStream(tmp);
            StringBuilder b = new StringBuilder("[");
            for (Clip c : clips) b.append(b.length() > 1 ? "," : "").append(c.json());
            out.write(b.append(']').toString().getBytes("UTF-8"));
            out.close();
            tmp.renameTo(new File(dir, "scenes.json"));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ network

    private void report(Progress p, String s) {
        status = s;
        if (p != null) p.onProgress(s);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Lumen-Launcher");
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code == 400 || code == 401 || code == 403) throw new IOException("That Pixabay key isn't valid");
            if (code == 429) throw new IOException("Pixabay is busy, try again in a minute");
            if (code >= 400) throw new IOException("Pixabay error " + code);
            return new String(TrendingFeed.readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Streams a clip to disk (via a .part file, so a cut download never shows up as a scene). */
    private void download(String url, File to, Progress p, String name, int n) throws IOException {
        HttpURLConnection c = open(url);
        File part = new File(to.getPath() + ".part");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("download failed (" + code + ")");
            long total = c.getContentLength();
            if (total > MAX_CLIP) throw new IOException("clip too large");
            if (dir.getUsableSpace() < Math.max(total, 20L * 1024 * 1024) + 200L * 1024 * 1024)
                throw new IOException("not enough space on the projector");
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part);
            long done = 0, last = 0;
            try {
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) {
                    out.write(buf, 0, k);
                    done += k;
                    if (done > MAX_CLIP) throw new IOException("clip too large");
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - last > 400) {
                        last = now;
                        report(p, "Downloading " + name + " (" + n + " of " + SCENES.length + ") · " + Math.round(done * 100f / total) + "%");
                    }
                }
            } finally {
                out.close();
                in.close();
            }
            if (total > 0 && done != total) throw new IOException("download cut short");
            if (!part.renameTo(to)) throw new IOException("couldn't save the clip");
        } finally {
            c.disconnect();
            part.delete();
        }
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
