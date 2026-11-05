package com.lumen.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live TV: thousands of free channels from the iptv-org project, browsed by country and category and
 * played full screen like a TV. Up/Down (or CH+/CH−) zaps, number keys jump, OK shows what's on, MENU
 * offers favourites, another link, or opening the channel in VLC.
 */
public class LiveTvActivity extends Activity {

    static final String EXTRA_QUERY = "query", EXTRA_SECTION = "section";
    private static final String S_FAV = "★  Favourites", S_RECENT = "Recently watched", S_SEARCH = "Search…",
            S_COUNTRIES = "All countries…";
    private static final long PLAY_TIMEOUT = 15000;

    /** Kept across openings so Live TV opens instantly the second time. */
    private static volatile List<IptvFeed.Channel> all;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService logoPool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> logos = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private SharedPreferences sp;
    private float d;
    private FrameLayout root;
    private View browser;
    private ListView sectionList;
    private GridView grid;
    private TextView heading, status;
    private ProgressBar spinner;

    // player
    private FrameLayout playerLayer;
    private VideoView video;
    private LinearLayout info;
    private TextView infoName, infoMeta, playStatus;
    private ProgressBar playSpinner;
    private List<IptvFeed.Channel> playing = new ArrayList<IptvFeed.Channel>();
    private int playIndex = -1, altIndex;
    private boolean prepared;
    private String digits = "";

    private final List<String> sections = new ArrayList<String>();
    private List<IptvFeed.Channel> shown = new ArrayList<IptvFeed.Channel>();
    private String currentSection = "";
    private String home = "";

