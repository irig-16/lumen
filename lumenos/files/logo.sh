#!/system/bin/sh
# LumenOS logo installer for the HY300 (runs via adb shell).
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

S=/data/local/tmp/lumenlogo
R=/Reserve0
M=/oem/media
B=/data/local/tmp/lumenos
mkdir -p $B
MODE="${1:-status}"

fp() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo none; }

status() {
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

# install <animation zip in $S> <description>
install() {
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
    install timing.zip "timing check (counting numbers)"
    ;;
  install-hold)
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
    rm -f $R/advert.bmp $R/bootlogo.jpg $M/bootanimation.zip
    for f in advert.bmp bootlogo.jpg; do
      if [ -f "$B/orig_$f" ]; then cp "$B/orig_$f" "$R/$f"; fi
    done
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
