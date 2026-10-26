package com.lumen.launcher;

import android.content.Intent;
import android.graphics.Bitmap;

/** One launchable app, with its artwork pre-rendered to small fixed-size bitmaps. */
final class AppInfo {
    final String pkg;
    final String label;
    final Bitmap icon;     // square icon
    final Bitmap banner;   // 16:9 TV banner, or null if the app has none
    final int color;       // vivid tone picked from the icon, used for card + ambient light
    final Intent launch;
    final boolean system;

    AppInfo(String pkg, String label, Bitmap icon, Bitmap banner, int color, Intent launch, boolean system) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
        this.banner = banner;
        this.color = color;
        this.launch = launch;
        this.system = system;
    }
}
