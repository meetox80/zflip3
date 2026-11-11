#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

need_toolchain
OUT="$ROOT/build/signout"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$DIST"
javac -nowarn -source 17 -target 17 -cp "$SDK" -d "$OUT/classes" "$ROOT/ims/signout/ImsSignout.java"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$SDK" --min-api 34 --release --output "$DIST/imssignout.jar" @"$OUT/classes.txt"
echo "$DIST/imssignout.jar"
