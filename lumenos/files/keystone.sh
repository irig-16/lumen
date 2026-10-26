#!/system/bin/sh
# LumenOS keystone tuner for the HY300 (runs via adb shell).
#
# The projector corrects keystone automatically from its tilt sensor: each time the
# tilt changes by more than a threshold, the system recomputes the four-corner warp.
# On a shaky build the sensor jitters, so the picture keeps nudging itself. These knobs
# live in system properties the shell is allowed to set; nothing here is permanent that
# "reset" can't undo, and none of it flashes anything.
#
# Usage (the .bat passes one of these): status | stable | balanced | responsive | recorrect | reset

B=/data/local/tmp/lumenos
mkdir -p $B
MODE="${1:-status}"

# Save the original values once, so reset is exact.
save_once() {
  [ -f $B/keystone_backup ] && return
  {
    echo "tpryauto=$(getprop persist.sys.tpryauto)"
    echo "diff_value=$(getprop persist.sys.diff_value)"
    echo "keystone_lr=$(getprop persist.sys.keystone_lr)"
  } > $B/keystone_backup
}

show() {
  echo "  Auto keystone:        $(getprop persist.sys.tpryauto    | sed 's/^$/(default on)/')"
  echo "  Stability threshold:  $(getprop persist.sys.diff_value  | sed 's/^$/3 (default)/')  (degrees of tilt before it re-corrects)"
  echo "  Last measured angle:  $(getprop persist.sys.keystoneAngle | sed 's/^$/unknown/')"
  echo "  Manual keystone mode: $(getprop persist.sys.prj.manualkeystone | sed 's/^$/0 (off)/')"
}

recorrect() {
  # Nudge the system to re-run the correction now (two broadcast names across builds).
  am broadcast -a com.htc.auto_keystone        >/dev/null 2>&1
  am broadcast -a android.intent.hotack_keystone >/dev/null 2>&1
}

case "$MODE" in
  status)
    echo ""
    echo "Current keystone settings:"
    show
    ;;
  stable|balanced|responsive)
    save_once
    setprop persist.sys.tpryauto 1
    case "$MODE" in
      stable)     V=6 ;;   # fewest re-corrections: best for a jittery sensor on a fixed shelf
      balanced)   V=3 ;;   # the projector's own default
      responsive) V=2 ;;   # re-corrects on smaller tilts: for a projector you move a lot
    esac
    setprop persist.sys.diff_value $V
    recorrect
    echo ""
    echo "Auto keystone on, stability = $MODE (threshold $V degrees)."
    echo "Tilt the projector slightly, or nudge it, to see it re-correct."
    show
    ;;
  recorrect)
    setprop persist.sys.tpryauto 1
    recorrect
    echo ""
    echo "Asked the projector to re-run keystone correction now."
    ;;
  reset)
    if [ -f $B/keystone_backup ]; then
      . $B/keystone_backup
      [ -n "$tpryauto" ]    && setprop persist.sys.tpryauto    "$tpryauto"    || setprop persist.sys.tpryauto 1
      [ -n "$diff_value" ]  && setprop persist.sys.diff_value  "$diff_value"  || setprop persist.sys.diff_value 3
      [ -n "$keystone_lr" ] && setprop persist.sys.keystone_lr "$keystone_lr"
      rm -f $B/keystone_backup
      recorrect
      echo ""
      echo "Keystone settings put back the way they were."
    else
      setprop persist.sys.tpryauto 1
      setprop persist.sys.diff_value 3
      recorrect
      echo ""
      echo "Restored the projector's default keystone settings (auto on, threshold 3)."
    fi
    show
    ;;
  *)
    echo "Unknown option: $MODE"
    exit 1
    ;;
esac
echo ""
echo "If the picture ends up skewed, run the Reset option, or use the projector's own"
echo "keystone screen in Settings. Nothing here was flashed, so it's all reversible."
