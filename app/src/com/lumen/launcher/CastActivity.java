package com.lumen.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.List;
import java.util.Locale;

/**
 * Lumen Cast: everything that gets sent to the projector shows here.
 *  • a laptop screen shared from a browser (live JPEG frames, newest wins),
 *  • photos, videos and music sent from the phone remote (◀ ▶ browse, OK play/pause),
 *  • and when nothing is playing, how to connect: mirror a phone, share a laptop, send photos.
 * BACK stops what's showing, then closes.
 */
public class CastActivity extends Activity implements ShareHub.Listener, MediaInbox.Listener, SurfaceHolder.Callback {

    static final String EXTRA_SHARE_URL = "share_url", EXTRA_PHONE_URL = "phone_url", EXTRA_MODE = "mode";
    static final String MODE_SCREEN = "screen", MODE_MEDIA = "media";

    private static final int IDLE = 0, SCREEN = 1, PHOTO = 2, PLAYER = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private float d;
    private int mode = IDLE;

    private FrameLayout root;
    private SurfaceView surface;
    private ImageView photo;
    private VideoView video;
    private LinearLayout idle;
    private TextView caption, osd;
    private volatile boolean surfaceReady, visible;
    private Thread renderer;

    private List<MediaInbox.Item> items;
    private int index = -1;
    private Bitmap photoBitmap;

