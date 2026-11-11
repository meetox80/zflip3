#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"
DL="$TOOLS/dl"
mkdir -p "$DL"

fetch() {
  local url=$1 file=$2 checker=$3 sum=$4
  if [ ! -f "$DL/$file" ]; then
    curl -fL --retry 3 -o "$DL/$file.part" "$url"
    mv "$DL/$file.part" "$DL/$file"
  fi
  echo "$sum  $DL/$file" | "$checker" -c -
}

unpack_zip() {
  local file=$1 dest=$2 tmp
  tmp="$(mktemp -d "$TOOLS/unpack.XXXXXX")"
  unzip -q "$DL/$file" -d "$tmp"
  rm -rf "$dest"
  mv "$tmp"/* "$dest"
  rmdir "$tmp"
}

echo "This downloads the Android SDK build tools and platform, which are covered by https://developer.android.com/studio/terms"

fetch "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz" \
  jdk21.tar.gz sha256sum d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c
fetch "https://dl.google.com/android/repository/build-tools_r36_linux.zip" \
  build-tools.zip sha1sum b0b6376977657e8ad9b969bacf4093601da2c6fb
fetch "https://dl.google.com/android/repository/platform-36_r02.zip" \
  platform.zip sha1sum 2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576

rm -rf "$TOOLS/jdk"
mkdir -p "$TOOLS/jdk"
tar -xzf "$DL/jdk21.tar.gz" -C "$TOOLS/jdk" --strip-components=1
unpack_zip build-tools.zip "$TOOLS/build-tools"
unpack_zip platform.zip "$TOOLS/platform"

"$TOOLS/jdk/bin/java" -version
"$TOOLS/build-tools/aapt2" version
JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH" "$TOOLS/build-tools/d8" --version
ls -la "$TOOLS/platform/android.jar"
echo "Toolchain ready in $TOOLS"
