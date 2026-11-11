#!/system/bin/sh
MODDIR=${0%/*}
ATTEMPTS="$MODDIR/boot_attempts"
COUNT=$(cat "$ATTEMPTS" 2>/dev/null)
case "$COUNT" in
  ''|*[!0-9]*) COUNT=0 ;;
esac
COUNT=$((COUNT + 1))
if [ "$COUNT" -ge 3 ]; then
  rm -f "$ATTEMPTS"
  touch "$MODDIR/disable"
  echo "Disabled after $COUNT boots that did not complete." > "$MODDIR/boot_guard.log"
  exit 0
fi
echo "$COUNT" > "$ATTEMPTS"
