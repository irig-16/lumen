#!/system/bin/sh
# LumenOS Unlock - the one optional 5-second step. Grants Lumen the two things no app can grant
# itself (secure settings + the thermal reader), then lets Lumen apply the rest by itself and keep
# it applied after every update. Nothing is removed and nothing is flashed.
L=com.lumen.launcher
if ! pm path $L >/dev/null 2>&1; then
  echo "Lumen isn't installed yet. Install the Lumen APK first, then run this."
  exit 1
fi
echo "Giving Lumen what it can't grant itself..."
appops set $L REQUEST_INSTALL_PACKAGES allow 2>/dev/null
appops set $L SYSTEM_ALERT_WINDOW allow 2>/dev/null
appops set $L WRITE_SETTINGS allow 2>/dev/null
pm grant $L android.permission.WRITE_SECURE_SETTINGS 2>/dev/null && echo "  secure settings: on (0.5x animations, ad blocking, screensaver)"
pm grant $L android.permission.DUMP 2>/dev/null && echo "  thermal reader: on (exact CPU/GPU temperatures)"
pm grant $L android.permission.RECORD_AUDIO 2>/dev/null
pm grant $L com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null
cmd notification allow_listener $L/$L.MediaWatch >/dev/null 2>&1 || \
  settings put secure enabled_notification_listeners "$L/$L.MediaWatch"
cmd package set-home-activity $L/.HomeActivity >/dev/null 2>&1
# Nudge Lumen to apply everything it now can, right away.
am start -n $L/.SetupActivity >/dev/null 2>&1 || am start -n $L/.HomeActivity >/dev/null 2>&1
echo ""
echo "Done. Lumen now keeps smooth animations, ad blocking and exact temperatures on by itself,"
echo "through every future update. You never need this again on this projector."
