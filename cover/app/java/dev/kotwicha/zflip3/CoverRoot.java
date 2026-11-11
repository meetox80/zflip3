package dev.kotwicha.zflip3;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CoverRoot extends FrameLayout implements CoverEditor.Listener {
    public interface KeyHandler {
        boolean onKey(KeyEvent event);
    }

    static final int COMMIT_DISTANCE = 56;

    private static final int NOTIFICATION_PAGE = 0;
    private static final int DEFAULT_PAGE = 1;
    private static final int QUICK_PANEL_ZONE = 90;
    private static final float TIMELINE_MS = 300f;
    private static final float CLOCK_MS = 250f;
    private static final float PAGER_DELAY_MS = 50f;
    private static final float CLOCK_MIN_SCALE = 0.9f;
    private static final int INDICATOR_SIZE = 30;
    private static final int INDICATOR_BOTTOM = 2;
    private static final int MAX_DOTS = 13;
    private static final float DOT_DIM = 0.3f;
    private static final long DOT_MS = 300;
    private static final long INDICATOR_MOVE_MS = 150;
    private static final long INDICATOR_HOLD_MS = 500;
    private static final int NOTI_CONTAINER_WIDTH = 38;
    private static final int NOTI_CONTAINER_HEIGHT = 44;
    private static final int NOTI_MARGIN_START = -2;
    private static final int NOTI_DOT_SIZE = 18;
    private static final int ONGOING_CONTAINER_WIDTH = 44;
    private static final int ONGOING_CONTAINER_HEIGHT = 46;
    private static final int ONGOING_INNER_SIZE = 40;
    private static final int ONGOING_INNER_PADDING = 2;
    private static final int VOLUME_MARGIN = 12;
    private static final int VOLUME_BOTTOM = 38;
    private static final int VOLUME_HEIGHT = 48;
    private static final long VOLUME_VISIBLE_MS = 1500;
    private static final long VOLUME_FADE_MS = 200;
    private static final int NOTI_DOT_COLOR = 0xFFFF9E00;

    private enum Mode { NONE, HORIZONTAL, VERTICAL, IGNORE, CONSUMED }

    private final TileStore store;
    private final WidgetHost widgets;
    private final ClockPage clock;
    private final FrameLayout pageHost;
    private final List<View> pages = new ArrayList<>();
    private final List<String> pageKeys = new ArrayList<>();
    private final Map<String, View> tileViews = new HashMap<>();
    private final NotificationPage notificationPage;
    private final View defaultPage;
    private final View addTile;
    private final QuickPanel panel;
    private final PillBar volumeBar;
    private final LinearLayout indicator;
    private final FrameLayout notificationDot;
    private final FrameLayout musicIndicator;
    private final FrameLayout overlayHost;
    private final int touchSlop;
    private final int flingVelocity;
    private final int longPressTimeout;
    private final Runnable hideVolume = this::fadeOutVolume;
    private final Runnable hideIndicator = this::slideIndicatorAway;
    private final Runnable longPress = this::onLongPress;
    private final Runnable hideAlert = this::dismissAlert;

    private KeyHandler keyHandler;
    private VelocityTracker tracker;
    private ValueAnimator pageAnimator;
    private ValueAnimator panelAnimator;
    private CoverEditor editor;
    private View alertView;
    private Mode mode = Mode.NONE;
    private float position = DEFAULT_PAGE;
    private float panelOffset;
    private float startPosition;
    private float startPanel;
    private float downX;
    private float downY;
    private boolean panelAllowed;
    private boolean hasNotifications;
    private boolean musicPlaying;
    private int selectedDot = -1;

    public CoverRoot(Context context, QuickPanel.Host host) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        flingVelocity = config.getScaledMinimumFlingVelocity() * 4;
        longPressTimeout = ViewConfiguration.getLongPressTimeout();
        store = new TileStore(context);
        widgets = new WidgetHost(context);

        clock = new ClockPage(context, store.clockType(), store.paletteIndex());
        addView(clock, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        pageHost = new FrameLayout(context);
        addView(pageHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        notificationPage = new NotificationPage(context, count -> {
            hasNotifications = count > 0;
            updateSideIndicators();
        });
        defaultPage = new View(context);
        addTile = new Tiles.AddTile(context, () -> openEditor(CoverEditor.Mode.WIDGET_DISABLED));

        notificationDot = new FrameLayout(context);
        View unread = new View(context);
        GradientDrawable unreadShape = new GradientDrawable();
        unreadShape.setShape(GradientDrawable.OVAL);
        unreadShape.setColor(NOTI_DOT_COLOR);
        unread.setBackground(unreadShape);
        notificationDot.addView(unread, new LayoutParams(NOTI_DOT_SIZE, NOTI_DOT_SIZE, Gravity.CENTER));
        notificationDot.setOnClickListener(view -> goToPage(NOTIFICATION_PAGE));
        LayoutParams notiParams = new LayoutParams(NOTI_CONTAINER_WIDTH, NOTI_CONTAINER_HEIGHT, Gravity.START | Gravity.CENTER_VERTICAL);
        notiParams.leftMargin = NOTI_MARGIN_START;
        addView(notificationDot, notiParams);

        musicIndicator = new FrameLayout(context);
        ImageView musicIcon = new ImageView(context);
        musicIcon.setImageResource(R.drawable.ic_music_indicator);
        musicIcon.setPadding(ONGOING_INNER_PADDING, ONGOING_INNER_PADDING, ONGOING_INNER_PADDING, ONGOING_INNER_PADDING);
        musicIndicator.addView(musicIcon, new LayoutParams(ONGOING_INNER_SIZE, ONGOING_INNER_SIZE, Gravity.CENTER));
        musicIndicator.setOnClickListener(view -> {
            int index = pageKeys.indexOf(TileStore.MUSIC);
            if (index >= 0) {
                goToPage(index);
            }
        });
        addView(musicIndicator, new LayoutParams(ONGOING_CONTAINER_WIDTH, ONGOING_CONTAINER_HEIGHT, Gravity.END | Gravity.CENTER_VERTICAL));

        indicator = new LinearLayout(context);
        indicator.setOrientation(LinearLayout.HORIZONTAL);
        indicator.setGravity(Gravity.CENTER);
        LayoutParams indicatorParams = new LayoutParams(LayoutParams.MATCH_PARENT, INDICATOR_SIZE, Gravity.BOTTOM);
        indicatorParams.bottomMargin = INDICATOR_BOTTOM;
        addView(indicator, indicatorParams);
        indicator.setTranslationY(INDICATOR_SIZE + INDICATOR_BOTTOM);

        panel = new QuickPanel(context, host);
        panel.setVisibility(INVISIBLE);
        addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        volumeBar = new PillBar(context);
        volumeBar.setAlpha(0f);
        LayoutParams volumeParams = new LayoutParams(LayoutParams.MATCH_PARENT, VOLUME_HEIGHT, Gravity.BOTTOM);
        volumeParams.leftMargin = VOLUME_MARGIN;
        volumeParams.rightMargin = VOLUME_MARGIN;
        volumeParams.bottomMargin = VOLUME_BOTTOM;
        addView(volumeBar, volumeParams);

        overlayHost = new FrameLayout(context);
        addView(overlayHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        rebuildPages();
        updateSideIndicators();
    }

    public void setKeyHandler(KeyHandler handler) {
        keyHandler = handler;
    }

    public QuickPanel getPanel() {
        return panel;
    }

    public void startWidgets() {
        widgets.startListening();
    }

    public void stopWidgets() {
        widgets.stopListening();
    }

    public void refreshPrivacy() {
        KeyguardManager keyguard = getContext().getSystemService(KeyguardManager.class);
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        for (View tile : tileViews.values()) {
            if (tile instanceof Tiles.SecureFrame) {
                ((Tiles.SecureFrame) tile).setLocked(locked);
            }
        }
        notificationPage.onNotificationsChanged();
    }

    public boolean handleBack() {
        if (editor != null) {
            closeEditor();
            return true;
        }
        if (notificationPage.closeDetail()) {
            return true;
        }
        if (panelOffset > 0f) {
            if (!panel.closeSubView()) {
                settlePanel(0f);
            }
            return true;
        }
        if (Math.round(position) != DEFAULT_PAGE) {
            goToPage(DEFAULT_PAGE);
            return true;
        }
        return false;
    }

    public void resetToClock() {
        if (pageAnimator != null) {
            pageAnimator.cancel();
        }
        if (panelAnimator != null) {
            panelAnimator.cancel();
        }
        closeEditor();
        dismissAlert();
        notificationPage.closeDetail();
        panel.reset();
        applyPanel(0f);
        applyPosition(DEFAULT_PAGE);
    }

    void showPageForPreview(float page, float panelValue) {
        applyPosition(page);
        applyPanel(panelValue);
    }

    public void showVolume(float fraction, int icon) {
        volumeBar.setIcon(icon);
        volumeBar.setValue(fraction);
        volumeBar.animate().cancel();
        volumeBar.setAlpha(1f);
        removeCallbacks(hideVolume);
        postDelayed(hideVolume, VOLUME_VISIBLE_MS);
    }

    private void fadeOutVolume() {
        volumeBar.animate().alpha(0f).setDuration(VOLUME_FADE_MS).start();
    }

    public void showAlert(StatusBarNotification item, boolean full, long durationMs) {
        dismissAlert();
        alertView = full
            ? NotificationAlert.full(getContext(), item, () -> openAlert(item))
            : NotificationAlert.top(getContext(), item, () -> openAlert(item));
        overlayHost.addView(alertView);
        if (!full) {
            NotificationAlert.slideIn(alertView);
        }
        postDelayed(hideAlert, durationMs);
    }

    private void openAlert(StatusBarNotification item) {
        dismissAlert();
        resetEditorOnly();
        goToPage(NOTIFICATION_PAGE);
        notificationPage.showDetail(item);
    }

    private void dismissAlert() {
        removeCallbacks(hideAlert);
        if (alertView != null) {
            overlayHost.removeView(alertView);
            alertView = null;
        }
    }

    private void resetEditorOnly() {
        closeEditor();
        if (panelOffset > 0f) {
            applyPanel(0f);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (keyHandler != null && keyHandler.onKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private View createTile(String key, boolean live) {
        Context context = getContext();
        switch (key) {
            case TileStore.MUSIC:
                return new MusicPage(context, live ? playing -> {
                    musicPlaying = playing;
                    updateSideIndicators();
                } : playing -> { });
            case TileStore.ALARM:
                return new Tiles.AlarmTile(context);
            case TileStore.SCHEDULE:
                return live ? new Tiles.SecureFrame(context, new Tiles.ScheduleTile(context), tileLabel(key)) : new Tiles.ScheduleTile(context);
            default:
                if (!TileStore.isWidget(key)) {
                    return null;
                }
                View view = widgets.createView(context, TileStore.widgetId(key));
                return view == null ? null : new Tiles.SecureFrame(context, view, tileLabel(key));
        }
    }

    private void rebuildPages() {
        pageHost.removeAllViews();
        pages.clear();
        pageKeys.clear();
        pages.add(notificationPage);
        pageKeys.add("notifications");
        pages.add(defaultPage);
        pageKeys.add("clock");
        List<String> enabled = store.enabledTiles();
        tileViews.keySet().retainAll(enabled);
        for (String key : enabled) {
            View tile = tileViews.get(key);
            if (tile == null) {
                tile = createTile(key, true);
                if (tile == null) {
                    continue;
                }
                tileViews.put(key, tile);
            }
            pages.add(tile);
            pageKeys.add(key);
        }
        if (!enabled.contains(TileStore.MUSIC)) {
            musicPlaying = false;
        }
        pages.add(addTile);
        pageKeys.add("add");
        for (View page : pages) {
            pageHost.addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        rebuildDots();
        refreshPrivacy();
        applyPosition(Math.max(0f, Math.min(pages.size() - 1f, position)));
        updateSideIndicators();
    }

    private void rebuildDots() {
        indicator.removeAllViews();
        selectedDot = -1;
        int count = Math.min(pages.size(), MAX_DOTS);
        for (int index = 0; index < count; index++) {
            ImageView dot = new ImageView(getContext());
            dot.setImageResource(index == NOTIFICATION_PAGE ? R.drawable.page_notification_selected
                : index == DEFAULT_PAGE ? R.drawable.page_home_selected : R.drawable.page_dot_selected);
            dot.setAlpha(DOT_DIM);
            indicator.addView(dot, new LinearLayout.LayoutParams(INDICATOR_SIZE, INDICATOR_SIZE));
        }
        updateDots();
    }

    private void openEditor(CoverEditor.Mode editorMode) {
        if (editor != null) {
            return;
        }
        dismissAlert();
        editor = new CoverEditor(getContext(), editorMode, store, widgets, this);
        overlayHost.addView(editor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void closeEditor() {
        if (editor != null) {
            overlayHost.removeView(editor);
            editor = null;
        }
    }

    @Override
    public void onClockChosen(int type, int palette) {
        store.setClock(type, palette);
        clock.setFace(type);
        clock.setPaletteIndex(palette);
        closeEditor();
        applyPosition(DEFAULT_PAGE);
    }

    @Override
    public void onTilesEdited(List<String> tiles, String focusKey) {
        store.setEnabledTiles(tiles);
        closeEditor();
        rebuildPages();
        int index = focusKey == null ? -1 : pageKeys.indexOf(focusKey);
        applyPosition(index >= 0 ? index : DEFAULT_PAGE);
    }

    @Override
    public void onTileAdded(String key) {
        List<String> tiles = store.enabledTiles();
        tiles.add(key);
        onTilesEdited(tiles, key);
    }

    @Override
    public void onEditorClosed() {
        closeEditor();
        rebuildPages();
    }

    @Override
    public View createTilePreview(String key) {
        return createTile(key, false);
    }

    @Override
    public View liveTile(String key) {
        View tile = tileViews.get(key);
        return tile instanceof Tiles.SecureFrame ? ((Tiles.SecureFrame) tile).getChildAt(0) : tile;
    }

    @Override
    public CharSequence tileLabel(String key) {
        switch (key) {
            case TileStore.MUSIC:
                return "Music";
            case TileStore.ALARM:
                return "Next alarm";
            case TileStore.SCHEDULE:
                return "Today's schedule";
            default:
                AppWidgetProviderInfo info = TileStore.isWidget(key) ? widgets.info(TileStore.widgetId(key)) : null;
                return info == null ? "" : widgets.label(info);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        applyPosition(position);
        applyPanel(panelOffset);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        if (disallow) {
            removeCallbacks(longPress);
        }
        super.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (editor != null) {
            return false;
        }
        track(event);
        return mode == Mode.HORIZONTAL || mode == Mode.VERTICAL || mode == Mode.CONSUMED;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            track(event);
        } else {
            begin(event);
        }
        return true;
    }

    private void begin(MotionEvent event) {
        if (pageAnimator != null) {
            pageAnimator.cancel();
        }
        if (panelAnimator != null) {
            panelAnimator.cancel();
        }
        downX = event.getX();
        downY = event.getY();
        startPosition = position;
        startPanel = panelOffset;
        mode = Mode.NONE;
        boolean atDefault = Math.abs(position - DEFAULT_PAGE) < 0.001f;
        panelAllowed = panelOffset > 0f || (atDefault && event.getY() < QUICK_PANEL_ZONE);
        recycleTracker();
        tracker = VelocityTracker.obtain();
        tracker.addMovement(event);
        removeCallbacks(longPress);
        postDelayed(longPress, longPressTimeout);
    }

    private void onLongPress() {
        if (mode != Mode.NONE || panelOffset > 0f) {
            return;
        }
        int page = Math.round(position);
        if (Math.abs(position - page) > 0.001f) {
            return;
        }
        String key = pageKeys.get(page);
        if (page == DEFAULT_PAGE) {
            mode = Mode.CONSUMED;
            openEditor(CoverEditor.Mode.CLOCK);
        } else if (page > DEFAULT_PAGE && !"add".equals(key)) {
            mode = Mode.CONSUMED;
            openEditor(CoverEditor.Mode.WIDGET_ENABLED);
            editor.focusKey(key);
        }
    }

    private void track(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null) {
                    tracker.addMovement(event);
                }
                if (mode == Mode.NONE) {
                    lockDirection(event);
                }
                if (mode == Mode.HORIZONTAL) {
                    float page = startPosition - (event.getX() - downX) / Math.max(1, getWidth());
                    applyPosition(Math.max(0f, Math.min(pages.size() - 1f, page)));
                } else if (mode == Mode.VERTICAL) {
                    applyPanel(Motion.clamp(startPanel + (event.getY() - downY) / Math.max(1, getHeight())));
                }
                break;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                finishGesture(event);
                break;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (mode == Mode.HORIZONTAL) {
                    settlePage(Math.round(position));
                } else if (mode == Mode.VERTICAL) {
                    settlePanel(panelOffset >= 0.5f ? 1f : 0f);
                } else {
                    settleInterrupted();
                }
                mode = Mode.NONE;
                recycleTracker();
                break;
            default:
                break;
        }
    }

    private void lockDirection(MotionEvent event) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        if (Math.max(Math.abs(dx), Math.abs(dy)) <= touchSlop) {
            return;
        }
        removeCallbacks(longPress);
        if (Math.abs(dx) > Math.abs(dy)) {
            boolean onCard = Math.round(position) == NOTIFICATION_PAGE && dx > 0 && notificationPage.isCardAt(downX, downY);
            mode = panelOffset > 0f || onCard ? Mode.IGNORE : Mode.HORIZONTAL;
            if (mode == Mode.HORIZONTAL) {
                showIndicator();
            }
        } else {
            boolean opening = panelOffset == 0f && dy > 0;
            boolean closing = panelOffset == 1f && dy < 0;
            mode = panelAllowed && (opening || closing) ? Mode.VERTICAL : Mode.IGNORE;
        }
        if (mode == Mode.HORIZONTAL || mode == Mode.VERTICAL) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void finishGesture(MotionEvent event) {
        float velocityX = 0f;
        float velocityY = 0f;
        if (tracker != null) {
            tracker.addMovement(event);
            tracker.computeCurrentVelocity(1000);
            velocityX = tracker.getXVelocity();
            velocityY = tracker.getYVelocity();
        }
        if (mode == Mode.HORIZONTAL) {
            float dx = event.getX() - downX;
            int origin = Math.round(startPosition);
            int target;
            if (origin == DEFAULT_PAGE && Math.abs(dx) < COMMIT_DISTANCE) {
                target = origin;
            } else if (Math.abs(velocityX) > flingVelocity) {
                target = origin + (velocityX < 0 ? 1 : -1);
            } else {
                target = Math.round(position);
            }
            settlePage(Math.max(0, Math.min(pages.size() - 1, target)));
        } else if (mode == Mode.VERTICAL) {
            float dy = event.getY() - downY;
            float target;
            if (startPanel == 0f && dy < COMMIT_DISTANCE) {
                target = 0f;
            } else if (Math.abs(velocityY) > flingVelocity) {
                target = velocityY > 0 ? 1f : 0f;
            } else {
                target = panelOffset >= 0.5f ? 1f : 0f;
            }
            settlePanel(target);
        } else {
            settleInterrupted();
        }
        mode = Mode.NONE;
        recycleTracker();
    }

    private void settleInterrupted() {
        int nearest = Math.round(position);
        if (Math.abs(position - nearest) > 0.001f) {
            settlePage(nearest);
        }
        if (panelOffset > 0f && panelOffset < 1f) {
            settlePanel(panelOffset >= 0.5f ? 1f : 0f);
        }
    }

    private void goToPage(int page) {
        showIndicator();
        settlePage(page);
    }

    private void settlePage(int target) {
        if (pageAnimator != null) {
            pageAnimator.cancel();
        }
        pageAnimator = ValueAnimator.ofFloat(position, target);
        pageAnimator.setDuration(Motion.PAGE_MS);
        pageAnimator.setInterpolator(Motion.PAGE);
        pageAnimator.addUpdateListener(animation -> applyPosition((float) animation.getAnimatedValue()));
        pageAnimator.start();
        scheduleIndicatorHide();
    }

    private void settlePanel(float target) {
        if (panelAnimator != null) {
            panelAnimator.cancel();
        }
        panelAnimator = ValueAnimator.ofFloat(panelOffset, target);
        panelAnimator.setDuration(Motion.PAGE_MS);
        panelAnimator.setInterpolator(Motion.PAGE);
        panelAnimator.addUpdateListener(animation -> applyPanel((float) animation.getAnimatedValue()));
        panelAnimator.start();
        if (target == 0f) {
            postDelayed(panel::reset, Motion.PAGE_MS);
        }
    }

    private void applyPosition(float value) {
        position = value;
        int width = getWidth();
        for (int index = 0; index < pages.size(); index++) {
            float offset = index - value;
            View page = pages.get(index);
            page.setTranslationX(offset * width);
            page.setVisibility(Math.abs(offset) < 1f ? VISIBLE : INVISIBLE);
        }
        applyClock();
        updateDots();
        updateSideIndicators();
    }

    private void applyPanel(float value) {
        panelOffset = value;
        panel.setVisibility(value > 0f ? VISIBLE : INVISIBLE);
        panel.setTranslationY((value - 1f) * getHeight());
        panel.setRevealFraction(value);
        applyClock();
        updateSideIndicators();
    }

    private void applyClock() {
        float pageProgress = Math.min(1f, Math.abs(position - DEFAULT_PAGE));
        float progress = Math.max(pageProgress, panelOffset);
        float clockProgress = Motion.clamp(progress * TIMELINE_MS / CLOCK_MS);
        clock.setAlpha(1f - clockProgress);
        float scale = 1f - (1f - CLOCK_MIN_SCALE) * Motion.scaleCurve(clockProgress);
        clock.setScaleX(scale);
        clock.setScaleY(scale);
        pageHost.setAlpha(pageProgress == 0f ? 1f : Motion.clamp((pageProgress * TIMELINE_MS - PAGER_DELAY_MS) / CLOCK_MS));
    }

    private void updateSideIndicators() {
        boolean resting = Math.abs(position - DEFAULT_PAGE) < 0.001f && panelOffset == 0f;
        notificationDot.setVisibility(resting && hasNotifications ? VISIBLE : GONE);
        musicIndicator.setVisibility(resting && musicPlaying ? VISIBLE : GONE);
    }

    private void updateDots() {
        int selected = Math.round(position);
        if (selected == selectedDot) {
            return;
        }
        selectedDot = selected;
        for (int index = 0; index < indicator.getChildCount(); index++) {
            indicator.getChildAt(index).animate().alpha(index == selected ? 1f : DOT_DIM).setDuration(DOT_MS).setInterpolator(Motion.PAGE).start();
        }
    }

    private void showIndicator() {
        removeCallbacks(hideIndicator);
        indicator.animate().cancel();
        indicator.animate().translationY(0f).setDuration(INDICATOR_MOVE_MS).setInterpolator(Motion::scaleCurve).start();
    }

    private void scheduleIndicatorHide() {
        removeCallbacks(hideIndicator);
        postDelayed(hideIndicator, INDICATOR_HOLD_MS + Motion.PAGE_MS);
    }

    private void slideIndicatorAway() {
        indicator.animate().translationY(indicator.getHeight() + INDICATOR_BOTTOM).setDuration(INDICATOR_MOVE_MS).setInterpolator(Motion::scaleCurve).start();
    }

    private void recycleTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }
}
