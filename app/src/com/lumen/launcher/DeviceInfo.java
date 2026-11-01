package com.lumen.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * What projector Lumen is running on: chip family, model, Android version, resolution and memory.
 * Lumen works on any Android 6+ projector; this decides which chip-specific extras to try (the
 * Allwinner picture processor, the HY300 toolkit steps) and is shown in About and on the phone.
 * The classification is pure Java (tested on a desktop JVM); {@link #read} gathers the facts on Android.
 */
final class DeviceInfo {

    static final String ALLWINNER = "Allwinner", AMLOGIC = "Amlogic", ROCKCHIP = "Rockchip", MEDIATEK = "MediaTek",
            REALTEK = "Realtek", HISILICON = "HiSilicon", QUALCOMM = "Qualcomm", UNISOC = "Unisoc", OTHER = "";

    String family = OTHER;       // one of the constants above ("" when unknown)
    String chip = "";            // e.g. "H713", "S905X4", "RK3566", when it can be told
    String brand = "", model = "";
    int sdk;
    String release = "";
    int width, height;           // the real drawing resolution, landscape
    long ramMb;
    boolean h713;                // the HY300 / HY320 family (Allwinner H713): the deepest support

    private DeviceInfo() {
    }

    /**
     * Classifies the chip from build properties. Any argument may be empty.
     * platform = ro.board.platform, hardware = ro.hardware (Build.HARDWARE), board = ro.product.board,
     * socModel / socMaker = ro.soc.model / ro.soc.manufacturer (Android 12+).
     */
    static DeviceInfo classify(String platform, String hardware, String board, String socModel, String socMaker,
                               String brand, String model) {
        DeviceInfo d = new DeviceInfo();
        d.brand = clean(brand);
        d.model = clean(model);
        String all = (" " + platform + " " + hardware + " " + board + " " + socModel + " " + socMaker + " ").toLowerCase(Locale.ROOT);
        String maker = socMaker == null ? "" : socMaker.toLowerCase(Locale.ROOT);

        if (maker.contains("allwinner") || all.matches(".*\\b(sun\\d+[a-z0-9]*|ares|h713|h616|h618|h313|h6|a133|t507|t113)\\b.*")) {
            d.family = ALLWINNER;
            if (all.matches(".*\\b(ares|sun50iw12p1|sun50iw12|h713)\\b.*")) {
                d.chip = "H713";
                d.h713 = true;
            } else if (all.contains("sun50iw9")) d.chip = "H616/H618";
            else if (all.contains("sun50iw6")) d.chip = "H6";
        } else if (maker.contains("amlogic") || all.matches(".*\\b(meson\\w*|amlogic|g12a|g12b|sm1|sc2|s4|t3|t5w?|t7|t9\\d+|s9\\d\\d\\w*|s805\\w*|s905\\w*|s922\\w*|gxl|gxm|tl1|tm2)\\b.*")) {
            d.family = AMLOGIC;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(s9\\d\\d[a-z0-9]*|s805[a-z0-9]*|t9\\d\\d[a-z0-9]*)\\b").matcher(all);
            if (m.find()) d.chip = m.group(1).toUpperCase(Locale.ROOT);
        } else if (maker.contains("rockchip") || all.matches(".*\\b(rk\\d{3,4}\\w*|rk30board|rockchip)\\b.*")) {
            d.family = ROCKCHIP;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(rk\\d{4}[a-z]?)\\b").matcher(all);
            if (m.find()) d.chip = m.group(1).toUpperCase(Locale.ROOT);
        } else if (maker.contains("mediatek") || all.matches(".*\\b(mt\\d{4}\\w*|mtk|mstar|m7\\d{3}\\w*|mediatek|maserati|mango|mooney|marlon|merak|m7332|m7642)\\b.*")) {
            d.family = MEDIATEK;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(mt\\d{4}[a-z]?|m7\\d{3})\\b").matcher(all);
            if (m.find()) d.chip = m.group(1).toUpperCase(Locale.ROOT);
        } else if (maker.contains("realtek") || all.matches(".*\\b(rtd\\d{4}\\w*|realtek|stark|kylin|hercules|thor)\\b.*")) {
            d.family = REALTEK;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(rtd\\d{4})\\b").matcher(all);
            if (m.find()) d.chip = m.group(1).toUpperCase(Locale.ROOT);
        } else if (maker.contains("hisilicon") || all.matches(".*\\b(hi\\d{4}\\w*|bigfish|hisilicon)\\b.*")) {
            d.family = HISILICON;
        } else if (maker.contains("qualcomm") || maker.contains("qti") || all.matches(".*\\b(qcom|msm\\d+|sdm\\d+|sm\\d{4}|apq\\d+|qcs\\d+)\\b.*")) {
            d.family = QUALCOMM;
        } else if (maker.contains("unisoc") || maker.contains("spreadtrum") || all.matches(".*\\b(sp\\d{4}\\w*|ums\\d+|sc\\d{4}\\w*|unisoc|sprd)\\b.*")) {
            d.family = UNISOC;
        }
        if (d.chip.length() == 0 && socModel != null && socModel.trim().length() > 0 && !socModel.trim().equalsIgnoreCase("unknown"))
            d.chip = socModel.trim().toUpperCase(Locale.ROOT);
        return d;
    }

    /** "Allwinner H713 · HY300 family", "Amlogic S905X4", "Rockchip", or "Android projector". */
    String chipLabel() {
        if (family.length() == 0) return "Android projector";
        String s = family + (chip.length() > 0 ? " " + chip : "");
        return h713 ? s + " · HY300 family" : s;
    }

    /** One line for About and the phone: "Magcubic HY300 · Allwinner H713 · Android 11 · 1280×720 · 1 GB". */
    String summary() {
        StringBuilder b = new StringBuilder();
        String name = (brand.length() > 0 && !model.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT)) ? brand + " " : "") + model;
        if (name.trim().length() > 0) b.append(name.trim()).append(" · ");
        b.append(family.length() > 0 ? family + (chip.length() > 0 ? " " + chip : "") : "Android");
        if (release.length() > 0) b.append(" · Android ").append(release);
        if (width > 0 && height > 0) b.append(" · ").append(width).append('×').append(height);
        if (ramMb > 0) b.append(" · ").append(ramMb >= 900 ? Math.round(ramMb / 1024f * 2) / 2f + " GB" : ramMb + " MB");
        return b.toString().replace(".0 GB", " GB");
    }

    /** The Allwinner picture processor (tvserver) only exists on Allwinner TV firmware. */
    boolean mayHavePictureProcessor() {
        return ALLWINNER.equals(family);
    }

    /** Low-memory projectors (≤ 1.5 GB) get the leaner defaults. */
    boolean lowMemory() {
        return ramMb > 0 && ramMb <= 1536;
    }

    String json() {
        return "{\"family\":" + RemoteServer.json(family) + ",\"chip\":" + RemoteServer.json(chip) + ",\"brand\":" + RemoteServer.json(brand)
                + ",\"model\":" + RemoteServer.json(model) + ",\"android\":" + RemoteServer.json(release) + ",\"sdk\":" + sdk
                + ",\"width\":" + width + ",\"height\":" + height + ",\"ramMb\":" + ramMb + ",\"h713\":" + h713
                + ",\"summary\":" + RemoteServer.json(summary()) + "}";
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.equalsIgnoreCase("unknown") ? "" : s;
    }

    // ------------------------------------------------------------------ Android

    private static DeviceInfo cached;

    /** Gathers the facts once (reads a few build properties; cheap). */
    static synchronized DeviceInfo read(android.content.Context c) {
        if (cached != null) return cached;
        String socModel = "", socMaker = "";
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            socModel = prop("ro.soc.model");
            socMaker = prop("ro.soc.manufacturer");
        }
        DeviceInfo d = classify(prop("ro.board.platform"), android.os.Build.HARDWARE, android.os.Build.BOARD, socModel, socMaker,
                android.os.Build.BRAND, android.os.Build.MODEL);
        d.sdk = android.os.Build.VERSION.SDK_INT;
        d.release = android.os.Build.VERSION.RELEASE;
        try {
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            android.view.WindowManager wm = (android.view.WindowManager) c.getSystemService(android.content.Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(m);
            d.width = Math.max(m.widthPixels, m.heightPixels);
            d.height = Math.min(m.widthPixels, m.heightPixels);
        } catch (RuntimeException ignored) {
        }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            d.ramMb = mi.totalMem / (1024 * 1024);
        } catch (RuntimeException ignored) {
        }
        cached = d;
        return d;
    }

    /** A system property through the public getprop tool (no hidden APIs). Empty if unavailable. */
    static String prop(String name) {
        Process p = null;
        try {
            p = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
