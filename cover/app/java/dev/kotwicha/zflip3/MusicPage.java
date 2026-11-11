package dev.kotwicha.zflip3;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class MusicPage extends FrameLayout implements MediaSessionManager.OnActiveSessionsChangedListener {
    interface Listener {
        void onPlayingChanged(boolean playing);
    }

    private static final String TAG = "Flip3Cover";
    private static final int ROOT_MARGIN = 12;
    private static final int INFO_MARGIN_TOP = 23;
    private static final int INFO_PADDING = 12;
    private static final int ART_SIZE = 102;
    private static final int ART_RADIUS = 24;
    private static final int ART_DEFAULT_COLOR = 0xFF424343;
    private static final int ART_ICON_PADDING = 27;
    private static final int APP_LINE_MARGIN = 14;
    private static final int APP_ICON_SIZE = 28;
    private static final float APP_ICON_ALPHA = 0.6f;
    private static final float APP_NAME_SIZE = 24f;
    private static final int APP_NAME_COLOR = 0x99FAFAFA;
    private static final int APP_NAME_HEIGHT = 32;
    private static final int APP_NAME_MARGIN = 4;
    private static final float TITLE_SIZE = 26f;
    private static final int TEXT_COLOR = 0xFFFAFAFA;
    private static final int TITLE_HEIGHT = 35;
    private static final int TITLE_MARGIN = 18;
    private static final int CONTROLS_MARGIN_TOP = 20;
    private static final int SEEK_MARGIN_TOP = 4;
    private static final int SEEK_HEIGHT = 32;
    private static final float WAVE_LENGTH = 30f;
    private static final float WAVE_AMPLITUDE = 2.25f;
    private static final float WAVE_PHASE_SPEED = 12f;
    private static final float WAVE_STROKE = 3f;
    private static final int THUMB_GRAB_RADIUS = 24;
    private static final int THUMB_DENSITY = 240;
    private static final String ANDROID_THUMB = "seekbar_thumb_material_anim";
    private static final String MISSING_TEXT = "null";
    private static final long POSITION_POLL_MS = 100;
    private static final long SEEK_SETTLE_MS = 1500;
    private static final float TIME_TEXT_SIZE = 24f;
    private static final int TIME_WIDTH = 110;
    private static final int BUTTON_SIZE = 80;
    private static final int BUTTON_PADDING = 20;
    private static final int BUTTON_GAP = 46;
    private static final int SIDE_BUTTON_TINT = 0x66FAFAFA;
    private static final int RIPPLE = 0x33FFFFFF;
    private static final float EMPTY_TEXT_SIZE = 30f;
    private static final int EMPTY_MARGIN_TOP = 62;
    private static final int EMPTY_MARGIN_SIDE = 24;
    private static final int EMPTY_TEXT_MIN_HEIGHT = 40;
    private static final int PLAY_WIDTH = 217;
    private static final int PLAY_HEIGHT = 72;
    private static final int PLAY_MARGIN_TOP = 24;
    private static final float PLAY_RADIUS = 38f;
    private static final int PLAY_COLOR = 0x4DFFFFFF;
    private static final int PLAY_RIPPLE = 0x1A000000;
    private static final int TEXT_WEIGHT = 400;

    private final MediaSessionManager sessions;
    private final ComponentName listenerComponent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private final Listener listener;
    private final LinearLayout content;
    private final LinearLayout empty;
    private final ImageView art;
    private final ImageView appIcon;
    private final TextView appName;
    private final TextView title;
    private final TextView artist;
    private final ImageView previous;
    private final ImageView playPause;
    private final ImageView next;
    private final LinearLayout controls;
    private final SeekBar seek;
    private final SquigglyProgress squiggly;
    private final TextView scrubElapsed;
    private final TextView scrubTotal;
    private final int touchSlop;
    private final Runnable positionTick = this::updatePosition;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refresh();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    private MediaController current;
    private boolean playing;
    private boolean watching;
    private boolean windowVisible;
    private boolean scrubbing;
    private boolean seekable;
    private boolean thumbGrabbed;
    private float touchDownX;
    private float touchDownY;
    private long duration;
    private long seekRequestedAt;

    MusicPage(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        sessions = context.getSystemService(MediaSessionManager.class);
        listenerComponent = new ComponentName(context, CoverNotificationListener.class);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ROOT_MARGIN, 0, ROOT_MARGIN, 0);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setPadding(INFO_PADDING, 0, INFO_PADDING, 0);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ART_SIZE);
        infoParams.topMargin = INFO_MARGIN_TOP;
        content.addView(info, infoParams);

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ART_RADIUS);
            }
        });
        art.setClipToOutline(true);
        info.addView(art, new LinearLayout.LayoutParams(ART_SIZE, ART_SIZE));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        info.addView(column, new LinearLayout.LayoutParams(0, ART_SIZE, 1f));

        LinearLayout appLine = new LinearLayout(context);
        appLine.setOrientation(LinearLayout.HORIZONTAL);
        appLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams appLineParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, APP_NAME_HEIGHT);
        appLineParams.leftMargin = APP_LINE_MARGIN;
        column.addView(appLine, appLineParams);
        appIcon = new ImageView(context);
        appIcon.setAlpha(APP_ICON_ALPHA);
        appLine.addView(appIcon, new LinearLayout.LayoutParams(APP_ICON_SIZE, APP_ICON_SIZE));
        appName = text(context, APP_NAME_SIZE, APP_NAME_COLOR, false);
        LinearLayout.LayoutParams appNameParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        appNameParams.leftMargin = APP_NAME_MARGIN;
        appLine.addView(appName, appNameParams);

        title = text(context, TITLE_SIZE, TEXT_COLOR, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, TITLE_HEIGHT);
        titleParams.leftMargin = TITLE_MARGIN;
        column.addView(title, titleParams);
        artist = text(context, TITLE_SIZE, TEXT_COLOR, true);
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, TITLE_HEIGHT);
        artistParams.leftMargin = TITLE_MARGIN;
        column.addView(artist, artistParams);

        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        squiggly = new SquigglyProgress(WAVE_LENGTH, WAVE_AMPLITUDE, WAVE_PHASE_SPEED, WAVE_STROKE, TEXT_COLOR);
        seek = new SeekBar(context);
        seek.setProgressDrawable(squiggly);
        Resources resources = context.getResources();
        int thumbId = resources.getIdentifier(ANDROID_THUMB, "drawable", "android");
        if (thumbId != 0) {
            seek.setThumb(resources.getDrawableForDensity(thumbId, THUMB_DENSITY, context.getTheme()));
        }
        seek.setThumbTintList(ColorStateList.valueOf(TEXT_COLOR));
        seek.setSplitTrack(false);
        seek.setBackground(null);
        int thumbInset = seek.getThumb().getIntrinsicWidth() / 2;
        seek.setPadding(thumbInset, 0, thumbInset, 0);
        seek.setThumbOffset(thumbInset);
        seek.setOnTouchListener(this::onSeekTouch);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    scrubElapsed.setText(DateUtils.formatElapsedTime(progress / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                setScrubbing(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seekTo(bar.getProgress());
                setScrubbing(false);
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, SEEK_HEIGHT);
        seekParams.topMargin = SEEK_MARGIN_TOP;
        seekParams.leftMargin = INFO_PADDING - thumbInset;
        seekParams.rightMargin = INFO_PADDING - thumbInset;
        content.addView(seek, seekParams);

        controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, BUTTON_SIZE);
        controlsParams.topMargin = CONTROLS_MARGIN_TOP;
        content.addView(controls, controlsParams);
        scrubElapsed = timeLabel(context);
        scrubTotal = timeLabel(context);
        previous = addButton(controls, R.drawable.ic_music_previous, SIDE_BUTTON_TINT, 0, scrubElapsed, () -> {
            if (current != null) {
                current.getTransportControls().skipToPrevious();
            }
        });
        playPause = addButton(controls, R.drawable.ic_music_play, TEXT_COLOR, BUTTON_GAP, null, this::togglePlay);
        next = addButton(controls, R.drawable.ic_music_next, SIDE_BUTTON_TINT, BUTTON_GAP, scrubTotal, () -> {
            if (current != null) {
                current.getTransportControls().skipToNext();
            }
        });
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutParams emptyParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        emptyParams.topMargin = EMPTY_MARGIN_TOP;
        emptyParams.leftMargin = EMPTY_MARGIN_SIDE;
        emptyParams.rightMargin = EMPTY_MARGIN_SIDE;
        addView(empty, emptyParams);
        TextView emptyText = new TextView(context);
        emptyText.setText("No music playing");
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, EMPTY_TEXT_SIZE);
        emptyText.setTextColor(TEXT_COLOR);
        emptyText.setTypeface(CoverFonts.oneUi(context, TEXT_WEIGHT));
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setMinHeight(EMPTY_TEXT_MIN_HEIGHT);
        emptyText.setIncludeFontPadding(false);
        empty.addView(emptyText);
        TextView playMusic = new TextView(context);
        playMusic.setText("Play music");
        playMusic.setTextSize(TypedValue.COMPLEX_UNIT_DIP, EMPTY_TEXT_SIZE);
        playMusic.setTextColor(TEXT_COLOR);
        playMusic.setTypeface(CoverFonts.oneUi(context, TEXT_WEIGHT));
        playMusic.setGravity(Gravity.CENTER);
        playMusic.setSingleLine(true);
        GradientDrawable playShape = new GradientDrawable();
        playShape.setColor(PLAY_COLOR);
        playShape.setCornerRadius(PLAY_RADIUS);
        playMusic.setBackground(new RippleDrawable(ColorStateList.valueOf(PLAY_RIPPLE), playShape, null));
        playMusic.setOnClickListener(view -> resumeLastPlayer());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(PLAY_WIDTH, PLAY_HEIGHT);
        playParams.topMargin = PLAY_MARGIN_TOP;
        empty.addView(playMusic, playParams);
        showController(null);
    }

    private TextView text(Context context, float size, int color, boolean marquee) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        view.setTypeface(CoverFonts.oneUi(context, TEXT_WEIGHT));
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setEllipsize(marquee ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        if (marquee) {
            view.setMarqueeRepeatLimit(-1);
            view.setSelected(true);
        }
        return view;
    }

    private TextView timeLabel(Context context) {
        TextView label = text(context, TIME_TEXT_SIZE, TEXT_COLOR, false);
        label.setGravity(Gravity.CENTER);
        label.setVisibility(GONE);
        return label;
    }

    private ImageView addButton(LinearLayout row, int icon, int tint, int leftMargin, TextView label, Runnable action) {
        FrameLayout slot = new FrameLayout(getContext());
        slot.setClipChildren(false);
        ImageView button = new ImageView(getContext());
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setPadding(BUTTON_PADDING, BUTTON_PADDING, BUTTON_PADDING, BUTTON_PADDING);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask));
        button.setOnClickListener(view -> action.run());
        slot.addView(button, new LayoutParams(BUTTON_SIZE, BUTTON_SIZE));
        if (label != null) {
            slot.addView(label, new LayoutParams(TIME_WIDTH, BUTTON_SIZE, Gravity.CENTER));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE);
        params.leftMargin = leftMargin;
        row.addView(slot, params);
        return button;
    }

    private boolean onSeekTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                thumbGrabbed = seekable && Math.abs(touchDownX - thumbX()) <= THUMB_GRAB_RADIUS;
                if (thumbGrabbed) {
                    seek.getParent().requestDisallowInterceptTouchEvent(true);
                    return false;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (thumbGrabbed) {
                    return false;
                }
                if (seekable && Math.abs(event.getX() - touchDownX) <= touchSlop && Math.abs(event.getY() - touchDownY) <= touchSlop) {
                    int target = progressAt(event.getX());
                    seek.setProgress(target);
                    seekTo(target);
                }
                return true;
            default:
                return !thumbGrabbed;
        }
    }

    private int trackWidth() {
        return Math.max(1, seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight());
    }

    private float thumbX() {
        return seek.getPaddingLeft() + trackWidth() * (seek.getProgress() / (float) Math.max(1, seek.getMax()));
    }

    private int progressAt(float x) {
        return (int) (Motion.clamp((x - seek.getPaddingLeft()) / trackWidth()) * seek.getMax());
    }

    private void seekTo(int position) {
        if (current != null && seekable) {
            current.getTransportControls().seekTo(position);
            seekRequestedAt = SystemClock.elapsedRealtime();
        }
    }

    private void setScrubbing(boolean value) {
        scrubbing = value;
        previous.setVisibility(value ? INVISIBLE : VISIBLE);
        next.setVisibility(value ? INVISIBLE : VISIBLE);
        scrubElapsed.setVisibility(value ? VISIBLE : GONE);
        scrubTotal.setVisibility(value ? VISIBLE : GONE);
        if (value) {
            scrubElapsed.setText(DateUtils.formatElapsedTime(seek.getProgress() / 1000));
            scrubTotal.setText(DateUtils.formatElapsedTime(duration / 1000));
        }
        squiggly.setTransitionEnabled(!value);
        squiggly.setAnimating(playing && !value);
        updatePosition();
    }

    private long currentPosition(PlaybackState state) {
        long position = state.getPosition();
        long updated = state.getLastPositionUpdateTime();
        if (state.getState() == PlaybackState.STATE_PLAYING && updated > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - updated) * state.getPlaybackSpeed());
        }
        return Math.max(0, Math.min(duration, position));
    }

    private void updatePosition() {
        removeCallbacks(positionTick);
        if (current == null || duration <= 0) {
            return;
        }
        PlaybackState state = current.getPlaybackState();
        long now = SystemClock.elapsedRealtime();
        boolean seekPending = now - seekRequestedAt < SEEK_SETTLE_MS && (state == null || state.getLastPositionUpdateTime() < seekRequestedAt);
        if (state != null && !scrubbing && !seekPending) {
            seek.setProgress((int) currentPosition(state));
        }
        if (playing && windowVisible) {
            postDelayed(positionTick, POSITION_POLL_MS);
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        updatePosition();
    }

    private void resumeLastPlayer() {
        AudioManager audio = getContext().getSystemService(AudioManager.class);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
    }

    private void togglePlay() {
        if (current == null) {
            return;
        }
        if (playing) {
            current.getTransportControls().pause();
        } else {
            current.getTransportControls().play();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            sessions.addOnActiveSessionsChangedListener(this, listenerComponent, handler);
            onActiveSessionsChanged(sessions.getActiveSessions(listenerComponent));
            watching = true;
        } catch (SecurityException notEnabled) {
            Log.w(TAG, "Notification access is needed to read media sessions", notEnabled);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (watching) {
            sessions.removeOnActiveSessionsChangedListener(this);
            watching = false;
        }
        clearControllers();
        removeCallbacks(positionTick);
        super.onDetachedFromWindow();
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
        refresh();
    }

    private void clearControllers() {
        for (MediaController controller : controllers) {
            controller.unregisterCallback(callback);
        }
        controllers.clear();
    }

    private void refresh() {
        MediaController chosen = null;
        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                chosen = controller;
                break;
            }
            if (chosen == null && controller.getMetadata() != null) {
                chosen = controller;
            }
        }
        showController(chosen);
    }

    private void showController(MediaController controller) {
        current = controller;
        boolean nowPlaying = false;
        if (controller == null) {
            content.setVisibility(GONE);
            empty.setVisibility(VISIBLE);
        } else {
            content.setVisibility(VISIBLE);
            empty.setVisibility(GONE);
            MediaMetadata metadata = controller.getMetadata();
            title.setText(firstText(metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_ALBUM));
            artist.setText(firstText(metadata, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
            showArt(metadata);
            showApp(controller.getPackageName());
            PlaybackState state = controller.getPlaybackState();
            nowPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            playPause.setImageResource(nowPlaying ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            showSeekBar(metadata, state);
        }
        if (nowPlaying != playing) {
            playing = nowPlaying;
            listener.onPlayingChanged(playing);
        }
        squiggly.setAnimating(playing && !scrubbing);
        updatePosition();
    }

    private void showSeekBar(MediaMetadata metadata, PlaybackState state) {
        long length = metadata != null ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        boolean known = length > 0 && length <= Integer.MAX_VALUE;
        seek.setVisibility(known ? VISIBLE : GONE);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) controls.getLayoutParams();
        int margin = known ? 0 : CONTROLS_MARGIN_TOP;
        if (params.topMargin != margin) {
            params.topMargin = margin;
            controls.setLayoutParams(params);
        }
        duration = known ? length : 0;
        if (known && seek.getMax() != (int) length) {
            seek.setMax((int) length);
        }
        seekable = known && state != null && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
        seek.getThumb().setAlpha(seekable ? 255 : 0);
    }

    private static String firstText(MediaMetadata metadata, String... keys) {
        if (metadata != null) {
            for (String key : keys) {
                CharSequence value = metadata.getText(key);
                if (!TextUtils.isEmpty(value) && !MISSING_TEXT.contentEquals(value)) {
                    return value.toString();
                }
            }
        }
        return "";
    }

    private void showArt(MediaMetadata metadata) {
        Bitmap bitmap = null;
        if (metadata != null) {
            bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bitmap == null) {
                bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (bitmap == null) {
                bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
        }
        if (bitmap != null) {
            art.setImageTintList(null);
            art.setPadding(0, 0, 0, 0);
            art.setBackground(null);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(bitmap);
        } else {
            art.setImageResource(R.drawable.ic_music_album);
            art.setImageTintList(ColorStateList.valueOf(TEXT_COLOR));
            art.setPadding(ART_ICON_PADDING, ART_ICON_PADDING, ART_ICON_PADDING, ART_ICON_PADDING);
            art.setBackgroundColor(ART_DEFAULT_COLOR);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    private void showApp(String packageName) {
        PackageManager packages = getContext().getPackageManager();
        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            appName.setText(packages.getApplicationLabel(info));
            appIcon.setImageTintList(null);
            appIcon.setImageDrawable(packages.getApplicationIcon(info));
        } catch (PackageManager.NameNotFoundException gone) {
            appName.setText("Music");
            appIcon.setImageResource(R.drawable.ic_music_note);
            appIcon.setImageTintList(ColorStateList.valueOf(TEXT_COLOR));
        }
    }
}
