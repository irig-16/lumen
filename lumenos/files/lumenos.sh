#!/system/bin/sh
# LumenOS - turns any Android projector's stock firmware into a clean Lumen system.
# Runs on the projector through adb shell. Every change is recorded so
# lumenos-undo.sh can put it all back.
#
# Usage: sh lumenos.sh [auto|keep|720|1080]
#   The argument is the drawing resolution. auto (default): 720p on the HY300 family
#   (Allwinner H713, a 720p panel that Android draws at 1080p), unchanged everywhere else.

B=/data/local/tmp/lumenos
L=com.lumen.launcher
mkdir -p $B
LOG=$B/lumenos.log
echo "=== LumenOS $(date) ===" >> $LOG

say() { echo "  $*"; echo "$*" >> $LOG; }
step() { echo ""; echo "[$1] $2"; echo "[$1] $2" >> $LOG; }
has() { pm path "$1" >/dev/null 2>&1; }

if ! has $L; then
  echo "Lumen is not installed on the projector. Run LumenOS-Install.bat again."
  exit 1
fi

# ---------------------------------------------------------------- which projector is this?
RES=${1:-auto}
SDK=$(getprop ro.build.version.sdk); SDK=${SDK:-0}
PLAT="$(getprop ro.board.platform) $(getprop ro.hardware) $(getprop ro.product.board)"
MODEL="$(getprop ro.product.brand) $(getprop ro.product.model)"
H713=0
case "$PLAT" in *ares*|*sun50iw12*|*h713*) H713=1 ;; esac
CHIP="Android"
case "$PLAT" in
  *sun*|*ares*|*allwinner*) CHIP="Allwinner" ;;
  *meson*|*amlogic*|*g12*|*sm1*|*sc2*|*s905*|*s922*|*t9*) CHIP="Amlogic" ;;
  *rk3*|*rk30board*|*rockchip*) CHIP="Rockchip" ;;
  *mt[0-9]*|*mtk*|*mstar*|*m7[0-9][0-9][0-9]*) CHIP="MediaTek" ;;
  *rtd*|*realtek*) CHIP="Realtek" ;;
  *qcom*|*msm*|*sdm*) CHIP="Qualcomm" ;;
esac
[ $H713 = 1 ] && CHIP="Allwinner H713 (HY300 family)"
echo "  Projector: $MODEL - $CHIP - Android $(getprop ro.build.version.release) (SDK $SDK)"
echo "projector=$MODEL chip=$CHIP sdk=$SDK res=$RES" >> $LOG

# ---------------------------------------------------------------- backup
step 1/6 "Saving your current setup (for Undo)"
if [ ! -f $B/backup.done ]; then
  wm size    | sed -n 's/^Override size: //p'    > $B/wm_size.txt
  wm density | sed -n 's/^Override density: //p' > $B/wm_density.txt
  : > $B/settings.txt
  for k in window_animation_scale transition_animation_scale animator_duration_scale private_dns_mode private_dns_specifier; do
    echo "global $k $(settings get global $k)" >> $B/settings.txt
  done
  for k in screensaver_enabled screensaver_components; do
    echo "secure $k $(settings get secure $k)" >> $B/settings.txt
  done
  : > $B/removed.txt
  : > $B/disabled.txt
  touch $B/backup.done
  say "Saved."
else
  say "Already saved on the first run; keeping that copy."
fi

# ---------------------------------------------------------------- debloat
step 2/6 "Removing bloatware and ad sources"
# Factory test and aging tools, vendor OTA updaters (they could flash stock
# firmware back over your setup), the ad-filled app store, office suite.
# Vendor OTA updaters only on the HY300 family (its updater can flash old stock firmware back);
# on any other projector they stay, so the maker's updates keep arriving.
UPDATERS=""
[ $H713 = 1 ] && UPDATERS="com.htc.htcotaupdate android.rockchip.update.service"
for p in \
  com.softwinner.dragonatt \
  com.softwinner.dragonbox \
  com.softwinner.agingdragonbox \
  com.htc.hyk_test \
  com.htc.storeos \
  com.mobisystems.office \
  com.android.egg \
  $UPDATERS
do
  if has $p; then
    if pm uninstall -k --user 0 $p >/dev/null 2>&1; then
      grep -qx "$p" $B/removed.txt || echo "$p" >> $B/removed.txt
      say "removed  $p"
    else
      say "skipped  $p (Android refused)"
    fi
  fi
done

