#!/system/bin/sh
# LumenOS speed for the HY300: memory tuning and a faster start. Runs via adb shell.
#
#   report  read-only: startup timeline, memory, and which apps wake up at every start
#   apply   memory tuning + holds back the apps in the "can be held back" list + compiles apps
#   undo    puts everything back
#
# Nothing is uninstalled or disabled. "Held back" apps still open and work normally when you
# use them; they just can't run in the background or start themselves at boot.

B=/data/local/tmp/lumenos
mkdir -p $B
MODE="${1:-report}"
MAX_CACHED=12

# Never held back: Android itself, Google services, Lumen, the projector's own services
# (picture, keystone, remote, casting), connectivity, keyboards, and video/music apps.
ESSENTIAL='^android$|^com\.android\.|^com\.google\.android\.(gms|gsf|ext|tts|webview|packageinstaller|permissioncontroller|configupdater|partnersetup|syncadapters|onetimeinitializer|backuptransport|inputmethod|leanbacklauncher|tvlauncher|tv\.|katniss|apps\.mediashell)|^com\.lumen\.|softwinner|allwinner|^com\.aw\.|tvserver|hotack|keystone|projector|bluetooth|wifi|settings|systemui|provision|inputmethod|keyboard|\.ime|remote|dlna|airplay|miracast|cast|mirror|dragonbox|launcher'
MEDIA='netflix|youtube|smarttube|videomanager|amazon|primevideo|hotstar|disney|spotify|kodi|xbmc|vlc|plex|jellyfin|mxtech|stremio|music|player|zee5|sonyliv|jio'

starters() {
  cmd package query-receivers --components -a android.intent.action.BOOT_COMPLETED 2>/dev/null \
    | sed -n 's#^[[:space:]]*\([A-Za-z0-9_.]*\)/.*#\1#p' | sort -u
}

ime_pkgs() {
  ime list -s 2>/dev/null | sed 's#/.*##'
}

candidates() {
  imes=$(ime_pkgs)
  for p in $(starters); do
    echo "$p" | grep -qE "$ESSENTIAL" && continue
    echo "$p" | grep -qiE "$MEDIA" && continue
    case " $imes " in *" $p "*) continue ;; esac
    echo "$p"
  done
}

timeline() {
  ev=$(logcat -b events -d 2>/dev/null | grep -E "boot_progress_(start|system_run|pms_start|pms_ready|ams_ready|enable_screen)")
  if [ -z "$ev" ]; then
    echo "  (not available: the log has moved on. Restart the projector, then run this within a few minutes)"
    return
  fi
  echo "$ev" | awk '{
      name=$0; sub(/.*boot_progress_/,"",name); sub(/:.*/,"",name);
      v=$NF+0;
      label["start"]="Android framework starts";
      label["system_run"]="system services start";
      label["pms_start"]="app scan starts";
      label["pms_ready"]="app scan done";
      label["ams_ready"]="system ready, home screen starting";
      label["enable_screen"]="home screen on (boot animation ends)";
      printf "  %6.1f s  %s\n", v/1000, label[name]
    }'
  b=$(getprop sys.boot_completed)
  [ "$b" = "1" ] && echo "  (times are seconds since power-on)"
}

memory() {
  dumpsys meminfo 2>/dev/null | grep -E "^ *(Total RAM|Free RAM|Used RAM|Lost RAM)" | sed 's/^ */  /'
  echo "  cached-app limit:  $(device_config get activity_manager max_cached_processes 2>/dev/null | sed 's/^null$/default/')"
  echo "  cached-app freezer: $(settings get global cached_apps_freezer | sed 's/^null$/default/')"
}

