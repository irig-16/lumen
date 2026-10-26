package com.lumen.launcher;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Direct line to the projector's picture processor (Allwinner H713 "tvserver" HAL,
 * vendor.aw.homlet.tvsystem.tvserver@1.0::ITvServer) — the same service the projector's own
 * picture menu uses.
 *
 * The firmware's security policy lets apps targeting Android 11 (SDK 30) find and call this
 * service. Two ways in, tried in order:
 *  1. the vendor's Java client in the shared library "com.softwinner.tv" (declared with
 *     uses-library in the manifest), loaded by reflection so Lumen still runs where it's missing;
 *  2. the raw HIDL transactions, for firmware without that library (needs hidden-API access).
 *
 * Every call is blocking binder IPC: call from a background thread (see ScreenEngine).
 */
final class PictureEngine {

    private static final String TAG = "LumenPQ";
    private static final String IFACE = "vendor.aw.homlet.tvsystem.tvserver@1.0::ITvServer";
    private static final String CLIENT = "vendor.aw.homlet.tvsystem.tvserver.V1_0.ITvServer";

    // ---- getPQValue / setPQValue ids (from the vendor picture menu)
    static final int BRIGHTNESS = 1, CONTRAST = 2, COLOR = 3, TINT = 4, SHARPNESS = 5, BACKLIGHT = 6,
            TNR = 7, SNR = 8, DCI = 9, BLACK_EXT = 10, GAMMA = 11, COLOR_TEMP = 12, DYNAMIC_BACKLIGHT = 13;
    static final int[] ALL_PQ = {BRIGHTNESS, CONTRAST, COLOR, TINT, SHARPNESS, BACKLIGHT, TNR, SNR, DCI,
            BLACK_EXT, GAMMA, COLOR_TEMP, DYNAMIC_BACKLIGHT};

    // ---- factory picture parameters: {paramType, item}
    static final int P_ENABLE = 0, P_NR = 2, P_CTI = 3, P_SSR = 4;
    static final int EN_PRE_DCTI = 6, EN_POST_DCTI = 7, EN_SSR = 8, EN_PEAKING = 9;
    static final int CTI_PRE_GAIN = 768, CTI_PRE_STEP = 769, CTI_POST_GAIN = 784, CTI_POST_STEP = 785;
    static final int SSR_POS_GAIN = 1024, SSR_NEG_GAIN = 1025,
            PEAK_H2 = 1040, PEAK_H4 = 1041, PEAK_H6 = 1042, PEAK_H8 = 1043,
            PEAK_V2 = 1044, PEAK_V4 = 1045, PEAK_V6 = 1046, PEAK_S2 = 1047, PEAK_S4 = 1048;
    static final int NR_MNR_THR = 547;

    // ---- raw HIDL transaction codes (ITvServer@1.0)
    private static final int TX_SET_PQ = 97, TX_GET_PQ = 98, TX_SET_MODE = 99, TX_GET_MODE = 100,
            TX_SET_PARAM = 122, TX_GET_PARAM = 123;

    private static Object client;          // path 1
    private static Object binder;          // path 2 (IHwBinder)
    private static Method mSetPq, mGetPq, mSetMode, mGetMode, mSetParam, mGetParam;
    private static boolean tried;
    private static String how = "", why = "";

    private PictureEngine() {
    }

    /** Connects once. True if the picture processor answers. */
    static synchronized boolean available() {
        if (tried) return client != null || binder != null;
        tried = true;
        try {
            Class<?> k = Class.forName(CLIENT);
            // retry=false: never block waiting for a service this firmware doesn't have
            Object s = k.getMethod("getService", boolean.class).invoke(null, false);
            if (s != null) {
                mSetPq = k.getMethod("setPQValue", int.class, int.class);
                mGetPq = k.getMethod("getPQValue", int.class);
                mSetMode = k.getMethod("setPictureModeByName", String.class);
                mGetMode = k.getMethod("getPictureModeName");
                mSetParam = k.getMethod("factorySetPictureParam", int.class, int.class, int.class);
                mGetParam = k.getMethod("factoryGetPictureParam", int.class, int.class);
                mGetPq.invoke(s, CONTRAST); // proves the call path, not just the lookup
                client = s;
                how = "vendor client";
                return true;
            }
            why = "picture service not running";
        } catch (Throwable t) {
            why = rootMessage(t);
            Log.w(TAG, "vendor client: " + why);
        }
        try {
            exemptHiddenApis();
            Class<?> hw = Class.forName("android.os.HwBinder");
            Object b = hw.getMethod("getService", String.class, String.class).invoke(null, IFACE, "default");
            if (b != null) {
                binder = b;
                rawInt(TX_GET_PQ, CONTRAST);
                how = "direct HAL";
                return true;
            }
        } catch (Throwable t) {
            binder = null;
            String w = rootMessage(t);
            Log.w(TAG, "direct HAL: " + w);
            if (why.length() == 0) why = w;
        }
        return false;
    }

