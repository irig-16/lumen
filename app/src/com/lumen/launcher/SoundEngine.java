package com.lumen.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sound Studio engine (v2): speaker-aware, level-matched presets.
 *
 * Precision engine (Android 9+): one DynamicsProcessing effect = 8-band EQ → compressor → limiter.
 * Basic engine (fallback): the device's fixed-band Equalizer + a careful loudness lift.
 * The tuning itself lives in {@link SoundTuning}.
 *
 * It processes all audio when the firmware allows it, otherwise it attaches to players that announce
 * their audio session (VLC, Kodi, MX Player…). Process-wide singleton.
 */
final class SoundEngine {

    static final String[] PRESETS = SoundTuning.PRESETS;
    static final String[] PRESET_NAMES = SoundTuning.NAMES;
    static final String[] PARAMS = {"bass", "treble", "clarity", "surround", "loudness"};

    interface Listener {
        void onSoundChanged();
    }

    private static SoundEngine instance;

    static synchronized SoundEngine get(Context c) {
        if (instance == null) instance = new SoundEngine(c.getApplicationContext());
        return instance;
    }

    // ---- state. Sliders are fine-tune offsets: 50 = the preset's own tuning.
    String preset = "lumen";
    int bass = 50, treble = 50, clarity = 50, surround = 0, loudness = 50;
    boolean bypass;                       // A/B compare: temporarily hear "Off"
    int output = SoundTuning.SPEAKER;
    /** "auto" or one of {@link SoundTuning#OUTPUT_KEYS}. */
    String outputMode = "auto";
    /** What's physically connected right now (for the status line and the intro). */
    boolean bluetoothConnected, cableConnected;

    private final Context ctx;
    private final SharedPreferences sp;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Map<Integer, Chain> sessions = new HashMap<Integer, Chain>();
    private Chain global;
    private boolean globalWorks, effectsAvailable, precision;

    private final Runnable endBypass = new Runnable() {
        @Override
        public void run() {
            setBypass(false);
        }
    };

