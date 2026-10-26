#!/system/bin/sh
# LumenOS thermal check for the HY300 - samples the CPU and GPU sensors live and
# reports min / average / max, against the projector's own throttle limits.
# Read-only: it changes nothing. Runs via adb shell.

SECS="${1:-60}"
echo ""
echo "Sampling CPU and GPU temperature for ${SECS}s (leave something playing for a real reading)..."
echo ""

# Find the CPU and GPU thermal zones by their type name.
CPU_Z=""; GPU_Z=""
for z in /sys/class/thermal/thermal_zone*; do
  [ -r "$z/type" ] || continue
  t=$(cat "$z/type" 2>/dev/null)
  case "$t" in
    *cpu*|*CPU*)   [ -z "$CPU_Z" ] && CPU_Z="$z" ;;
    *gpu*|*GPU*|*mali*) [ -z "$GPU_Z" ] && GPU_Z="$z" ;;
  esac
done

read_c() {   # prints temperature in whole degrees C ("" if unreadable)
  [ -n "$1" ] && [ -r "$1/temp" ] || return
  v=$(cat "$1/temp" 2>/dev/null)
  [ -n "$v" ] || return
  # values are usually millidegrees (e.g. 81700); some are already in C
  if [ "$v" -gt 1000 ] 2>/dev/null; then echo $((v/1000)); else echo "$v"; fi
}

cmin=999; cmax=0; csum=0; gmin=999; gmax=0; gsum=0; n=0
i=0
while [ $i -lt "$SECS" ]; do
  c=$(read_c "$CPU_Z"); g=$(read_c "$GPU_Z")
  if [ -n "$c" ]; then
    [ "$c" -lt "$cmin" ] && cmin=$c; [ "$c" -gt "$cmax" ] && cmax=$c; csum=$((csum+c))
  fi
  if [ -n "$g" ]; then
    [ "$g" -lt "$gmin" ] && gmin=$g; [ "$g" -gt "$gmax" ] && gmax=$g; gsum=$((gsum+g))
  fi
  n=$((n+1))
  # a tiny live readout every 10s
  if [ $((i % 10)) -eq 0 ]; then echo "  ${i}s:  CPU ${c:-?}C   GPU ${g:-?}C"; fi
  sleep 1
  i=$((i+1))
done

echo ""
echo "================= Thermal report ================="
if [ "$n" -gt 0 ] && [ "$cmax" -gt 0 ]; then
  echo "CPU   min ${cmin}C   avg $((csum/n))C   max ${cmax}C"
else
  echo "CPU   (sensor not readable on this build)"
fi
if [ "$n" -gt 0 ] && [ "$gmax" -gt 0 ]; then
  echo "GPU   min ${gmin}C   avg $((gsum/n))C   max ${gmax}C"
else
  echo "GPU   (sensor not readable on this build)"
fi
echo ""
echo "This projector's own limits (from its firmware):"
echo "  CPU   throttles at 75C, harder at 85C, emergency at 115C"
echo "  GPU   throttles at 85C, emergency at 110C"
echo ""
if [ "$cmax" -ge 85 ] 2>/dev/null; then
  echo "  Your CPU peaked at ${cmax}C, past the 85C hard-throttle point. That is the stutter:"
  echo "  the chip is slowing itself to cool down. Better airflow around the vents and the"
  echo "  720p render (already set by LumenOS) are what help most."
elif [ "$cmax" -ge 75 ] 2>/dev/null; then
  echo "  Your CPU peaked at ${cmax}C, into the first throttle band (75C). Normal for this"
  echo "  projector under load; keep the vents clear and it stays out of the harder band."
elif [ "$cmax" -gt 0 ] 2>/dev/null; then
  echo "  Your CPU stayed under 75C the whole time - no throttling. Good."
fi
echo "=================================================="
