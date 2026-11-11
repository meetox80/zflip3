package dev.kotwicha.zflip3;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

public final class QuickPanel extends FrameLayout {
    public interface Host {
        void onBrightnessLevel(int level, boolean committed);

        void onSystemCommand(String command);
    }

    static final int[] LEVELS = {0, 24, 49, 73, 96, 120};
    static final int MAX_LEVEL = 120;
    static final int DEFAULT_LEVEL = 73;

    private static final String TAG = "Flip3Cover";
    private static final int PANEL_RADIUS = 36;
    private static final int PANEL_INSET_BOTTOM = 8;
    private static final int SIDE_MARGIN = 45;
    private static final int TILE_SIZE = 118;
    private static final int TILE_GAP = 34;
    private static final int TILE_PADDING = 17;
    private static final int PAGER_HEIGHT = 188;
    private static final int PAGER_BOTTOM = 22;
    private static final int DOT_SIZE = 4;
    private static final int DOT_GAP = 16;
    private static final int DOTS_BOTTOM = 14;
    private static final int COLOR_ON = 0xFF2D66FF;
    private static final int COLOR_OFF = 0x66FFFFFF;
    private static final int COLOR_RIPPLE = 0x33FFFFFF;
    private static final int BAR_TOP = 81;
    private static final int BAR_WIDTH = 422;
    private static final int BAR_HEIGHT = 108;
    private static final int BACK_SIZE = 82;
    private static final int BACK_PADDING = 20;
    private static final int SCRIM_START = Color.rgb(128, 128, 128);

