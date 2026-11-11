package dev.kotwicha.zflip3;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;

public final class CoverActivity extends Activity implements QuickPanel.Host, CoverRoot.KeyHandler, CoverNotificationListener.AlertTarget {
    private static final String TAG = "Flip3Cover";
    private static final int PANEL_LONG = 512;
    private static final int PANEL_SHORT = 260;
    private static final String PREFS = "cover";
    private static final String KEY_LEVEL = "brightness_level";
    private static final int BACKLIGHT_MAX = 510;
    private static final int BACKLIGHT_MIN = 6;
    private static final double BACKLIGHT_GAMMA = 1.6;
    private static final long SCREEN_TIMEOUT_MS = 15000;
    private static final long ALERT_MS = 3000;
    private static final long ALERT_SLEEP_MS = 3400;
    private static final long VOLUME_SETTLE_MS = 1000;
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sleepRunnable = this::requestSleep;
    private AudioManager audio;
    private SharedPreferences prefs;
    private CoverRoot root;
    private boolean resumed;
    private boolean glance;
    private long lastVolumeChange;
    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (root != null) {
                root.refreshPrivacy();
            }
        }
    };
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM, -1);
            boolean shown = stream == AudioManager.STREAM_MUSIC || stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_VOICE_CALL;
            if (root != null && resumed && shown) {
                lastVolumeChange = SystemClock.elapsedRealtime();
                showVolume(stream);
                armTimeout();
            }
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.densityDpi = DisplayMetrics.DENSITY_MEDIUM;
        config.fontScale = 1f;
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!isOnCoverPanel()) {
            Log.w(TAG, "Refusing to run outside the cover panel");
            finishAndRemoveTask();
            return;
        }
        audio = getSystemService(AudioManager.class);
        prefs = createDeviceProtectedStorageContext().getSharedPreferences(PREFS, MODE_PRIVATE);
        root = new CoverRoot(this, this);
        root.setKeyHandler(this);
        setContentView(root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.requestFocus();
        int level = prefs.getInt(KEY_LEVEL, QuickPanel.DEFAULT_LEVEL);
        root.getPanel().setBrightnessLevel(level);
        CoverCommands.write(this, CoverCommands.BRIGHTNESS, String.valueOf(backlightFor(level)));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> root.handleBack());
        handleVolumeIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        closeIfNotOnCoverPanel();
        armTimeout();
        CoverNotificationListener.setAlertTarget(this);
        if (root != null) {
            root.refreshPrivacy();
        }
        StatusBarNotification pending = CoverNotificationListener.takePendingAlert();
        if (pending != null && root != null) {
            root.showAlert(pending, true, ALERT_MS);
            handler.removeCallbacks(sleepRunnable);
            handler.postDelayed(sleepRunnable, ALERT_SLEEP_MS);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleVolumeIntent(intent);
        armTimeout();
    }

    private void handleVolumeIntent(Intent intent) {
        if (root == null || !intent.getBooleanExtra(CoverCommands.EXTRA_SHOW_VOLUME, false)) {
            return;
        }
        if (intent.getBooleanExtra(CoverCommands.EXTRA_VOLUME_WOKE, false)) {
            glance = true;
        }
        if (SystemClock.elapsedRealtime() - lastVolumeChange > VOLUME_SETTLE_MS) {
            showVolume(activeStream());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (root != null) {
            root.startWidgets();
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(unlockReceiver, filter);
        registerReceiver(volumeReceiver, new IntentFilter(VOLUME_CHANGED), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        resumed = false;
        glance = false;
        CoverNotificationListener.setAlertTarget(null);
        handler.removeCallbacks(sleepRunnable);
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(unlockReceiver);
        unregisterReceiver(volumeReceiver);
        if (root != null) {
            root.stopWidgets();
            root.resetToClock();
        }
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        closeIfNotOnCoverPanel();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        glance = false;
        armTimeout();
        return super.dispatchTouchEvent(event);
    }

    private void armTimeout() {
        handler.removeCallbacks(sleepRunnable);
        if (resumed) {
            handler.postDelayed(sleepRunnable, glance ? ALERT_SLEEP_MS : SCREEN_TIMEOUT_MS);
        }
    }

    private void requestSleep() {
        CoverCommands.write(this, CoverCommands.COMMAND, System.currentTimeMillis() + " sleep");
        armTimeout();
    }

    @Override
    public boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                adjustVolume(code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER);
                armTimeout();
            }
            return true;
        }
        return false;
    }

    private void adjustVolume(int direction) {
        int stream = activeStream();
        try {
            audio.adjustStreamVolume(stream, direction, 0);
        } catch (SecurityException blockedByDoNotDisturb) {
            Log.i(TAG, "Volume change blocked by Do Not Disturb");
        }
        lastVolumeChange = SystemClock.elapsedRealtime();
        showVolume(stream);
    }

    private void showVolume(int stream) {
        root.showVolume(streamFraction(stream), volumeIcon(stream));
    }

    private int volumeIcon(int stream) {
        boolean silent = audio.getStreamVolume(stream) <= audio.getStreamMinVolume(stream);
        if (stream == AudioManager.STREAM_VOICE_CALL) {
            return R.drawable.ic_volume_call;
        }
        if (stream == AudioManager.STREAM_MUSIC) {
            return silent ? R.drawable.ic_volume_media_mute : R.drawable.ic_volume_media;
        }
        int ringer = audio.getRingerMode();
        if (ringer == AudioManager.RINGER_MODE_VIBRATE) {
            return R.drawable.ic_volume_ringer_vibrate;
        }
        return ringer == AudioManager.RINGER_MODE_SILENT || silent ? R.drawable.ic_volume_ringer_mute : R.drawable.ic_volume_ringer;
    }

    private int activeStream() {
        int mode = audio.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
            return AudioManager.STREAM_VOICE_CALL;
        }
        return audio.isMusicActive() ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_RING;
    }

    private float streamFraction(int stream) {
        int min = audio.getStreamMinVolume(stream);
        int max = audio.getStreamMaxVolume(stream);
        if (max <= min) {
            return 0f;
        }
        return (audio.getStreamVolume(stream) - min) / (float) (max - min);
    }

    @Override
    public void onNotificationAlert(StatusBarNotification item) {
        if (root != null) {
            root.showAlert(item, false, ALERT_MS);
            armTimeout();
        }
    }

    @Override
    public void onBrightnessLevel(int level, boolean committed) {
        CoverCommands.write(this, CoverCommands.BRIGHTNESS, String.valueOf(backlightFor(level)));
        if (committed) {
            prefs.edit().putInt(KEY_LEVEL, level).apply();
        }
        armTimeout();
    }

    private static int backlightFor(int level) {
        double fraction = Math.pow(level / (double) QuickPanel.MAX_LEVEL, BACKLIGHT_GAMMA);
        return Math.max(BACKLIGHT_MIN, (int) Math.round(fraction * BACKLIGHT_MAX));
    }

    @Override
    public void onSystemCommand(String command) {
        CoverCommands.write(this, CoverCommands.COMMAND, System.currentTimeMillis() + " " + command);
        armTimeout();
    }

    private void closeIfNotOnCoverPanel() {
        if (!isFinishing() && !isOnCoverPanel()) {
            finishAndRemoveTask();
        }
    }

    private boolean isOnCoverPanel() {
        Display display = getDisplay();
        if (display == null) {
            return false;
        }
        Display.Mode mode = display.getMode();
        int width = mode.getPhysicalWidth();
        int height = mode.getPhysicalHeight();
        return Math.max(width, height) == PANEL_LONG && Math.min(width, height) == PANEL_SHORT;
    }
}
