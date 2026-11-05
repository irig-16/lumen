#!/system/bin/sh
# LumenOS boot check (runs via adb shell).
#   report    read-only: how this start went (boot timeline, boot logo state, home screens)
#   launcher  turns the projector's own launcher(s) back on; Lumen stays the home screen
L=com.lumen.launcher
B=/data/local/tmp/lumenos
MODE="${1:-report}"

if [ "$MODE" = launcher ]; then
  list=$( { cat $B/disabled.txt 2>/dev/null; pm list packages -d 2>/dev/null | sed 's/^package://' | grep -iE 'launcher|home'; } | grep -v "^$L\$" | sort -u)
  if [ -z "$list" ]; then echo "  No launcher of the projector's is switched off."; fi
  for p in $list; do
    pm enable "$p" >/dev/null 2>&1 && echo "  switched back on: $p"
  done
  : > $B/disabled.txt 2>/dev/null
  if cmd package set-home-activity $L/.HomeActivity >/dev/null 2>&1; then echo "  Lumen stays your home screen."; fi
  exit 0
fi

echo "=== Boot check $(date) ==="
echo "Projector: $(getprop ro.product.model) - $(getprop ro.board.platform) - Android $(getprop ro.build.version.release)"
echo "Up for: $(cut -d' ' -f1 /proc/uptime) s"
echo ""
echo "--- boot timeline (clock time, then ms since power-on)"
logcat -b events -d 2>/dev/null | grep -E "boot_progress_(start|system_run|pms_ready|ams_ready|enable_screen)|sf_stop_bootanim|wm_boot_animation_done"
echo ""
echo "--- boot properties"
getprop | grep -iE "bootanim|bootvideo|boot_completed|bootlogo|sys.boot|logo" | head -n 30
echo ""
echo "--- home screens"
cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null
echo "--- switched off"
pm list packages -d 2>/dev/null
echo "--- switched off by LumenOS"
cat $B/disabled.txt 2>/dev/null
echo ""
echo "--- Lumen"
dumpsys package $L 2>/dev/null | grep -m 2 -E "versionName|firstInstallTime"
