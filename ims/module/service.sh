#!/system/bin/sh
MODDIR=${0%/*}
MULTICLIENT_ROW=55208
WAITED=0
until [ "$(getprop sys.boot_completed)" = 1 ]; do
  sleep 5
  WAITED=$((WAITED + 5))
  if [ "$WAITED" -ge 240 ]; then
    touch "$MODDIR/disable"
    echo "Disabled because Android had not finished booting after $WAITED seconds." > "$MODDIR/boot_guard.log"
    reboot
    exit 0
  fi
done
rm -f "$MODDIR/boot_attempts"
for permission in READ_PHONE_STATE READ_PHONE_NUMBERS ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  pm grant com.sec.imsservice "android.permission.$permission" >/dev/null 2>&1
done
IMS_UID=$(stat -c %u /data/data/com.sec.imsservice)
printf "$(printf '\\%03o\\%03o' $((IMS_UID & 255)) $((IMS_UID >> 8)))" | dd of="$MODDIR/system/bin/multiclientd" bs=1 seek="$MULTICLIENT_ROW" conv=notrunc 2>/dev/null
(
  while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
    "$MODDIR/multiclientstart" >> "$MODDIR/multiclient.log" 2>&1
    sleep 5
  done
) &
while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
  "$MODDIR/imsdstart" >> "$MODDIR/imsd.log" 2>&1
  sleep 5
done
