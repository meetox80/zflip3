#!/system/bin/sh
MODDIR=${0%/*}
LOG="$MODDIR/coverd.log"
LOCK=/dev/flip3-coverd.lock
mkdir "$LOCK" 2>/dev/null || exit 0
while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
  APK=$(pm path dev.kotwicha.zflip3 2>/dev/null | head -1 | cut -d: -f2)
  if [ -n "$APK" ]; then
    [ ! -f "$LOG" ] || [ "$(wc -c < "$LOG")" -lt 262144 ] || mv -f "$LOG" "$LOG.1"
    CLASSPATH="$APK" app_process /system/bin dev.kotwicha.zflip3.CoverDaemon >> "$LOG" 2>&1
  fi
  sleep 5
done
rmdir "$LOCK" 2>/dev/null
