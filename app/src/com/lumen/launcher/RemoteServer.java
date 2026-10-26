package com.lumen.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ServerSocketFactory;

/**
 * Tiny HTTP server for the phone remote (home network only).
 *   GET  /            → the remote web page
 *   GET  /ping?pin=   → {"ok":true,"name":...}   (checks the PIN)
 *   GET  /apps?pin=   → JSON list of app names
 *   POST /cmd         → body = command text, header X-Pin → {"ok":..,"reply":..}
 *   GET  /sound       → Sound Studio state as JSON
 *   POST /sound       → body "key=value&…" (preset, bass, treble, clarity, surround, loudness, night, protect)
 *   GET  /update      → update state;  POST /update → source, auto, check, install
 *   POST /key         → body = button name (up, down, left, right, ok, back, home, menu, playpause, volup…)
 *   POST /text        → body = text typed on the phone keyboard
 *   POST /share/frame  → one JPEG of a shared laptop screen (headers X-Pin, X-Share); keep-alive
 *   POST /share/stop   → the laptop stopped sharing
 *   POST /cast/upload?name=&type= → a photo or video from the phone, streamed to storage
 *   POST /update/upload → raw APK bytes from the phone (a Lumen update, same signing key only)
 * Every API call needs the 4-digit PIN shown on the projector.
 * Runs as plain HTTP and, with an SSL factory, as HTTPS (needed for the phone's microphone).
 * Each connection is served on a small pool, so idle browser pre-connections never block others.
 * Pure Java (no Android imports) so it can be tested on a desktop JVM.
 */
final class RemoteServer extends Thread {

    interface Bridge {
        /** Runs a command and returns {ok, reply}. Called on the server thread. */
        String[] command(String text);

        String appsJson();

        String soundJson();

        /** Applies "key=value&…" to Sound Studio, returns the new state JSON. */
        String soundSet(String form);

        String settingsJson();

        /** ytkey, region, yton — returns the new settings JSON. */
        String settingsSet(String form);

        String updateJson();

        /** source, auto, check, install — returns the new update JSON. */
        String updateSet(String form);

        /** An APK uploaded from the phone. Returns {ok, message}. */
        String[] installApk(byte[] apk);

        /** A remote button: up, down, left, right, ok, back, home, menu, playpause, next, prev, volup, voldown, mute. */
        String[] key(String key);

        /** Text from the phone keyboard. Returns {ok, message}. */
        String[] text(String text);

        /** One JPEG frame of a shared screen. Returns JSON {ok, reply?}; ok false tells the sender to stop. */
        String frame(String session, byte[] jpeg);

        /** The sender stopped sharing. */
        void shareStopped(String session);

        /** A photo or video from the phone, streamed to storage. Reads exactly {@code length} bytes. Returns {ok, message}. */
        String[] receiveFile(String name, String type, long length, InputStream in, String dest) throws IOException;
    }

    static final int MAX_UPLOAD = 20 * 1024 * 1024;
    static final int MAX_FRAME = 3 * 1024 * 1024;
    static final long MAX_MEDIA = 4L * 1024 * 1024 * 1024;

    /** Whether the response being written keeps the connection open (set per request). */
    private static final ThreadLocal<Boolean> KEEP = new ThreadLocal<Boolean>();

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // shared by the HTTP and HTTPS servers so the PIN can't be guessed twice as fast
    private static final Object LOCK = new Object();
    private static int failures;
    private static long lockUntil;

    private final int port;
    private final String pin;
    private final byte[] page;
    private final Bridge bridge;
    private final ServerSocketFactory factory;
    private final ExecutorService pool = Executors.newFixedThreadPool(16);
    private final Map<String, byte[]> pages = new HashMap<String, byte[]>();
    private volatile boolean running = true;
    private ServerSocket server;

    RemoteServer(int port, String pin, byte[] page, Bridge bridge) {
        this(port, pin, page, bridge, ServerSocketFactory.getDefault());
    }

    RemoteServer(int port, String pin, byte[] page, Bridge bridge, ServerSocketFactory factory) {
        super("lumen-remote-" + port);
        setDaemon(true);
        this.port = port;
        this.pin = pin;
        this.page = page;
        this.bridge = bridge;
        this.factory = factory;
    }

    /** Extra static page served without a PIN (e.g. a help page). */
    void addPage(String path, byte[] bytes) {
        pages.put(path, bytes);
    }

