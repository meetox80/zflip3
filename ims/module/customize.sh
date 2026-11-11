#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install from the Magisk app in Android, not from recovery."
[ "$ARCH" = arm64 ] || abort "This module requires ARM64."
[ "$(getprop ro.product.device)" = b2q ] || abort "This module is only for the Galaxy Z Flip3 (b2q)."
find /data/system/package_cache \( -name 'imsservice-*' -o -name 'Flip3Ims-*' \) -delete
set_perm_recursive "$MODPATH/system" 0 0 0755 0644 u:object_r:system_file:s0
set_perm "$MODPATH/system/bin/imsd" 0 2000 0755 u:object_r:system_file:s0
set_perm "$MODPATH/system/bin/multiclientd" 0 2000 0755 u:object_r:system_file:s0
set_perm "$MODPATH/imsdstart" 0 0 0755
set_perm "$MODPATH/multiclientstart" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
ui_print "Reboot to apply."
