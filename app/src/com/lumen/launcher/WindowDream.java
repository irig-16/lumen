package com.lumen.launcher;

import android.service.dreams.DreamService;

/**
 * Lumen Window as Android's own screensaver (Settings → Display → Screen saver, where the projector offers it),
 * so the view also appears when another app has been left idle. Any button wakes it.
 */
public class WindowDream extends DreamService {

    private WindowView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        view = new WindowView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start(null);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        view.stop();
    }
}