    void shutdown() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
        interrupt();
    }

    @Override
    public void run() {
        try {
            server = factory.createServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            return;
        }
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            s.setSoTimeout(8000);
                            handle(s);
                        } catch (IOException ignored) {
                        } catch (RuntimeException ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                });
            } catch (IOException ignored) {
            } catch (RuntimeException ignored) {
                // pool rejected after shutdown
            }
        }
    }

    /** Serves requests on one connection; keeps it open (HTTP/1.1 keep-alive) while the client wants it. */
    private void handle(Socket s) throws IOException {
        InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 16384);
        OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 16384);
        for (int n = 0; n < 5000 && running; n++) {
            KEEP.set(Boolean.FALSE);
            s.setSoTimeout(n == 0 ? 8000 : 6000);   // after the first request, wait a little for the next
            if (!handleOne(s, in, out)) return;
        }
    }

    private boolean handleOne(Socket s, InputStream in, OutputStream out) throws IOException {
        String head;
        try {
            head = readHead(in);
        } catch (java.net.SocketTimeoutException e) {
            return false;
        }
        if (head == null) return false;
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) return false;
        String method = req[0];
        String target = req[1];
        Map<String, String> headers = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
        }
        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        Map<String, String> params = parseQuery(query);
        boolean http11 = lines[0].endsWith("HTTP/1.1");
        boolean canKeep = http11 && !"close".equalsIgnoreCase(headers.get("connection"));
        long contentLength = 0;
        try {
            contentLength = Long.parseLong(headers.containsKey("content-length") ? headers.get("content-length") : "0");
        } catch (NumberFormatException ignored) {
            canKeep = false;
        }
        if (headers.containsKey("transfer-encoding")) canKeep = false;
        // requests without a body can always keep the connection; ones with a body only once it's fully read
        if (canKeep && contentLength == 0) KEEP.set(Boolean.TRUE);

        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            send(out, 200, "text/html; charset=utf-8", page);
            return KEEP.get();
        }
        if ("/favicon.ico".equals(path)) {
            send(out, 204, "text/plain", new byte[0]);
            return KEEP.get();
        }
        if ("GET".equals(method) && pages.containsKey(path)) {
            send(out, 200, "text/html; charset=utf-8", pages.get(path));
            return KEEP.get();
        }
        String given = headers.containsKey("x-pin") ? headers.get("x-pin") : params.get("pin");
        if (!checkPin(given)) {
            KEEP.set(Boolean.FALSE); // a body may be unread
            sendJson(out, 403, "{\"ok\":false,\"reply\":\"Wrong PIN\"}");
            return false;
        }

        if ("POST".equals(method) && "/share/frame".equals(path)) {
            if (contentLength <= 0 || contentLength > MAX_FRAME) {
                sendJson(out, 200, "{\"ok\":false,\"reply\":\"Frame too large\"}");
                return false;
            }
            byte[] jpeg = readBody(in, (int) contentLength);
            if (jpeg.length == contentLength && canKeep) KEEP.set(Boolean.TRUE);
            String session = headers.containsKey("x-share") ? headers.get("x-share") : "";
            sendJson(out, 200, bridge.frame(session, jpeg));
            return KEEP.get();
        }
        if ("POST".equals(method) && "/share/stop".equals(path)) {
            readBody(in, (int) Math.max(0, Math.min(contentLength, 1000)));
            bridge.shareStopped(headers.containsKey("x-share") ? headers.get("x-share") : "");
            sendJson(out, 200, "{\"ok\":true}");
            return false;
        }
        if ("POST".equals(method) && "/cast/upload".equals(path)) {
            if (contentLength <= 0 || contentLength > MAX_MEDIA) {
                sendJson(out, 200, "{\"ok\":false,\"reply\":\"That file is too big to send\"}");
                return false;
            }
            s.setSoTimeout(30000);
            String[] r;
            try {
                r = bridge.receiveFile(params.containsKey("name") ? params.get("name") : "file",
                        params.containsKey("type") ? params.get("type") : "", contentLength, in,
                        params.containsKey("dest") ? params.get("dest") : "");
            } catch (IOException e) {
                r = new String[]{"false", "The transfer was interrupted: " + e.getMessage()};
            }
            sendJson(out, 200, "{\"ok\":" + r[0] + ",\"reply\":" + json(r[1]) + "}");
            return false;
        }

        if ("GET".equals(method) && "/ping".equals(path)) {
            sendJson(out, 200, "{\"ok\":true,\"name\":\"Lumen\"}");
        } else if ("GET".equals(method) && "/apps".equals(path)) {
            sendJson(out, 200, bridge.appsJson());
        } else if ("GET".equals(method) && "/sound".equals(path)) {
            sendJson(out, 200, bridge.soundJson());
        } else if ("GET".equals(method) && "/settings".equals(path)) {
            sendJson(out, 200, bridge.settingsJson());
        } else if ("GET".equals(method) && "/update".equals(path)) {
            sendJson(out, 200, bridge.updateJson());
        } else if ("POST".equals(method) && "/update/upload".equals(path)) {
            long len = -1;
            try {
                len = Long.parseLong(headers.containsKey("content-length") ? headers.get("content-length") : "-1");
            } catch (NumberFormatException ignored) {
            }
            if (len <= 0 || len > MAX_UPLOAD) {
                sendJson(out, 200, "{\"ok\":false,\"reply\":\"That file is too big for a Lumen update\"}");
                return false;
            }
            s.setSoTimeout(30000);
            byte[] apk = readBody(in, (int) len);
            String[] r = bridge.installApk(apk);
            sendJson(out, 200, "{\"ok\":" + r[0] + ",\"reply\":" + json(r[1]) + "}");
        } else if ("POST".equals(method) && ("/key".equals(path) || "/text".equals(path))) {
            int len = 0;
            try {
                len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (NumberFormatException ignored) {
            }
            String body = new String(readBody(in, Math.max(0, Math.min(len, 1000))), UTF8);
            if (canKeep && len <= 1000) KEEP.set(Boolean.TRUE);
            String[] r = "/key".equals(path) ? bridge.key(body.trim()) : bridge.text(body);
            sendJson(out, 200, "{\"ok\":" + r[0] + ",\"reply\":" + json(r[1]) + "}");
        } else if ("POST".equals(method) && ("/cmd".equals(path) || "/sound".equals(path) || "/settings".equals(path) || "/update".equals(path))) {
            int len = 0;
            try {
                len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (NumberFormatException ignored) {
            }
            if (canKeep && len >= 0 && len <= 2000) KEEP.set(Boolean.TRUE);
            len = Math.max(0, Math.min(len, 2000));
            String text = new String(readBody(in, len), UTF8).trim();
            if ("/sound".equals(path)) {
                sendJson(out, 200, bridge.soundSet(text));
            } else if ("/update".equals(path)) {
                sendJson(out, 200, bridge.updateSet(text));
            } else if ("/settings".equals(path)) {
                sendJson(out, 200, bridge.settingsSet(text));
            } else {
                String[] r = bridge.command(text);
                sendJson(out, 200, "{\"ok\":" + r[0] + ",\"reply\":" + json(r[1]) + "}");
            }
        } else {
            KEEP.set(Boolean.FALSE);
            sendJson(out, 404, "{\"ok\":false,\"reply\":\"Not found\"}");
        }
        return KEEP.get();
    }

    /** Wrong PINs lock the remote for 30 s after 5 tries, so the PIN can't be brute-forced quickly. */
    private boolean checkPin(String given) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now < lockUntil) return false;
            if (pin.equals(given)) {
                failures = 0;
                return true;
            }
            if (++failures >= 5) {
                failures = 0;
                lockUntil = now + 30000;
            }
            return false;
        }
    }

    /** Parses "a=1&b=two" (URL-encoded) into a map. */
    static Map<String, String> form(String body) {
        return parseQuery(body == null ? "" : body);
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) != -1) {
            b.write(c);
            if (b.size() > 8192) return null;
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) return new String(b.toByteArray(), UTF8).trim();
        }
        return null;
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return off == len ? buf : java.util.Arrays.copyOf(buf, off);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q.length() == 0) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e > 0) m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static void sendJson(OutputStream out, int code, String body) throws IOException {
        send(out, code, "application/json; charset=utf-8", body.getBytes(UTF8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 403 ? "Forbidden" : "Not Found";
        String h = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + (Boolean.TRUE.equals(KEEP.get()) ? "Connection: keep-alive\r\nKeep-Alive: timeout=6\r\n\r\n" : "Connection: close\r\n\r\n");
        out.write(h.getBytes(UTF8));
        out.write(body);
        out.flush();
    }

    static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