report() {
  echo ""
  echo "=== Startup timeline ==="
  timeline
  echo ""
  echo "=== Memory ==="
  memory
  echo ""
  echo "=== Apps that start themselves at every boot ==="
  c=$(candidates)
  n=0
  for p in $(starters); do
    if echo "$c" | grep -qx "$p"; then tag="can be held back"; else tag="needed"; fi
    if grep -qx "$p" $B/speed_held.txt 2>/dev/null; then tag="held back"; fi
    printf "  %-17s %s\n" "$tag" "$p"
    n=$((n+1))
  done
  [ $n -eq 0 ] && echo "  (none found)"
  echo ""
  if [ -f $B/speed.done ]; then echo "LumenOS speed-ups: ON (undo with option 3)"; else echo "LumenOS speed-ups: not applied yet"; fi
}

apply() {
  echo ""
  echo "[1/3] Memory tuning"
  if [ ! -f $B/speed.done ]; then
    echo "mc=$(device_config get activity_manager max_cached_processes 2>/dev/null)" > $B/speed_orig.txt
    echo "fz=$(settings get global cached_apps_freezer)" >> $B/speed_orig.txt
  fi
  if device_config put activity_manager max_cached_processes $MAX_CACHED 2>/dev/null; then
    echo "  Background apps kept in memory: at most $MAX_CACHED (frees room for the app you're watching)"
  else
    echo "  (this firmware doesn't allow changing the cached-app limit - skipped)"
  fi
  if [ -e /sys/fs/cgroup/uid_0/cgroup.freeze ] || [ -d /sys/fs/cgroup/freezer ] || [ -d /dev/freezer ]; then
    settings put global cached_apps_freezer enabled
    echo "  Cached-app freezer: on (apps in the background use no CPU; less heat). Active after a restart."
  else
    echo "  Cached-app freezer: not supported by this projector's kernel - skipped"
  fi

  echo "[2/3] Holding back apps that start themselves at boot"
  touch $B/speed_held.txt
  n=0
  for p in $(candidates); do
    cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1
    am set-standby-bucket "$p" restricted >/dev/null 2>&1 || am set-standby-bucket "$p" rare >/dev/null 2>&1
    grep -qx "$p" $B/speed_held.txt || echo "$p" >> $B/speed_held.txt
    echo "  held back: $p"
    n=$((n+1))
  done
  [ $n -eq 0 ] && echo "  nothing to hold back"

  echo "[3/3] Compiling apps for speed (can take up to 10 minutes; leave the projector on)"
  cmd package bg-dexopt-job >/dev/null 2>&1 && echo "  done" || echo "  (skipped by this firmware)"

  cp "$0" $B/speed.sh 2>/dev/null
  touch $B/speed.done
  echo ""
  echo "Speed-ups applied. Restart the projector, then run option 1 again to compare the timeline."
}

undo() {
  echo ""
  if [ -f $B/speed_held.txt ]; then
    while read -r p; do
      [ -z "$p" ] && continue
      cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
      am set-standby-bucket "$p" active >/dev/null 2>&1
      echo "  released: $p"
    done < $B/speed_held.txt
  fi
  if [ -f $B/speed_orig.txt ]; then
    mc=$(sed -n 's/^mc=//p' $B/speed_orig.txt)
    fz=$(sed -n 's/^fz=//p' $B/speed_orig.txt)
    if [ -z "$mc" ] || [ "$mc" = "null" ]; then device_config delete activity_manager max_cached_processes >/dev/null 2>&1
    else device_config put activity_manager max_cached_processes "$mc"; fi
    if [ -z "$fz" ] || [ "$fz" = "null" ]; then settings delete global cached_apps_freezer >/dev/null 2>&1
    else settings put global cached_apps_freezer "$fz"; fi
    echo "  memory settings restored"
  fi
  rm -f $B/speed.done $B/speed_held.txt $B/speed_orig.txt
  echo ""
  echo "Speed-ups undone. Restart the projector to finish."
}

case "$MODE" in
  report) report ;;
  list) candidates ;;
  apply) apply ;;
  undo) undo ;;
  *) echo "Unknown option: $MODE"; exit 1 ;;
esac
