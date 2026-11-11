package dev.kotwicha.zflip3;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class CoverEditor extends FrameLayout {
    enum Mode { CLOCK, WIDGET_ENABLED, WIDGET_DISABLED }

    interface Listener {
        void onClockChosen(int type, int palette);

        void onTilesEdited(List<String> tiles, String focusKey);

        void onTileAdded(String key);

        void onEditorClosed();

        View createTilePreview(String key);

        View liveTile(String key);

        CharSequence tileLabel(String key);
    }

    private static final int PANEL_WIDTH = 512;
    private static final int PANEL_HEIGHT = 260;
    private static final int BACKGROUND = 0xFF2F2F2F;
    private static final int ITEM_WIDTH = 336;
    private static final int ITEM_HEIGHT = 200;
    private static final int CARD_WIDTH = 307;
    private static final int CARD_HEIGHT = 156;
    private static final float CARD_RADIUS = 21.6f;
    private static final float CARD_SCALE = 0.6f;
    private static final int LIST_TOP = 30;
    private static final int REMOVE_SIZE = 44;
    private static final int REMOVE_CIRCLE = 34;
    private static final int EDIT_WIDTH = 154;
    private static final int EDIT_HEIGHT = 54;
    private static final int EDIT_COLOR = 0xFF6B6B6B;
    private static final float EDIT_RADIUS = 27f;
    private static final int HANDLE_COLOR = 0xFF008CFF;
    private static final int BACK_SIZE = 56;
    private static final int DOT_SIZE = 30;
    private static final int MAX_DOTS = 13;
    private static final float DOT_DIM = 0.3f;
    private static final int PALETTE_DOT = 36;
    private static final int PALETTE_GAP = 8;
    private static final long ENTER_MS = 300;
    private static final long RETURN_MS = 200;

    private final Mode mode;
    private final Listener listener;
    private final TileStore store;
    private final WidgetHost widgets;
    private final HorizontalScrollView scroll;
    private final LinearLayout row;
    private final LinearLayout dots;
    private final TextView caption;
    private final List<Item> items = new ArrayList<>();
    private LinearLayout palettePanel;
    private int palette;
    private int selected;
    private boolean dragging;
    private float dragAnchor;

    private static final class Item {
        String key;
        int clockType;
        ClockPage clock;
        FrameLayout view;
        View handle;
        View editButton;
        CharSequence caption;
        Runnable action;
    }

    CoverEditor(Context context, Mode mode, TileStore store, WidgetHost widgets, Listener listener) {
        super(context);
        this.mode = mode;
        this.store = store;
        this.widgets = widgets;
        this.listener = listener;
        palette = store.paletteIndex();
        setBackgroundColor(BACKGROUND);
        setClickable(true);

        scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int side = (PANEL_WIDTH - ITEM_WIDTH) / 2;
        row.setPadding(side, LIST_TOP, side, 0);
        row.setClipChildren(false);
        scroll.addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> updateSelection());
        scroll.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.postDelayed(this::snap, 120);
            }
            return false;
        });

        dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LayoutParams dotsParams = new LayoutParams(LayoutParams.MATCH_PARENT, DOT_SIZE, Gravity.TOP);
        addView(dots, dotsParams);

        caption = Tiles.text(context, 24f, 0xFFFAFAFA);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        LayoutParams captionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        captionParams.bottomMargin = 16;
        captionParams.leftMargin = 24;
        captionParams.rightMargin = 24;
        addView(caption, captionParams);

        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        back.setPadding(5, 5, 5, 5);
        back.setOnClickListener(view -> listener.onEditorClosed());
        LayoutParams backParams = new LayoutParams(BACK_SIZE, BACK_SIZE);
        backParams.topMargin = 13;
        backParams.leftMargin = 10;
        addView(back, backParams);

        switch (mode) {
            case CLOCK:
                buildClockItems();
                break;
            case WIDGET_ENABLED:
                buildEnabledItems();
                break;
            default:
                buildDisabledItems();
                break;
        }
        rebuildDots();
        setAlpha(0f);
        scroll.setScaleX(0.9f);
        scroll.setScaleY(0.9f);
        animate().alpha(1f).setDuration(ENTER_MS).start();
        scroll.animate().scaleX(1f).scaleY(1f).setDuration(ENTER_MS).setInterpolator(Motion.PAGE).start();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed && mode == Mode.CLOCK) {
            for (int index = 0; index < items.size(); index++) {
                if (items.get(index).clockType == store.clockType()) {
                    int target = index;
                    post(() -> jumpTo(target));
                    break;
                }
            }
        }
        updateSelection();
    }

    private void buildClockItems() {
        for (int type : ClockPage.SELECTABLE) {
            Item item = new Item();
            item.clockType = type;
            item.clock = new ClockPage(getContext(), type, palette);
            FrameLayout live = new FrameLayout(getContext());
            live.addView(item.clock, new LayoutParams(PANEL_WIDTH, PANEL_HEIGHT));
            item.action = () -> listener.onClockChosen(item.clockType, palette);
            addItem(item, scaledContent(live), false);
            item.editButton = editButton(item);
        }
    }

    private void buildEnabledItems() {
        for (String key : store.enabledTiles()) {
            View live = listener.liveTile(key);
            if (live == null) {
                continue;
            }
            Item item = new Item();
            item.key = key;
            item.caption = listener.tileLabel(key);
            item.action = () -> listener.onTilesEdited(currentKeys(), item.key);
            addItem(item, snapshotView(live), true);
            makeDraggable(item);
        }
    }

    private void buildDisabledItems() {
        List<String> enabled = store.enabledTiles();
        for (String key : new String[] {TileStore.MUSIC, TileStore.ALARM, TileStore.SCHEDULE}) {
            if (enabled.contains(key)) {
                continue;
            }
            Item item = new Item();
            item.key = key;
            item.caption = listener.tileLabel(key);
            item.action = () -> listener.onTileAdded(item.key);
            addItem(item, snapshotView(listener.createTilePreview(key)), false);
        }
        List<AppWidgetProviderInfo> providers = widgets.available();
        providers.sort((a, b) -> widgets.label(a).toString().compareToIgnoreCase(widgets.label(b).toString()));
        for (AppWidgetProviderInfo info : providers) {
            Item item = new Item();
            item.caption = widgets.label(info);
            item.action = () -> {
                int id = widgets.bind(info.provider);
                if (id >= 0) {
                    listener.onTileAdded(TileStore.widgetKey(id));
                }
            };
            ImageView preview = new ImageView(getContext());
            Drawable image = widgets.preview(info);
            preview.setImageDrawable(image);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addItem(item, preview, false);
        }
        if (items.isEmpty()) {
            caption.setText("No widgets available");
        }
    }

    private View scaledContent(View live) {
        FrameLayout holder = new FrameLayout(getContext());
        live.setPivotX(0f);
        live.setPivotY(0f);
        live.setScaleX(CARD_SCALE);
        live.setScaleY(CARD_SCALE);
        holder.addView(live, new LayoutParams(PANEL_WIDTH, PANEL_HEIGHT));
        return holder;
    }

    private View snapshotView(View source) {
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap bitmap = Bitmap.createBitmap(PANEL_WIDTH, PANEL_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            if (source.getWidth() != PANEL_WIDTH || source.getHeight() != PANEL_HEIGHT) {
                source.measure(MeasureSpec.makeMeasureSpec(PANEL_WIDTH, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(PANEL_HEIGHT, MeasureSpec.EXACTLY));
                source.layout(0, 0, PANEL_WIDTH, PANEL_HEIGHT);
            }
            Canvas canvas = new Canvas(bitmap);
            float translation = source.getTranslationX();
            source.draw(canvas);
            source.setTranslationX(translation);
        } catch (RuntimeException notDrawable) {
            bitmap.eraseColor(Color.BLACK);
        }
        image.setImageBitmap(bitmap);
        return image;
    }

    private void addItem(Item item, View content, boolean removable) {
        Context context = getContext();
        item.view = new FrameLayout(context);
        item.view.setClipChildren(false);
        FrameLayout card = new FrameLayout(context);
        card.setBackgroundColor(Color.BLACK);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), CARD_RADIUS);
            }
        });
        card.setClipToOutline(true);
        card.addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        View shield = new View(context);
        shield.setOnClickListener(view -> onCardTapped(item));
        card.addView(shield, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        item.view.addView(card, new LayoutParams(CARD_WIDTH, CARD_HEIGHT, Gravity.CENTER));
        if (mode == Mode.WIDGET_ENABLED) {
            View handle = new View(context);
            GradientDrawable outline = new GradientDrawable();
            outline.setCornerRadius(CARD_RADIUS);
            outline.setStroke(2, HANDLE_COLOR);
            handle.setBackground(outline);
            handle.setVisibility(GONE);
            item.view.addView(handle, new LayoutParams(CARD_WIDTH, CARD_HEIGHT, Gravity.CENTER));
            item.handle = handle;
        }
        if (removable) {
            FrameLayout remove = new FrameLayout(context);
            View circle = new View(context);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.WHITE);
            circle.setBackground(shape);
            remove.addView(circle, new LayoutParams(REMOVE_CIRCLE, REMOVE_CIRCLE, Gravity.CENTER));
            ImageView minus = new ImageView(context);
            minus.setImageResource(R.drawable.ic_minus);
            remove.addView(minus, new LayoutParams(REMOVE_CIRCLE, REMOVE_CIRCLE, Gravity.CENTER));
            remove.setOnClickListener(view -> removeItem(item));
            item.view.addView(remove, new LayoutParams(REMOVE_SIZE, REMOVE_SIZE, Gravity.TOP | Gravity.END));
        }
        items.add(item);
        row.addView(item.view, new LinearLayout.LayoutParams(ITEM_WIDTH, ITEM_HEIGHT));
    }

    private View editButton(Item item) {
        TextView button = Tiles.text(getContext(), 25f, Color.WHITE);
        button.setText("Edit");
        button.setGravity(Gravity.CENTER);
        button.setBackground(Tiles.ripple(EDIT_COLOR, EDIT_RADIUS));
        button.setOnClickListener(view -> showPalette());
        button.setVisibility(GONE);
        LayoutParams params = new LayoutParams(EDIT_WIDTH, EDIT_HEIGHT - 4, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = 4;
        item.view.addView(button, params);
        return button;
    }

    private void showPalette() {
        if (palettePanel != null) {
            return;
        }
        Context context = getContext();
        palettePanel = new LinearLayout(context);
        palettePanel.setOrientation(LinearLayout.HORIZONTAL);
        palettePanel.setGravity(Gravity.CENTER);
        for (int index = 0; index < ClockPage.PALETTE.length; index++) {
            int choice = index;
            View dot = new View(context);
            dot.setBackground(paletteDot(index, index == palette));
            dot.setOnClickListener(view -> choosePalette(choice));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(PALETTE_DOT, PALETTE_DOT);
            params.leftMargin = PALETTE_GAP / 2;
            params.rightMargin = PALETTE_GAP / 2;
            palettePanel.addView(dot, params);
        }
        LayoutParams panelParams = new LayoutParams(LayoutParams.MATCH_PARENT, PALETTE_DOT, Gravity.BOTTOM);
        panelParams.bottomMargin = 8;
        addView(palettePanel, panelParams);
        caption.setVisibility(GONE);
        updateSelection();
    }

    private Drawable paletteDot(int index, boolean chosen) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(ClockPage.PALETTE[index]);
        if (chosen) {
            shape.setStroke(4, HANDLE_COLOR);
        }
        return shape;
    }

    private void choosePalette(int index) {
        palette = index;
        for (Item item : items) {
            item.clock.setPaletteIndex(index);
        }
        for (int position = 0; position < palettePanel.getChildCount(); position++) {
            palettePanel.getChildAt(position).setBackground(paletteDot(position, position == index));
        }
    }

    private void onCardTapped(Item item) {
        int index = items.indexOf(item);
        if (index != selected) {
            jumpSmooth(index);
        } else if (item.action != null) {
            item.action.run();
        }
    }

    private void removeItem(Item item) {
        items.remove(item);
        row.removeView(item.view);
        if (TileStore.isWidget(item.key)) {
            widgets.delete(TileStore.widgetId(item.key));
        }
        store.setEnabledTiles(currentKeys());
        rebuildDots();
        if (items.isEmpty()) {
            listener.onTilesEdited(currentKeys(), null);
        } else {
            jumpSmooth(Math.min(selected, items.size() - 1));
        }
    }

    private List<String> currentKeys() {
        List<String> keys = new ArrayList<>();
        for (Item item : items) {
            keys.add(item.key);
        }
        return keys;
    }

    private void makeDraggable(Item item) {
        item.view.setOnLongClickListener(view -> false);
        View card = item.view.getChildAt(0);
        View shield = ((FrameLayout) card).getChildAt(1);
        shield.setOnLongClickListener(view -> {
            dragging = true;
            dragAnchor = lastRawX;
            item.view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(RETURN_MS).start();
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        });
        shield.setOnTouchListener((view, event) -> {
            lastRawX = event.getRawX();
            if (!dragging) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    dragTo(item, event.getRawX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    finishDrag(item);
                    return true;
                default:
                    return true;
            }
        });
    }

    private float lastRawX;

    private void dragTo(Item item, float rawX) {
        float dx = rawX - dragAnchor;
        int index = items.indexOf(item);
        if (dx > ITEM_WIDTH / 2f && index < items.size() - 1) {
            swap(index, index + 1);
            dragAnchor += ITEM_WIDTH;
            scroll.smoothScrollBy(ITEM_WIDTH, 0);
            dx -= ITEM_WIDTH;
        } else if (dx < -ITEM_WIDTH / 2f && index > 0) {
            swap(index - 1, index);
            dragAnchor -= ITEM_WIDTH;
            scroll.smoothScrollBy(-ITEM_WIDTH, 0);
            dx += ITEM_WIDTH;
        }
        item.view.setTranslationX(dx);
    }

    private void swap(int first, int second) {
        Item moved = items.get(first);
        items.set(first, items.get(second));
        items.set(second, moved);
        View neighbour = dragNeighbour(first, second);
        int target = neighbour == row.getChildAt(first) ? second : first;
        row.removeView(neighbour);
        row.addView(neighbour, target);
    }

    private View dragNeighbour(int first, int second) {
        View a = row.getChildAt(first);
        View b = row.getChildAt(second);
        return a.isPressed() || a.getTranslationX() != 0f || a.getScaleX() != 1f ? b : a;
    }

    private void finishDrag(Item item) {
        dragging = false;
        item.view.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(RETURN_MS).start();
        store.setEnabledTiles(currentKeys());
        snap();
    }

    private void snap() {
        if (!dragging && !items.isEmpty()) {
            jumpSmooth(Math.round(scroll.getScrollX() / (float) ITEM_WIDTH));
        }
    }

    void focusKey(String key) {
        for (int index = 0; index < items.size(); index++) {
            if (key.equals(items.get(index).key)) {
                int target = index;
                scroll.post(() -> jumpTo(target));
                return;
            }
        }
    }

    private void jumpTo(int index) {
        scroll.scrollTo(index * ITEM_WIDTH, 0);
        updateSelection();
    }

    private void jumpSmooth(int index) {
        scroll.smoothScrollTo(Math.max(0, Math.min(items.size() - 1, index)) * ITEM_WIDTH, 0);
    }

    private void updateSelection() {
        if (items.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(items.size() - 1, Math.round(scroll.getScrollX() / (float) ITEM_WIDTH)));
        selected = index;
        for (int position = 0; position < items.size(); position++) {
            Item item = items.get(position);
            if (item.handle != null) {
                item.handle.setVisibility(position == index ? VISIBLE : GONE);
            }
            if (item.editButton != null) {
                item.editButton.setVisibility(position == index && palettePanel == null ? VISIBLE : GONE);
            }
        }
        Item current = items.get(index);
        if (current.caption != null) {
            caption.setText(current.caption);
        }
        for (int position = 0; position < dots.getChildCount(); position++) {
            dots.getChildAt(position).setAlpha(position == index ? 1f : DOT_DIM);
        }
    }

    private void rebuildDots() {
        dots.removeAllViews();
        if (items.size() < 2 || items.size() > MAX_DOTS) {
            return;
        }
        for (int position = 0; position < items.size(); position++) {
            ImageView dot = new ImageView(getContext());
            dot.setImageResource(R.drawable.page_dot_selected);
            dots.addView(dot, new LinearLayout.LayoutParams(DOT_SIZE, DOT_SIZE));
        }
    }
}
