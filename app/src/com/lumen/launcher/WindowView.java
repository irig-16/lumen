package com.lumen.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lumen Window: real outdoor video scenes, full screen. A new scene crossfades in
 * every few minutes (3 by default) while the picture drifts very slowly, so it feels like a view, not a video.
 * Used by {@link WindowActivity} and by the system screensaver ({@link WindowDream}).
 */
final class WindowView extends FrameLayout {

    static final String PREFS = "lumen_window";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SceneLibrary library;

    static synchronized SceneLibrary library(Context c) {
        if (library == null) {
            File base = c.getExternalFilesDir(null);
            library = new SceneLibrary(new File(base != null ? base : c.getFilesDir(), "window"));
        }
        return library;
    }

    static int minutes(Context c) {
        return Math.max(1, prefs(c).getInt("minutes", 3));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private final Player[] players = new Player[2];
    private int front;                    // the player currently showing
    private final LinearLayout caption;
    private final TextView captionTitle, captionCredit, empty;
    private List<SceneLibrary.Clip> order = new ArrayList<SceneLibrary.Clip>();
    private int index = -1;
    private boolean running;

    WindowView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            players[i] = new Player(c);
            addView(players[i].view, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(dp(18), dp(10), dp(18), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x990A0A0A);
        bg.setCornerRadius(dp(16));
        caption.setBackground(bg);
        captionTitle = text("", 17, 0xFFF5F5F7);
        captionTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        captionCredit = text("", 12, 0xB3F5F5F7);
        caption.addView(captionTitle);
        caption.addView(captionCredit);
        caption.setAlpha(0f);
        LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = dp(28);
        clp.bottomMargin = dp(28);
        addView(caption, clp);

        empty = text("", 17, 0xCCF5F5F7);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(60), 0, dp(60), 0);
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------------------------------------------------------- control

    /** Starts the scenes, beginning with {@code sceneId} when given. */
    void start(String sceneId) {
        running = true;
        List<SceneLibrary.Clip> clips = library(getContext()).clips();
        if (clips.isEmpty()) {
            empty.setText("No scenes yet\n\nOn the phone remote: More → Window → Download scenes (once, over Wi-Fi).\n"
                    + "After that they play offline. You can also add your own videos there.");
            empty.setVisibility(VISIBLE);
            return;
        }
        empty.setVisibility(GONE);
        order = new ArrayList<SceneLibrary.Clip>(clips);
        Collections.shuffle(order);
        index = 0;
        if (sceneId != null) {
            for (int i = 0; i < order.size(); i++) if (order.get(i).sceneId.equals(sceneId)) index = i;
        }
        show(order.get(index), false);
    }

    void stop() {
        running = false;
        main.removeCallbacks(advance);
        for (Player p : players) p.release();
    }

    void next() {
        if (order.isEmpty()) return;
        index = (index + 1) % order.size();
        show(order.get(index), true);
    }

    void previous() {
        if (order.isEmpty()) return;
        index = (index - 1 + order.size()) % order.size();
        show(order.get(index), true);
    }

    void showCaption() {
        SceneLibrary.Clip c = current();
        if (c != null) caption(c);
    }

    private SceneLibrary.Clip current() {
        return index >= 0 && index < order.size() ? order.get(index) : null;
    }

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (running) next();
        }
    };

    private void show(final SceneLibrary.Clip clip, boolean crossfade) {
        main.removeCallbacks(advance);
        final Player incoming = players[1 - front];
        final Player outgoing = players[front];
        File f = new File(library(getContext()).dir(), clip.file);
        incoming.play(f, new Runnable() {
            @Override
            public void run() {   // first frame is ready: fade it in over the old scene
                incoming.view.bringToFront();
                caption.bringToFront();
                incoming.view.setAlpha(0f);
                incoming.view.animate().alpha(1f).setDuration(2500).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        outgoing.release();
                    }
                }).start();
                incoming.drift(minutes(getContext()) * 60000L + 4000);
                front = 1 - front;
                caption(clip);
                main.postDelayed(advance, minutes(getContext()) * 60000L);
            }
        }, new Runnable() {
            @Override
            public void run() {   // this clip won't play: drop it and move on
                order.remove(clip);
                if (order.isEmpty()) {
                    empty.setText("These scenes can't be played on this projector. Download them again on the phone remote.");
                    empty.setVisibility(VISIBLE);
                } else {
                    index = index % order.size();
                    show(order.get(index), true);
                }
            }
        });
    }

    private final Runnable hideCaption = new Runnable() {
        @Override
        public void run() {
            caption.animate().alpha(0f).setDuration(800).start();
        }
    };

    private void caption(SceneLibrary.Clip c) {
        captionTitle.setText(c.name);
        captionCredit.setText(c.credit);
        caption.animate().alpha(1f).setDuration(600).start();
        main.removeCallbacks(hideCaption);
        main.postDelayed(hideCaption, 5000);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    // ---------------------------------------------------------------- one video layer

    /** A TextureView + MediaPlayer that loops one clip silently, cropped to fill the screen. */
    private final class Player implements TextureView.SurfaceTextureListener {
        final TextureView view;
        private MediaPlayer mp;
        private Surface surface;
        private File pending;
        private Runnable onReady, onError;
        private int vw, vh;

        Player(Context c) {
            view = new TextureView(c);
            view.setSurfaceTextureListener(this);
            view.setAlpha(0f);
        }

        void play(File f, Runnable ready, Runnable error) {
            release();
            pending = f;
            onReady = ready;
            onError = error;
            if (surface != null) begin();
        }

        private void begin() {
            final File f = pending;
            pending = null;
            if (f == null) return;
            try {
                mp = new MediaPlayer();
                mp.setSurface(surface);
                mp.setDataSource(f.getPath());
                mp.setLooping(true);
                mp.setVolume(0f, 0f);    // a quiet view; the scenes' own soundtracks often have music
                mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                        vw = w;
                        vh = h;
                        crop();
                    }
                });
                mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                    @Override
                    public boolean onInfo(MediaPlayer m, int what, int extra) {
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && onReady != null) {
                            Runnable r = onReady;
                            onReady = null;
                            r.run();
                        }
                        return false;
                    }
                });
                mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer m, int what, int extra) {
                        fail();
                        return true;
                    }
                });
                mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer m) {
                        m.start();
                    }
                });
                mp.prepareAsync();
            } catch (Exception e) {
                fail();
            }
        }

        private void fail() {
            Runnable r = onError;
            onError = null;
            onReady = null;
            release();
            if (r != null) main.post(r);
        }

        /** Fills the screen without stretching: scales the video up and crops the overflow evenly. */
        private void crop() {
            int w = view.getWidth(), h = view.getHeight();
            if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
            float sx = (float) w / vw, sy = (float) h / vh, s = Math.max(sx, sy);
            Matrix m = new Matrix();
            m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f);
            view.setTransform(m);
        }

        /** A very slow push-in and pan across the scene's whole stay. */
        void drift(long ms) {
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationX(0);
            boolean left = Math.random() < 0.5;
            view.animate().scaleX(1.07f).scaleY(1.07f).translationX((left ? -1 : 1) * view.getWidth() * 0.018f)
                    .setDuration(ms).setInterpolator(new LinearInterpolator()).start();
        }

        void release() {
            if (mp != null) {
                try {
                    mp.release();
                } catch (RuntimeException ignored) {
                }
                mp = null;
            }
            view.animate().cancel();
            view.setAlpha(0f);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            surface = new Surface(st);
            if (pending != null) begin();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            crop();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            release();
            if (surface != null) surface.release();
            surface = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
