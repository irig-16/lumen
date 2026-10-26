package com.lumen.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heat guard: watches the projector's CPU and GPU temperature and steps in before its own
 * emergency shutdown (CPU 115 °C, GPU 110 °C on the HY300's firmware).
 *
 *   Warm      (limit − 15 °C, CPU 100 °C)  a short notice: keep the vents clear
 *   Cooling   (limit − 10 °C, CPU 105 °C)  frees memory and background work, lowers the light a step
 *   Critical  (limit −  5 °C, CPU 110 °C)  pauses playback and shows a full-screen warning
 *
 * If the projector switches itself off anyway, the next start explains why.
 *
 * Temperature sources, best first: the thermal sensors in /sys (when the firmware lets apps read
 * them), Android's thermal service (needs the one-time DUMP grant from LumenOS-Update), or the
 * coarse thermal status every app may read.
 */
final class HeatGuard {

    static final int NORMAL = 0, WARM = 1, COOLING = 2, CRITICAL = 3;
    static final String[] LEVEL_NAMES = {"Normal", "Warm", "Cooling down", "Too hot"};

    /** The firmware's own shutdown limits (thermal_info_config.json). */
    static final int CPU_LIMIT = 115, GPU_LIMIT = 110;
    static final int[] MARGIN = {15, 10, 5};           // warm, cooling, critical: degrees below the limit
    static final int HYSTERESIS = 4;

    private static HeatGuard instance;

    static synchronized HeatGuard get(Context c) {
        if (instance == null) instance = new HeatGuard(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    // live readings (°C, -1 = unknown)
    volatile int cpu = -1, gpu = -1;
    volatile int level = NORMAL;
    volatile String source = "starting";
    private int sessionMax = -1, sessionMin = 999;
    private long sessionSum, sessionN;
    private long coolingSince, lastWarmNotice;
    private View alertView;

    // sysfs zones, found once
    private String cpuZone, gpuZone;
    private boolean sysfsTried, dumpsysWorks = true;

    private String pendingNotice;

    private HeatGuard(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("lumen_heat", Context.MODE_PRIVATE);
        if (sp.getBoolean("cooling", false)) restoreBrightness();   // a cool-down left over from before
        pendingNotice = previousBootNotice();   // before the first sample overwrites the last readings
    }

    // ---------------------------------------------------------------- settings

    boolean enabled() {
        return sp.getBoolean("on", true);
    }

    void setEnabled(boolean on) {
        sp.edit().putBoolean("on", on).apply();
        if (!on) leaveCooling();
        else start();
    }

    /** Extra degrees of caution (0 = standard, 5 = act 5 °C earlier). */
    int earlier() {
        return sp.getInt("earlier", 0);
    }

    void setEarlier(int d) {
        sp.edit().putInt("earlier", d).apply();
    }

    int threshold(int limit, int lvl) {
        return limit - MARGIN[lvl - 1] - earlier();
    }

    // ---------------------------------------------------------------- loop

    private boolean shutdownHooked;

    void start() {
        if (!shutdownHooked) {      // the manifest receiver covers this too; belt and braces
            shutdownHooked = true;
            try {
                ctx.registerReceiver(new HeatReceiver(), new android.content.IntentFilter(android.content.Intent.ACTION_SHUTDOWN));
            } catch (RuntimeException ignored) {
            }
        }
        if (running || !enabled()) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && enabled()) {
                    try {
                        sample();
                    } catch (Throwable ignored) {
                    }
                    SystemClock.sleep(level >= WARM ? 5000 : 15000);
                }
                running = false;
            }
        }, "lumen-heat");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    private void sample() {
        int[] t = readSensors();
        cpu = t[0];
        gpu = t[1];
        int hot = Math.max(cpu, gpu);
        if (hot > 0) {
            sessionMax = Math.max(sessionMax, hot);
            sessionMin = Math.min(sessionMin, hot);
            sessionSum += hot;
            sessionN++;
            String day = today();
            SharedPreferences.Editor e = sp.edit().putInt("last_cpu", cpu).putInt("last_gpu", gpu)
                    .putLong("last_at", System.currentTimeMillis());
            if (!day.equals(sp.getString("max_day", ""))) e.putString("max_day", day).putInt("max_today", hot);
            else if (hot > sp.getInt("max_today", 0)) e.putInt("max_today", hot);
            e.apply();
        }
        int next = computeLevel(cpu, gpu, coarseStatus());
        final int prev = level;
        level = next;
        if (next != prev) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onLevel(prev, level);
                }
            });
        } else if (next == COOLING) {
            coolingSince = coolingSince == 0 ? SystemClock.elapsedRealtime() : coolingSince;
        }
    }

    /** Pure decision, with hysteresis so the level doesn't flicker around a threshold. */
    int computeLevel(int c, int g, int coarse) {
        int lvl = NORMAL;
        for (int k = CRITICAL; k >= WARM; k--) {
            int cut = (k <= level) ? HYSTERESIS : 0;    // already at/above this level: need to drop further to leave it
            if ((c > 0 && c >= threshold(CPU_LIMIT, k) - cut) || (g > 0 && g >= threshold(GPU_LIMIT, k) - cut)) {
                lvl = k;
                break;
            }
        }
        // no readable temperature: Android's own status still flags the emergency band
        if (c <= 0 && g <= 0 && coarse >= 4) lvl = CRITICAL;   // THERMAL_STATUS_CRITICAL and up
        return lvl;
    }

    // ---------------------------------------------------------------- actions

    private void onLevel(int prev, int now) {
        if (now > prev) {
            if (now == WARM) {
                long t = SystemClock.elapsedRealtime();
                if (t - lastWarmNotice > 20 * 60 * 1000) {
                    lastWarmNotice = t;
                    toast("Projector is running hot · " + reading() + ". Keep the vents clear.");
                }
            } else if (now == COOLING) {
                enterCooling();
            } else if (now == CRITICAL) {
                enterCooling();
                critical();
            }
        } else {
            if (now < COOLING && prev >= COOLING) leaveCooling();
            if (now < CRITICAL) dismissAlert();
        }
    }

    private void enterCooling() {
        if (sp.getBoolean("cooling", false)) return;
        coolingSince = SystemClock.elapsedRealtime();
        SharedPreferences.Editor e = sp.edit().putBoolean("cooling", true);
        boolean dimmed = false;
        if (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(ctx)) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                int b = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255);
                e.putInt("saved_brightness", b);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(90, b * 4 / 5));
                dimmed = true;
            } catch (RuntimeException ignored) {
            }
        }
        e.apply();
        MemoryTuner.freeBackground(ctx, null, true);
        toast("Cooling down · " + reading() + ". " + (dimmed ? "Light lowered a step, " : "") + "background work paused.");
    }

    private void leaveCooling() {
        if (!sp.getBoolean("cooling", false)) return;
        restoreBrightness();
        coolingSince = 0;
        toast("Projector cooled down · " + reading() + ". Back to normal.");
    }

    private void restoreBrightness() {
        int b = sp.getInt("saved_brightness", -1);
        if (b >= 0 && (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(ctx))) {
            try {
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, b);
            } catch (RuntimeException ignored) {
            }
        }
        sp.edit().putBoolean("cooling", false).remove("saved_brightness").apply();
    }

    /** Is a cool-down in effect (Lumen holds off its own background work). */
    boolean cooling() {
        return level >= COOLING;
    }

    private void critical() {
        // pause whatever is playing: less decoding, less heat
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        } catch (RuntimeException ignored) {
        }
        sp.edit().putInt("critical_at_temp", Math.max(cpu, gpu)).apply();
        if (!showAlert()) {
            toast("Projector is too hot (" + reading() + "). Playback paused. It switches off by itself at "
                    + CPU_LIMIT + "°C: let it cool and keep the vents clear.");
        }
    }

    private boolean showAlert() {
        if (alertView != null) return true;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return false;
        try {
            final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            float d = ctx.getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(0xF20A0A0A);
            box.setPadding((int) (80 * d), (int) (40 * d), (int) (80 * d), (int) (40 * d));

            TextView title = new TextView(ctx);
            title.setText("Your projector is too hot");
            title.setTextColor(0xFFFF8A3D);
            title.setTextSize(30);
            title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            title.setGravity(Gravity.CENTER);
            box.addView(title);

            TextView body = new TextView(ctx);
            body.setText(reading() + "\n\nPlayback is paused to protect it. At " + CPU_LIMIT
                    + "°C the projector switches itself off.\nGive it a few minutes, and make sure nothing is blocking the vents.");
            body.setTextColor(0xFFE6E6EA);
            body.setTextSize(17);
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(0, 1.25f);
            body.setPadding(0, (int) (18 * d), 0, (int) (28 * d));
            box.addView(body);

            Button ok = new Button(ctx);
            ok.setText("OK");
            ok.setTextColor(Color.WHITE);
            ok.setAllCaps(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.CERULEAN);
            bg.setCornerRadius(24 * d);
            bg.setStroke((int) (2 * d), Theme.ROSE);
            ok.setBackground(bg);
            ok.setPadding((int) (48 * d), 0, (int) (48 * d), 0);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissAlert();
                }
            });
            box.addView(ok, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (48 * d)));
            box.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent e) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) dismissAlert();
                    return keyCode == KeyEvent.KEYCODE_BACK;
                }
            });

            int type = Build.VERSION.SDK_INT >= 26 ? 2038 /* TYPE_APPLICATION_OVERLAY */
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            wm.addView(box, lp);
            alertView = box;
            ok.requestFocus();
            return true;
        } catch (RuntimeException e) {
            alertView = null;
            return false;
        }
    }

    private void dismissAlert() {
        if (alertView == null) return;
        try {
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).removeView(alertView);
        } catch (RuntimeException ignored) {
        }
        alertView = null;
    }

    // ---------------------------------------------------------------- shutdown memory

    /** Called when Android shuts down: remember how hot it was. */
    void onShutdown() {
        int t = Math.max(cpu, gpu);
        if (t <= 0) t = Math.max(sp.getInt("last_cpu", -1), sp.getInt("last_gpu", -1));
        sp.edit().putInt("shutdown_cpu", cpu > 0 ? cpu : sp.getInt("last_cpu", -1))
                .putInt("shutdown_gpu", gpu > 0 ? gpu : sp.getInt("last_gpu", -1))
                .putInt("shutdown_temp", t).putLong("shutdown_at", System.currentTimeMillis()).commit();
    }

    /** After a restart: a message if the projector most likely switched off because of heat, else null. Once. */
    String bootNotice() {
        String n = pendingNotice;
        pendingNotice = null;
        return n;
    }

    private String previousBootNotice() {
        long bootStart = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        int c = sp.getInt("shutdown_cpu", -1), g = sp.getInt("shutdown_gpu", -1);
        long at = sp.getLong("shutdown_at", 0);
        if (at == 0) {   // no clean shutdown seen (power cut or a hard thermal stop): the last reading before power went
            c = sp.getInt("last_cpu", -1);
            g = sp.getInt("last_gpu", -1);
            at = sp.getLong("last_at", 0);
        }
        if (at == 0 || at > bootStart) return null;    // nothing from before this start (Lumen restarted, not the projector)
        sp.edit().remove("shutdown_cpu").remove("shutdown_gpu").remove("shutdown_temp").remove("shutdown_at")
                .remove("last_cpu").remove("last_gpu").remove("last_at").putBoolean("cooling", false).apply();
        boolean hot = (c > 0 && c >= CPU_LIMIT - 3) || (g > 0 && g >= GPU_LIMIT - 3);
        if (!hot) return null;
        String when = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(at));
        return "The projector switched itself off at " + when + " because it got too hot ("
                + describe(c, g) + "). That's its built-in protection.\n\n"
                + "To keep it cooler: leave space around the vents, don't run it inside a closed cabinet, "
                + "and keep LumenOS's 720p mode on. Lumen's heat guard pauses playback before it gets that hot.";
    }

    // ---------------------------------------------------------------- reading

    private int[] readSensors() {
        int[] t = readSysfs();
        if (t != null) {
            source = "projector sensors";
            return t;
        }
        t = readDumpsys();
        if (t != null) {
            source = "Android thermal service";
            return t;
        }
        source = "basic (run LumenOS-Update for exact temperatures)";
        return new int[]{-1, -1};
    }

    private int[] readSysfs() {
        if (!sysfsTried) {
            sysfsTried = true;
            File[] zones = new File("/sys/class/thermal").listFiles();
            if (zones != null) {
                for (File z : zones) {
                    if (!z.getName().startsWith("thermal_zone")) continue;
                    String type = readLine(new File(z, "type")).toLowerCase(Locale.ROOT);
                    String p = new File(z, "temp").getPath();
                    if (cpuZone == null && type.contains("cpu")) cpuZone = p;
                    else if (gpuZone == null && (type.contains("gpu") || type.contains("mali"))) gpuZone = p;
                }
            }
        }
        if (cpuZone == null && gpuZone == null) return null;
        int c = milli(readLine(cpuZone == null ? null : new File(cpuZone)));
        int g = milli(readLine(gpuZone == null ? null : new File(gpuZone)));
        if (c <= 0 && g <= 0) {
            cpuZone = gpuZone = null;     // not readable by apps on this firmware
            return null;
        }
        return new int[]{c, g};
    }

    private static final Pattern TEMP = Pattern.compile("Temperature\\{mValue=([0-9.]+), mType=(\\d+), mName=([^,]*)");

    private int[] readDumpsys() {
        if (!dumpsysWorks) return null;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"dumpsys", "thermalservice"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> lines = new ArrayList<String>();
            String l;
            while ((l = r.readLine()) != null && lines.size() < 400) lines.add(l);
            int[] t = parseThermalService(lines);
            if (t == null) dumpsysWorks = false;   // no permission (Permission Denial) or no HAL data
            return t;
        } catch (Exception e) {
            dumpsysWorks = false;
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** The "Current temperatures from HAL" block of dumpsys thermalservice → {cpu, gpu}. Pure, testable. */
    static int[] parseThermalService(List<String> lines) {
        boolean inHal = false;
        int c = -1, g = -1;
        for (String l : lines) {
            if (l.contains("Current temperatures from HAL")) {
                inHal = true;
                continue;
            }
            if (inHal && l.trim().length() > 0 && !l.contains("Temperature{")) {
                if (c > 0 || g > 0) break;
                continue;
            }
            Matcher m = TEMP.matcher(l);
            if (!inHal || !m.find()) continue;
            int v = Math.round(Float.parseFloat(m.group(1)));
            int type = Integer.parseInt(m.group(2));
            String name = m.group(3).toLowerCase(Locale.ROOT);
            if ((type == 0 || name.contains("cpu")) && v > c) c = v;
            else if ((type == 1 || name.contains("gpu")) && v > g) g = v;
        }
        return c > 0 || g > 0 ? new int[]{c, g} : null;
    }

    /** PowerManager.getCurrentThermalStatus() (Android 10+): 0 none … 6 shutdown. */
    private int coarseStatus() {
        if (Build.VERSION.SDK_INT < 29) return 0;
        try {
            Object pm = ctx.getSystemService(Context.POWER_SERVICE);
            return (Integer) pm.getClass().getMethod("getCurrentThermalStatus").invoke(pm);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readLine(File f) {
        if (f == null) return "";
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** "81700" (millidegrees) or "81" → 81; "" → -1. */
    static int milli(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 1000 ? Math.round(v / 1000f) : v;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- text

    String reading() {
        return describe(cpu, gpu);
    }

    static String describe(int c, int g) {
        if (c <= 0 && g <= 0) return "temperature unknown";
        StringBuilder b = new StringBuilder();
        if (c > 0) b.append("CPU ").append(c).append("°C");
        if (g > 0) b.append(b.length() > 0 ? ", " : "").append("GPU ").append(g).append("°C");
        return b.toString();
    }

    /** One line for Lumen settings. */
    String summary() {
        if (!enabled()) return "Off";
        StringBuilder b = new StringBuilder(cpu > 0 || gpu > 0 ? reading() : "reading…");
        int maxToday = today().equals(sp.getString("max_day", "")) ? sp.getInt("max_today", -1) : -1;
        if (maxToday > 0) b.append("  ·  today's max ").append(maxToday).append("°C");
        if (level > NORMAL) b.append("  ·  ").append(LEVEL_NAMES[level]);
        return b.toString();
    }

    /** The details dialog text. */
    String details() {
        String avg = sessionN > 0 ? (sessionSum / sessionN) + "°C" : "–";
        return "Now: " + reading() + "  (" + LEVEL_NAMES[level] + ")\n"
                + "Since Lumen started: min " + (sessionN > 0 ? sessionMin + "°C" : "–") + " · avg " + avg
                + " · max " + (sessionMax > 0 ? sessionMax + "°C" : "–") + "\n\n"
                + "What the heat guard does (CPU / GPU):\n"
                + "• " + threshold(CPU_LIMIT, WARM) + " / " + threshold(GPU_LIMIT, WARM) + "°C  a short notice to keep the vents clear\n"
                + "• " + threshold(CPU_LIMIT, COOLING) + " / " + threshold(GPU_LIMIT, COOLING) + "°C  cools down: background work paused, light lowered a step\n"
                + "• " + threshold(CPU_LIMIT, CRITICAL) + " / " + threshold(GPU_LIMIT, CRITICAL) + "°C  pauses playback with a full-screen warning\n"
                + "• " + CPU_LIMIT + " / " + GPU_LIMIT + "°C  the projector switches itself off (its own protection)\n\n"
                + "Reading from: " + source;
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new java.util.Date());
    }

    private void toast(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ctx, s, Toast.LENGTH_LONG).show();
            }
        });
    }
}
