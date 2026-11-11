package dev.kotwicha.zflip3;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class NotificationPage extends FrameLayout implements CoverNotificationListener.Observer {
    interface Listener {
        void onCountChanged(int count);
    }

    private static final String TAG = "Flip3Cover";
    private static final String[] QUICK_REPLIES = {
        "Yes", "Yes, please", "Ok", "No", "No Thanks", "Nope", "Thanks", "Much appreciated",
        "On my way", "What's up?", "Later", "Nice", "I agree", "Great!", "What?", "OMG!"
    };
    private static final int CARD_MARGIN = 10;
    private static final int CARD_MIN_HEIGHT = 84;
    private static final int CARD_RADIUS = 26;
    private static final int CARD_COLOR = 0xFF252525;
    private static final int RIPPLE = 0x42FFFFFF;
    private static final int ICON_SIZE = 52;
    private static final int ICON_START = 16;
    private static final int ICON_PADDING = 10;
    private static final int ICON_CIRCLE = 0xFF333333;
    private static final int ICON_TINT = 0xFFFAFAFA;
    private static final int TEXT_START = 20;
    private static final int TEXT_END = 16;
    private static final int TEXT_VERTICAL = 20;
    private static final float TEXT_SIZE = 26f;
    private static final int TITLE_COLOR = 0xFFFAFAFA;
    private static final int CONTENT_COLOR = 0xFFE5E5E5;
    private static final int CONTENT_MARGIN = 12;
    private static final int CLEAR_MARGIN = 24;
    private static final int BUTTON_MIN_WIDTH = 226;
    private static final int BUTTON_HEIGHT = 72;
    private static final float BUTTON_RADIUS = 37.5f;
    private static final int BUTTON_COLOR = 0xFF484848;
    private static final float BUTTON_TEXT = 29f;
    private static final int BUTTON_PADDING = 24;
    private static final float EMPTY_TEXT = 30f;
    private static final float DISMISS_FRACTION = 0.3f;
    private static final long DISMISS_MS = 200;
    private static final long RETURN_MS = 200;
    private static final long FADE_MS = 250;
    private static final float DIM_ALPHA = 0.2f;
    private static final int REGULAR = 400;
    private static final int BOLD = 700;
    private static final int HEADER_ICON = 40;
    private static final float HEADER_TEXT = 35f;
    private static final int BACK_SIZE = 46;
    private static final float DETAIL_TEXT = 29f;
    private static final int DETAIL_MARGIN = 24;
    private static final float TIME_TEXT = 25f;
    private static final int TIME_COLOR = 0xFF999999;
    private static final int IMAGE_RADIUS = 26;
    private static final int WORD_MIN_HEIGHT = 60;
    private static final int WORD_SELECTED = 0x1AFAFAFA;
    private static final int DIVIDER_WIDTH = 360;
    private static final int DIVIDER_COLOR = 0x33FAFAFA;
    private static final int REPLY_BUTTON_WIDTH = 216;
    private static final int REPLY_BUTTON_HEIGHT = 76;
    private static final int REPLY_BUTTON_GAP = 16;
    private static final int PANEL_WIDTH = 512;

    private final Listener listener;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final TextView empty;
    private final FrameLayout detailHost;
    private final int touchSlop;
    private final float flingVelocity;
    private final Rect hit = new Rect();
    private StatusBarNotification shownDetail;

    NotificationPage(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        flingVelocity = config.getScaledMinimumFlingVelocity() * 5f;

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        empty = new TextView(context);
        empty.setText("No notifications");
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, EMPTY_TEXT);
        empty.setTextColor(TITLE_COLOR);
        empty.setTypeface(CoverFonts.oneUi(context, REGULAR));
        empty.setGravity(Gravity.CENTER);
        addView(empty, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        detailHost = new FrameLayout(context);
        detailHost.setBackgroundColor(Color.BLACK);
        detailHost.setClickable(true);
        detailHost.setVisibility(GONE);
        addView(detailHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    boolean isCardAt(float x, float y) {
        if (shownDetail != null) {
            return false;
        }
        float listY = y + scroll.getScrollY();
        for (int index = 0; index < list.getChildCount(); index++) {
            View child = list.getChildAt(index);
            if (child instanceof Card) {
                child.getHitRect(hit);
                if (hit.contains((int) x, (int) listY)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean closeDetail() {
        if (shownDetail == null) {
            return false;
        }
        shownDetail = null;
        detailHost.removeAllViews();
        detailHost.setVisibility(GONE);
        return true;
    }

    void showDetail(StatusBarNotification item) {
        shownDetail = item;
        detailHost.removeAllViews();
        detailHost.addView(buildDetail(item), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        detailHost.setVisibility(VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        CoverNotificationListener.addObserver(this);
        onNotificationsChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        CoverNotificationListener.removeObserver(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onNotificationsChanged() {
        List<StatusBarNotification> items = CoverNotificationListener.visibleNotifications();
        list.removeAllViews();
        boolean anyClearable = false;
        boolean detailStillExists = false;
        for (StatusBarNotification item : items) {
            list.addView(new Card(getContext(), item));
            anyClearable |= item.isClearable();
            detailStillExists |= shownDetail != null && item.getKey().equals(shownDetail.getKey());
        }
        if (anyClearable) {
            list.addView(clearAllButton());
        }
        boolean none = items.isEmpty();
        empty.setVisibility(none ? VISIBLE : GONE);
        scroll.setVisibility(none ? GONE : VISIBLE);
        if (shownDetail != null && !detailStillExists) {
            closeDetail();
        }
        listener.onCountChanged(items.size());
    }

    private TextView button(String label, Runnable action) {
        Context context = getContext();
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, BUTTON_TEXT);
        button.setTextColor(TITLE_COLOR);
        button.setTypeface(CoverFonts.oneUi(context, BOLD));
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setMinWidth(BUTTON_MIN_WIDTH);
        button.setPadding(BUTTON_PADDING, 0, BUTTON_PADDING, 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(BUTTON_COLOR);
        shape.setCornerRadius(BUTTON_RADIUS);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private View clearAllButton() {
        FrameLayout holder = new FrameLayout(getContext());
        holder.setPadding(0, CLEAR_MARGIN, 0, CLEAR_MARGIN);
        holder.addView(button("Clear all", CoverNotificationListener::clearAll), new LayoutParams(LayoutParams.WRAP_CONTENT, BUTTON_HEIGHT, Gravity.CENTER_HORIZONTAL));
        return holder;
    }

    private static CharSequence bodyText(StatusBarNotification item) {
        Bundle extras = item.getNotification().extras;
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
        if (messages != null && messages.length > 0) {
            StringBuilder joined = new StringBuilder();
            for (Notification.MessagingStyle.Message message : Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)) {
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                if (message.getSenderPerson() != null && !TextUtils.isEmpty(message.getSenderPerson().getName())) {
                    joined.append(message.getSenderPerson().getName()).append(": ");
                }
                joined.append(message.getText());
            }
            return joined;
        }
        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        return !TextUtils.isEmpty(big) ? big : extras.getCharSequence(Notification.EXTRA_TEXT);
    }

    static CharSequence title(Context context, StatusBarNotification item, boolean hide) {
        CharSequence title = hide ? null : item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        return TextUtils.isEmpty(title) ? CoverNotificationListener.appLabel(context, item) : title;
    }

    static CharSequence preview(StatusBarNotification item, boolean hide) {
        return hide ? "New notification" : item.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
    }

    static ImageView icon(Context context, StatusBarNotification item, int size, int padding) {
        Notification notification = item.getNotification();
        ImageView icon = new ImageView(context);
        icon.setPadding(padding, padding, padding, padding);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        int tint = notification.color;
        circle.setColor(tint != Notification.COLOR_DEFAULT && Color.alpha(tint) != 0 ? tint : ICON_CIRCLE);
        icon.setBackground(circle);
        Icon small = notification.getSmallIcon();
        if (small != null) {
            Drawable drawable = small.loadDrawable(context);
            if (drawable != null) {
                icon.setImageDrawable(drawable);
                icon.setImageTintList(ColorStateList.valueOf(ICON_TINT));
            }
        }
        icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return icon;
    }

    private View buildDetail(StatusBarNotification item) {
        Context context = getContext();
        boolean hide = CoverNotificationListener.shouldHideContent(item);
        Notification notification = item.getNotification();
        FrameLayout frame = new FrameLayout(context);
        ScrollView detailScroll = new ScrollView(context);
        detailScroll.setVerticalScrollBarEnabled(false);
        detailScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, CLEAR_MARGIN);
        detailScroll.addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        frame.addView(detailScroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(14, 24, 24, 28);
        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_detail_back);
        back.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        back.setOnClickListener(view -> closeDetail());
        header.addView(back, new LinearLayout.LayoutParams(BACK_SIZE, BACK_SIZE));
        ImageView appIcon = icon(context, item, HEADER_ICON, 7);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(HEADER_ICON, HEADER_ICON);
        iconParams.leftMargin = 16;
        header.addView(appIcon, iconParams);
        TextView name = bold(HEADER_TEXT, Color.WHITE);
        name.setText(CoverNotificationListener.appLabel(context, item));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = 12;
        header.addView(name, nameParams);
        column.addView(header);

        TextView title = bold(DETAIL_TEXT, TITLE_COLOR);
        title.setText(title(context, item, hide));
        column.addView(title, detailParams(0));
        CharSequence body = hide ? "New notification" : bodyText(item);
        if (!TextUtils.isEmpty(body)) {
            TextView content = plain(DETAIL_TEXT, CONTENT_COLOR);
            content.setText(body);
            LinearLayout.LayoutParams contentParams = detailParams(8);
            contentParams.bottomMargin = 8;
            column.addView(content, contentParams);
        }
        Bitmap picture = hide ? null : notification.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap.class);
        if (picture != null) {
            ImageView image = new ImageView(context);
            image.setImageBitmap(picture);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), IMAGE_RADIUS);
                }
            });
            image.setClipToOutline(true);
            column.addView(image, detailParams(8));
        }
        TextView time = plain(TIME_TEXT, TIME_COLOR);
        String pattern = DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm a";
        time.setText(DateFormat.format(pattern, notification.when != 0 ? notification.when : item.getPostTime()));
        LinearLayout.LayoutParams timeParams = detailParams(8);
        timeParams.bottomMargin = 10;
        column.addView(time, timeParams);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, BUTTON_HEIGHT);
        buttonParams.topMargin = 12;
        if (notification.contentIntent != null) {
            TextView[] holder = new TextView[1];
            holder[0] = button("Open app", () -> {
                if (openOnPhone(notification.contentIntent)) {
                    holder[0].setText("Open your phone");
                }
            });
            buttons.addView(holder[0], new LinearLayout.LayoutParams(buttonParams));
        }
        if (item.isClearable()) {
            buttons.addView(button("Clear", () -> {
                CoverNotificationListener.dismiss(item);
                closeDetail();
            }), new LinearLayout.LayoutParams(buttonParams));
        }
        column.addView(buttons);

        Notification.Action reply = hide ? null : replyAction(notification);
        if (reply != null) {
            addQuickReplies(frame, column, buttons, item, reply);
        }
        return frame;
    }

    private LinearLayout.LayoutParams detailParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.leftMargin = DETAIL_MARGIN;
        params.rightMargin = DETAIL_MARGIN;
        params.topMargin = top;
        return params;
    }

    private TextView bold(float size, int color) {
        TextView view = plain(size, color);
        view.setTypeface(CoverFonts.oneUi(getContext(), BOLD));
        return view;
    }

    private TextView plain(float size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        view.setTypeface(CoverFonts.oneUi(getContext(), REGULAR));
        view.setIncludeFontPadding(false);
        return view;
    }

    private boolean openOnPhone(PendingIntent intent) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(0);
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            intent.send(getContext(), 0, null, null, null, null, options.toBundle());
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException failed) {
            Log.e(TAG, "Could not open the notification", failed);
            return false;
        }
    }

    private static Notification.Action replyAction(Notification notification) {
        if (notification.actions == null) {
            return null;
        }
        for (Notification.Action action : notification.actions) {
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs != null) {
                for (RemoteInput input : inputs) {
                    if (input.getAllowFreeFormInput()) {
                        return action;
                    }
                }
            }
        }
        return null;
    }

    private void addQuickReplies(FrameLayout frame, LinearLayout column, View others, StatusBarNotification item, Notification.Action action) {
        Context context = getContext();
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        wordsParams.topMargin = 20;
        column.addView(words, wordsParams);

        LinearLayout confirm = new LinearLayout(context);
        confirm.setOrientation(LinearLayout.HORIZONTAL);
        confirm.setGravity(Gravity.CENTER);
        confirm.setVisibility(GONE);
        FrameLayout.LayoutParams confirmParams = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, REPLY_BUTTON_HEIGHT, Gravity.BOTTOM);
        confirmParams.bottomMargin = 8;
        frame.addView(confirm, confirmParams);

        List<TextView> rows = new ArrayList<>();
        String[] chosen = new String[1];
        Runnable restore = () -> {
            chosen[0] = null;
            confirm.setVisibility(GONE);
            others.animate().alpha(1f).setDuration(FADE_MS).start();
            for (TextView row : rows) {
                row.setBackgroundColor(Color.TRANSPARENT);
                row.setAlpha(1f);
                row.setTypeface(CoverFonts.oneUi(context, REGULAR));
            }
        };
        confirm.addView(replyButton("Cancel", restore), new LinearLayout.LayoutParams(REPLY_BUTTON_WIDTH, REPLY_BUTTON_HEIGHT));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(REPLY_BUTTON_WIDTH, REPLY_BUTTON_HEIGHT);
        sendParams.leftMargin = REPLY_BUTTON_GAP;
        confirm.addView(replyButton("Send", () -> {
            if (chosen[0] != null && sendReply(action, chosen[0])) {
                CoverNotificationListener.dismiss(item);
                closeDetail();
            }
        }), sendParams);

        for (int index = 0; index < QUICK_REPLIES.length; index++) {
            String word = QUICK_REPLIES[index];
            if (index > 0) {
                View divider = new View(context);
                divider.setBackgroundColor(DIVIDER_COLOR);
                words.addView(divider, new LinearLayout.LayoutParams(DIVIDER_WIDTH, 1));
            }
            TextView row = plain(DETAIL_TEXT, TITLE_COLOR);
            row.setText(word);
            row.setGravity(Gravity.CENTER);
            row.setMinHeight(WORD_MIN_HEIGHT);
            row.setOnClickListener(view -> {
                chosen[0] = word;
                confirm.setVisibility(VISIBLE);
                others.animate().alpha(DIM_ALPHA).setDuration(FADE_MS).start();
                for (TextView other : rows) {
                    boolean selected = other == view;
                    other.setBackgroundColor(selected ? WORD_SELECTED : Color.TRANSPARENT);
                    other.setAlpha(selected ? 1f : DIM_ALPHA);
                    other.setTypeface(CoverFonts.oneUi(context, selected ? 500 : REGULAR));
                }
            });
            rows.add(row);
            words.addView(row, new LinearLayout.LayoutParams(PANEL_WIDTH - DETAIL_MARGIN * 2, LayoutParams.WRAP_CONTENT));
        }
    }

    private TextView replyButton(String label, Runnable action) {
        TextView view = button(label, action);
        view.setMinWidth(REPLY_BUTTON_WIDTH);
        return view;
    }

    private boolean sendReply(Notification.Action action, String text) {
        RemoteInput[] inputs = action.getRemoteInputs();
        Bundle results = new Bundle();
        for (RemoteInput input : inputs) {
            if (input.getAllowFreeFormInput()) {
                results.putCharSequence(input.getResultKey(), text);
            }
        }
        Intent intent = new Intent();
        RemoteInput.addResultsToIntent(inputs, intent, results);
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_CHOICE);
        try {
            action.actionIntent.send(getContext(), 0, intent);
            return true;
        } catch (PendingIntent.CanceledException failed) {
            Log.e(TAG, "Could not send the reply", failed);
            return false;
        }
    }

    private final class Card extends LinearLayout {
        private final StatusBarNotification item;
        private VelocityTracker tracker;
        private float startX;
        private float startY;
        private boolean swiping;

        Card(Context context, StatusBarNotification item) {
            super(context);
            this.item = item;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(CARD_MIN_HEIGHT);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(CARD_COLOR);
            shape.setCornerRadius(CARD_RADIUS);
            setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            cardParams.topMargin = CARD_MARGIN;
            cardParams.leftMargin = CARD_MARGIN;
            cardParams.rightMargin = CARD_MARGIN;
            setLayoutParams(cardParams);

            ImageView appIcon = icon(context, item, ICON_SIZE, ICON_PADDING);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE);
            iconParams.leftMargin = ICON_START;
            addView(appIcon, iconParams);

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = TEXT_START;
            columnParams.rightMargin = TEXT_END;
            columnParams.topMargin = TEXT_VERTICAL;
            columnParams.bottomMargin = TEXT_VERTICAL;
            addView(column, columnParams);

            boolean hide = CoverNotificationListener.shouldHideContent(item);
            TextView title = bold(TEXT_SIZE, TITLE_COLOR);
            title.setText(NotificationPage.title(context, item, hide));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(title);

            CharSequence bodyText = preview(item, hide);
            TextView content = plain(TEXT_SIZE, CONTENT_COLOR);
            content.setText(bodyText);
            content.setMaxLines(1);
            content.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            contentParams.topMargin = CONTENT_MARGIN;
            if (TextUtils.isEmpty(bodyText)) {
                content.setVisibility(GONE);
            }
            column.addView(content, contentParams);
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animate().cancel();
                    startX = event.getRawX();
                    startY = event.getRawY();
                    swiping = false;
                    recycle();
                    tracker = VelocityTracker.obtain();
                    tracker.addMovement(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (tracker != null) {
                        tracker.addMovement(event);
                    }
                    float dx = event.getRawX() - startX;
                    float dy = event.getRawY() - startY;
                    if (!swiping && dx > touchSlop && dx > Math.abs(dy)) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        setPressed(false);
                    }
                    if (swiping) {
                        setTranslationX(Math.max(0f, dx));
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (swiping) {
                        finishSwipe();
                        swiping = false;
                        recycle();
                        return true;
                    }
                    if (Math.abs(event.getRawX() - startX) <= touchSlop && Math.abs(event.getRawY() - startY) <= touchSlop) {
                        showDetail(item);
                    }
                    recycle();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (swiping) {
                        returnToPlace();
                    }
                    swiping = false;
                    recycle();
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }

        private void finishSwipe() {
            float velocity = 0f;
            if (tracker != null) {
                tracker.computeCurrentVelocity(1000);
                velocity = tracker.getXVelocity();
            }
            boolean dismiss = item.isClearable() && (getTranslationX() > getWidth() * DISMISS_FRACTION || velocity > flingVelocity);
            if (!dismiss) {
                returnToPlace();
                return;
            }
            animate().translationX(getWidth() + getLeft()).setDuration(DISMISS_MS).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    CoverNotificationListener.dismiss(item);
                }
            }).start();
        }

        private void returnToPlace() {
            animate().translationX(0f).setDuration(RETURN_MS).setListener(null).start();
        }

        private void recycle() {
            if (tracker != null) {
                tracker.recycle();
                tracker = null;
            }
        }
    }
}
