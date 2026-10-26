package com.lumen.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where Lumen finds its updates, and how it reads them. Pure Java (tested on a desktop JVM).
 *
 * The update source is either
 *   • a GitHub repo, "owner/repo" or "github:owner/repo": the latest release, tagged like v6.2, with
 *     Lumen.apk attached (optionally update.json too), or
 *   • any https URL of an update.json:
 *       {"versionCode":9,"versionName":"6.2","url":"https://…/Lumen.apk","sha256":"…","notes":"…"}
 *
 * Safety: Android only installs an update signed with the same key as the installed Lumen, and Lumen
 * also checks the package name, the signature and (when the feed gives one) the SHA-256 before installing.
 */
final class UpdateFeed {

    static final long MAX_APK = 60L * 1024 * 1024;

    static final class Release {
        String versionName = "";
        int versionCode;            // 0 = unknown (GitHub tag only): compare names instead
        String url = "";
        String sha256 = "";         // lowercase hex, may be empty
        String notes = "";
        long size;
        String manifestUrl;         // GitHub only: the release's update.json, if attached

        /** Is this newer than what's installed? */
        boolean newerThan(int code, String name) {
            if (versionCode > 0 && code > 0) return versionCode > code;
            return compareVersions(versionName, name) > 0;
        }

        String json() {
            return "{\"versionName\":" + RemoteServer.json(versionName) + ",\"versionCode\":" + versionCode
                    + ",\"url\":" + RemoteServer.json(url) + ",\"sha256\":" + RemoteServer.json(sha256)
                    + ",\"notes\":" + RemoteServer.json(notes) + ",\"size\":" + size + "}";
        }
    }

    private UpdateFeed() {
    }

    private static final Pattern REPO = Pattern.compile("^(?:github:|https?://github\\.com/)?([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?$");