    private SoundEngine(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("lumen_sound", Context.MODE_PRIVATE);
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (sp.getInt("version", 1) < 2) {
            // v1 values meant something else; keep only whether sound processing was off
            boolean wasOff = "off".equals(sp.getString("preset", "off"));
            sp.edit().clear().putInt("version", 2).putString("preset", wasOff ? "off" : "lumen").apply();
        }
        preset = sp.getString("preset", "lumen");
        if (!SoundTuning.known(preset)) preset = "lumen";
        bass = sp.getInt("bass", 50);
        treble = sp.getInt("treble", 50);
        clarity = sp.getInt("clarity", 50);
        surround = sp.getInt("surround", 0);
        loudness = sp.getInt("loudness", 50);
        outputMode = sp.getString("output_mode", "auto");
        if (!"auto".equals(outputMode) && SoundTuning.outputIndex(outputMode) < 0) outputMode = "auto";

        try {
            AudioEffect.Descriptor[] all = AudioEffect.queryEffects();
            effectsAvailable = all != null && all.length > 0;
        } catch (Throwable t) {
            effectsAvailable = false;
        }
        global = new Chain(0);
        globalWorks = global.dp != null || global.eq != null;
        precision = global.dp != null;
        if (!globalWorks) {
            global.release();
            global = null;
        }

        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        try {
            ctx.registerReceiver(sessionReceiver, f);
        } catch (RuntimeException ignored) {
        }
        output = detectOutput();
        try {
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    outputMaybeChanged();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    outputMaybeChanged();
                }
            }, main);
        } catch (Throwable ignored) {
        }
        applyAll();
    }

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (globalWorks) return;
            int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
            if (session <= 0) return;
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
                if (!sessions.containsKey(session)) {
                    Chain ch = new Chain(session);
                    if (ch.dp != null) precision = true;
                    sessions.put(session, ch);
                    ch.apply();
                    notifyListeners();
                }
            } else {
                Chain ch = sessions.remove(session);
                if (ch != null) ch.release();
                notifyListeners();
            }
        }
    };

    private void outputMaybeChanged() {
        int o = detectOutput();
        if (o != output) {
            output = o;
            applyAll();
        }
        notifyListeners();
    }

    private int detectOutput() {
        boolean bt = false, cable = false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                        bt = true;
                        break;
                    case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:  // the 3.5 mm jack, whatever is on the other end
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    case AudioDeviceInfo.TYPE_LINE_ANALOG:
                    case AudioDeviceInfo.TYPE_LINE_DIGITAL:      // optical / S/PDIF
                    case AudioDeviceInfo.TYPE_AUX_LINE:
                    case AudioDeviceInfo.TYPE_USB_DEVICE:        // USB DAC or USB soundbar
                    case 22:                                     // TYPE_USB_HEADSET (API 26)
                        cable = true;
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        }
        bluetoothConnected = bt;
        cableConnected = cable;
        if (bt) sp.edit().putBoolean("last_bt", true).apply();
        else if (cable) sp.edit().putBoolean("last_bt", false).apply();
        int i = SoundTuning.outputIndex(outputMode);
        return i >= 0 ? i : SoundTuning.autoOutput(bt, cable);
    }

    /** "auto", "speaker", "hometheatre", "portable" or "headphones". */
    void setOutputMode(String mode) {
        if (mode == null) return;
        mode = mode.toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode) && SoundTuning.outputIndex(mode) < 0) return;
        outputMode = mode;
        sp.edit().putString("output_mode", mode).apply();
        output = detectOutput();
        applyAll();
        notifyListeners();
    }

    /** Steps through Auto → Projector speaker → Home theatre → Bluetooth speaker → Headphones. */
    void cycleOutputMode() {
        String[] order = {"auto", "speaker", "hometheatre", "portable", "headphones"};
        int i = 0;
        for (int k = 0; k < order.length; k++) if (order[k].equals(outputMode)) i = k;
        setOutputMode(order[(i + 1) % order.length]);
    }

    String outputModeLabel() {
        if ("auto".equals(outputMode)) return "Auto: " + outputName() + connectionNote();
        return outputName();
    }

    private String connectionNote() {
        if (output == SoundTuning.SPEAKER) return "";
        return bluetoothConnected ? " (Bluetooth)" : cableConnected ? " (cable)" : "";
    }

    /** True when the last external sound system seen was Bluetooth (the intro waits for it after boot). */
    boolean expectsBluetooth() {
        return sp.getBoolean("last_bt", false) && !"speaker".equals(outputMode);
    }

    // ================================================================ control

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    boolean isOn() {
        return !"off".equals(preset) && !bypass;
    }

    /** Selecting a preset resets the fine-tune sliders to that preset's own tuning. */
    void applyPreset(String key) {
        key = key == null ? "lumen" : key.toLowerCase(Locale.ROOT);
        if ("custom".equals(key)) key = "lumen";
        if (!SoundTuning.known(key)) key = "lumen";
        preset = key;
        bass = treble = clarity = loudness = 50;
        bypass = false;
        commit();
    }

    void set(String key, int value) {
        value = Math.max(0, Math.min(100, value));
        if ("bass".equals(key)) bass = value;
        else if ("treble".equals(key)) treble = value;
        else if ("clarity".equals(key)) clarity = value;
        else if ("surround".equals(key)) surround = value;
        else if ("loudness".equals(key)) loudness = value;
        else return;
        if ("off".equals(preset)) preset = "lumen"; // touching a slider means "turn it on"
        bypass = false;
        commit();
    }

    void adjust(String key, int delta) {
        set(key, get(key) + delta);
    }

    int get(String key) {
        if ("bass".equals(key)) return bass;
        if ("treble".equals(key)) return treble;
        if ("clarity".equals(key)) return clarity;
        if ("surround".equals(key)) return surround;
        if ("loudness".equals(key)) return loudness;
        return 0;
    }

    /** A/B compare: hear "Off" for a moment (auto-returns after 12 s). Not saved. */
    void setBypass(boolean on) {
        main.removeCallbacks(endBypass);
        bypass = on && !"off".equals(preset);
        if (bypass) main.postDelayed(endBypass, 12000);
        applyAll();
        notifyListeners();
    }

    boolean tuned() {
        return bass != 50 || treble != 50 || clarity != 50 || loudness != 50;
    }

    private void commit() {
        sp.edit().putInt("version", 2).putString("preset", preset).putInt("bass", bass).putInt("treble", treble)
                .putInt("clarity", clarity).putInt("surround", surround).putInt("loudness", loudness).apply();
        applyAll();
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<Listener>(listeners)) l.onSoundChanged();
    }

    private void applyAll() {
        if (global != null) global.apply();
        for (Chain c : sessions.values()) c.apply();
    }

    String outputName() {
        return SoundTuning.OUTPUT_NAMES[Math.max(0, Math.min(SoundTuning.OUTPUT_NAMES.length - 1, output))];
    }

    String status() {
        if (!globalWorks && !effectsAvailable)
            return "This projector's firmware has no audio effects, so Sound Studio can't change the sound";
        if (!globalWorks && sessions.isEmpty())
            return "Your firmware blocks system-wide effects. Sound Studio applies inside players that support "
                    + "audio effects (VLC, Kodi, MX Player): open one and it switches on automatically";
        if (bypass) return "A/B: you're hearing OFF now. Press A/B again (or wait) to hear " + presetName(preset);
        String where = globalWorks ? "all sound" : sessions.size() + " playing app" + (sessions.size() > 1 ? "s" : "");
        if (!engineHasControl())
            return "Another sound effect on this projector has taken control. Sound Studio will take over again when it can";
        if ("off".equals(preset)) return "Off: sound passes through untouched";
        return (engineActive() ? "Active" : "Not active yet") + " · "
                + (precision ? "precision engine (8-band EQ, compressor, limiter)" : "basic engine (fixed-band EQ)")
                + " · tuned for " + outputName().toLowerCase(Locale.ROOT) + " · on " + where;
    }

    static String presetName(String key) {
        return SoundTuning.name(key);
    }

    String json() {
        return "{\"preset\":\"" + preset + "\",\"bass\":" + bass + ",\"treble\":" + treble + ",\"clarity\":" + clarity
                + ",\"surround\":" + surround + ",\"loudness\":" + loudness + ",\"ab\":" + bypass
                + ",\"output\":\"" + outputName() + "\",\"outputMode\":\"" + outputMode + "\",\"connection\":\""
                + (bluetoothConnected ? "bluetooth" : cableConnected ? "cable" : "none") + "\",\"precision\":" + precision + ",\"active\":" + engineActive() + ",\"gains\":" + gainsJson()
                + ",\"status\":" + RemoteServer.json(status()) + "}";
    }

    private String gainsJson() {
        float[] g = currentGains();
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < g.length; i++) b.append(i > 0 ? "," : "").append(Math.round(g[i] * 10) / 10f);
        return b.append(']').toString();
    }

    // ============================================================ the effects

    /** Highest priority: firmware sound effects can't take control away from Sound Studio. */
    private static final int PRIORITY = Integer.MAX_VALUE;

    /** True when the main effect is attached, enabled and in control (what the status line reports). */
    boolean engineActive() {
        Chain c = global != null ? global : (sessions.isEmpty() ? null : sessions.values().iterator().next());
        return c != null && c.active();
    }

    boolean engineHasControl() {
        Chain c = global != null ? global : (sessions.isEmpty() ? null : sessions.values().iterator().next());
        return c == null || c.control();
    }

    /** The 8 band gains in dB that are being applied right now (all 0 when Off / bypassed). */
    float[] currentGains() {
        if (!isOn()) return new float[SoundTuning.CUTOFFS.length];
        return SoundTuning.gains(Math.max(0, SoundTuning.index(preset)), output, bass, treble, clarity);
    }

    private final class Chain {
        final int session;
        DynamicsProcessing dp;
        Equalizer eq;
        LoudnessEnhancer le;
        Virtualizer vz;

        Chain(int session) {
            this.session = session;
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                            true, SoundTuning.CUTOFFS.length, true, 1, false, 0, true).build();
                    dp = new DynamicsProcessing(PRIORITY, session, cfg);
                } catch (Throwable t) {
                    dp = null;
                }
            }
            if (dp == null) {
                try {
                    eq = new Equalizer(PRIORITY, session);
                } catch (Throwable t) {
                    eq = null;
                }
                try {
                    le = new LoudnessEnhancer(session);
                } catch (Throwable t) {
                    le = null;
                }
            }
            try {
                vz = new Virtualizer(PRIORITY, session);
            } catch (Throwable t) {
                vz = null;
            }
            // if something else grabs the effect, re-apply our settings as soon as we get it back
            android.media.audiofx.AudioEffect.OnControlStatusChangeListener back = new android.media.audiofx.AudioEffect.OnControlStatusChangeListener() {
                @Override
                public void onControlStatusChange(android.media.audiofx.AudioEffect effect, final boolean controlGranted) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (controlGranted) apply();
                            notifyListeners();
                        }
                    });
                }
            };
            try {
                if (dp != null) dp.setControlStatusListener(back);
                if (eq != null) eq.setControlStatusListener(back);
            } catch (Throwable ignored) {
            }
        }

        void apply() {
            boolean on = isOn();
            int idx = Math.max(0, SoundTuning.index(preset));
            float[] g = SoundTuning.gains(idx, output, bass, treble, clarity);
            float[] comp = SoundTuning.compressor(idx, output, loudness);

            if (dp != null) {
                try {
                    for (int i = 0; i < g.length; i++) {
                        dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, SoundTuning.CUTOFFS[i], g[i]));
                    }
                    dp.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(comp[0] > 1.01f, 20000f, 10f, 250f,
                            comp[0], comp[1], 6f, -90f, 1f, 0f, comp[2]));
                    dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 50f, 10f, -1f, 0f));
                    dp.setEnabled(on);
                } catch (Throwable ignored) {
                }
            }
            if (eq != null) {
                try {
                    short[] range = eq.getBandLevelRange();
                    short n = eq.getNumberOfBands();
                    for (short i = 0; i < n; i++) {
                        int mb = Math.round(SoundTuning.gainAt(g, eq.getCenterFreq(i) / 1000.0) * 100);
                        eq.setBandLevel(i, (short) Math.max(range[0], Math.min(range[1], mb)));
                    }
                    eq.setEnabled(on);
                } catch (Throwable ignored) {
                }
            }
            if (le != null) {
                try {
                    // no compressor on this path, so only half the make-up gain
                    le.setTargetGain(Math.round(comp[2] * 50));
                    le.setEnabled(on && comp[2] > 0);
                } catch (Throwable ignored) {
                }
            }
            if (vz != null) {
                try {
                    // surround only makes sense on headphones; on a single small speaker it sounds hollow
                    boolean use = on && output == SoundTuning.HEADPHONES && surround > 0;
                    if (vz.getStrengthSupported()) vz.setStrength((short) (surround * 10));
                    vz.setEnabled(use);
                } catch (Throwable ignored) {
                }
            }
        }

        boolean control() {
            try {
                if (dp != null) return dp.hasControl();
                if (eq != null) return eq.hasControl();
            } catch (Throwable ignored) {
            }
            return false;
        }

        boolean active() {
            try {
                if (dp != null) return dp.hasControl() && dp.getEnabled();
                if (eq != null) return eq.hasControl() && eq.getEnabled();
            } catch (Throwable ignored) {
            }
            return false;
        }

        void release() {
            try {
                if (dp != null) dp.release();
                if (eq != null) eq.release();
                if (le != null) le.release();
                if (vz != null) vz.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
