package com.lumen.launcher;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Lets "turn off the projector" work from voice and the phone. Android lets an app turn the screen
 * off (the projector's standby) only as a device admin, and only with the "force lock" right, which
 * is the single right Lumen asks for. The user allows it once on the projector (or LumenOS-Unlock
 * sets it). To uninstall Lumen later, first switch it off in Settings → Security → Device admin apps.
 */
public class PowerAdmin extends DeviceAdminReceiver {

    static ComponentName component(Context c) {
        return new ComponentName(c, PowerAdmin.class);
    }

    static boolean ready(Context c) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(component(c));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Screen (and the projector's light) off. False if Lumen hasn't been allowed yet. */
    static boolean sleep(Context c) {
        if (!ready(c)) return false;
        try {
            ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The system's "Allow Lumen to turn the screen off?" page. */
    static Intent askIntent(Context c) {
        return new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets you turn the projector off by voice and from the phone remote. Lumen only uses \"lock the screen\".")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Screen back on, when the projector is in standby but still on Wi-Fi. */
    @SuppressWarnings("deprecation")
    static boolean wake(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm.isInteractive()) return false;
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK
                    | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP | android.os.PowerManager.ON_AFTER_RELEASE, "lumen:wake");
            wl.acquire(4000);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean screenOn(Context c) {
        try {
            return ((android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE)).isInteractive();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
