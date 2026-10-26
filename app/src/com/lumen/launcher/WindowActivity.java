package com.lumen.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Lumen Window, full screen. ◀ ▶ (or ▲ ▼) change the scene, OK shows the scene's name. BACK or HOME leaves.
 */
public class WindowActivity extends Activity {

    static final String EXTRA_SCENE = "scene";

    private WindowView view;

    static void open(Context c, String scene) {
        Intent i = new Intent(c, WindowActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (scene != null) i.putExtra(EXTRA_SCENE, scene);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new WindowView(this);
        setContentView(view);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);   // onResume (which always follows) starts the requested scene
    }

    @Override
    protected void onResume() {
        super.onResume();
        Front.resumed(this);
        view.start(getIntent().getStringExtra(EXTRA_SCENE));
    }

    @Override
    protected void onPause() {
        super.onPause();
        Front.paused(this);
        view.stop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                view.next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                view.previous();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                view.showCaption();
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(e);
            default:   // BACK, MENU or anything else: back to the home screen
                finish();
                overridePendingTransition(0, android.R.anim.fade_out);
                return true;
        }
    }
}
