package dev.kotwicha.zflip3;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class NotificationAlert {
    private static final int TOP_MARGIN = 10;
    private static final int TOP_MIN_HEIGHT = 84;
    private static final int TOP_COLOR = 0xFF252525;
    private static final float TOP_RADIUS = 26f;
    private static final float TOP_ELEVATION = 4f;
    private static final int TOP_ICON = 52;
    private static final int TOP_ICON_PADDING = 10;
    private static final int TOP_ICON_START = 16;
    private static final int TEXT_START = 20;
    private static final int TEXT_END = 16;
    private static final int TEXT_VERTICAL = 20;
    private static final float TITLE_SIZE = 26f;
    private static final float TOP_CONTENT_SIZE = 24f;
    private static final int TOP_CONTENT_MARGIN = 14;
    private static final int TEXT_COLOR = 0xFFFAFAFA;
    private static final float SLIDE_FROM = -71f;
    private static final long SLIDE_MS = 200;
    private static final int FULL_ICON = 80;
    private static final int FULL_ICON_PADDING = 15;
    private static final int FULL_TITLE_MARGIN = 16;
    private static final float FULL_CONTENT_SIZE = 26f;
    private static final int FULL_CONTENT_MARGIN = 8;
    private static final int FULL_SIDE_MARGIN = 24;
    private static final int REGULAR = 400;
    private static final int BOLD = 700;

    private NotificationAlert() {
    }

    private static TextView text(Context context, float size, int weight) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(TEXT_COLOR);
        view.setTypeface(CoverFonts.oneUi(context, weight));
        view.setIncludeFontPadding(false);
        return view;
    }

    static View top(Context context, StatusBarNotification item, Runnable onTap) {
        boolean hide = CoverNotificationListener.shouldHideContent(item);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(TOP_MIN_HEIGHT);
        card.setBackground(Tiles.ripple(TOP_COLOR, TOP_RADIUS));
        card.setElevation(TOP_ELEVATION);
        card.setOnClickListener(view -> onTap.run());
        ImageView icon = NotificationPage.icon(context, item, TOP_ICON, TOP_ICON_PADDING);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(TOP_ICON, TOP_ICON);
        iconParams.leftMargin = TOP_ICON_START;
        card.addView(icon, iconParams);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.leftMargin = TEXT_START;
        columnParams.rightMargin = TEXT_END;
        columnParams.topMargin = TEXT_VERTICAL;
        columnParams.bottomMargin = TEXT_VERTICAL;
        card.addView(column, columnParams);
        TextView title = text(context, TITLE_SIZE, BOLD);
        title.setText(NotificationPage.title(context, item, hide));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(title);
        CharSequence body = NotificationPage.preview(item, hide);
        if (!TextUtils.isEmpty(body)) {
            TextView content = text(context, TOP_CONTENT_SIZE, REGULAR);
            content.setText(body);
            content.setSingleLine(true);
            content.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            contentParams.topMargin = TOP_CONTENT_MARGIN;
            column.addView(content, contentParams);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        params.topMargin = TOP_MARGIN;
        params.leftMargin = TOP_MARGIN;
        params.rightMargin = TOP_MARGIN;
        card.setLayoutParams(params);
        return card;
    }

    static void slideIn(View view) {
        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, SLIDE_FROM, 0f).setDuration(SLIDE_MS).start();
    }

    static View full(Context context, StatusBarNotification item, Runnable onTap) {
        boolean hide = CoverNotificationListener.shouldHideContent(item);
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(Color.BLACK);
        page.setOnClickListener(view -> onTap.run());
        ImageView icon = NotificationPage.icon(context, item, FULL_ICON, FULL_ICON_PADDING);
        page.addView(icon, new LinearLayout.LayoutParams(FULL_ICON, FULL_ICON));
        TextView title = text(context, TITLE_SIZE, BOLD);
        title.setText(NotificationPage.title(context, item, hide));
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = FULL_TITLE_MARGIN;
        titleParams.leftMargin = FULL_SIDE_MARGIN;
        titleParams.rightMargin = FULL_SIDE_MARGIN;
        page.addView(title, titleParams);
        CharSequence body = NotificationPage.preview(item, hide);
        if (!TextUtils.isEmpty(body)) {
            TextView content = text(context, FULL_CONTENT_SIZE, REGULAR);
            content.setText(body);
            content.setGravity(Gravity.CENTER);
            content.setMaxLines(2);
            content.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            contentParams.topMargin = FULL_CONTENT_MARGIN;
            contentParams.leftMargin = FULL_SIDE_MARGIN;
            contentParams.rightMargin = FULL_SIDE_MARGIN;
            page.addView(content, contentParams);
        }
        page.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return page;
    }
}
