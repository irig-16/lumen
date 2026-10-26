#!/system/bin/sh
# LumenOS update: Lumen 7 (heat guard, updates from GitHub). Runs on the projector via adb shell.
L=com.lumen.launcher
B=/data/local/tmp/lumenos
mkdir -p $B
say() { echo "  $*"; echo "$*" >> $B/lumenos.log; }
echo "=== Update $(date) ===" >> $B/lumenos.log

if ! pm path $L >/dev/null 2>&1; then
  echo "Lumen is not installed. Run LumenOS-Install.bat instead."
  exit 1
fi
v=$(dumpsys package $L | sed -n 's/.*versionName=//p' | head -n 1)
echo ""
echo "[1/4] Lumen $v is installed"

echo "[2/4] Giving Lumen its access"
appops set $L REQUEST_INSTALL_PACKAGES allow
appops set $L SYSTEM_ALERT_WINDOW allow
appops set $L WRITE_SETTINGS allow
pm grant $L android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
pm grant $L android.permission.RECORD_AUDIO 2>/dev/null
# Heat guard: exact CPU/GPU temperatures from Android's thermal service
pm grant $L android.permission.DUMP 2>/dev/null && say "Heat guard: exact temperatures on"
# Continue Watching: Android TV's Watch Next list (where the firmware shares it) ...
pm grant $L com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null && say "Watch Next access: granted"
# ... and the "now playing" info apps publish (media sessions; notifications are never read)
if cmd notification allow_listener $L/$L.MediaWatch >/dev/null 2>&1; then
  say "Continue Watching: on"
else
  settings put secure enabled_notification_listeners "$L/$L.MediaWatch" && say "Continue Watching: on"
fi
cmd package set-home-activity $L/.HomeActivity >/dev/null 2>&1

echo "[3/4] Compiling Lumen to native code (about a minute)"
cmd package compile -m speed -f $L >/dev/null 2>&1 && say "Done."

echo "[4/4] Restarting Lumen"
am force-stop $L
am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $L/.HomeActivity >/dev/null 2>&1
echo ""
echo "Lumen is updated. From now on it updates itself from github.com/irig-16/lumen:"
echo "when a new version is out, the home screen asks you to install it."
