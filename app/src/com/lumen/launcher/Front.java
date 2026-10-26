package com.lumen.launcher;

import android.app.Activity;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import java.lang.ref.WeakReference;

/**
 * Which Lumen screen is in front, so phone-remote keys can drive it: arrows move the focus, OK selects,
 * Back closes, and typed text goes into the focused text box. Lumen's own screens only.
 */
final class Front {

    private static WeakReference<Activity> front = new WeakReference<Activity>(null);

    private Front() {
    }

    static void resumed(Activity a) {
        front = new WeakReference<Activity>(a);
    }

    static void paused(Activity a) {
        if (front.get() == a) front = new WeakReference<Activity>(null);
    }

    static Activity get() {
        Activity a = front.get();
        return a == null || a.isFinishing() ? null : a;
    }

    /** Presses a key on the Lumen screen in front. Returns false if no Lumen screen is in front. Main thread. */
    static boolean press(int keyCode) {
        Activity a = get();
        if (a == null) return false;
        long t = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, keyCode, 0);
        boolean handled = a.dispatchKeyEvent(down);
        handled |= a.dispatchKeyEvent(up);
        if (handled) return true;
        int dir = keyCode == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        if (dir != 0) {
            View f = a.getCurrentFocus();
            if (f == null) {
                View root = a.getWindow().getDecorView();
                View first = root.focusSearch(View.FOCUS_DOWN);
                if (first != null) first.requestFocus();
                return true;
            }
            View next = f.focusSearch(dir);
            if (next != null) next.requestFocus(dir);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            a.onBackPressed();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            View f = a.getCurrentFocus();
            if (f != null) f.performClick();
            return true;
        }
        return true;
    }

    /** Types into the focused text box on the Lumen screen in front. False if there isn't one. */
    static boolean type(String text) {
        Activity a = get();
        if (a == null) return false;
        View f = a.getCurrentFocus();
        if (!(f instanceof EditText)) return false;
        EditText e = (EditText) f;
        Editable ed = e.getText();
        int s = Math.max(0, e.getSelectionStart()), en = Math.max(0, e.getSelectionEnd());
        ed.replace(Math.min(s, en), Math.max(s, en), text);
        return true;
    }
}
