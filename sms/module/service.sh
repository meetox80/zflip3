#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
LAST=
while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
  PID=$(pidof rild)
  if [ -n "$PID" ] && [ "$PID" != "$LAST" ]; then
    sleep 5
    if CLASSPATH="$MODDIR/smsready.jar" app_process /system/bin SmsReady > "$MODDIR/smsready.log" 2>&1; then
      LAST=$PID
    fi
  fi
  sleep 30
done
