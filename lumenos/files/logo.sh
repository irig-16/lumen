#!/system/bin/sh
# LumenOS logo installer (runs via adb shell). Full on the HY300 family; boot animation only elsewhere.
#
#  - Boot splash: the projector's loader shows a picture from the Reserve0 partition when
#    its "advert" feature is on (it is: advert_enable = 1). We ADD advert.bmp and
#    bootlogo.jpg there. panel_config.ini (the loader's panel settings) is never touched,
#    and its fingerprint is checked before and after.
#  - Boot animation: Android plays /oem/media/bootanimation.zip before the one in /system.
#
# Usage: install-black | install-ember | install-timing | install-hold <seconds|0>
#        | restore | status
# The PC pushes the pictures to /data/local/tmp/lumenlogo first.

# The boot splash (Reserve0) and the Lumen hold are for the Allwinner H713 family only: they rely on
# its loader's "advert" logo and its 720p switch. On any other projector only the boot animation is
# changed, and only where Android reads one from a writable place (/oem/media).
PLAT="$(getprop ro.board.platform) $(getprop ro.hardware)"
H713=0
case "$PLAT" in *ares*|*sun50iw12*|*h713*) H713=1 ;; esac

S=/data/local/tmp/lumenlogo
R=/Reserve0
M=/oem/media
B=/data/local/tmp/lumenos
mkdir -p $B
MODE="${1:-status}"

fp() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo none; }

status() {
  if [ $H713 = 0 ]; then
    echo "  Boot animation:"
    if [ -f "$M/bootanimation.zip" ]; then echo "    Lumen: $(cat $B/logo_style 2>/dev/null)"; else echo "    the projector's own"; fi
    if [ -d "$M" ] && [ -w "$M" ]; then echo "    (this projector allows changing it)"; else echo "    (locked on this projector: it can't be changed without a flash)"; fi
    return
  fi
  echo "  Boot splash (Reserve0):"
  for f in advert.bmp bootlogo.jpg; do
    if [ -f "$R/$f" ]; then echo "    $f  present ($(wc -c < "$R/$f") bytes)"; else echo "    $f  not present"; fi
  done
  echo "  Boot animation:"
  if [ -f "$M/bootanimation.zip" ]; then
    st=$(cat $B/logo_style 2>/dev/null)
    echo "    Lumen: ${st:-installed} (used instead of MAGCUBIC)"
  else echo "    using the projector's own (MAGCUBIC)"; fi
  echo "  panel_config.ini fingerprint: $(fp $R/panel_config.ini)"
}

# Any projector but the H713 family: the boot animation only
install_anim() {
  [ -f "$S/$1" ] || { echo "Missing $1 - run the .bat again. Nothing changed."; exit 1; }
  if [ ! -d "$M" ] || [ ! -w "$M" ]; then
    echo "  This projector ($PLAT) keeps its boot logo and animation in its locked system"
    echo "  partitions; there is no writable place Android reads them from (/oem/media)."
    echo "  Changing them would need a firmware flash, which LumenOS never does. Nothing changed."
    exit 3
  fi
  if [ ! -f $B/logo_orig.done ]; then
    rm -f $B/orig_bootanimation.zip
    [ -f "$M/bootanimation.zip" ] && cp "$M/bootanimation.zip" "$B/orig_bootanimation.zip"
    touch $B/logo_orig.done
  fi
  cp "$S/$1" $M/bootanimation.zip || { echo "Copy failed. Nothing changed."; exit 1; }
  chmod 644 $M/bootanimation.zip 2>/dev/null
  echo "$2" > $B/logo_style
  sync
  echo ""
  echo "Installed: $2 (boot animation). The first logo the projector's loader shows"
  echo "can't be changed on this model without a firmware flash, so it stays."
}