# Anything else that looks like an ad or store service: listed, not removed.
pm list packages -s | sed 's/^package://' | \
  grep -iE 'zeasn|whale|ads|advert|adserv|market|appstore|store|tencent|umeng|iflytek|push' | \
  grep -vE '^com\.android\.|^com\.google\.|^android$|com\.android\.vending|storage' > $B/review.txt
if [ -s $B/review.txt ]; then
  say "Found other possible ad/store packages (left alone, listed in review.txt):"
  while read -r p; do say "  ? $p"; done < $B/review.txt
fi

# ---------------------------------------------------------------- Lumen as the OS
step 3/6 "Making Lumen the home screen and system shell"
appops set $L REQUEST_INSTALL_PACKAGES allow
appops set $L SYSTEM_ALERT_WINDOW allow
appops set $L WRITE_SETTINGS allow
pm grant $L android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
pm grant $L android.permission.RECORD_AUDIO 2>/dev/null
pm grant $L android.permission.DUMP 2>/dev/null   # heat guard: exact CPU/GPU temperatures
pm grant $L com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null
cmd notification allow_listener $L/$L.MediaWatch >/dev/null 2>&1 || \
  settings put secure enabled_notification_listeners "$L/$L.MediaWatch"
say "Permissions: updates, draw over apps, screen presets, colour warmth, voice, Continue Watching."

if cmd package set-home-activity $L/.HomeActivity >/dev/null 2>&1 || pm set-home-activity $L/.HomeActivity >/dev/null 2>&1; then
  say "Lumen set as home."
else
  say "This Android version can't set the home screen from here: press Home once and choose Lumen, Always."
fi

# Disable every other launcher (the stock one carries the ads).
cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | \
  sed 's|/.*||' | sort -u | grep -i launcher | grep -v "^$L\$" | \
while read -r p; do
  if pm disable-user --user 0 $p >/dev/null 2>&1; then
    grep -qx "$p" $B/disabled.txt || echo "$p" >> $B/disabled.txt
    say "stock launcher off: $p"
  fi
done

# Lumen Window as the screensaver.
settings put secure screensaver_components $L/.WindowDream
settings put secure screensaver_enabled 1
say "Lumen Window is the screensaver."

# ---------------------------------------------------------------- smoothness
step 4/6 "Tuning for smoothness"
# The HY300's panel is 1280x720 but Android draws 1920x1080 and throws 56% of the pixels away;
# drawing at 720p (same layout size) cuts GPU work and heat. Other projectors keep their resolution
# unless one was asked for (the panel's real resolution can't be read, so we never guess it).
[ "$RES" = "auto" ] && { [ $H713 = 1 ] && RES=720 || RES=keep; }
case "$RES" in
  720)  wm size 1280x720;  wm density 213; say "UI now renders at 1280x720 (layout unchanged)." ;;
  1080) wm size 1920x1080; wm density 320; say "UI now renders at 1920x1080 (layout unchanged)." ;;
  *)    say "Resolution left as it is ($(wm size | sed -n 's/^Physical size: //p'))." ;;
esac

settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5
say "Animations at 0.5x."

if [ "$SDK" -ge 24 ]; then
  say "Compiling Lumen to native code (about a minute)..."
  cmd package compile -m speed -f $L >/dev/null 2>&1 && say "Lumen compiled."
  cmd package compile -m speed -f com.android.tv.settings >/dev/null 2>&1 && say "Settings compiled."
fi

# ---------------------------------------------------------------- ad blocking
step 5/6 "Blocking ads system-wide"
if [ "$SDK" -ge 28 ]; then
  settings put global private_dns_mode hostname
  settings put global private_dns_specifier dns.adguard-dns.com
  say "Checking your network allows it..."
  sleep 10
  if dumpsys connectivity 2>/dev/null | grep -q "PrivateDnsBroken"; then
    # This network blocks encrypted DNS: go back so the internet keeps working.
    old=$(sed -n 's/^global private_dns_mode //p' $B/settings.txt)
    if [ "$old" = "null" ] || [ -z "$old" ]; then settings delete global private_dns_mode >/dev/null 2>&1
    else settings put global private_dns_mode "$old"; fi
    say "Your network blocks encrypted DNS, so ad blocking was switched back off."
    say "(Lumen's home screen itself has no ads either way.)"
  else
    say "Private DNS: dns.adguard-dns.com (blocks ad and tracker domains in every app)."
  fi
else
  say "Encrypted DNS ad blocking needs Android 9 or later; skipped on this projector."
fi

# ---------------------------------------------------------------- clean up
step 6/6 "Freeing memory"
am kill-all >/dev/null 2>&1
say "Done."

echo ""
echo "LumenOS is set up. The projector will restart now."
