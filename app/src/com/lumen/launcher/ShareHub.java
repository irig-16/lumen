package com.lumen.launcher;

/**
 * Live screen sharing from a laptop browser: the sender posts JPEG frames, the projector shows the newest.
 * Old frames are simply replaced, so a slow network or a busy projector drops frames instead of lagging behind.
 * Pure Java, thread-safe (tested on a desktop JVM).
 */
final class ShareHub {

    static final ShareHub I = new ShareHub();

    interface Listener {
        /** A share started (first frame of a new session). Called on the server thread. */
        void onShareStarted();
    }

    private byte[] frame;
    private long seq;
    private String session = "";
    private String stoppedSession = "";
    private long lastFrameAt;
    private int width, height;
    private Listener listener;
    // simple frame-rate meter
    private long windowStart;
    private int windowFrames;
    private float fps;

    ShareHub() {
    }

    synchronized void setListener(Listener l) {
        listener = l;
    }

    /**
     * A frame from {@code sess}. Returns false when the viewer stopped this session (the sender should stop).
     * A different session takes over the screen: the newest sender wins.
     */
    boolean offer(String sess, byte[] jpeg) {
        Listener notify = null;
        synchronized (this) {
            if (sess == null) sess = "";
            if (sess.length() > 0 && sess.equals(stoppedSession)) return false;
            if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return true; // not a JPEG: ignore
            long now = System.currentTimeMillis();
            boolean fresh = !sess.equals(session) || now - lastFrameAt > 20000;
            session = sess;
            frame = jpeg;
            seq++;
            lastFrameAt = now;
            if (now - windowStart > 2000) {
                fps = windowFrames * 1000f / Math.max(1, now - windowStart);
                windowStart = now;
                windowFrames = 0;
            }
            windowFrames++;
            if (fresh) notify = listener;
            notifyAll();
        }
        if (notify != null) notify.onShareStarted();
        return true;
    }

    /** Waits up to {@code timeoutMs} for a frame newer than {@code afterSeq}; returns it with its seq, or null. */
    synchronized Object[] waitNewer(long afterSeq, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (seq <= afterSeq || frame == null) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            wait(left);
        }
        return new Object[]{frame, seq};
    }

    /** The viewer pressed Back: the current sender is told to stop on its next frame. */
    synchronized void stopByViewer() {
        stoppedSession = session;
        frame = null;
        lastFrameAt = 0;
        notifyAll();
    }

    /** The sender stopped. */
    synchronized void stopBySender(String sess) {
        if (sess != null && sess.equals(session)) {
            frame = null;
            lastFrameAt = 0;
            notifyAll();
        }
    }

    synchronized boolean active() {
        return frame != null && System.currentTimeMillis() - lastFrameAt < 20000;
    }

    /** Milliseconds since the last frame (Long.MAX_VALUE if none). */
    synchronized long idleMs() {
        return lastFrameAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastFrameAt;
    }

    synchronized float fps() {
        return fps;
    }

    synchronized void setSize(int w, int h) {
        width = w;
        height = h;
    }

    synchronized String json(boolean ok) {
        return ok ? "{\"ok\":true,\"fps\":" + Math.round(fps) + ",\"w\":" + width + ",\"h\":" + height + "}"
                : "{\"ok\":false,\"reply\":\"Sharing was stopped on the projector\"}";
    }
}
