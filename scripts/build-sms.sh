#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

need_toolchain
SRC="$ROOT/sms"
OUT="$ROOT/build/sms"
PKG="$OUT/pkg"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$PKG"
javac -nowarn -source 17 -target 17 -cp "$SDK" -d "$OUT/classes" "$SRC/SmsReady.java"
"$BT/d8" --lib "$SDK" --min-api 34 --release --output "$PKG/smsready.jar" "$OUT/classes/SmsReady.class"
cp -r "$SRC/module/." "$PKG/"

make_zip "$PKG" "$DIST/flip3-smsready.zip"
echo "$DIST/flip3-smsready.zip"
