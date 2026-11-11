package dev.kotwicha.zflip3;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SongTheme implements MediaSessionManager.OnActiveSessionsChangedListener {
    private static final String TAG = "Flip3Cover";
    private static final String SETTING = "theme_customization_overlay_packages";
    private static final String KEY_SOURCE = "android.theme.customization.color_source";
    private static final String KEY_PALETTE = "android.theme.customization.system_palette";
    private static final String KEY_ACCENT = "android.theme.customization.accent_color";
    private static final String KEY_STYLE = "android.theme.customization.theme_style";
    private static final String KEY_TIMESTAMP = "_applied_timestamp";
    private static final String[] THEME_KEYS = {KEY_SOURCE, KEY_PALETTE, KEY_ACCENT, KEY_STYLE};
    private static final String[] ART_KEYS = {
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON
    };
    private static final String[] ART_URI_KEYS = {
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
    };
    private static final String PREFS = "song_theme";
    private static final String PREF_ACTIVE = "active";
    private static final String PREF_SEED = "seed";
    private static final String PREF_SAVED = "saved_";
    private static final long SETTLE_MS = 1200;
    private static final int HASH_GRID = 6;
    private static final long RESTORE_MS = 120000;
    private static final float MIN_SEED_SATURATION = 0.2f;
    private static final float MIN_SEED_VALUE = 0.15f;
    private static final float MIN_COLORFUL_SHARE = 0.01f;
    private static final int SAMPLE_SIZE = 96;
    private static final int HUE_BUCKETS = 36;

    private final Context context;
    private final ComponentName component;
    private final MediaSessionManager sessions;
    private final DisplayManager displays;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<MediaController> controllers = new ArrayList<>();
    private final Runnable restore = this::restoreTheme;
    private final Runnable applyPending = this::applyPendingArt;
    private final DisplayManager.DisplayListener mainDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                flushDeferred();
            }
        }
    };
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            MediaController playing = findPlaying();
            if (!Objects.equals(playing != null ? playing.getSessionToken() : null, lastPlaying)) {
                evaluate();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            evaluate();
        }

        @Override
        public void onSessionDestroyed() {
            evaluate();
        }
    };

    private MediaSession.Token lastPlaying;
    private MediaMetadata pending;
    private String appliedKey;
    private String pendingKey;
    private Integer deferredSeed;
    private String deferredTitle;
    private boolean deferredRestore;

    SongTheme(Context context, ComponentName component) {
        this.context = context;
        this.component = component;
        sessions = context.getSystemService(MediaSessionManager.class);
        displays = context.getSystemService(DisplayManager.class);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void start() {
        displays.registerDisplayListener(mainDisplayListener, handler);
        sessions.addOnActiveSessionsChangedListener(this, component, handler);
        List<MediaController> active = sessions.getActiveSessions(component);
        Log.i(TAG, "Watching media sessions, active: " + active.size());
        onActiveSessionsChanged(active);
    }

    void stop() {
        displays.unregisterDisplayListener(mainDisplayListener);
        sessions.removeOnActiveSessionsChangedListener(this);
        clearControllers();
        handler.removeCallbacks(applyPending);
        handler.removeCallbacks(restore);
        worker.shutdown();
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> active) {
        clearControllers();
        if (active != null) {
            for (MediaController controller : active) {
                controller.registerCallback(callback, handler);
                controllers.add(controller);
            }
        }
        evaluate();
    }

    private void clearControllers() {
        for (MediaController controller : controllers) {
            controller.unregisterCallback(callback);
        }
        controllers.clear();
    }

    private void evaluate() {
        MediaController playing = findPlaying();
        lastPlaying = playing != null ? playing.getSessionToken() : null;
        if (playing == null) {
            handler.removeCallbacks(applyPending);
            pendingKey = null;
            if (prefs.getBoolean(PREF_ACTIVE, false) && !handler.hasCallbacks(restore)) {
                handler.postDelayed(restore, RESTORE_MS);
            }
            return;
        }
        handler.removeCallbacks(restore);
        deferredRestore = false;
        MediaMetadata metadata = playing.getMetadata();
        if (metadata == null) {
            return;
        }
        String key = describe(metadata);
        if (key.equals(appliedKey) || key.equals(pendingKey)) {
            return;
        }
        pending = metadata;
        pendingKey = key;
        Log.i(TAG, "New track: " + key);
        handler.removeCallbacks(applyPending);
        handler.postDelayed(applyPending, SETTLE_MS);
    }

    private MediaController findPlaying() {
        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && (state.getState() == PlaybackState.STATE_PLAYING || state.getState() == PlaybackState.STATE_BUFFERING)) {
                return controller;
            }
        }
        return null;
    }

    private String describe(MediaMetadata metadata) {
        Bitmap art = embeddedArt(metadata);
        return metadata.getString(MediaMetadata.METADATA_KEY_TITLE) + "|"
            + metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) + "|"
            + metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) + "|"
            + (art != null ? pixelHash(art) : artUri(metadata));
    }

    private static int pixelHash(Bitmap art) {
        int width = art.getWidth();
        int height = art.getHeight();
        int hash = width * 31 + height;
        if (art.getConfig() == Bitmap.Config.HARDWARE) {
            return hash;
        }
        for (int y = 0; y < HASH_GRID; y++) {
            for (int x = 0; x < HASH_GRID; x++) {
                hash = hash * 31 + art.getPixel(x * width / HASH_GRID, y * height / HASH_GRID);
            }
        }
        return hash;
    }

    private void applyPendingArt() {
        MediaMetadata metadata = pending;
        String key = pendingKey;
        if (metadata == null || worker.isShutdown()) {
            return;
        }
        worker.execute(() -> {
            Bitmap art = loadArt(metadata);
            if (art == null) {
                Log.i(TAG, "No artwork for " + metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                return;
            }
            Integer picked = pickSeed(art);
            if (picked == null) {
                Log.i(TAG, "Artwork has no usable color, keeping current theme");
                handler.post(() -> appliedKey = key);
                return;
            }
            int seed = picked;
            Log.i(TAG, String.format(Locale.ROOT, "Artwork %dx%d seed %06X", art.getWidth(), art.getHeight(), seed & 0xFFFFFF));
            handler.post(() -> {
                appliedKey = key;
                if (key.equals(pendingKey)) {
                    pendingKey = null;
                }
                writeTheme(seed, metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            });
        });
    }

    private static Bitmap embeddedArt(MediaMetadata metadata) {
        for (String key : ART_KEYS) {
            Bitmap art = metadata.getBitmap(key);
            if (art != null && !art.isRecycled()) {
                return art;
            }
        }
        return null;
    }

    private static String artUri(MediaMetadata metadata) {
        for (String key : ART_URI_KEYS) {
            String uri = metadata.getString(key);
            if (!TextUtils.isEmpty(uri)) {
                return uri;
            }
        }
        return null;
    }

    private Bitmap loadArt(MediaMetadata metadata) {
        Bitmap embedded = embeddedArt(metadata);
        if (embedded != null) {
            return embedded.copy(Bitmap.Config.ARGB_8888, false);
        }
        String uri = artUri(metadata);
        if (uri == null) {
            return null;
        }
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme) && !"android.resource".equals(scheme)) {
            return null;
        }
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), parsed);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        } catch (Exception unreadable) {
            Log.w(TAG, "Could not read artwork " + uri, unreadable);
            return null;
        }
    }

    private static Integer pickSeed(Bitmap art) {
        Bitmap small = Bitmap.createScaledBitmap(art, SAMPLE_SIZE, SAMPLE_SIZE, true);
        int[] pixels = new int[SAMPLE_SIZE * SAMPLE_SIZE];
        small.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
        float[] weight = new float[HUE_BUCKETS];
        float[] red = new float[HUE_BUCKETS];
        float[] green = new float[HUE_BUCKETS];
        float[] blue = new float[HUE_BUCKETS];
        float[] hsv = new float[3];
        int colorful = 0;
        for (int pixel : pixels) {
            Color.colorToHSV(pixel, hsv);
            if (hsv[1] < MIN_SEED_SATURATION || hsv[2] < MIN_SEED_VALUE) {
                continue;
            }
            colorful++;
            int bucket = (int) (hsv[0] / 360f * HUE_BUCKETS) % HUE_BUCKETS;
            float score = hsv[1] * hsv[2];
            weight[bucket] += score;
            red[bucket] += Color.red(pixel) * score;
            green[bucket] += Color.green(pixel) * score;
            blue[bucket] += Color.blue(pixel) * score;
        }
        if (colorful < pixels.length * MIN_COLORFUL_SHARE) {
            return null;
        }
        int best = 0;
        float bestScore = -1f;
        for (int bucket = 0; bucket < HUE_BUCKETS; bucket++) {
            float neighbours = weight[(bucket + 1) % HUE_BUCKETS] + weight[(bucket + HUE_BUCKETS - 1) % HUE_BUCKETS];
            float score = weight[bucket] + neighbours * 0.5f;
            if (weight[bucket] > 0f && score > bestScore) {
                bestScore = score;
                best = bucket;
            }
        }
        float total = weight[best];
        return Color.rgb((int) (red[best] / total), (int) (green[best] / total), (int) (blue[best] / total));
    }

    private boolean isMainScreenOn() {
        Display main = displays.getDisplay(Display.DEFAULT_DISPLAY);
        return main != null && main.getState() == Display.STATE_ON;
    }

    private void flushDeferred() {
        if (isMainScreenOn()) {
            return;
        }
        if (deferredSeed != null) {
            int seed = deferredSeed;
            String title = deferredTitle;
            deferredSeed = null;
            deferredTitle = null;
            writeTheme(seed, title);
        } else if (deferredRestore) {
            deferredRestore = false;
            restoreTheme();
        }
    }

    private void writeTheme(int seed, String title) {
        deferredRestore = false;
        if (isMainScreenOn()) {
            deferredSeed = seed;
            deferredTitle = title;
            Log.i(TAG, String.format(Locale.ROOT, "Holding song theme %06X until the main screen is off", seed & 0xFFFFFF));
            return;
        }
        boolean active = prefs.getBoolean(PREF_ACTIVE, false);
        if (active && isSimilar(prefs.getInt(PREF_SEED, 0), seed)) {
            Log.i(TAG, "Keeping current theme, new color is close");
            return;
        }
        try {
            JSONObject json = readSetting();
            SharedPreferences.Editor editor = prefs.edit();
            if (!active) {
                for (String key : THEME_KEYS) {
                    editor.putString(PREF_SAVED + key, json.has(key) ? json.getString(key) : null);
                }
            }
            String hex = String.format(Locale.ROOT, "%06X", seed & 0xFFFFFF);
            json.put(KEY_SOURCE, "preset");
            json.put(KEY_PALETTE, hex);
            json.put(KEY_ACCENT, hex);
            json.put(KEY_STYLE, "CONTENT");
            json.put(KEY_TIMESTAMP, System.currentTimeMillis());
            if (Settings.Secure.putString(context.getContentResolver(), SETTING, json.toString())) {
                editor.putBoolean(PREF_ACTIVE, true).putInt(PREF_SEED, seed).apply();
                Log.i(TAG, "Song theme " + hex + " from " + title);
            } else {
                Log.e(TAG, "Settings rejected song theme " + hex);
            }
        } catch (JSONException | SecurityException failed) {
            Log.e(TAG, "Could not write song theme", failed);
        }
    }

    private void restoreTheme() {
        deferredSeed = null;
        deferredTitle = null;
        if (!prefs.getBoolean(PREF_ACTIVE, false)) {
            return;
        }
        if (isMainScreenOn()) {
            deferredRestore = true;
            Log.i(TAG, "Holding theme restore until the main screen is off");
            return;
        }
        try {
            JSONObject json = readSetting();
            for (String key : THEME_KEYS) {
                String saved = prefs.getString(PREF_SAVED + key, null);
                if (saved == null) {
                    json.remove(key);
                } else {
                    json.put(key, saved);
                }
            }
            json.put(KEY_TIMESTAMP, System.currentTimeMillis());
            if (Settings.Secure.putString(context.getContentResolver(), SETTING, json.toString())) {
                prefs.edit().clear().apply();
                appliedKey = null;
                Log.i(TAG, "Restored theme after playback stopped");
            }
        } catch (JSONException | SecurityException failed) {
            Log.e(TAG, "Could not restore theme", failed);
        }
    }

    private JSONObject readSetting() throws JSONException {
        String value = Settings.Secure.getString(context.getContentResolver(), SETTING);
        return TextUtils.isEmpty(value) ? new JSONObject() : new JSONObject(value);
    }

    private static boolean isSimilar(int a, int b) {
        float[] first = new float[3];
        float[] second = new float[3];
        Color.colorToHSV(a, first);
        Color.colorToHSV(b, second);
        float hue = Math.abs(first[0] - second[0]);
        hue = Math.min(hue, 360f - hue);
        return hue <= 6f && Math.abs(first[1] - second[1]) <= 0.06f && Math.abs(first[2] - second[2]) <= 0.1f;
    }
}