# install <animation zip in $S> <description>
install() {
  if [ $H713 = 0 ]; then install_anim "$@"; return; fi
  [ -f "$S/$1" ] || { echo "Missing $1 - run the .bat again. Nothing changed."; exit 1; }
  [ -w "$R" ] || { echo "Reserve0 isn't writable on this projector. Nothing changed."; exit 1; }
  [ -w "$M" ] || { echo "/oem/media isn't writable on this projector. Nothing changed."; exit 1; }
  before=$(fp $R/panel_config.ini)
  echo "$before" > $B/panel_config.md5

  # Record the ORIGINAL state only on the very first install (later installs just switch styles)
  if [ ! -f $B/logo_orig.done ]; then
    rm -f $B/orig_advert.bmp $B/orig_bootlogo.jpg $B/orig_bootanimation.zip
    for f in advert.bmp bootlogo.jpg; do
      if [ -f "$R/$f" ]; then cp "$R/$f" "$B/orig_$f"; fi
    done
    if [ -f "$M/bootanimation.zip" ]; then cp "$M/bootanimation.zip" "$B/orig_bootanimation.zip"; fi
    touch $B/logo_orig.done
  fi

  cp $S/advert.bmp   $R/advert.bmp   || { echo "Copy failed. Nothing else changed."; exit 1; }
  cp $S/bootlogo.jpg $R/bootlogo.jpg
  cp "$S/$1" $M/bootanimation.zip
  chmod 644 $M/bootanimation.zip 2>/dev/null
  echo "$2" > $B/logo_style
  sync

  after=$(fp $R/panel_config.ini)
  if [ "$before" != "$after" ]; then
    echo "WARNING: panel_config.ini changed unexpectedly. Undoing now."
    rm -f $R/advert.bmp $R/bootlogo.jpg $M/bootanimation.zip; sync
    exit 1
  fi
  echo ""
  echo "Installed: Lumen boot splash + $2."
  echo "panel_config.ini untouched (fingerprint $after)."
  status
}

case "$MODE" in
  install-black)
    install black.zip "quiet black animation"
    echo ""; echo "Restart the projector to see it."
    ;;
  install-ember)
    install ember.zip "Lumen ember animation"
    echo ""; echo "Restart the projector to see it."
    ;;
  install-timing)
    if [ $H713 = 0 ]; then
      echo "  The Lumen hold is made for the HY300 family's 720p switch; this projector ($PLAT) doesn't"
      echo "  have it. Use 1 or 2 instead. Nothing changed."
      exit 3
    fi
    install timing.zip "timing check (counting numbers)"
    ;;
  install-hold)
    [ $H713 = 0 ] && { echo "  The Lumen hold is for the HY300 family only. Nothing changed."; exit 3; }
    N="$2"
    phys=$(wm size | sed -n 's/^Physical size: //p')
    if [ "$phys" = "1280x720" ]; then
      f=hold-flat.zip            # screen already 720p: nothing switches mid-boot
    elif [ "$phys" != "1920x1080" ]; then
      echo "This projector's screen is $phys, which the Lumen hold isn't made for. Nothing changed."
      exit 1
    elif [ "$N" = "0" ]; then
      f=hold-full.zip            # no switch seen
    else
      f=hold.zip                 # the PC pushed the file matching the number
    fi
    install $f "Lumen hold (logo stays on until the intro; timing $N s)"
    echo ""; echo "Restart the projector to see it."
    ;;
  restore)
    rm -f $M/bootanimation.zip 2>/dev/null
    if [ $H713 = 1 ]; then
      rm -f $R/advert.bmp $R/bootlogo.jpg
      for f in advert.bmp bootlogo.jpg; do
        if [ -f "$B/orig_$f" ]; then cp "$B/orig_$f" "$R/$f"; fi
      done
    fi
    if [ -f "$B/orig_bootanimation.zip" ]; then cp "$B/orig_bootanimation.zip" "$M/bootanimation.zip"; fi
    rm -f $B/logo_orig.done $B/orig_advert.bmp $B/orig_bootlogo.jpg $B/orig_bootanimation.zip $B/logo_style
    sync
    echo ""
    echo "Restored the projector's own boot logo and animation."
    status
    ;;
  status)
    echo ""
    status
    ;;
  *) echo "Unknown option: $MODE"; exit 1 ;;
esac
