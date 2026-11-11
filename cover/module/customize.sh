#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install from the Magisk app in Android, not from recovery."
[ "$ARCH" = arm64 ] || abort "This module requires ARM64."
[ "$(getprop ro.product.device)" = b2q ] || abort "This module is only for the Galaxy Z Flip3 (b2q)."
[ -d /vendor/etc/displayconfig ] || abort "The vendor display configuration was not found."
LAYOUT=system/vendor/etc/displayconfig/display_layout_configuration.xml
set_perm "$MODPATH/$LAYOUT" 0 0 0644 u:object_r:vendor_configs_file:s0
set_perm "$MODPATH/system/product/overlay/Flip3CoverPrivate.apk" 0 0 0644 u:object_r:system_file:s0
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/coverd.sh" 0 0 0755
set_perm "$MODPATH/coverkeys" 0 0 0755
set_perm "$MODPATH/Flip3Cover.apk" 0 0 0644
ui_print "Reboot to apply. Android stays on the main screen when the phone is folded."
