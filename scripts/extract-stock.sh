#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

FILES=(
  system/build.prop
  system/etc/floating_feature.xml
  system/fonts/OneUISans-VF.ttf
  system/priv-app/AODService_v80/AODService_v80.apk
  system/system_ext/priv-app/SystemUI/SystemUI.apk
  system/priv-app/imsservice/imsservice.apk
  system/framework/imsmanager.jar
  system/framework/vsimmanager.jar
  system/framework/EpdgManager.jar
  system/framework/rcsopenapi.jar
  system/bin/imsd
  system/bin/multiclientd
  system/lib64/libsec-ims.so
  system/lib64/libaresdns.so
  system/lib64/vendor.samsung.hardware.radio-V1-ndk.so
  system/lib64/vendor.samsung.hardware.radio.bridge-V1-ndk.so
  system/lib64/vendor.samsung.hardware.radio.bridge@2.0.so
  system/lib64/vendor.samsung.hardware.radio.bridge@2.1.so
)

[ $# -eq 1 ] && [ -f "$1" ] || die "usage: scripts/extract-stock.sh <AP_F711B...tar.md5 | super.img | system.img>"
for tool in debugfs lz4 simg2img lpunpack; do
  command -v "$tool" >/dev/null || die "$tool is not installed"
done

INPUT="$(realpath "$1")"
WORK="$STOCK/.work"
rm -rf "$WORK"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

magic() {
  od -An -tx1 -j "$2" -N "$3" "$1" | tr -d ' \n'
}

replace() {
  [ "$1" = "$INPUT" ] || rm -f "$1"
}

IMAGE="$INPUT"
if [ "$(magic "$IMAGE" 257 5)" = 7573746172 ]; then
  ENTRY="$(tar -tf "$IMAGE" 2>/dev/null | grep -E '^super\.img(\.lz4)?$' | head -1 || true)"
  [ -n "$ENTRY" ] || die "No super.img in $(basename "$IMAGE"). Pass the AP file from the firmware."
  echo "Unpacking $ENTRY"
  if [ "${ENTRY%.lz4}" != "$ENTRY" ]; then
    tar -xOf "$IMAGE" "$ENTRY" 2>/dev/null | lz4 -dc > "$WORK/super.img"
  else
    tar -xOf "$IMAGE" "$ENTRY" 2>/dev/null > "$WORK/super.img"
  fi
  IMAGE="$WORK/super.img"
fi

if [ "$(magic "$IMAGE" 0 4)" = 3aff26ed ]; then
  echo "Converting sparse image"
  simg2img "$IMAGE" "$WORK/raw.img"
  replace "$IMAGE"
  IMAGE="$WORK/raw.img"
fi

if [ "$(magic "$IMAGE" 4096 4)" = 67446c61 ]; then
  echo "Extracting system from super"
  lpunpack -p system "$IMAGE" "$WORK" >/dev/null || lpunpack -p system_a "$IMAGE" "$WORK" >/dev/null
  replace "$IMAGE"
  if [ -f "$WORK/system.img" ]; then
    IMAGE="$WORK/system.img"
  elif [ -f "$WORK/system_a.img" ]; then
    IMAGE="$WORK/system_a.img"
  else
    die "lpunpack did not produce a system image"
  fi
fi

[ "$(magic "$IMAGE" 1080 2)" = 53ef ] || die "$(basename "$IMAGE") is not an ext4 system image"

missing=0
for file in "${FILES[@]}"; do
  mkdir -p "$STOCK/$(dirname "$file")"
  rm -f "$STOCK/$file"
  debugfs -R "dump /$file $STOCK/$file" "$IMAGE" >/dev/null 2>&1 || true
  if [ -s "$STOCK/$file" ]; then
    echo "ok $file"
  else
    rm -f "$STOCK/$file"
    echo "missing $file" >&2
    missing=1
  fi
done
grep -E '^ro\.build\.(display\.id|version\.incremental)=' "$STOCK/system/build.prop" || true
[ "$missing" = 0 ] || die "Some files were not found. This firmware may not match the one these modules were built against."
