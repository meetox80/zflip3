#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

LIBRARIES=(
  libaresdns.so
  vendor.samsung.hardware.radio-V1-ndk.so
  vendor.samsung.hardware.radio.bridge-V1-ndk.so
  vendor.samsung.hardware.radio.bridge@2.0.so
  vendor.samsung.hardware.radio.bridge@2.1.so
)
JARS=(imsmanager vsimmanager EpdgManager rcsopenapi)

STOCK_FILES=(
  system/build.prop
  system/etc/floating_feature.xml
  system/priv-app/imsservice/imsservice.apk
  system/bin/imsd
  system/bin/multiclientd
  system/lib64/libsec-ims.so
)
for library in "${LIBRARIES[@]}"; do
  STOCK_FILES+=("system/lib64/$library")
done
for jar in "${JARS[@]}"; do
  STOCK_FILES+=("system/framework/$jar.jar")
done

need_toolchain
need_stock "${STOCK_FILES[@]}"
command -v zig >/dev/null || die "zig is needed to build the daemon launchers"

SRC="$ROOT/ims"
OUT="$ROOT/build/ims"
PKG="$OUT/pkg"
APK="$STOCK/system/priv-app/imsservice/imsservice.apk"
SEM_INT="$(sed -n 's/^ro\.build\.version\.sem=//p' "$STOCK/system/build.prop")"
[ -n "$SEM_INT" ] || die "ro.build.version.sem is missing from the stock build.prop"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/shim" "$PKG/system/priv-app/imsservice" "$PKG/system/lib64" "$PKG/system/bin" \
  "$PKG/system/etc/permissions" "$PKG/system/etc/sysconfig" "$PKG/system/product/overlay"

python3 -I "$ROOT/scripts/ims/gen_shim.py" "$ROOT/scripts/ims/patch_dex.py" "$SRC/missing-types.txt" "$APK" "$OUT/generated"
find "$SRC/shim/java" "$OUT/generated" -name '*.java' > "$OUT/sources.txt"
if ! javac -nowarn -source 17 -target 17 -cp "$SDK" -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log" >&2
  exit 1
fi
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$SDK" --min-api 34 --release --output "$OUT/shim" @"$OUT/classes.txt"

unzip -p "$APK" classes.dex > "$OUT/orig.dex"
unzip -p "$APK" AndroidManifest.xml > "$OUT/orig.xml"
python3 -I "$ROOT/scripts/ims/patch_dex.py" "$OUT/orig.dex" "$OUT/patched.dex" "$SEM_INT" | tail -1
python3 -I "$ROOT/scripts/ims/patch_manifest.py" "$OUT/orig.xml" "$OUT/AndroidManifest.xml" "$OUT/added-permissions.txt"
python3 -I - "$APK" "$STOCK/system/framework" "$OUT" "${JARS[@]}" <<'PY'
import sys
import zipfile

apk, framework, out, jars = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
source = zipfile.ZipFile(apk)
target = zipfile.ZipFile(out + "/unsigned.apk", "w")
for item in source.infolist():
    name = item.filename
    if name == "classes.dex" or name.startswith("META-INF/"):
        continue
    data = open(out + "/AndroidManifest.xml", "rb").read() if name == "AndroidManifest.xml" else source.read(name)
    target.writestr(name, data, compress_type=zipfile.ZIP_STORED if name == "resources.arsc" else zipfile.ZIP_DEFLATED)
target.write(out + "/shim/classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
target.write(out + "/patched.dex", "classes2.dex", compress_type=zipfile.ZIP_DEFLATED)
index = 3
for jar in jars:
    library = zipfile.ZipFile(framework + "/" + jar + ".jar")
    for name in sorted(n for n in library.namelist() if n.startswith("classes") and n.endswith(".dex")):
        target.writestr("classes%d.dex" % index, library.read(name), compress_type=zipfile.ZIP_DEFLATED)
        index += 1
target.close()
print("dex files:", index - 1)
PY
sign_apk "$OUT/unsigned.apk" "$PKG/system/priv-app/imsservice/imsservice.apk"
build_overlay "$SRC/overlay-phone" "$PKG/system/product/overlay/Flip3Ims-phone.apk"
build_overlay "$SRC/overlay-android" "$PKG/system/product/overlay/Flip3Ims-android.apk"
static_arm64 "$SRC/native/imsdstart.c" "$PKG/imsdstart"
static_arm64 "$SRC/native/multiclientstart.c" "$PKG/multiclientstart"
cp -r "$SRC/module/." "$PKG/"

python3 -I "$ROOT/scripts/ims/patch_native.py" "$STOCK/system/lib64/libsec-ims.so" "$PKG/system/lib64/libsec-ims.so"
for library in "${LIBRARIES[@]}"; do
  cp "$STOCK/system/lib64/$library" "$PKG/system/lib64/"
done
cp "$STOCK/system/bin/imsd" "$STOCK/system/bin/multiclientd" "$PKG/system/bin/"
python3 -I "$ROOT/scripts/ims/check_multiclient.py" "$PKG/system/bin/multiclientd" "$(sed -n 's/^MULTICLIENT_ROW=//p' "$SRC/module/service.sh")"
cp "$STOCK/system/etc/floating_feature.xml" "$PKG/system/etc/"

"$BT/aapt2" dump permissions "$APK" | sed -nE "s/^uses-permission[^:]*: name='([^']+)'.*/\1/p" > "$OUT/requested-permissions.txt"
python3 -I - "$OUT/requested-permissions.txt" "$OUT/added-permissions.txt" "$PKG/system/etc/permissions/privapp-permissions-imsservice.xml" <<'PY'
import sys

names = sorted(set(open(sys.argv[1]).read().split()) | set(open(sys.argv[2]).read().split()))
lines = ['<?xml version="1.0" encoding="utf-8"?>', "<permissions>", '    <privapp-permissions package="com.sec.imsservice">']
lines += ['        <permission name="%s" />' % name for name in names]
lines += ["    </privapp-permissions>", "</permissions>", ""]
open(sys.argv[3], "w").write("\n".join(lines))
print("privileged permission entries:", len(names))
PY
cat > "$PKG/system/etc/sysconfig/imsservice-sysconfig.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<config>
    <hidden-api-whitelisted-app package="com.sec.imsservice" />
    <allow-in-power-save package="com.sec.imsservice" />
    <allow-in-data-usage-save package="com.sec.imsservice" />
</config>
XML

make_zip "$PKG" "$DIST/flip3-ims.zip"
echo "$DIST/flip3-ims.zip"
