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
resetprop -n ro.csc.sales_code PRT
resetprop -n ro.csc.countryiso_code PL
resetprop -n ro.csc.country_code POLAND
resetprop -n persist.omc.sales_code PRT
resetprop -n persist.sys.disable_rescue true
resetprop -n persist.dbg.volte_avail_ovr 1
resetprop -n persist.dbg.allow_ims_off 1
magiskpolicy --live \
  "allow priv_app default_android_service service_manager find" \
  "allow priv_app hal_telephony_hwservice hwservice_manager find" \
  "allow priv_app hidl_base_hwservice hwservice_manager find" \
  "allow priv_app hwservicemanager binder { call transfer }" \
  "allow hwservicemanager priv_app binder { call transfer }" \
  "allow hwservicemanager priv_app dir search" \
  "allow hwservicemanager priv_app file { read open }" \
  "allow hwservicemanager priv_app process getattr" \
  "allow priv_app hwbinder_device chr_file { read write open ioctl map getattr }" \
  "allow priv_app hwservicemanager_prop file { read open getattr map }" \
  "allow priv_app rild binder { call transfer }" \
  "allow rild priv_app binder { call transfer }" \
  "allow rild priv_app fd use" \
  "allow priv_app magisk unix_stream_socket connectto" \
  "allow priv_app magisk_file sock_file { read write getattr }" \
  "allow priv_app sec_efs_file dir { search read open getattr }" \
  "allow priv_app sec_efs_file file { read open getattr }"
