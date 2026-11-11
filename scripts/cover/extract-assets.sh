#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../lib.sh"

OUT=$1
AOD="$STOCK/system/priv-app/AODService_v80/AODService_v80.apk"
SYSTEMUI="$STOCK/system/system_ext/priv-app/SystemUI/SystemUI.apk"
FONTS="$OUT/assets/fonts"
DRAWABLES="$OUT/res/drawable-nodpi"

rm -rf "$OUT"
mkdir -p "$FONTS" "$DRAWABLES"
cp "$STOCK/system/fonts/OneUISans-VF.ttf" "$FONTS/"
unzip -o -j -q "$AOD" assets/fonts/Clock2021.ttf -d "$FONTS"

aod() {
  unzip -p "$AOD" "res/$1.png" > "$DRAWABLES/$2.png"
}
aod CE page_notification_selected
aod Hi page_home_selected
aod Cq page_dot_selected
aod 0i ic_music_indicator
aod Wx face_num_index
aod Wt face_num_hour
aod O_1 face_num_min
aod ig face_num_sec
aod LU face_num_pin
aod lI1 face_dot_index
aod bL face_dot_hour
aod bD face_dot_min
aod Kj1 face_dot_sec
aod E2 face_dot_pin

last_frame() {
  local entry
  entry=$(unzip -Z1 "$SYSTEMUI" | grep -E "^res/drawable[^/]*/$1_[0-9]+\.webp$" | sort | tail -1)
  [ -n "$entry" ] || die "No frames for $1 in SystemUI.apk"
  unzip -p "$SYSTEMUI" "$entry" > "$DRAWABLES/$2.webp"
}
last_frame quick_panel_icon_wifi_on ic_tile_wifi
last_frame quick_panel_icon_airplane_mode_on ic_tile_airplane
last_frame quick_panel_icon_flashlight_on ic_tile_flashlight
unzip -p "$SYSTEMUI" res/drawable/quick_panel_icon_sound_vibrate_015.webp > "$DRAWABLES/ic_tile_vibrate.webp"
unzip -p "$SYSTEMUI" res/drawable/quick_panel_icon_sound_mute_008.webp > "$DRAWABLES/ic_tile_mute.webp"

python3 -I "$ROOT/scripts/cover/extract-icons.py" "$BT/aapt2" "$SYSTEMUI" "$AOD" "$OUT/res/drawable"

for file in "$DRAWABLES"/* "$FONTS"/*; do
  [ -s "$file" ] || die "$(basename "$file") came out empty. The AODService or SystemUI build differs from F711BXXSJJZH3."
done
