#!/system/bin/sh
# LumenOS logo check - READ-ONLY. Looks for where the boot logo lives on this
# projector and whether ADB could replace it. Writes nothing to those partitions;
# it only copies any pictures it finds to /data/local/tmp/logocheck for the PC to pull.

PLAT="$(getprop ro.board.platform) $(getprop ro.hardware)"

O=/data/local/tmp/logocheck
rm -rf $O
mkdir -p $O
R=$O/report.txt

{
echo "=== LumenOS logo check  $(date) ==="
echo "Projector: $(getprop ro.product.brand) $(getprop ro.product.model) - $PLAT - Android $(getprop ro.build.version.release)"
echo ""
echo "--- mounts"
grep -iE "Reserve0|/oem |media_data|bootloader|logo|splash|resource" /proc/mounts
echo ""
for d in /Reserve0 /oem /oem/media; do
  echo "--- $d"
  if [ -d "$d" ]; then
    ls -laZ "$d" 2>&1 | head -40
    if [ -w "$d" ]; then echo ">> $d is WRITABLE from adb"; else echo ">> $d is not writable from adb"; fi
  else
    echo "(not present)"
  fi
  echo ""
done
echo "--- block devices (names only)"
for b in /dev/block/by-name/*; do
  case "$b" in *bootloader*|*Reserve0*|*media_data*|*logo*|*splash*|*resource*|*misc*) echo "  $b  ($(ls -lL "$b" 2>/dev/null | cut -c1-10))" ;; esac
done
echo ""
echo "--- boot animation"
ls -la /system/media/bootanimation.zip /product/media/bootanimation.zip /oem/media/bootanimation.zip /vendor/media/bootanimation.zip /data/local/bootanimation.zip 2>&1 | grep -v "No such"
echo ""
echo "--- pictures found (copied for the PC)"
} > $R 2>&1

n=0
for d in /Reserve0 /oem /oem/media; do
  [ -d "$d" ] || continue
  for f in "$d"/*; do
    case "$f" in
      *.bmp|*.BMP|*.jpg|*.JPG|*.jpeg|*.png|*.PNG|*.zip)
        if [ -r "$f" ]; then
          base=$(echo "$d" | tr '/' '_')_$(basename "$f")
          cp "$f" "$O/$base" 2>/dev/null && { echo "  $f  ($(wc -c < "$f") bytes)" >> $R; n=$((n+1)); }
        else
          echo "  $f  (exists but not readable)" >> $R
        fi
        ;;
    esac
  done
done
[ $n -eq 0 ] && echo "  (none readable)" >> $R

{
echo ""
echo "--- verdict"
if [ -d /oem/media ] && [ -w /oem/media ]; then echo "  Boot animation: CAN be changed (LumenOS-Logo.bat, options 1 or 2)."
else echo "  Boot animation: locked (no writable /oem/media) - needs a firmware flash to change."; fi
if [ -d /Reserve0 ] && [ -w /Reserve0 ]; then echo "  Boot splash: Reserve0 is writable (HY300-style loader logo)."
else echo "  Boot splash: in the locked loader/firmware on this projector."; fi
} >> $R

cat $R
echo ""
echo "Done. Nothing was changed on the projector."
