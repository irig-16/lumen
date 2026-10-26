package com.lumen.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows what's playing in other apps (Netflix, Prime Video, YouTube…) through their media
 * sessions, for Continue Watching and Auto Picture. Android only shares other apps' sessions
 * with an enabled notification listener, so this is one (enabled once by the LumenOS script);
 * it never reads or touches notifications themselves.
 */
public class MediaWatch extends NotificationListenerService {

    /** Music apps don't belong in Continue Watching. */
    private static final String[] MUSIC = {"com.spotify", "com.anghami", "com.google.android.apps.youtube.music",
            "deezer", "com.soundcloud", "com.aspiro.tidal", "com.apple.android.music", "com.maxmpz.audioplayer",
            "com.google.android.music", "com.amazon.mp3", "com.jio.media.jiobeats", "com.gaana", "com.bsbportal.music",
            "fm.castbox", "au.com.shiftyjelly.pocketcasts", "com.android.bluetooth"};

    private static final long SAVE_EVERY = 30000;

    private static MediaWatch running;
    private final Map<MediaSession.Token, Watcher> watchers = new HashMap<MediaSession.Token, Watcher>();
    private MediaSessionManager msm;
    private HandlerThread thread;
    private Handler handler;
    private MediaSessionManager.OnActiveSessionsChangedListener sessions;

    @Override
    public void onListenerConnected() {
        running = this;
        HeatGuard.get(this).start();     // keeps watching the temperature while you watch films
        thread = new HandlerThread("lumen-mediawatch");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            final ComponentName me = new ComponentName(this, MediaWatch.class);
            sessions = new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> list) {
                    attach(list);
                }
            };
            msm.addOnActiveSessionsChangedListener(sessions, me, handler);
            attach(msm.getActiveSessions(me));
        } catch (RuntimeException ignored) {
        }
        handler.postDelayed(tick, SAVE_EVERY);
    }

    // API 24 callback: no @Override, Lumen compiles against API 23
    public void onListenerDisconnected() {
        if (running == this) running = null;
        try {
            if (msm != null && sessions != null) msm.removeOnActiveSessionsChangedListener(sessions);
        } catch (RuntimeException ignored) {
        }
        for (Watcher w : watchers.values()) w.detach();
        watchers.clear();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        try {
            NotificationListenerService.class.getMethod("requestRebind", ComponentName.class)
                    .invoke(null, new ComponentName(this, MediaWatch.class));
        } catch (Throwable ignored) {
        }
    }

    /** The live session of an app (to resume exactly where it paused), or null. */
    static MediaController controllerFor(String pkg) {
        MediaWatch m = running;
        if (m == null) return null;
        synchronized (m.watchers) {
            for (Watcher w : m.watchers.values()) if (w.c.getPackageName().equals(pkg)) return w.c;
        }
        return null;
    }

    static boolean connected() {
        return running != null;
    }

    private void attach(List<MediaController> list) {
        if (list == null) list = new ArrayList<MediaController>();
        synchronized (watchers) {
            Map<MediaSession.Token, Watcher> keep = new HashMap<MediaSession.Token, Watcher>();
            for (MediaController c : list) {
                if (c.getPackageName().equals(getPackageName()) || isMusic(c.getPackageName())) continue;
                Watcher w = watchers.remove(c.getSessionToken());
                if (w == null) {
                    w = new Watcher(c);
                    c.registerCallback(w, handler);
                    w.save(false);
                }
                keep.put(c.getSessionToken(), w);
            }
            for (Watcher gone : watchers.values()) {
                gone.save(true);
                gone.detach();
            }
            watchers.clear();
            watchers.putAll(keep);
        }
    }

    private static boolean isMusic(String pkg) {
        for (String m : MUSIC) if (pkg.startsWith(m) || pkg.contains(m)) return true;
        return false;
    }

    /** While something plays, saves its position every 30 s so Continue Watching is never far off. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            synchronized (watchers) {
                for (Watcher w : watchers.values()) if (w.playing()) w.save(false);
            }
            handler.postDelayed(this, SAVE_EVERY);
        }
    };

    private final class Watcher extends MediaController.Callback {
        final MediaController c;
        boolean wasPlaying;
        String lastTitle = "";

        Watcher(MediaController c) {
            this.c = c;
        }

        void detach() {
            try {
                c.unregisterCallback(this);
            } catch (RuntimeException ignored) {
            }
        }

        boolean playing() {
            PlaybackState s = c.getPlaybackState();
            return s != null && s.getState() == PlaybackState.STATE_PLAYING;
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            save(true);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            boolean now = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            if (now && !wasPlaying) {
                ScreenEngine.onApp(MediaWatch.this, c.getPackageName());          // Auto Picture
                MemoryTuner.onPlaybackStart(MediaWatch.this, c.getPackageName()); // room for the player
            }
            if (now != wasPlaying) save(true);
            wasPlaying = now;
        }

        @Override
        public void onSessionDestroyed() {
            save(true);
        }

        void save(boolean notify) {
            MediaMetadata m;
            PlaybackState s;
            try {
                m = c.getMetadata();
                s = c.getPlaybackState();
            } catch (RuntimeException e) {
                return;
            }
            if (m == null) return;
            ContinueStore.Entry e = new ContinueStore.Entry();
            e.pkg = c.getPackageName();
            if (ContinueStore.ignored(c.getPackageName())) return;      // YouTube stays out of Continue Watching
            e.title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            e.subtitle = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            if (e.subtitle.equals(e.title)) e.subtitle = "";
            // series: keep the show's name and "S5 E14", not the episode's own name (one card per show)
            String[] se = ContinueStore.showAndEpisode(e.title, e.subtitle,
                    first(m, MediaMetadata.METADATA_KEY_ARTIST), first(m, MediaMetadata.METADATA_KEY_ALBUM),
                    first(m, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION));
            if (se[1].length() > 0) {
                e.title = se[0];
                e.subtitle = se[1];
            }
            e.mediaId = first(m, MediaMetadata.METADATA_KEY_MEDIA_ID);
            e.uri = first(m, "android.media.metadata.MEDIA_URI");
            e.duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            if (s != null) {
                long pos = s.getPosition();
                if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                }
                e.position = Math.max(0, pos);
            }
            if (e.title.length() == 0) return;
            e.updatedAt = System.currentTimeMillis();
            Bitmap art = null;
            if (!e.title.equals(lastTitle)) { // artwork only when the title changes
                art = bitmap(m, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (art == null) {
                    String u = first(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                    if (u.length() > 0) art = ContinueStore.fetch(MediaWatch.this, u);
                }
                lastTitle = e.title;
            }
            ContinueStore.record(MediaWatch.this, e, art, notify);
        }
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            try {
                CharSequence v = m.getText(k);
                if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    private static Bitmap bitmap(MediaMetadata m, String... keys) {
        for (String k : keys) {
            try {
                Bitmap b = m.getBitmap(k);
                if (b != null && b.getWidth() >= 64) return b;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }
}