    static void open(Context c, String mode, String shareUrl, String phoneUrl) {
        Intent i = new Intent(c, CastActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (mode != null) i.putExtra(EXTRA_MODE, mode);
        if (shareUrl != null) i.putExtra(EXTRA_SHARE_URL, shareUrl);
        if (phoneUrl != null) i.putExtra(EXTRA_PHONE_URL, phoneUrl);
        c.startActivity(i);
    }

    private static volatile boolean showing;

    /** Is the Cast screen on the projector right now? */
    static boolean isShowing() {
        return showing;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, match());
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(photo, match());
        video = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(video, vlp);
        idle = buildIdle(getIntent());
        root.addView(idle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        osd = text("", 18, Theme.TEXT);
        osd.setPadding(dp(18), dp(10), dp(18), dp(10));
        osd.setBackground(pill(0xCC0A0A0A, dp(22)));
        osd.setAlpha(0f);
        root.addView(osd, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) osd.getLayoutParams()).bottomMargin = dp(36);
        caption = text("", 15, Theme.SUB);
        caption.setPadding(dp(16), dp(8), dp(16), dp(8));
        caption.setBackground(pill(0x990A0A0A, dp(18)));
        caption.setVisibility(View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clp.topMargin = dp(20);
        clp.rightMargin = dp(24);
        root.addView(caption, clp);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                video.start();
                flash("▶  " + current().name);
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                flash("Finished · ◀ ▶ for more, BACK to close");
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                flash("This projector can't play that video format");
                return true;
            }
        });

        ShareHub.I.setListener(this);
        MediaInbox.I.setListener(this);
        enter(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i.hasExtra(EXTRA_SHARE_URL) || i.hasExtra(EXTRA_PHONE_URL)) {
            root.removeView(idle);
            idle = buildIdle(i);
            root.addView(idle, 3, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        enter(i);
    }

    private void enter(Intent i) {
        String m = i.getStringExtra(EXTRA_MODE);
        if (MODE_SCREEN.equals(m) || ShareHub.I.active()) show(SCREEN);
        else if (MODE_MEDIA.equals(m)) showLatestMedia();
        else show(IDLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        showing = true;
        Front.resumed(this);
        startRenderer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        showing = false;
        Front.paused(this);
        if (video.isPlaying()) video.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        visible = false;
        ShareHub.I.setListener(null);
        MediaInbox.I.setListener(null);
        if (photoBitmap != null) photoBitmap.recycle();
    }

    // ---------------------------------------------------------------- modes

    private void show(int m) {
        mode = m;
        surface.setVisibility(m == SCREEN ? View.VISIBLE : View.INVISIBLE);
        photo.setVisibility(m == PHOTO ? View.VISIBLE : View.GONE);
        video.setVisibility(m == PLAYER ? View.VISIBLE : View.GONE);
        idle.setVisibility(m == IDLE ? View.VISIBLE : View.GONE);
        if (m != PLAYER && video.isPlaying()) video.stopPlayback();
        if (m == IDLE) {
            caption.setVisibility(View.GONE);
            View first = idle.findFocus() != null ? idle.findFocus() : idle.focusSearch(View.FOCUS_DOWN);
            if (first != null) first.requestFocus();
        }
    }

    private MediaInbox.Item current() {
        return items != null && index >= 0 && index < items.size() ? items.get(index) : null;
    }

    private void showLatestMedia() {
        items = MediaInbox.I.items();
        if (items.isEmpty()) {
            show(IDLE);
            return;
        }
        showItem(items.size() - 1);
    }

    private void showItem(int i) {
        if (items == null || items.isEmpty()) return;
        index = Math.max(0, Math.min(items.size() - 1, i));
        final MediaInbox.Item it = items.get(index);
        if (it.kind == MediaInbox.PHOTO) {
            show(PHOTO);
            final int w = Math.max(1280, getResources().getDisplayMetrics().widthPixels);
            final int h = Math.max(720, getResources().getDisplayMetrics().heightPixels);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap b = decodePhoto(it.file.getPath(), w, h);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (current() != it) {
                                if (b != null) b.recycle();
                                return;
                            }
                            photo.setImageBitmap(b);
                            if (photoBitmap != null && photoBitmap != b) photoBitmap.recycle();
                            photoBitmap = b;
                            photo.setAlpha(0f);
                            photo.animate().alpha(1f).setDuration(250).start();
                            if (b == null) flash("This projector can't open that photo format");
                        }
                    });
                }
            }, "lumen-cast-photo").start();
        } else {
            show(PLAYER);
            video.setVideoURI(Uri.fromFile(it.file));
        }
        if (items.size() > 1) flash((index + 1) + " of " + items.size() + "  ·  ◀ ▶ to browse");
    }

    /** Decodes a phone photo at screen size (12 MP photos would not fit in memory) and applies its rotation. */
    static Bitmap decodePhoto(String path, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int rot = 0;
            try {
                int or = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {
            }
            int w = rot % 180 == 0 ? o.outWidth : o.outHeight, h = rot % 180 == 0 ? o.outHeight : o.outWidth;
            int sample = 1;
            while (w / (sample * 2) >= maxW && h / (sample * 2) >= maxH) sample *= 2; // stay at least screen-sized
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(path, o2);
            if (b == null || rot == 0) return b;
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- live screen

    @Override
    public void onShareStarted() {
        main.post(new Runnable() {
            @Override
            public void run() {
                show(SCREEN);
                flash("Showing your computer screen · BACK to stop");
            }
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
    }

    /** Decodes the newest frame and draws it, on its own thread, so the UI never waits for the network. */
    private void startRenderer() {
        if (renderer != null && renderer.isAlive()) return;
        renderer = new Thread(new Runnable() {
            @Override
            public void run() {
                long seq = 0;
                Bitmap reuse = null;
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                RectF dst = new RectF();
                boolean paused = false;
                while (visible) {
                    Object[] f;
                    try {
                        f = ShareHub.I.waitNewer(seq, 600);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (mode == SCREEN) {
                        long idleMs = ShareHub.I.idleMs();
                        if (f == null && idleMs > 3000 && !paused) {
                            paused = true;
                            caption(idleMs > 60000 ? null : "Waiting for the computer…");
                        }
                        if (f == null && idleMs > 20000) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (mode == SCREEN && !ShareHub.I.active()) show(IDLE);
                                }
                            });
                        }
                    }
                    if (f == null || mode != SCREEN || !surfaceReady) continue;
                    if (paused) {
                        paused = false;
                        caption(null);
                    }
                    byte[] jpeg = (byte[]) f[0];
                    seq = (Long) f[1];
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inMutable = true;
                    if (reuse != null) o.inBitmap = reuse;
                    Bitmap b;
                    try {
                        b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                    } catch (IllegalArgumentException e) {    // size changed: can't reuse the old bitmap
                        o.inBitmap = null;
                        b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                    }
                    if (b == null) continue;
                    reuse = b;
                    ShareHub.I.setSize(b.getWidth(), b.getHeight());
                    SurfaceHolder holder = surface.getHolder();
                    Canvas c = null;
                    try {
                        c = holder.lockCanvas();
                        if (c == null) continue;
                        float sc = Math.min(c.getWidth() / (float) b.getWidth(), c.getHeight() / (float) b.getHeight());
                        float w = b.getWidth() * sc, h = b.getHeight() * sc;
                        dst.set((c.getWidth() - w) / 2f, (c.getHeight() - h) / 2f, (c.getWidth() + w) / 2f, (c.getHeight() + h) / 2f);
                        c.drawColor(0xFF000000);
                        c.drawBitmap(b, null, dst, paint);
                    } catch (RuntimeException ignored) {
                    } finally {
                        if (c != null) {
                            try {
                                holder.unlockCanvasAndPost(c);
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                }
            }
        }, "lumen-cast-render");
        renderer.start();
    }

    // ---------------------------------------------------------------- media from the phone

    @Override
    public void onReceiving(final String name, final int kind, final float progress) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (progress >= 0.999f) return;
                caption((kind == MediaInbox.PHOTO ? "Receiving photo" : kind == MediaInbox.VIDEO ? "Receiving video" : "Receiving music")
                        + (progress > 0 ? "  " + Math.round(progress * 100) + "%" : "…"));
            }
        });
    }

    @Override
    public void onReceived(MediaInbox.Item item) {
        main.post(new Runnable() {
            @Override
            public void run() {
                caption(null);
                if (mode == SCREEN && ShareHub.I.active()) return; // a live share keeps the screen
                items = MediaInbox.I.items();
                showItem(items.size() - 1);
            }
        });
    }

    @Override
    public void onFailed(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                caption(null);
                flash(message);
            }
        });
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return mode != IDLE || super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {
            if (mode == SCREEN) {
                ShareHub.I.stopByViewer();
                flash("Sharing stopped");
                show(IDLE);
            } else if (mode == PHOTO || mode == PLAYER) {
                show(IDLE);
            } else {
                finish();
            }
            return true;
        }
        if (mode == IDLE) return super.dispatchKeyEvent(e);
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (mode == PLAYER && k == KeyEvent.KEYCODE_DPAD_RIGHT) seek(10000);
            else if (items != null && index < items.size() - 1) showItem(index + 1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (mode == PLAYER && k == KeyEvent.KEYCODE_DPAD_LEFT) seek(-10000);
            else if (items != null && index > 0) showItem(index - 1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (mode == PHOTO || mode == PLAYER) {
                int next = index + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                if (items != null && next >= 0 && next < items.size()) showItem(next);
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (mode == PLAYER) {
                if (video.isPlaying()) {
                    video.pause();
                    flash("❚❚  " + time(video.getCurrentPosition()) + " / " + time(video.getDuration()));
                } else {
                    video.start();
                    flash("▶  " + time(video.getCurrentPosition()) + " / " + time(video.getDuration()));
                }
            } else if (mode == SCREEN) {
                flash(Math.round(ShareHub.I.fps()) + " frames a second · BACK to stop");
            } else if (current() != null) {
                flash((index + 1) + " of " + items.size() + "  ·  " + current().name);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void seek(int deltaMs) {
        int to = Math.max(0, Math.min(Math.max(0, video.getDuration() - 1000), video.getCurrentPosition() + deltaMs));
        video.seekTo(to);
        flash((deltaMs > 0 ? "»  " : "«  ") + time(to) + " / " + time(video.getDuration()));
    }

    private static String time(int ms) {
        int s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- idle screen

    private LinearLayout buildIdle(Intent i) {
        String shareUrl = i.getStringExtra(EXTRA_SHARE_URL);
        String phoneUrl = i.getStringExtra(EXTRA_PHONE_URL);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(Theme.JET);
        page.setPadding(dp(48), dp(24), dp(48), dp(24));

        TextView title = text("Cast to Lumen", 30, Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        TextView sub = text("Show a phone, a laptop, or your photos and videos on the big screen", 15, Theme.SUB);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(22));
        page.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. mirror a phone with the projector's own receiver
        final MirrorApps.App mirror = MirrorApps.best(this);
        LinearLayout c1 = card("Mirror your phone",
                mirror != null ? "Opens " + mirror.label + ". Then on the phone: Cast / Smart View (Android) or Screen Mirroring (iPhone)"
                        : "No mirroring app was found on this projector. Install a receiver (Miracast or AirPlay), or use the other two ways");
        if (mirror != null) {
            TextView b = button("Open " + mirror.label);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(mirror.launch);
                    } catch (RuntimeException e) {
                        flash("Couldn't open " + mirror.label);
                    }
                }
            });
            c1.addView(b, buttonLp());
        }
        row.addView(c1, cardLp(false));

        // 2. share a laptop screen from its browser
        LinearLayout c2 = card("Share a computer screen",
                "On a laptop on this Wi-Fi, open this in Chrome or Edge and choose a screen, window or tab");
        if (shareUrl != null) {
            TextView url = text(shareUrl.replaceFirst("#.*$", ""), 16, Theme.CERULEAN_LIGHT);
            url.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            url.setPadding(0, dp(12), 0, 0);
            c2.addView(url);
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("pin=(\\d{4})").matcher(shareUrl);
            if (pm.find()) {
                TextView pin = text("PIN  " + pm.group(1), 16, Theme.ROSE);
                pin.setPadding(0, dp(6), 0, 0);
                c2.addView(pin);
            }
            if (shareUrl.startsWith("http:")) {
                TextView warn = text("The secure address is still starting; browsers need it to share a screen", 12, Theme.DIM);
                warn.setPadding(0, dp(6), 0, 0);
                c2.addView(warn);
            }
        }
        row.addView(c2, cardLp(true));

        // 3. photos and videos from the phone
        LinearLayout c3 = card("Send photos and videos", "Scan with your phone, then tap Cast in the remote and pick photos or videos");
        if (phoneUrl != null) {
            QrView qr = new QrView(this);
            qr.setText(phoneUrl);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(120), dp(120));
            qlp.topMargin = dp(12);
            qlp.gravity = Gravity.CENTER_HORIZONTAL;
            c3.addView(qr, qlp);
        }
        if (!MediaInbox.I.items().isEmpty()) {
            TextView b = button("Show what was sent (" + MediaInbox.I.items().size() + ")");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showLatestMedia();
                }
            });
            c3.addView(b, buttonLp());
        }
        row.addView(c3, cardLp(true));

        TextView foot = text("BACK closes  ·  say “screen share” or “mirror my phone” any time", 13, Theme.DIM);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(22), 0, 0);
        page.addView(foot);
        return page;
    }

    private LinearLayout card(String title, String body) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Theme.GLASS_TOP, Theme.GLASS_BOTTOM});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Theme.GLASS_STROKE);
        c.setBackground(bg);
        TextView t = text(title, 18, Theme.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.addView(t);
        TextView b = text(body, 13, Theme.SUB);
        b.setPadding(0, dp(6), 0, 0);
        c.addView(b);
        return c;
    }

    private LinearLayout.LayoutParams cardLp(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.leftMargin = dp(16);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(14);
        return lp;
    }

    private TextView button(String label) {
        final TextView t = text(label, 15, Theme.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setSingleLine(true);
        final GradientDrawable idleBg = pill(Theme.CERULEAN_GLASS, dp(22));
        final GradientDrawable focusBg = pill(Theme.CERULEAN, dp(22));
        focusBg.setStroke(dp(2), Theme.ROSE);
        t.setBackground(idleBg);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                t.setBackground(has ? focusBg : idleBg);
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(150).start();
            }
        });
        return t;
    }

    // ---------------------------------------------------------------- helpers

    private void caption(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                caption.setText(s == null ? "" : s);
                caption.setVisibility(s == null ? View.GONE : View.VISIBLE);
            }
        });
    }

    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osd.animate().alpha(0f).setDuration(300).start();
        }
    };

    private void flash(String s) {
        osd.setText(s);
        osd.animate().cancel();
        osd.setAlpha(1f);
        main.removeCallbacks(hideOsd);
        main.postDelayed(hideOsd, 2600);
    }

    private GradientDrawable pill(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
