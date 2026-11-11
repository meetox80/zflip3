package dev.kotwicha.zflip3;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TileStore {
    static final String MUSIC = "music";
    static final String ALARM = "alarm";
    static final String SCHEDULE = "schedule";
    static final String WIDGET_PREFIX = "widget:";

    private static final String PREFS = "cover";
    private static final String KEY_ENABLED = "enable_widgets_list";
    private static final String KEY_CLOCK = "selected_clock_type";
    private static final String KEY_PALETTE = "selected_palette_index";
    private static final String SEPARATOR = ",";
    private static final String DEFAULT_TILES = MUSIC + SEPARATOR + ALARM + SEPARATOR + SCHEDULE;

    private final SharedPreferences prefs;

    TileStore(Context context) {
        prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<String> enabledTiles() {
        String stored = prefs.getString(KEY_ENABLED, DEFAULT_TILES);
        List<String> tiles = new ArrayList<>();
        if (!TextUtils.isEmpty(stored)) {
            tiles.addAll(Arrays.asList(stored.split(SEPARATOR)));
        }
        return tiles;
    }

    void setEnabledTiles(List<String> tiles) {
        prefs.edit().putString(KEY_ENABLED, TextUtils.join(SEPARATOR, tiles)).apply();
    }

    int clockType() {
        return prefs.getInt(KEY_CLOCK, ClockPage.TYPE_HORIZONTAL);
    }

    int paletteIndex() {
        return prefs.getInt(KEY_PALETTE, 0);
    }

    void setClock(int type, int palette) {
        prefs.edit().putInt(KEY_CLOCK, type).putInt(KEY_PALETTE, palette).apply();
    }

    static String widgetKey(int appWidgetId) {
        return WIDGET_PREFIX + appWidgetId;
    }

    static boolean isWidget(String key) {
        return key.startsWith(WIDGET_PREFIX);
    }

    static int widgetId(String key) {
        return Integer.parseInt(key.substring(WIDGET_PREFIX.length()));
    }
}