    /** Cleans what the user typed; returns "" when it's neither a repo nor an https URL. */
    static String normalizeSource(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() == 0) return "";
        Matcher m = REPO.matcher(s);
        if (m.matches()) return "github:" + m.group(1) + "/" + m.group(2);
        if (s.startsWith("https://") && s.length() > 12 && !s.contains(" ")) return s;
        return "";
    }

    static boolean isGithub(String source) {
        return source.startsWith("github:");
    }

    /** The URL to ask for the latest release. */
    static String feedUrl(String source) {
        if (isGithub(source)) return "https://api.github.com/repos/" + source.substring(7) + "/releases/latest";
        return source;
    }

    /** Fetches and reads the latest release. */
    static Release check(String source) throws IOException {
        if (source.length() == 0) throw new IOException("No update source set");
        String body = get(feedUrl(source));
        Release r = isGithub(source) ? parseGithub(body) : parseManifest(body);
        if (r == null) throw new IOException("No Lumen APK in the latest release");
        // a GitHub release may also carry update.json with the exact versionCode and checksum
        if (isGithub(source) && r.versionCode == 0 && r.manifestUrl != null) {
            try {
                Release m = parseManifest(get(r.manifestUrl));
                if (m != null) {
                    r.versionCode = m.versionCode;
                    if (r.sha256.length() == 0) r.sha256 = m.sha256;
                    if (m.notes.length() > 0 && r.notes.length() == 0) r.notes = m.notes;
                }
            } catch (IOException ignored) {
                // the tag still tells us the version
            }
        }
        return r;
    }

    /** GitHub "latest release" JSON → Release (null if no APK is attached). */
    static Release parseGithub(String json) {
        String manifest = null;
        Object root = TrendingFeed.Json.parse(json);
        Object assets = TrendingFeed.Json.path(root, "assets");
        if (!(assets instanceof List)) return null;
        Map<?, ?> best = null;
        for (Object a : (List<?>) assets) {
            if (!(a instanceof Map)) continue;
            String name = str(((Map<?, ?>) a).get("name")).toLowerCase(Locale.ROOT);
            if (name.equals("update.json")) manifest = str(((Map<?, ?>) a).get("browser_download_url"));
            if (!name.endsWith(".apk")) continue;
            if (best == null || name.contains("lumen")) best = (Map<?, ?>) a;
        }
        if (best == null) return null;
        Release r = new Release();
        r.manifestUrl = manifest;
        String tag = str(TrendingFeed.Json.path(root, "tag_name"));
        r.versionName = tag.replaceFirst("^[vV]", "");
        r.url = str(best.get("browser_download_url"));
        r.size = (long) num(best.get("size"));
        String digest = str(best.get("digest"));          // GitHub adds "sha256:…" to release assets
        if (digest.startsWith("sha256:")) r.sha256 = digest.substring(7).toLowerCase(Locale.ROOT);
        r.notes = firstLines(str(TrendingFeed.Json.path(root, "body")), 400);
        Matcher m = Pattern.compile("(?i)sha-?256[:=\\s]+([0-9a-f]{64})").matcher(r.notes);
        if (r.sha256.length() == 0 && m.find()) r.sha256 = m.group(1).toLowerCase(Locale.ROOT);
        return r.url.startsWith("https://") ? r : null;
    }

    /** update.json → Release (null if it isn't one). */
    static Release parseManifest(String json) {
        Object root = TrendingFeed.Json.parse(json);
        if (!(root instanceof Map)) return null;
        Release r = new Release();
        r.versionCode = (int) num(TrendingFeed.Json.path(root, "versionCode"));
        r.versionName = str(TrendingFeed.Json.path(root, "versionName"));
        r.url = str(TrendingFeed.Json.path(root, "url"));
        r.sha256 = str(TrendingFeed.Json.path(root, "sha256")).toLowerCase(Locale.ROOT);
        r.notes = firstLines(str(TrendingFeed.Json.path(root, "notes")), 400);
        r.size = (long) num(TrendingFeed.Json.path(root, "size"));
        if (!r.url.startsWith("https://")) return null;
        if (r.versionCode <= 0 && r.versionName.length() == 0) return null;
        return r;
    }

    /** "6.10" > "6.9" > "6.1"; non-numeric parts are ignored. Returns <0, 0 or >0. */
    static int compareVersions(String a, String b) {
        String[] x = (a == null ? "" : a).replaceAll("[^0-9.]", "").split("\\.");
        String[] y = (b == null ? "" : b).replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && x[i].length() > 0 ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && y[i].length() > 0 ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    /**
     * Downloads the APK into {@code out}, checking size and SHA-256 on the way. Returns the hex SHA-256.
     * Throws if the checksum doesn't match.
     */
    static String download(Release r, OutputStream out) throws IOException {
        HttpURLConnection c = open(r.url, 15000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Download failed (HTTP " + code + ")");
            MessageDigest md = sha256();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_APK) throw new IOException("Update is too large");
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            in.close();
            String hex = hex(md.digest());
            if (r.sha256.length() > 0 && !r.sha256.equals(hex)) throw new IOException("Checksum mismatch: the download was damaged");
            if (r.size > 0 && total != r.size) throw new IOException("Download incomplete");
            return hex;
        } finally {
            c.disconnect();
        }
    }

    static String sha256Hex(byte[] data) {
        MessageDigest md = sha256();
        md.update(data);
        return hex(md.digest());
    }

    /** A plain-English line for the settings screen. */
    static String describeSource(String source) {
        if (source.length() == 0) return "not set";
        if (isGithub(source)) return "GitHub " + source.substring(7);
        String host = source.replaceFirst("^https://", "");
        int s = host.indexOf('/');
        return s > 0 ? host.substring(0, s) : host;
    }

    // ------------------------------------------------------------------ helpers

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static HttpURLConnection open(String url, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(true);   // GitHub release files redirect to their CDN (https → https)
        c.setRequestProperty("User-Agent", "Lumen-Launcher");
        c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "*/*");
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url, 8000);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new IOException(url.contains("api.github.com") ? "No release found (is the repo public?)" : "Update file not found");
            if (code == 403 || code == 429) throw new IOException("The update server is busy, try later");
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 2 * 1024 * 1024) throw new IOException("Update feed too large");
            }
            in.close();
            return new String(b.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String firstLines(String s, int max) {
        s = s.replace("\r", "").trim();
        return s.length() > max ? s.substring(0, max).trim() + "…" : s;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
