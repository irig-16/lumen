package com.lumen.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Remembers how hot the projector was when it shut down, so the next start can explain a heat shutdown. */
public class HeatReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_SHUTDOWN.equals(i.getAction())) HeatGuard.get(c).onShutdown();
    }
}
