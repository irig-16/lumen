package com.lumen.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live TV: free channels from iptv-org, played full screen like a TV.
 *
 * Fast (each country / category is a small list fetched on demand and cached), clean (channels that
 * don't answer are hidden), and easy to drive: voice search, number keys, CH+/CH−, and the phone
 * remote's TV tab.
 */
public class LiveTvActivity extends Activity implements TvCatalog.Listener {

    static final String EXTRA_QUERY = "query", EXTRA_SECTION = "section", EXTRA_KEY = "key";
    private static final long PLAY_TIMEOUT = 15000;
    private static final int REQ_MIC = 7;

    private static WeakReference<LiveTvActivity> live = new WeakReference<LiveTvActivity>(null);

    /** What's on screen right now (for the phone remote), or null. */
    static IptvFeed.Channel nowPlaying() {
        LiveTvActivity a = live.get();
        return a != null && a.playerLayer.getVisibility() == View.VISIBLE ? a.current() : null;
    }

    /** The phone remote's controls: "next", "prev", "stop". Returns a reply. */
    static String control(final String what) {
        final LiveTvActivity a = live.get();
        if (a == null || a.playerLayer.getVisibility() != View.VISIBLE) return "Nothing is playing";
        a.main.post(new Runnable() {
            @Override
            public void run() {
                if ("next".equals(what)) a.zap(1);
                else if ("prev".equals(what)) a.zap(-1);
                else if ("stop".equals(what)) a.stopPlayer();
            }
        });
        return "next".equals(what) ? "Next channel" : "prev".equals(what) ? "Previous channel" : "Back to the channel list";
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService logoPool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> logos = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private TvCatalog cat;
    private float d;
    private View browser;
    private ListView side;
    private GridView grid;
    private TextView heading, status;
    private ProgressBar spinner;

    private final List<Row> rows = new ArrayList<Row>();
    private List<IptvFeed.Channel> shown = new ArrayList<IptvFeed.Channel>();
    private String section = "";
    private String query = "";            // non-empty while showing search results
    private boolean showAll;

    // player
    private FrameLayout playerLayer;
    private VideoView video;
    private LinearLayout info;
    private TextView infoName, infoMeta, playStatus;
    private ProgressBar playSpinner;
    private List<IptvFeed.Channel> playing = new ArrayList<IptvFeed.Channel>();
    private int playIndex = -1, altIndex, altTried;
    private boolean prepared;
    private String digits = "";

    // voice
    private SpeechRecognizer recognizer;

    private static final int R_VOICE = 0, R_SEARCH = 1, R_SECTION = 2, R_COUNTRIES = 3, R_OFFLINE = 4;

    private static final class Row {
        final int type;
        final String label;

        Row(int type, String label) {
            this.type = type;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        live = new WeakReference<LiveTvActivity>(this);
        d = getResources().getDisplayMetrics().density;
        cat = TvCatalog.get(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.JET);
        browser = buildBrowser();
        root.addView(browser);
        playerLayer = buildPlayer();
        playerLayer.setVisibility(View.GONE);
        root.addView(playerLayer);
        setContentView(root);
        cat.addListener(this);
        buildRows();
        String start = getSharedPreferences("lumen_tv", MODE_PRIVATE).getString("section", "");
        List<String> names = cat.sectionNames();
        if (!names.contains(start)) start = names.size() > 2 ? names.get(2) : names.get(0);
        openSection(start);
        handleIntent(getIntent());
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                cat.loadIndex();          // for searching later; low priority, after the first screen is up
            }
        }, 8000);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (playerLayer.getVisibility() == View.VISIBLE) video.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        live = new WeakReference<LiveTvActivity>(this);
        if (playerLayer.getVisibility() == View.VISIBLE && prepared) video.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cat.removeListener(this);
        main.removeCallbacksAndMessages(null);
        logoPool.shutdownNow();
        video.stopPlayback();
        if (recognizer != null) recognizer.destroy();
    }

    private void handleIntent(Intent i) {
        String key = i.getStringExtra(EXTRA_KEY), sec = i.getStringExtra(EXTRA_SECTION), q = i.getStringExtra(EXTRA_QUERY);
        i.removeExtra(EXTRA_KEY);
        i.removeExtra(EXTRA_SECTION);
        i.removeExtra(EXTRA_QUERY);
        if (sec != null && sec.length() > 0 && key == null) openSection(sec);
        if (key != null) {
            IptvFeed.Channel c = cat.find(key);
            if (c != null) {
                List<IptvFeed.Channel> l = sec != null ? cat.section(sec, false) : shown;
                int at = l.indexOf(c);
                if (at < 0) {
                    l = new ArrayList<IptvFeed.Channel>(l);
                    l.add(0, c);
                    at = 0;
                }
                play(l, at);
            }
        } else if (q != null && q.trim().length() > 0) {
            search(q.trim(), true);
        }
    }

    // ================================================================ catalog updates

    @Override
    public void onSection(final String name) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                if ("search".equals(name) && query.length() > 0) search(query, false);
                else if (query.length() == 0 && (name.equals(section) || TvCatalog.FAV.equals(section)
                        || TvCatalog.RECENT.equals(section))) refreshGrid(false);
                else if (query.length() > 0 && name.equals("list:" + query)) refreshSearch();
            }
        });
    }

    // ================================================================ browsing

    private void buildRows() {
        rows.clear();
        rows.add(new Row(R_VOICE, "Search by voice"));
        rows.add(new Row(R_SEARCH, "Search by name"));
        for (String s : cat.sectionNames()) rows.add(new Row(R_SECTION, s));
        rows.add(new Row(R_COUNTRIES, "Other countries…"));
        rows.add(new Row(R_OFFLINE, "Offline channels"));
        ((BaseAdapter) side.getAdapter()).notifyDataSetChanged();
    }

    private void openSection(String name) {
        query = "";
        section = name;
        getSharedPreferences("lumen_tv", MODE_PRIVATE).edit().putString("section", name).apply();
        ((BaseAdapter) side.getAdapter()).notifyDataSetChanged();
        refreshGrid(true);
    }

    /** Re-reads the section; keeps the focused channel in place when the list changes under it. */
    private void refreshGrid(boolean top) {
        IptvFeed.Channel keep = top ? null : selected();
        shown = cat.section(section, showAll);
        int working = 0;
        for (IptvFeed.Channel c : shown) if (cat.state(c) == 1) working++;
        heading.setText(section);
        boolean loading = !cat.loaded(section);
        boolean checking = cat.checking(section);
        spinner.setVisibility(loading || checking ? View.VISIBLE : View.GONE);
        String st;
        if (loading) st = cat.error().length() > 0 ? cat.error() : "Getting the channels…";
        else if (shown.isEmpty()) st = TvCatalog.FAV.equals(section) ? "No favourites yet. While a channel plays, press MENU → Add to favourites."
                : TvCatalog.RECENT.equals(section) ? "Channels you watch show up here."
                : cat.error().length() > 0 ? cat.error() : "No working channels here right now.";
        else {
            st = working + " working";
            if (checking) st += "   ·   checking the rest…";
            int hidden = cat.hiddenCount(section);
            if (hidden > 0 && !showAll) st += "   ·   " + hidden + " offline hidden";
        }
        status.setText(st);
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        if (keep != null) {
            int at = shown.indexOf(keep);
            if (at >= 0) grid.setSelection(at);
        } else if (top) {
            grid.setSelection(0);
        }
    }

    private IptvFeed.Channel selected() {
        int i = grid.getSelectedItemPosition();
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    private void search(String q, boolean playBest) {
        query = q;
        heading.setText("“" + q + "”");
        if (!cat.searchReady()) {
            cat.loadIndex();
            spinner.setVisibility(View.VISIBLE);
            status.setText("Searching every channel… (the first search takes a moment)");
        }
        List<IptvFeed.Channel> hits = cat.search(q);
        shown = hits;
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        ((BaseAdapter) side.getAdapter()).notifyDataSetChanged();
        grid.setSelection(0);
        if (cat.searchReady()) {
            spinner.setVisibility(View.GONE);
            status.setText(hits.isEmpty() ? "No working channel called “" + q + "”. Try a shorter name."
                    : hits.size() + " found");
            cat.checkList(q, hits);
            if (playBest && !hits.isEmpty()) play(hits, 0);
            else if (!hits.isEmpty()) grid.requestFocus();
        } else if (playBest) {
            pendingPlay = q;
        }
    }

    private String pendingPlay;

    private void refreshSearch() {
        IptvFeed.Channel keep = selected();
        List<IptvFeed.Channel> hits = cat.search(query);
        shown = hits;
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        if (keep != null && hits.indexOf(keep) >= 0) grid.setSelection(hits.indexOf(keep));
        if (pendingPlay != null && pendingPlay.equals(query) && !hits.isEmpty()) {
            pendingPlay = null;
            play(hits, 0);
        }
    }

    private void onRow(Row r) {
        switch (r.type) {
            case R_VOICE:
                voiceSearch();
                break;
            case R_SEARCH:
                typeSearch(null);
                break;
            case R_COUNTRIES:
                pickCountry();
                break;
            case R_OFFLINE:
                showAll = !showAll;
                ((BaseAdapter) side.getAdapter()).notifyDataSetChanged();
                if (query.length() == 0) refreshGrid(false);
                break;
            default:
                openSection(r.label);
                if (!shown.isEmpty()) grid.requestFocus();
        }
    }

    private void typeSearch(String hint) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        e.setHint("Channel name, like CNN, Sun TV or Al Jazeera");
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Find a channel")
                .setMessage(hint)
                .setView(e)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String q = e.getText().toString().trim();
                        if (q.length() > 0) search(q, false);
                    }
                })
                .setNegativeButton("Cancel", null).create();
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                String q = e.getText().toString().trim();
                if (q.length() > 0) search(q, false);
                dlg.dismiss();
                return true;
            }
        });
        dlg.show();
    }

    private void pickCountry() {
        final List<String[]> cs = TvCatalog.allCountries();
        String[] names = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) names[i] = cs.get(i)[1];
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Channels from")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        cat.pin(cs.get(w)[0]);
                        buildRows();
                        openSection(cs.get(w)[1]);
                        for (int i = 0; i < rows.size(); i++) if (rows.get(i).label.equals(section)) side.setSelection(i);
                    }
                }).show();
    }

    // ================================================================ voice

    private void voiceSearch() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            typeSearch("This projector has no speech engine. Type here, or say it from your phone: "
                    + "open the Lumen remote, tap the mic and say “watch CNN live”.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { status.setText("Listening… say a channel name"); }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { status.setText("Searching…"); }
                public void onError(int e) { status.setText("Didn't catch that. Try again, or search by name."); }
                public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) status.setText("“" + r.get(0) + "”");
                }
                public void onEvent(int t, Bundle b) { }
                public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r == null || r.isEmpty()) return;
                    String said = r.get(0).replaceAll("(?i)^(?:play|watch|put on|open|tune to|switch to)\\s+", "")
                            .replaceAll("(?i)\\s+(?:channel|live|tv channel)$", "").trim();
                    search(said, true);
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            typeSearch(null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_MIC && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) voiceSearch();
    }

    // ================================================================ playing

    private void play(List<IptvFeed.Channel> list, int index) {
        if (list.isEmpty()) return;
        playing = new ArrayList<IptvFeed.Channel>(list);
        playIndex = (index + playing.size()) % playing.size();
        browser.setVisibility(View.GONE);
        playerLayer.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startChannel();
    }

    private IptvFeed.Channel current() {
        return playIndex >= 0 && playIndex < playing.size() ? playing.get(playIndex) : null;
    }

    private void startChannel() {
        IptvFeed.Channel c = current();
        if (c == null) return;
        altIndex = cat.workingAlt(c);       // the link that worked last time
        altTried = 0;
        cat.remember(c, true);
        startStream();
    }

    private IptvFeed.Channel link() {
        IptvFeed.Channel c = current();
        if (c == null) return null;
        return altIndex == 0 ? c : c.alts.get(altIndex - 1);
    }

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (!prepared) failed();
        }
    };

    private void startStream() {
        IptvFeed.Channel l = link();
        if (l == null) return;
        prepared = false;
        main.removeCallbacks(timeout);
        playSpinner.setVisibility(View.VISIBLE);
        playStatus.setText("");
        showInfo();
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", l.userAgent.length() > 0 ? l.userAgent : "Mozilla/5.0 (Linux; Android 11) Lumen");
        if (l.referrer.length() > 0) h.put("Referer", l.referrer);
        try {
            video.stopPlayback();
            video.setVideoURI(Uri.parse(l.url), h);
            video.start();
        } catch (RuntimeException e) {
            failed();
            return;
        }
        main.postDelayed(timeout, PLAY_TIMEOUT);
    }

    private void onPrepared(MediaPlayer mp) {
        prepared = true;
        main.removeCallbacks(timeout);
        playSpinner.setVisibility(View.GONE);
        playStatus.setText("");
        IptvFeed.Channel c = current();
        if (c != null) cat.markGood(c, altIndex);
        mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer m, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) playSpinner.setVisibility(View.VISIBLE);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                    playSpinner.setVisibility(View.GONE);
                return false;
            }
        });
        showInfo();
    }

    /** One link failed: the next link; when all failed, hide the channel and move on to the next one. */
    private void failed() {
        main.removeCallbacks(timeout);
        IptvFeed.Channel c = current();
        if (c == null) return;
        altTried++;
        if (altTried <= c.alts.size()) {
            altIndex = (altIndex + 1) % (c.alts.size() + 1);
            playStatus.setText("Trying another link…");
            startStream();
            return;
        }
        try {
            video.stopPlayback();
        } catch (RuntimeException ignored) {
        }
        cat.markBad(c);
        playSpinner.setVisibility(View.GONE);
        playStatus.setText(c.name + " isn't on right now, so it's hidden. Next channel…");
        info.setVisibility(View.VISIBLE);
        if (playing.size() > 1) {
            playing.remove(playIndex);
            if (playIndex >= playing.size()) playIndex = 0;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (playerLayer.getVisibility() == View.VISIBLE) startChannel();
                }
            }, 1800);
        }
    }

    private void zap(int step) {
        if (playing.isEmpty()) return;
        for (int i = 0; i < playing.size(); i++) {      // skip channels already known to be off
            playIndex = (playIndex + step + playing.size()) % playing.size();
            if (cat.state(playing.get(playIndex)) != 0) break;
        }
        startChannel();
    }

    private void stopPlayer() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(hideInfo);
        try {
            video.stopPlayback();
        } catch (RuntimeException ignored) {
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerLayer.setVisibility(View.GONE);
        browser.setVisibility(View.VISIBLE);
        IptvFeed.Channel c = current();
        if (query.length() > 0) refreshSearch();
        else refreshGrid(false);
        int at = shown.indexOf(c);
        if (at >= 0) grid.setSelection(at);
        grid.requestFocus();
    }

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            if (prepared) info.setVisibility(View.GONE);
        }
    };

    private void showInfo() {
        IptvFeed.Channel c = current();
        if (c == null) return;
        infoName.setText((playIndex + 1) + "   " + c.name + (cat.isFavourite(c) ? "   ★" : ""));
        String g = c.groups.isEmpty() ? "" : TextUtils.join(", ", c.groups);
        String b = c.badge();
        infoMeta.setText(g + (g.length() > 0 && b.length() > 0 ? "   ·   " : "") + b);
        info.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideInfo);
        main.postDelayed(hideInfo, 5000);
    }

    private void playerMenu() {
        final IptvFeed.Channel c = current();
        if (c == null) return;
        final boolean fav = cat.isFavourite(c);
        final boolean vlc = isInstalled("org.videolan.vlc");
        final List<String> items = new ArrayList<String>();
        items.add(fav ? "Remove from favourites" : "Add to favourites");
        if (!c.alts.isEmpty()) items.add("Try another link");
        items.add(vlc ? "Open in VLC" : "Open in another player");
        items.add("Back to the channels");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(c.name)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String a = items.get(w);
                        if (a.endsWith("favourites")) {
                            cat.toggleFavourite(c);
                            showInfo();
                        } else if (a.startsWith("Try another")) {
                            altIndex = (altIndex + 1) % (c.alts.size() + 1);
                            startStream();
                        } else if (a.startsWith("Open in")) {
                            openExternally(vlc);
                        } else {
                            stopPlayer();
                        }
                    }
                }).show();
    }

    private void openExternally(boolean vlc) {
        IptvFeed.Channel l = link();
        if (l == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(l.url), "video/*")
                .putExtra("title", current().name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (vlc) i.setPackage("org.videolan.vlc");
        try {
            video.stopPlayback();
            startActivity(vlc ? i : Intent.createChooser(i, "Play with"));
        } catch (RuntimeException e) {
            playStatus.setText("No other video player is installed. VLC from the Play Store plays almost every stream.");
        }
    }

    // ================================================================ keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (playerLayer.getVisibility() == View.VISIBLE) stopPlayer();
            voiceSearch();
            return true;
        }
        if (playerLayer.getVisibility() != View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK && query.length() > 0) {
                openSection(section);
                return true;
            }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_INFO:
                if (info.getVisibility() == View.VISIBLE && prepared) info.setVisibility(View.GONE);
                else showInfo();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                playerMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                stopPlayer();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (video.isPlaying()) video.pause();
                else video.start();
                return true;
            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    typeDigit((char) ('0' + code - KeyEvent.KEYCODE_0));
                    return true;
                }
                return super.onKeyDown(code, e);
        }
    }

    private final Runnable jump = new Runnable() {
        @Override
        public void run() {
            try {
                int n = Integer.parseInt(digits);
                if (n >= 1 && n <= playing.size()) {
                    playIndex = n - 1;
                    startChannel();
                }
            } catch (NumberFormatException ignored) {
            }
            digits = "";
        }
    };

    private void typeDigit(char c) {
        digits += c;
        infoName.setText(digits + "_");
        info.setVisibility(View.VISIBLE);
        main.removeCallbacks(jump);
        main.postDelayed(jump, 1500);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================ views

    private View buildBrowser() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(20), dp(24), dp(12));

        // ---- the side panel: big, high-contrast rows
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(14), dp(16), dp(14), dp(14));
        left.setBackground(round(0xFF15181E, 18));
        TextView title = text("Live TV", 28, Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(dp(10), 0, 0, dp(10));
        left.addView(title);
        side = new ListView(this);
        side.setDivider(null);
        side.setDividerHeight(dp(2));
        GradientDrawable sel = round(0xFFFF2D95, 12);
        side.setSelector(sel);
        side.setDrawSelectorOnTop(false);
        side.setAdapter(new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int i) { return rows.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                Row r = rows.get(i);
                TextView t = v instanceof TextView ? (TextView) v : text("", 17, Theme.TEXT);
                String label = r.label;
                if (r.type == R_VOICE) label = "🎙  " + label;
                else if (r.type == R_SEARCH) label = "⌕  " + label;
                else if (r.type == R_OFFLINE) label = label + ":  " + (showAll ? "shown" : "hidden");
                else if (TvCatalog.FAV.equals(r.label)) label = "★  " + label;
                t.setText(label);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(12), dp(10), dp(10), dp(10));
                boolean on = r.type == R_SECTION && r.label.equals(section) && query.length() == 0;
                t.setTextColor(on ? Color.WHITE : (r.type == R_SECTION ? 0xE6F5F5F7 : Theme.CERULEAN_LIGHT));
                t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                t.setBackground(on ? round(0x40007BA7, 12) : null);
                return t;
            }
        });
        side.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> a, View v, int i, long id) {
                onRow(rows.get(i));
            }
        });
        left.addView(side, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView hint = text("◀ ▶ move between list and channels", 11, Theme.DIM);
        hint.setPadding(dp(10), dp(8), 0, 0);
        left.addView(hint);
        row.addView(left, new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- channels
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(22), dp(4), 0, 0);
        heading = text("", 24, Theme.TEXT);
        heading.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        right.addView(heading);
        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this);
        st.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        status = text("", 14, Theme.SUB);
        status.setPadding(dp(8), dp(4), 0, dp(4));
        st.addView(status);
        right.addView(st);
        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(14));
        grid.setHorizontalSpacing(dp(14));
        GradientDrawable ring = round(0x26FF2D95, 14);
        ring.setStroke(dp(3), Theme.ROSE);
        grid.setSelector(ring);
        grid.setDrawSelectorOnTop(true);
        grid.setClipToPadding(false);
        grid.setPadding(dp(4), dp(10), dp(4), dp(24));
        grid.setAdapter(new ChannelAdapter());
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> a, View v, int i, long id) {
                play(shown, i);
            }
        });
        right.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return row;
    }

    private final class ChannelAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup p) {
            Holder h;
            if (v == null) {
                LinearLayout card = new LinearLayout(LiveTvActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(10), dp(10), dp(10), dp(10));
                card.setBackground(round(0xFF15181E, 14));
                h = new Holder();
                h.logo = new ImageView(LiveTvActivity.this);
                h.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.logo.setPadding(dp(10), dp(8), dp(10), dp(8));
                h.logo.setBackground(round(0xFF1F232B, 10));
                card.addView(h.logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
                h.name = text("", 15, Theme.TEXT);
                h.name.setSingleLine(true);
                h.name.setEllipsize(TextUtils.TruncateAt.END);
                h.name.setPadding(dp(2), dp(7), 0, 0);
                card.addView(h.name);
                h.meta = text("", 11, Theme.DIM);
                h.meta.setSingleLine(true);
                h.meta.setEllipsize(TextUtils.TruncateAt.END);
                h.meta.setPadding(dp(2), 0, 0, 0);
                card.addView(h.meta);
                card.setTag(h);
                v = card;
            } else {
                h = (Holder) v.getTag();
            }
            IptvFeed.Channel c = shown.get(i);
            int st = cat.state(c);
            h.name.setText((cat.isFavourite(c) ? "★ " : "") + c.name);
            h.name.setTextColor(st == 0 ? Theme.DIM : Theme.TEXT);
            String meta = st == 1 ? "● On   " : st == 0 ? "Offline   " : "";
            h.meta.setText(meta + c.badge());
            h.meta.setTextColor(st == 1 ? 0xFF5FD35F : Theme.DIM);
            if (!c.logo.equals(h.url)) {
                h.url = c.logo;
                h.logo.setImageBitmap(null);
                loadLogo(h, c.logo);
            }
            return v;
        }
    }

    private static final class Holder {
        ImageView logo;
        TextView name, meta;
        String url;
    }

    private void loadLogo(final Holder h, final String url) {
        if (url == null || url.length() == 0) return;
        Bitmap b = logos.get(url);
        if (b != null) {
            h.logo.setImageBitmap(b);
            return;
        }
        try {
            logoPool.execute(new Runnable() {
                @Override
                public void run() {
                    if (!url.equals(h.url)) return;          // scrolled past already
                    final Bitmap bmp = fetchLogo(url);
                    if (bmp == null) return;
                    logos.put(url, bmp);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (url.equals(h.url)) h.logo.setImageBitmap(bmp);
                        }
                    });
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private static Bitmap fetchLogo(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", "Lumen-Launcher");
            if (c.getResponseCode() >= 400) return null;
            InputStream in = c.getInputStream();
            byte[] data = TrendingFeed.readAll(in);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 180 && o.outHeight / (s * 2) >= 80) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private FrameLayout buildPlayer() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        f.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                LiveTvActivity.this.onPrepared(mp);
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                failed();
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                startStream();     // a live stream "ending" is a hiccup: reconnect
            }
        });
        playSpinner = new ProgressBar(this);
        f.addView(playSpinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(36), dp(30), dp(36), dp(26));
        info.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        infoName = text("", 28, Theme.TEXT);
        info.addView(infoName);
        infoMeta = text("", 15, Theme.SUB);
        infoMeta.setPadding(0, dp(4), 0, 0);
        info.addView(infoMeta);
        playStatus = text("", 16, 0xFFFFC46B);
        playStatus.setPadding(0, dp(8), 0, 0);
        info.addView(playStatus);
        TextView keys = text("▲ ▼ channels   ·   0–9 jump   ·   OK info   ·   MENU favourite / VLC   ·   BACK list", 12, Theme.DIM);
        keys.setPadding(0, dp(10), 0, 0);
        info.addView(keys);
        f.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return f;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    static void open(Context c, String query) {
        Intent i = new Intent(c, LiveTvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (query != null && query.length() > 0) i.putExtra(EXTRA_QUERY, query);
        c.startActivity(i);
    }
}