    static void open(Context c, String query) {
        Intent i = new Intent(c, LiveTvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (query != null && query.length() > 0) i.putExtra(EXTRA_QUERY, query);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        d = getResources().getDisplayMetrics().density;
        sp = getSharedPreferences("lumen_tv", MODE_PRIVATE);
        home = new AppStore(this).getString("geo_country", "").toLowerCase(Locale.ROOT);
        if (home.length() != 2) home = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.JET);
        browser = buildBrowser();
        root.addView(browser);
        playerLayer = buildPlayer();
        playerLayer.setVisibility(View.GONE);
        root.addView(playerLayer);
        setContentView(root);
        loadChannels(false);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (all != null) handleIntent();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && playerLayer.getVisibility() == View.VISIBLE) video.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (video != null && playerLayer.getVisibility() == View.VISIBLE && prepared) video.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        logoPool.shutdownNow();
        if (video != null) video.stopPlayback();
    }

    // ================================================================ loading

    private void loadChannels(final boolean force) {
        if (all != null && !force) {
            onChannels();
            return;
        }
        spinner.setVisibility(View.VISIBLE);
        status.setText("Getting the channel list…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<IptvFeed.Channel> l = IptvFeed.load(getCacheDir(), force);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            all = l;
                            onChannels();
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            spinner.setVisibility(View.GONE);
                            status.setText("Couldn't get the channel list (" + e.getMessage()
                                    + "). Check the internet connection, then press OK to try again.");
                            status.setFocusable(true);
                            status.requestFocus();
                            status.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    loadChannels(true);
                                }
                            });
                        }
                    });
                }
            }
        }, "lumen-iptv").start();
    }

    private void onChannels() {
        spinner.setVisibility(View.GONE);
        status.setText("");
        sections.clear();
        sections.add(S_FAV);
        sections.add(S_RECENT);
        if (home.length() == 2 && !IptvFeed.inCountry(all, home).isEmpty()) sections.add(countryLabel(home));
        for (String cc : pinnedCountries()) {
            String label = countryLabel(cc);
            if (!sections.contains(label)) sections.add(label);
        }
        sections.add(S_COUNTRIES);
        sections.addAll(IptvFeed.categories(all));
        sections.add(S_SEARCH);
        ((BaseAdapter) sectionList.getAdapter()).notifyDataSetChanged();

        String last = sp.getString("section", "");
        int pick = sections.indexOf(last);
        if (pick < 0) pick = favourites().isEmpty() ? Math.min(2, sections.size() - 1) : 0;
        showSection(sections.get(pick));
        sectionList.setSelection(pick);
        handleIntent();
    }

    private void handleIntent() {
        Intent i = getIntent();
        String q = i.getStringExtra(EXTRA_QUERY);
        String sec = i.getStringExtra(EXTRA_SECTION);
        i.removeExtra(EXTRA_QUERY);
        i.removeExtra(EXTRA_SECTION);
        if (sec != null) {
            for (String s : sections) if (s.equalsIgnoreCase(sec)) showSection(s);
        }
        if (q != null && q.trim().length() > 0) {
            List<IptvFeed.Channel> hits = IptvFeed.search(all, q);
            if (hits.isEmpty()) {
                showSearch(q);
                return;
            }
            shown = hits;
            currentSection = "“" + q + "”";
            refreshGrid();
            play(hits, 0);
        }
    }

    // ================================================================ browsing

    private void showSection(String s) {
        if (S_SEARCH.equals(s)) {
            askSearch();
            return;
        }
        if (S_COUNTRIES.equals(s)) {
            pickCountry();
            return;
        }
        currentSection = s;
        sp.edit().putString("section", s).apply();
        if (S_FAV.equals(s)) shown = byKeys(favourites());
        else if (S_RECENT.equals(s)) shown = byKeys(recent());
        else {
            String cc = codeForLabel(s);
            shown = cc != null ? IptvFeed.inCountry(all, cc) : IptvFeed.inCategory(all, s);
        }
        refreshGrid();
    }

    private void showSearch(String q) {
        shown = IptvFeed.search(all, q);
        currentSection = "Search: " + q;
        refreshGrid();
        if (shown.isEmpty()) status.setText("No channel called “" + q + "”. Try a shorter name.");
    }

    private void refreshGrid() {
        heading.setText(currentSection + "   ·   " + shown.size() + (shown.size() == 1 ? " channel" : " channels"));
        if (shown.isEmpty()) {
            status.setText(S_FAV.equals(currentSection) ? "No favourites yet. While a channel plays, press MENU → Add to favourites."
                    : S_RECENT.equals(currentSection) ? "Channels you watch show up here." : "");
        } else {
            status.setText("");
        }
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        grid.setSelection(0);
    }

    private void askSearch() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("Channel name, like CNN or Sun TV");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Find a channel")
                .setView(e)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String q = e.getText().toString().trim();
                        if (q.length() > 0) showSearch(q);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void pickCountry() {
        final LinkedHashMap<String, Integer> cs = IptvFeed.countries(all);
        final List<String> codes = new ArrayList<String>(cs.keySet());
        java.util.Collections.sort(codes, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return countryName(a).compareTo(countryName(b));
            }
        });
        String[] names = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++) names[i] = countryName(codes.get(i)) + "   (" + cs.get(codes.get(i)) + ")";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Channels from")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String cc = codes.get(w);
                        pinCountry(cc);
                        onChannels();
                        showSection(countryLabel(cc));
                        sectionList.setSelection(sections.indexOf(countryLabel(cc)));
                    }
                }).show();
    }

    // ================================================================ playing

    private void play(List<IptvFeed.Channel> list, int index) {
        if (list.isEmpty()) return;
        playing = new ArrayList<IptvFeed.Channel>(list);
        playIndex = (index + playing.size()) % playing.size();
        altIndex = 0;
        browser.setVisibility(View.GONE);
        playerLayer.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startStream();
    }

    private IptvFeed.Channel current() {
        return playIndex >= 0 && playIndex < playing.size() ? playing.get(playIndex) : null;
    }

    /** The link being tried: the channel's own, then its alternates. */
    private IptvFeed.Channel link() {
        IptvFeed.Channel c = current();
        if (c == null) return null;
        return altIndex == 0 ? c : c.alts.get(altIndex - 1);
    }

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (!prepared) failed("didn't start in time");
        }
    };

    private void startStream() {
        IptvFeed.Channel c = current(), l = link();
        if (c == null || l == null) return;
        prepared = false;
        main.removeCallbacks(timeout);
        playSpinner.setVisibility(View.VISIBLE);
        playStatus.setText(altIndex > 0 ? "Trying another link (" + (altIndex + 1) + " of " + (c.alts.size() + 1) + ")…" : "");
        showInfo();
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", l.userAgent.length() > 0 ? l.userAgent : "Mozilla/5.0 (Linux; Android 11) Lumen");
        if (l.referrer.length() > 0) h.put("Referer", l.referrer);
        try {
            video.stopPlayback();
            video.setVideoURI(Uri.parse(l.url), h);
            video.start();
        } catch (RuntimeException e) {
            failed(e.getMessage());
            return;
        }
        main.postDelayed(timeout, PLAY_TIMEOUT);
        remember(c);
    }

    private void onPrepared(MediaPlayer mp) {
        prepared = true;
        main.removeCallbacks(timeout);
        playSpinner.setVisibility(View.GONE);
        playStatus.setText("");
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

    /** A link failed: try the channel's next link, else say so and offer the next channel. */
    private void failed(String why) {
        main.removeCallbacks(timeout);
        IptvFeed.Channel c = current();
        if (c == null) return;
        if (altIndex < c.alts.size()) {
            altIndex++;
            startStream();
            return;
        }
        try {
            video.stopPlayback();
        } catch (RuntimeException ignored) {
        }
        playSpinner.setVisibility(View.GONE);
        sp.edit().putLong("bad:" + c.key(), System.currentTimeMillis()).apply();
        playStatus.setText(c.name + " isn't available right now" + (c.geoBlocked ? " (it may only play in its own country)" : "")
                + ".\n▲ ▼ other channels   ·   MENU: open in VLC   ·   BACK: channel list");
        info.setVisibility(View.VISIBLE);
    }

    private void zap(int step) {
        if (playing.isEmpty()) return;
        playIndex = (playIndex + step + playing.size()) % playing.size();
        altIndex = 0;
        startStream();
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
        if (S_RECENT.equals(currentSection)) showSection(S_RECENT);
        int at = shown.indexOf(current());
        if (at >= 0) {
            grid.setSelection(at);
            grid.requestFocus();
        }
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
        infoName.setText((playIndex + 1) + "   " + c.name + (isFavourite(c) ? "   ★" : ""));
        String cat = c.groups.isEmpty() ? "" : TextUtils.join(", ", c.groups);
        String b = c.badge();
        infoMeta.setText(cat + (cat.length() > 0 && b.length() > 0 ? "   ·   " : "") + b
                + "   ·   " + (playIndex + 1) + " of " + playing.size() + " in " + currentSection);
        info.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideInfo);
        main.postDelayed(hideInfo, 5000);
    }

    private void playerMenu() {
        final IptvFeed.Channel c = current();
        if (c == null) return;
        final boolean fav = isFavourite(c);
        final boolean vlc = isInstalled("org.videolan.vlc");
        List<String> items = new ArrayList<String>();
        items.add(fav ? "Remove from favourites" : "Add to favourites");
        items.add(vlc ? "Open in VLC" : "Open in another player");
        if (!c.alts.isEmpty()) items.add("Try another link (" + (c.alts.size() + 1) + " available)");
        items.add("Back to the channel list");
        final String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(c.name)
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String a = arr[w];
                        if (a.endsWith("favourites")) {
                            toggleFavourite(c);
                            showInfo();
                        } else if (a.startsWith("Open in")) {
                            openExternally(link(), vlc);
                        } else if (a.startsWith("Try another")) {
                            altIndex = (altIndex + 1) % (c.alts.size() + 1);
                            startStream();
                        } else {
                            stopPlayer();
                        }
                    }
                }).show();
    }

    private void openExternally(IptvFeed.Channel l, boolean vlc) {
        if (l == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(l.url), "video/*")
                .putExtra("title", current() != null ? current().name : "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
        if (playerLayer.getVisibility() != View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_SEARCH) {
                askSearch();
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
                    altIndex = 0;
                    startStream();
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

    // ================================================================ favourites, recent, countries

    private List<String> favourites() {
        return list("favs");
    }

    private List<String> recent() {
        return list("recent");
    }

    private List<String> pinnedCountries() {
        return list("countries");
    }

    private List<String> list(String k) {
        String v = sp.getString(k, "");
        List<String> out = new ArrayList<String>();
        for (String s : v.split("\n")) if (s.length() > 0) out.add(s);
        return out;
    }

    private void putList(String k, List<String> l) {
        sp.edit().putString(k, TextUtils.join("\n", l)).apply();
    }

    private boolean isFavourite(IptvFeed.Channel c) {
        return favourites().contains(c.key());
    }

    private void toggleFavourite(IptvFeed.Channel c) {
        List<String> f = favourites();
        if (!f.remove(c.key())) f.add(c.key());
        putList("favs", f);
    }

    private void remember(IptvFeed.Channel c) {
        List<String> r = recent();
        r.remove(c.key());
        r.add(0, c.key());
        while (r.size() > 30) r.remove(r.size() - 1);
        putList("recent", r);
    }

    private void pinCountry(String cc) {
        List<String> l = pinnedCountries();
        l.remove(cc);
        l.add(0, cc);
        while (l.size() > 4) l.remove(l.size() - 1);
        putList("countries", l);
    }

    private List<IptvFeed.Channel> byKeys(List<String> keys) {
        Map<String, IptvFeed.Channel> m = new HashMap<String, IptvFeed.Channel>();
        for (IptvFeed.Channel c : all) m.put(c.key(), c);
        List<IptvFeed.Channel> out = new ArrayList<IptvFeed.Channel>();
        for (String k : keys) if (m.containsKey(k)) out.add(m.get(k));
        return out;
    }

    private static String countryName(String cc) {
        if (cc.equals("uk")) cc = "gb";
        String n = new Locale("", cc.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH);
        return n.length() > 0 ? n : cc.toUpperCase(Locale.ROOT);
    }

    private static String countryLabel(String cc) {
        return "●  " + countryName(cc);
    }

    private String codeForLabel(String label) {
        if (!label.startsWith("●  ")) return null;
        String name = label.substring(3);
        for (String cc : IptvFeed.countries(all).keySet()) if (countryName(cc).equals(name)) return cc;
        return null;
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
        row.setPadding(dp(28), dp(24), dp(28), dp(16));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Live TV", 26, Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(title);
        TextView src = text("Free channels from iptv-org", 12, Theme.DIM);
        src.setPadding(0, dp(2), 0, dp(12));
        left.addView(src);
        sectionList = new ListView(this);
        sectionList.setDivider(null);
        sectionList.setSelector(new GradientDrawable());
        sectionList.setAdapter(new BaseAdapter() {
            public int getCount() { return sections.size(); }
            public Object getItem(int i) { return sections.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                TextView t = v instanceof TextView ? (TextView) v : text("", 15, Theme.SUB);
                t.setText(sections.get(i));
                t.setPadding(dp(14), dp(9), dp(10), dp(9));
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                boolean on = sections.get(i).equals(currentSection);
                t.setTextColor(on ? Theme.TEXT : Theme.SUB);
                t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                return t;
            }
        });
        sectionList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> a, View v, int i, long id) {
                showSection(sections.get(i));
                ((BaseAdapter) sectionList.getAdapter()).notifyDataSetChanged();
                if (!shown.isEmpty()) grid.requestFocus();
            }
        });
        sectionList.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean f) {
                sectionList.setBackground(f ? glass(Theme.CERULEAN_GLASS) : null);
            }
        });
        left.addView(sectionList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        row.addView(left, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(24), dp(6), 0, 0);
        heading = text("", 16, Theme.TEXT);
        right.addView(heading);
        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this);
        st.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));
        status = text("", 13, Theme.SUB);
        status.setPadding(dp(8), dp(6), 0, dp(6));
        st.addView(status);
        right.addView(st);
        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(12));
        grid.setHorizontalSpacing(dp(12));
        grid.setSelector(new GradientDrawable());
        grid.setDrawSelectorOnTop(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(4), dp(8), dp(4), dp(24));
        grid.setAdapter(new ChannelAdapter());
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> a, View v, int i, long id) {
                play(shown, i);
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> a, View v, int i, long id) {
                ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> a) {
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
                h = new Holder();
                h.logo = new ImageView(LiveTvActivity.this);
                h.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.logo.setPadding(dp(10), dp(8), dp(10), dp(8));
                card.addView(h.logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
                h.name = text("", 14, Theme.TEXT);
                h.name.setSingleLine(true);
                h.name.setEllipsize(TextUtils.TruncateAt.END);
                h.name.setPadding(0, dp(6), 0, 0);
                card.addView(h.name);
                h.meta = text("", 11, Theme.DIM);
                h.meta.setSingleLine(true);
                h.meta.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(h.meta);
                card.setTag(h);
                v = card;
            } else {
                h = (Holder) v.getTag();
            }
            IptvFeed.Channel c = shown.get(i);
            boolean sel = grid.getSelectedItemPosition() == i && grid.hasFocus();
            GradientDrawable bg = glass(sel ? 0x33FF2D95 : 0xFF15181D);
            if (sel) bg.setStroke(dp(2), Theme.ROSE);
            v.setBackground(bg);
            h.name.setText((isFavourite(c) ? "★ " : "") + c.name);
            h.meta.setText(c.badge().length() > 0 ? c.badge() : (c.groups.isEmpty() ? "" : c.groups.get(0)));
            h.url = c.logo;
            h.logo.setImageBitmap(null);
            h.logo.setBackground(glass(0xFF1E222A));
            loadLogo(h, c.logo);
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
        logoPool.execute(new Runnable() {
            @Override
            public void run() {
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
    }

    private static Bitmap fetchLogo(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Lumen-Launcher");
            if (c.getResponseCode() >= 400) return null;
            InputStream in = c.getInputStream();
            byte[] data = TrendingFeed.readAll(in);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 200 && o.outHeight / (s * 2) >= 90) s *= 2;
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
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        f.addView(video, vlp);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                LiveTvActivity.this.onPrepared(mp);
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                failed("error " + what + "/" + extra);
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
        info.setPadding(dp(36), dp(22), dp(36), dp(26));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000});
        info.setBackground(g);
        infoName = text("", 26, Theme.TEXT);
        info.addView(infoName);
        infoMeta = text("", 14, Theme.SUB);
        infoMeta.setPadding(0, dp(4), 0, 0);
        info.addView(infoMeta);
        playStatus = text("", 15, 0xFFFFC46B);
        playStatus.setPadding(0, dp(8), 0, 0);
        info.addView(playStatus);
        TextView keys = text("▲ ▼ channels   ·   0–9 jump   ·   OK info   ·   MENU options   ·   BACK list", 12, Theme.DIM);
        keys.setPadding(0, dp(10), 0, 0);
        info.addView(keys);
        f.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return f;
    }

    private GradientDrawable glass(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
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
}
