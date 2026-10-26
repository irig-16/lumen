package com.lumen.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Photos and videos sent from the phone. Each file is streamed to storage (never held in memory), the newest
 * few are kept, and the projector shows them. Pure Java (tested on a desktop JVM).
 */
final class MediaInbox {

    static final int PHOTO = 0, VIDEO = 1, AUDIO = 2;
    static final int KEEP = 30;                       // received items kept for browsing
    static final long SPARE = 300L * 1024 * 1024;     // always leave this much free space

    static final class Item {
        final File file;
        final String name;
        final int kind;

        Item(File file, String name, int kind) {
            this.file = file;
            this.name = name;
            this.kind = kind;
        }
    }

    interface Listener {
        /** Progress 0..1 while receiving; called often from the server thread. */
        void onReceiving(String name, int kind, float progress);

        /** A file arrived completely. */
        void onReceived(Item item);

        void onFailed(String message);
    }

    static final MediaInbox I = new MediaInbox();

    private final List<Item> items = new ArrayList<Item>();
    private File dir;
    private Listener listener;

    synchronized void init(File directory) {
        if (dir != null) return;
        dir = directory;
        dir.mkdirs();
        File[] old = dir.listFiles();      // a fresh start each boot: clear what an earlier session left
        if (old != null) for (File f : old) f.delete();
    }

    synchronized void setListener(Listener l) {
        listener = l;
    }

    synchronized List<Item> items() {
        return new ArrayList<Item>(items);
    }

    /** PHOTO, VIDEO, AUDIO or -1 from the MIME type, falling back to the file extension. */
    static int kindOf(String name, String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.startsWith("image/")) return PHOTO;
        if (t.startsWith("video/")) return VIDEO;
        if (t.startsWith("audio/")) return AUDIO;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.matches(".*\\.(jpe?g|png|webp|gif|bmp|heic|heif)$")) return PHOTO;
        if (n.matches(".*\\.(mp4|m4v|mov|mkv|webm|3gp|ts)$")) return VIDEO;
        if (n.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$")) return AUDIO;
        return -1;
    }

    /** A safe file name: letters, digits, dot, dash, underscore and space only. */
    static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.startsWith(".")) n = "_" + n;
        if (n.length() > 80) n = n.substring(n.length() - 80);
        return n.length() == 0 ? "file" : n;
    }

    /** Streams exactly {@code length} bytes from {@code in} into the inbox. Returns {ok, message}. */
    String[] receive(String name, String type, long length, InputStream in) throws IOException {
        final int kind = kindOf(name, type);
        File target;
        Listener l;
        synchronized (this) {
            l = listener;
            if (dir == null) return new String[]{"false", "The projector isn't ready yet"};
            if (kind < 0) {
                skip(in, length);
                return new String[]{"false", "Only photos, videos and music can be shown"};
            }
            prune(KEEP - 1);
            if (dir.getUsableSpace() < length + SPARE) {
                skip(in, length);
                return new String[]{"false", "Not enough space on the projector for that file"};
            }
            target = new File(dir, System.currentTimeMillis() + "-" + safeName(name));
        }
        String shown = safeName(name);
        if (l != null) l.onReceiving(shown, kind, 0f);
        OutputStream out = new FileOutputStream(target);
        long done = 0;
        long lastReport = 0;
        try {
            byte[] buf = new byte[65536];
            while (done < length) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, length - done));
                if (n < 0) break;
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (l != null && now - lastReport > 250) {
                    lastReport = now;
                    l.onReceiving(shown, kind, done / (float) length);
                }
            }
        } finally {
            out.close();
        }
        if (done < length) {
            target.delete();
            if (l != null) l.onFailed("The transfer stopped before the end");
            return new String[]{"false", "The transfer stopped before the end"};
        }
        Item item = new Item(target, shown, kind);
        synchronized (this) {
            items.add(item);
        }
        if (l != null) l.onReceived(item);
        return new String[]{"true", (kind == PHOTO ? "Photo" : kind == VIDEO ? "Video" : "Music") + " is on the projector"};
    }

    private void prune(int keep) {
        while (items.size() > keep) items.remove(0).file.delete();
    }

    private static void skip(InputStream in, long length) throws IOException {
        byte[] buf = new byte[65536];
        long left = length;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            left -= n;
        }
    }
}
