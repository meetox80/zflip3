ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/tools"
BT="$TOOLS/build-tools"
SDK="$TOOLS/platform/android.jar"
STOCK="$ROOT/stock"
DIST="$ROOT/dist"
KEY="$ROOT/signing/cover.jks"
KEY_PASS=flip3cover

export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"

die() {
  echo "$*" >&2
  exit 1
}

need_toolchain() {
  [ -x "$BT/aapt2" ] && [ -f "$SDK" ] && [ -x "$JAVA_HOME/bin/javac" ] || die "Toolchain missing. Run scripts/setup-toolchain.sh first."
}

need_stock() {
  local file
  for file in "$@"; do
    [ -f "$STOCK/$file" ] || die "stock/$file is missing. Run scripts/extract-stock.sh with your firmware first."
  done
}

ensure_key() {
  [ -f "$KEY" ] && return
  mkdir -p "$(dirname "$KEY")"
  keytool -genkeypair -keystore "$KEY" -alias cover -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEY_PASS" -keypass "$KEY_PASS" -dname "CN=Flip3 Cover" >/dev/null 2>&1
  echo "Created signing key $KEY"
}

sign_apk() {
  ensure_key
  "$BT/zipalign" -f -p 4 "$1" "$1.aligned"
  "$BT/apksigner" sign --ks "$KEY" --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --v4-signing-enabled false --out "$2" "$1.aligned"
  rm -f "$1.aligned"
}

build_overlay() {
  local src=$1 out=$2 work
  work="$(mktemp -d)"
  "$BT/aapt2" compile --dir "$src/res" -o "$work/res.zip"
  "$BT/aapt2" link -I "$SDK" --manifest "$src/AndroidManifest.xml" \
    --min-sdk-version 34 --target-sdk-version 36 --version-code 1 --version-name 1.0 \
    -o "$work/unsigned.apk" "$work/res.zip"
  sign_apk "$work/unsigned.apk" "$out"
  rm -rf "$work"
}

static_arm64() {
  zig cc -target aarch64-linux-musl -static -Os -s -Wall -Wextra -o "$2" "$1"
}

make_zip() {
  local dir=$1 out=$2
  mkdir -p "$(dirname "$out")" "$dir/META-INF/com/google/android"
  cp "$ROOT/scripts/magisk/update-binary" "$ROOT/scripts/magisk/updater-script" "$dir/META-INF/com/google/android/"
  rm -f "$out"
  (cd "$dir" && python3 -I -m zipfile -c "$out" ./*)
}
