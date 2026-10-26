#!/system/bin/sh
# LumenOS undo - puts back everything lumenos.sh changed.
B=/data/local/tmp/lumenos
L=com.lumen.launcher
if [ ! -f $B/backup.done ]; then
  echo "No LumenOS backup found on this projector, so there is nothing to undo."
  exit 1
fi
echo "=== Undo $(date) ===" >> $B/lumenos.log
say() { echo "  $*"; echo "$*" >> $B/lumenos.log; }

# LumenOS Speed (memory tuning, held-back apps), if it was applied
[ -f $B/speed.done ] && [ -f $B/speed.sh ] && sh $B/speed.sh undo

echo "[1/4] Bringing back removed apps"
while read -r p; do
  [ -n "$p" ] && cmd package install-existing --user 0 $p >/dev/null 2>&1 && say "restored $p"
done < $B/removed.txt

echo "[2/4] Turning the stock launcher back on"
while read -r p; do
  [ -n "$p" ] && pm enable --user 0 $p >/dev/null 2>&1 && say "enabled  $p"
done < $B/disabled.txt
first=$(head -n 1 $B/disabled.txt)
if [ -n "$first" ]; then
  comp=$(cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep "^$first/" | head -n 1)
  [ -n "$comp" ] && cmd package set-home-activity "$comp" >/dev/null 2>&1 && say "home set back to $comp"
fi
appops set $L SYSTEM_ALERT_WINDOW default
pm revoke $L android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
pm revoke $L com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null
cmd notification disallow_listener $L/$L.MediaWatch >/dev/null 2>&1

echo "[3/4] Restoring screen size and settings"
s=$(cat $B/wm_size.txt); d=$(cat $B/wm_density.txt)
if [ -n "$s" ]; then wm size $s; else wm size reset; fi
if [ -n "$d" ]; then wm density $d; else wm density reset; fi
while read -r ns k v; do
  [ -z "$k" ] && continue
  if [ "$v" = "null" ] || [ -z "$v" ]; then settings delete $ns $k >/dev/null 2>&1
  else settings put $ns $k "$v"; fi
done < $B/settings.txt
say "Settings restored."

echo "[4/4] Clearing the LumenOS record"
rm -f $B/backup.done $B/removed.txt $B/disabled.txt $B/settings.txt $B/wm_size.txt $B/wm_density.txt $B/review.txt
say "Undo complete. Lumen itself stays installed as a normal app."
echo ""
echo "Everything is back the way it was. The projector will restart now."
