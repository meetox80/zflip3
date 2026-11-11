#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

need_toolchain
need_stock system/fonts/OneUISans-VF.ttf \
  system/priv-app/AODService_v80/AODService_v80.apk \
  system/system_ext/priv-app/SystemUI/SystemUI.apk
command -v zig >/dev/null || die "zig is needed to build coverkeys"

SRC="$ROOT/cover"
OUT="$ROOT/build/cover"
PKG="$OUT/pkg"
VERSION_NAME="$(sed -n 's/^version=//p' "$SRC/module/module.prop")"
VERSION_CODE="$(sed -n 's/^versionCode=//p' "$SRC/module/module.prop")"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res"
"$ROOT/scripts/cover/extract-assets.sh" "$OUT/stock"
cp -r "$SRC/app/res/." "$OUT/stock/res/." "$OUT/res/"

"$BT/aapt2" compile --dir "$OUT/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -I "$SDK" \
  --manifest "$SRC/app/AndroidManifest.xml" \
  -A "$OUT/stock/assets" \
  -0 ttf \
  --java "$OUT/gen" \
  --min-sdk-version 34 \
  --target-sdk-version 36 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  -o "$OUT/res.apk" \
  "$OUT/res.zip"

find "$SRC/app/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -nowarn -Xlint:none -proc:none -source 17 -target 17 -cp "$SDK" -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log" >&2
  exit 1
fi
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$SDK" --min-api 34 --release --output "$OUT/dex" @"$OUT/classes.txt"
python3 -I "$ROOT/scripts/cover/add-dex.py" "$OUT/res.apk" "$OUT/dex" "$OUT/unsigned.apk"

mkdir -p "$PKG/system/product/overlay"
cp -r "$SRC/module/." "$PKG/"
sign_apk "$OUT/unsigned.apk" "$PKG/Flip3Cover.apk"
build_overlay "$SRC/overlay" "$PKG/system/product/overlay/Flip3CoverPrivate.apk"
static_arm64 "$SRC/native/coverkeys.c" "$PKG/coverkeys"

make_zip "$PKG" "$DIST/flip3-cover.zip"
echo "$DIST/flip3-cover.zip"