    private final Host host;
    private final AudioManager audio;
    private final CameraManager cameras;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GradientDrawable panelShape = new GradientDrawable();
    private final FrameLayout pager;
    private final FrameLayout dots;
    private final View firstDot;
    private final View secondDot;
    private final LinearLayout pageOne;
    private final LinearLayout pageTwo;
    private final FrameLayout brightnessView;
    private final BrightnessBar brightnessBar;
    private final ImageView wifi;
    private final ImageView sound;
    private final ImageView bluetooth;
    private final ImageView airplane;
    private final ImageView flashlight;
    private final int touchSlop;
    private final ContentObserver settingsObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshToggles();
        }
    };
    private final BroadcastReceiver ringerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSound();
        }
    };
    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCamera)) {
                torchOn = enabled;
                setTileOn(flashlight, enabled);
            }
        }
    };

    private String torchCamera;
    private boolean torchOn;
    private float pagePosition;
    private float dragStart;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean ignoring;
    private ValueAnimator pageAnimator;

    public QuickPanel(Context context, Host host) {
        super(context);
        this.host = host;
        audio = context.getSystemService(AudioManager.class);
        cameras = context.getSystemService(CameraManager.class);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        panelShape.setCornerRadii(new float[] {0, 0, 0, 0, PANEL_RADIUS, PANEL_RADIUS, PANEL_RADIUS, PANEL_RADIUS});
        panelShape.setColor(Color.BLACK);
        setBackground(new InsetDrawable(panelShape, 0, 0, 0, PANEL_INSET_BOTTOM));

        pager = new FrameLayout(context);
        LayoutParams pagerParams = new LayoutParams(LayoutParams.MATCH_PARENT, PAGER_HEIGHT, Gravity.CENTER);
        pagerParams.bottomMargin = PAGER_BOTTOM;
        addView(pager, pagerParams);

        pageOne = tileRow(context);
        wifi = addTile(pageOne, R.drawable.ic_tile_wifi, this::toggleWifi);
        sound = addTile(pageOne, R.drawable.ic_tile_sound, this::cycleRinger);
        bluetooth = addTile(pageOne, R.drawable.ic_tile_bluetooth, this::toggleBluetooth);
        pager.addView(pageOne, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        pageTwo = tileRow(context);
        airplane = addTile(pageTwo, R.drawable.ic_tile_airplane, this::toggleAirplane);
        flashlight = addTile(pageTwo, R.drawable.ic_tile_flashlight, this::toggleTorch);
        ImageView brightnessTile = addTile(pageTwo, R.drawable.ic_tile_brightness, this::showBrightness);
        setTileOn(brightnessTile, false);
        pager.addView(pageTwo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dots = new FrameLayout(context);
        firstDot = dot(context);
        secondDot = dot(context);
        LayoutParams firstParams = new LayoutParams(DOT_SIZE, DOT_SIZE, Gravity.CENTER_VERTICAL);
        LayoutParams secondParams = new LayoutParams(DOT_SIZE, DOT_SIZE, Gravity.CENTER_VERTICAL);
        secondParams.leftMargin = DOT_GAP;
        dots.addView(firstDot, firstParams);
        dots.addView(secondDot, secondParams);
        LayoutParams dotsParams = new LayoutParams(DOT_GAP + DOT_SIZE, 10, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dotsParams.bottomMargin = DOTS_BOTTOM;
        addView(dots, dotsParams);

        brightnessView = new FrameLayout(context);
        brightnessView.setVisibility(GONE);
        brightnessBar = new BrightnessBar(context);
        LayoutParams barParams = new LayoutParams(BAR_WIDTH, BAR_HEIGHT);
        barParams.leftMargin = SIDE_MARGIN;
        barParams.topMargin = BAR_TOP;
        brightnessView.addView(brightnessBar, barParams);
        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(BACK_PADDING, BACK_PADDING, BACK_PADDING, BACK_PADDING);
        back.setOnClickListener(view -> hideBrightness());
        brightnessView.addView(back, new LayoutParams(BACK_SIZE, BACK_SIZE));
        addView(brightnessView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        findTorchCamera();
        updateDots();
    }

    private static LinearLayout tileRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private View dot(Context context) {
        View view = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.WHITE);
        view.setBackground(shape);
        return view;
    }

    private ImageView addTile(LinearLayout row, int icon, Runnable action) {
        ImageView tile = new ImageView(getContext());
        tile.setImageResource(icon);
        tile.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.setPadding(TILE_PADDING, TILE_PADDING, TILE_PADDING, TILE_PADDING);
        tile.setOnClickListener(view -> action.run());
        tile.setClickable(true);
        setTileOn(tile, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(TILE_SIZE, TILE_SIZE);
        if (row.getChildCount() > 0) {
            params.leftMargin = TILE_GAP;
        }
        row.addView(tile, params);
        return tile;
    }

    private void setTileOn(ImageView tile, boolean on) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(on ? COLOR_ON : COLOR_OFF);
        Drawable ripple = new RippleDrawable(ColorStateList.valueOf(COLOR_RIPPLE), shape, null);
        tile.setBackground(ripple);
    }

    public void setRevealFraction(float fraction) {
        float eased = Motion.SCRIM.getInterpolation(Motion.clamp(fraction));
        int channel = (int) (Color.red(SCRIM_START) * (1f - eased));
        panelShape.setColor(Color.rgb(channel, channel, channel));
    }

    public void setBrightnessLevel(int level) {
        brightnessBar.setLevel(level);
    }

    public void reset() {
        hideBrightness();
        setPagePosition(0f);
    }

    public boolean closeSubView() {
        if (brightnessView.getVisibility() == VISIBLE) {
            hideBrightness();
            return true;
        }
        return false;
    }

    private void showBrightness() {
        pager.setVisibility(GONE);
        dots.setVisibility(GONE);
        brightnessView.setVisibility(VISIBLE);
    }

    private void hideBrightness() {
        brightnessView.setVisibility(GONE);
        pager.setVisibility(VISIBLE);
        dots.setVisibility(VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Context context = getContext();
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.WIFI_ON), false, settingsObserver);
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON), false, settingsObserver);
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, settingsObserver);
        context.registerReceiver(ringerReceiver, new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION));
        if (torchCamera != null) {
            cameras.registerTorchCallback(torchCallback, handler);
        }
        refreshToggles();
        refreshSound();
    }

    @Override
    protected void onDetachedFromWindow() {
        Context context = getContext();
        context.getContentResolver().unregisterContentObserver(settingsObserver);
        context.unregisterReceiver(ringerReceiver);
        if (torchCamera != null) {
            cameras.unregisterTorchCallback(torchCallback);
        }
        super.onDetachedFromWindow();
    }

    private boolean globalOn(String key) {
        return Settings.Global.getInt(getContext().getContentResolver(), key, 0) != 0;
    }

    private void refreshToggles() {
        setTileOn(wifi, globalOn(Settings.Global.WIFI_ON));
        setTileOn(bluetooth, globalOn(Settings.Global.BLUETOOTH_ON));
        setTileOn(airplane, globalOn(Settings.Global.AIRPLANE_MODE_ON));
    }

    private void refreshSound() {
        int mode = audio.getRingerMode();
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            sound.setImageResource(R.drawable.ic_tile_vibrate);
        } else if (mode == AudioManager.RINGER_MODE_SILENT) {
            sound.setImageResource(R.drawable.ic_tile_mute);
        } else {
            sound.setImageResource(R.drawable.ic_tile_sound);
        }
        setTileOn(sound, mode == AudioManager.RINGER_MODE_NORMAL || mode == AudioManager.RINGER_MODE_VIBRATE);
    }

    private void toggleWifi() {
        host.onSystemCommand(globalOn(Settings.Global.WIFI_ON) ? "wifi_off" : "wifi_on");
    }

    private void toggleBluetooth() {
        host.onSystemCommand(globalOn(Settings.Global.BLUETOOTH_ON) ? "bluetooth_off" : "bluetooth_on");
    }

    private void toggleAirplane() {
        host.onSystemCommand(globalOn(Settings.Global.AIRPLANE_MODE_ON) ? "airplane_off" : "airplane_on");
    }

    private void cycleRinger() {
        int mode = audio.getRingerMode();
        int next = mode == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
            : mode == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
            : AudioManager.RINGER_MODE_NORMAL;
        try {
            audio.setRingerMode(next);
        } catch (SecurityException noPolicyAccess) {
            Log.e(TAG, "Ringer change needs Do Not Disturb access", noPolicyAccess);
        }
        refreshSound();
    }

    private void findTorchCamera() {
        try {
            for (String id : cameras.getCameraIdList()) {
                Boolean hasFlash = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    torchCamera = id;
                    return;
                }
            }
        } catch (CameraAccessException | RuntimeException unavailable) {
            Log.e(TAG, "Could not find a flash", unavailable);
        }
    }

    private void toggleTorch() {
        if (torchCamera == null) {
            return;
        }
        try {
            cameras.setTorchMode(torchCamera, !torchOn);
        } catch (CameraAccessException | RuntimeException failed) {
            Log.e(TAG, "Could not switch the flashlight", failed);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        applyPagePosition();
    }

    private void setPagePosition(float position) {
        pagePosition = Motion.clamp(position);
        applyPagePosition();
    }

    private void applyPagePosition() {
        int width = getWidth();
        pageOne.setTranslationX(-pagePosition * width);
        pageTwo.setTranslationX((1f - pagePosition) * width);
        updateDots();
    }

    private void updateDots() {
        boolean first = pagePosition < 0.5f;
        firstDot.setAlpha(first ? 1f : 0.5f);
        secondDot.setAlpha(first ? 0.5f : 1f);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (brightnessView.getVisibility() == VISIBLE) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                return lockDirection(event);
            default:
                return dragging;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    lockDirection(event);
                }
                if (dragging) {
                    setPagePosition(dragStart - (event.getX() - downX) / Math.max(1, getWidth()));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    settlePage();
                }
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private void begin(MotionEvent event) {
        if (pageAnimator != null) {
            pageAnimator.cancel();
        }
        downX = event.getX();
        downY = event.getY();
        dragStart = pagePosition;
        dragging = false;
        ignoring = false;
    }

    private boolean lockDirection(MotionEvent event) {
        if (dragging || ignoring) {
            return dragging;
        }
        float dx = Math.abs(event.getX() - downX);
        float dy = Math.abs(event.getY() - downY);
        if (Math.max(dx, dy) > touchSlop) {
            if (dx > dy) {
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            } else {
                ignoring = true;
            }
        }
        return dragging;
    }

    private void settlePage() {
        float target = pagePosition >= 0.5f ? 1f : 0f;
        if (Math.abs(pagePosition - dragStart) * getWidth() < CoverRoot.COMMIT_DISTANCE) {
            target = dragStart;
        } else if (dragStart == 0f && pagePosition > 0f) {
            target = 1f;
        } else if (dragStart == 1f && pagePosition < 1f) {
            target = 0f;
        }
        pageAnimator = ValueAnimator.ofFloat(pagePosition, target);
        pageAnimator.setDuration(Motion.PAGE_MS);
        pageAnimator.setInterpolator(Motion.PAGE);
        pageAnimator.addUpdateListener(animation -> setPagePosition((float) animation.getAnimatedValue()));
        pageAnimator.start();
    }

    private final class BrightnessBar extends View {
        private static final float RADIUS = 24f;
        private static final int ICON_MARGIN = 20;
        private static final int ICON_SIZE = 74;
        private static final int TRACK = 0x26FFFFFF;
        private static final int ICON_TINT = 0xFF252525;

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private final Drawable icon;
        private float level = DEFAULT_LEVEL;
        private float touchDownX;
        private float touchDownY;
        private boolean tracking;
        private ValueAnimator snap;

        BrightnessBar(Context context) {
            super(context);
            track.setColor(TRACK);
            fill.setColor(Color.WHITE);
            icon = context.getDrawable(R.drawable.ic_tile_brightness).mutate();
            icon.setTint(ICON_TINT);
        }

        void setLevel(int value) {
            level = value;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            clip.reset();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW);
            int top = (h - ICON_SIZE) / 2;
            icon.setBounds(ICON_MARGIN, top, ICON_MARGIN + ICON_SIZE, top + ICON_SIZE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int floor = ICON_MARGIN * 2 + ICON_SIZE;
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(0, 0, w, h, track);
            canvas.drawRect(0, 0, floor + (w - floor) * (level / MAX_LEVEL), h, fill);
            canvas.restore();
            icon.draw(canvas);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (snap != null) {
                        snap.cancel();
                    }
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    tracking = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - touchDownX);
                    float dy = Math.abs(event.getY() - touchDownY);
                    if (!tracking && dx > dy && dx > touchSlop) {
                        tracking = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (tracking) {
                        int floor = ICON_MARGIN * 2 + ICON_SIZE;
                        level = Motion.clamp((event.getX() - floor) / Math.max(1, getWidth() - floor)) * MAX_LEVEL;
                        invalidate();
                        host.onBrightnessLevel(Math.round(level), false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (tracking) {
                        snapToNearest();
                    }
                    tracking = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    return true;
                default:
                    return true;
            }
        }

        private void snapToNearest() {
            int nearest = LEVELS[0];
            for (int candidate : LEVELS) {
                if (Math.abs(candidate - level) < Math.abs(nearest - level)) {
                    nearest = candidate;
                }
            }
            int finalLevel = nearest;
            host.onBrightnessLevel(finalLevel, true);
            snap = ValueAnimator.ofFloat(level, finalLevel);
            snap.setDuration(150);
            snap.setInterpolator(Motion.PAGE);
            snap.addUpdateListener(animation -> {
                level = (float) animation.getAnimatedValue();
                invalidate();
            });
            snap.start();
        }
    }
}
