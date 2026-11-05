#!/system/bin/sh
# LumenOS Rescue - for a projector stuck on its boot logo after LumenOS.
# Puts the projector's own launcher back, switches Lumen off (it stays installed) and undoes
# what LumenOS changed. Works while the projector is still on its boot logo. Nothing is flashed.
B=/data/local/tmp/lumenos
L=com.lumen.launcher

echo "[1/4] Putting back what LumenOS changed"
UNDONE=0
if [ -f $B/backup.done ] && [ -f /data/local/tmp/lumenos-undo.sh ]; then
  sh /data/local/tmp/lumenos-undo.sh 2>/dev/null | grep -v "restart now" && UNDONE=1
else
  echo "  No LumenOS record on this projector; doing the essentials below."
fi

echo "[2/4] Turning the projector's own launcher back on"
for p in $(pm list packages -d 2>/dev/null | sed 's/^package://'); do
  case "$p" in
    *[Ll]auncher*|*home*|*Home*|*desktop*|*tvlauncher*) pm enable "$p" >/dev/null 2>&1 && echo "  enabled $p" ;;
  esac
done

echo "[3/4] Switching Lumen off (it stays installed; nothing of yours is deleted)"
dpm remove-active-admin $L/.PowerAdmin >/dev/null 2>&1
out=$(pm disable-user --user 0 $L 2>&1); echo "$out" | grep -qi "disabled" || out=$(pm disable-user $L 2>&1)
if echo "$out" | grep -qi "disabled"; then
  echo "  Lumen is switched off."
elif pm uninstall $L >/dev/null 2>&1; then
  echo "  Lumen couldn't be switched off, so it was uninstalled."
else
  echo "  Lumen couldn't be switched off: $out"
fi
case "$(settings get secure screensaver_components 2>/dev/null)" in
  *lumen*) settings delete secure screensaver_components >/dev/null 2>&1 ;;
esac

echo "[4/4] Screen size"
if [ $UNDONE = 1 ]; then
  echo "  Restored by step 1."
else
  wm size reset >/dev/null 2>&1; wm density reset >/dev/null 2>&1
  echo "  Back to the projector's own."
fi
echo ""
echo "Rescue done."
