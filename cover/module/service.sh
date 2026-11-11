#!/system/bin/sh
MODDIR=${0%/*}
OVERLAY=com.android.shell:flip3_cover_no_mirror
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
if ! cmd overlay list android 2>/dev/null | grep -q "$OVERLAY"; then
  cmd overlay fabricate --target android --name flip3_cover_no_mirror android:bool/config_localDisplaysMirrorContent 0x12 0x00000000
fi
cmd overlay enable "$OVERLAY"
APK="$MODDIR/Flip3Cover.apk"
STAMP="$MODDIR/installed_apk"
CURRENT=$(sha256sum "$APK" | cut -d' ' -f1)
if [ "$CURRENT" != "$(cat "$STAMP" 2>/dev/null)" ] || [ -z "$(pm path dev.kotwicha.zflip3)" ]; then
  TEMP=/data/local/tmp/flip3-cover-install.apk
  cp "$APK" "$TEMP"
  chmod 644 "$TEMP"
  if pm install -r -d "$TEMP"; then
    echo "$CURRENT" > "$STAMP"
  fi
  rm -f "$TEMP"
fi
exec sh "$MODDIR/coverd.sh"