    static String how() {
        return how;
    }

    static String whyNot() {
        return why.length() > 0 ? why : "not connected";
    }

    /** For a retry after permissions change (e.g. the update script ran). */
    static synchronized void reset() {
        tried = false;
        client = null;
        binder = null;
        how = "";
        why = "";
    }

    // ------------------------------------------------------------------ calls

    static int set(int id, int value) {
        if (!available()) return -1;
        try {
            if (client != null) return (Integer) mSetPq.invoke(client, id, value);
            return rawInt(TX_SET_PQ, id, value);
        } catch (Throwable t) {
            Log.w(TAG, "set " + id + ": " + rootMessage(t));
            return -1;
        }
    }

    static int get(int id) {
        if (!available()) return -1;
        try {
            if (client != null) return (Integer) mGetPq.invoke(client, id);
            return rawInt(TX_GET_PQ, id);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int setMode(String name) {
        if (!available()) return -1;
        try {
            if (client != null) return (Integer) mSetMode.invoke(client, name);
            return rawString(TX_SET_MODE, name);
        } catch (Throwable t) {
            Log.w(TAG, "mode " + name + ": " + rootMessage(t));
            return -1;
        }
    }

    static String getMode() {
        if (!available()) return "";
        try {
            if (client != null) return String.valueOf(mGetMode.invoke(client));
            return rawGetString(TX_GET_MODE);
        } catch (Throwable t) {
            return "";
        }
    }

    static int setParam(int type, int item, int value) {
        if (!available()) return -1;
        try {
            if (client != null) return (Integer) mSetParam.invoke(client, type, item, value);
            return rawInt(TX_SET_PARAM, type, item, value);
        } catch (Throwable t) {
            Log.w(TAG, "param " + type + "/" + item + ": " + rootMessage(t));
            return -1;
        }
    }

    static int getParam(int type, int item) {
        if (!available()) return -1;
        try {
            if (client != null) return (Integer) mGetParam.invoke(client, type, item);
            return rawInt(TX_GET_PARAM, type, item);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ raw HIDL

    private static Object parcel() throws Exception {
        return Class.forName("android.os.HwParcel").getConstructor().newInstance();
    }

    private static Object transact(int code, Object req) throws Exception {
        Class<?> pc = req.getClass();
        Object reply = parcel();
        Class<?> ib = Class.forName("android.os.IHwBinder");
        ib.getMethod("transact", int.class, pc, pc, int.class).invoke(binder, code, req, reply, 0);
        pc.getMethod("verifySuccess").invoke(reply);
        pc.getMethod("releaseTemporaryStorage").invoke(req);
        return reply;
    }

    private static int rawInt(int code, int... args) throws Exception {
        Object req = parcel();
        Class<?> pc = req.getClass();
        pc.getMethod("writeInterfaceToken", String.class).invoke(req, IFACE);
        for (int a : args) pc.getMethod("writeInt32", int.class).invoke(req, a);
        Object reply = transact(code, req);
        int r = (Integer) pc.getMethod("readInt32").invoke(reply);
        pc.getMethod("release").invoke(reply);
        return r;
    }

    private static int rawString(int code, String s) throws Exception {
        Object req = parcel();
        Class<?> pc = req.getClass();
        pc.getMethod("writeInterfaceToken", String.class).invoke(req, IFACE);
        pc.getMethod("writeString", String.class).invoke(req, s);
        Object reply = transact(code, req);
        int r = (Integer) pc.getMethod("readInt32").invoke(reply);
        pc.getMethod("release").invoke(reply);
        return r;
    }

    private static String rawGetString(int code) throws Exception {
        Object req = parcel();
        Class<?> pc = req.getClass();
        pc.getMethod("writeInterfaceToken", String.class).invoke(req, IFACE);
        Object reply = transact(code, req);
        String r = (String) pc.getMethod("readString").invoke(reply);
        pc.getMethod("release").invoke(reply);
        return r;
    }

    /** Lets Lumen's own code reach the HIDL classes on firmware without the vendor library. */
    private static void exemptHiddenApis() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclared = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vm = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclared.invoke(vm, "getRuntime", null);
            Method exempt = (Method) getDeclared.invoke(vm, "setHiddenApiExemptions", new Class[]{String[].class});
            exempt.invoke(getRuntime.invoke(null), new Object[]{new String[]{"Landroid/os/Hw", "Landroid/os/IHw"}});
        } catch (Throwable ignored) {
            // not needed (or not possible) on this firmware
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
