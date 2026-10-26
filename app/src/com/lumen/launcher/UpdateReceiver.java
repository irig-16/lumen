package com.lumen.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Android's answer to an install session: show its confirm screen, or report a failure. */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Android 10+ blocks screens opened from the background. If Lumen isn't in front (e.g. the update
            // was sent from the phone during a film) and can't draw over apps, keep it for the home screen.
            if (Updater.homeVisible || android.provider.Settings.canDrawOverlays(c)) {
                try {
                    c.startActivity(confirm);
                    return;
                } catch (RuntimeException ignored) {
                }
            }
            Updater.pendingConfirm = confirm;
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.pendingConfirm = null;
        } else {
            Updater.pendingConfirm = null;
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Updater.get(c).failed(status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled"
                    : "Update failed" + (msg == null ? "" : ": " + msg));
        }
    }
}
