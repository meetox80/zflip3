package dev.kotwicha.zflip3;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class Tiles {
    private static final String TAG = "Flip3Cover";
    private static final int TEXT_COLOR = 0xFFFAFAFA;
    private static final int DIM_COLOR = 0x99FAFAFA;
    private static final int RIPPLE = 0x33FFFFFF;
    private static final int RIPPLE_RADIUS = 26;
    private static final int WEIGHT = 400;
    private static final int BLOCK_PADDING = 12;
    private static final int ICON_SIZE = 46;

    private Tiles() {
    }

    static TextView text(Context context, float size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        view.setTypeface(CoverFonts.oneUi(context, WEIGHT));
        view.setIncludeFontPadding(false);
        return view;
    }

    static Drawable ripple(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null);
    }

    private static Drawable rippleMask() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(RIPPLE_RADIUS);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask);
    }

    private static LinearLayout emptyBlock(Context context, int icon, String message) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER);
        block.setPadding(BLOCK_PADDING, BLOCK_PADDING, BLOCK_PADDING, BLOCK_PADDING);
        block.setBackground(rippleMask());
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(TEXT_COLOR));
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE);
        imageParams.bottomMargin = 10;
        block.addView(image, imageParams);
        TextView label = text(context, 30f, TEXT_COLOR);
        label.setGravity(Gravity.CENTER);
        label.setText(message);
        block.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return block;
    }

    static final class AlarmTile extends FrameLayout {
        private static final long DAY_MS = 86400000L;
        private static final long MINUTE_MS = 60000L;
        private static final int DISMISS_COLOR = 0xFF8587FE;
        private static final int DISMISS_WIDTH = 217;
        private static final int DISMISS_HEIGHT = 72;
        private static final float DISMISS_RADIUS = 38f;

        private final AlarmManager alarms;
        private final LinearLayout content;
        private final LinearLayout empty;
        private final TextView description;
        private final TextView time;
        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refresh();
            }
        };

        AlarmTile(Context context) {
            super(context);
            alarms = context.getSystemService(AlarmManager.class);
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            LinearLayout block = new LinearLayout(context);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(BLOCK_PADDING, BLOCK_PADDING, BLOCK_PADDING, BLOCK_PADDING);
            block.setBackground(rippleMask());
            TextView title = text(context, 24f, DIM_COLOR);
            title.setText("Next alarm");
            title.setMinHeight(32);
            title.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            titleParams.bottomMargin = 8;
            block.addView(title, titleParams);
            description = text(context, 30f, TEXT_COLOR);
            description.setMinHeight(38);
            description.setGravity(Gravity.CENTER_VERTICAL);
            block.addView(description, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            time = text(context, 24f, DIM_COLOR);
            time.setMinHeight(32);
            time.setGravity(Gravity.CENTER_VERTICAL);
            time.setSingleLine(true);
            block.addView(time, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            blockParams.leftMargin = 12;
            blockParams.rightMargin = 12;
            content.addView(block, blockParams);
            TextView dismiss = text(context, 30f, TEXT_COLOR);
            dismiss.setText("Dismiss");
            dismiss.setGravity(Gravity.CENTER);
            dismiss.setBackground(ripple(DISMISS_COLOR, DISMISS_RADIUS));
            dismiss.setOnClickListener(view -> dismissNext());
            LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(DISMISS_WIDTH, DISMISS_HEIGHT);
            dismissParams.topMargin = 16;
            dismissParams.gravity = Gravity.CENTER_HORIZONTAL;
            content.addView(dismiss, dismissParams);
            addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            empty = emptyBlock(context, R.drawable.ic_no_item_alarm, "No alarms in the next 24 hours");
            addView(empty, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            refresh();
        }

        private void dismissNext() {
            Intent intent = new Intent(AlarmClock.ACTION_DISMISS_ALARM);
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            } catch (RuntimeException failed) {
                Log.e(TAG, "Could not dismiss the alarm", failed);
            }
            refresh();
        }

        private void refresh() {
            AlarmManager.AlarmClockInfo next = alarms.getNextAlarmClock();
            long now = System.currentTimeMillis();
            boolean upcoming = next != null && next.getTriggerTime() - now < DAY_MS;
            content.setVisibility(upcoming ? VISIBLE : GONE);
            empty.setVisibility(upcoming ? GONE : VISIBLE);
            if (!upcoming) {
                return;
            }
            long minutes = Math.max(0, (next.getTriggerTime() - now) / MINUTE_MS);
            description.setText(describe(minutes));
            String pattern = DateFormat.is24HourFormat(getContext()) ? "EEE HH:mm" : "EEE h:mm a";
            time.setText(DateFormat.format(pattern, new Date(next.getTriggerTime())));
        }

        private static String describe(long totalMinutes) {
            long hours = totalMinutes / 60;
            long minutes = totalMinutes % 60;
            if (totalMinutes < 1) {
                return "Alarm will go off soon";
            }
            String hourPart = hours == 1 ? "1 hour" : hours + " hours";
            String minutePart = minutes == 1 ? "1 minute" : minutes + " minutes";
            if (hours == 0) {
                return "Alarm in " + minutePart;
            }
            return minutes == 0 ? "Alarm in " + hourPart : "Alarm in " + hourPart + " " + minutePart;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            IntentFilter filter = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            filter.addAction(Intent.ACTION_TIME_TICK);
            getContext().registerReceiver(receiver, filter);
            refresh();
        }

        @Override
        protected void onDetachedFromWindow() {
            getContext().unregisterReceiver(receiver);
            super.onDetachedFromWindow();
        }
    }

    static final class ScheduleTile extends FrameLayout {
        private static final String[] COLUMNS = {
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR
        };
        private static final int ROW_HEIGHT = 75;
        private static final int DOT_SIZE = 12;
        private static final int MAX_EVENTS = 20;

        private final ScrollView scroll;
        private final LinearLayout list;
        private final LinearLayout empty;
        private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };

        ScheduleTile(Context context) {
            super(context);
            scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(OVER_SCROLL_NEVER);
            scroll.setFillViewport(true);
            list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, 22, 0, 22);
            list.setGravity(Gravity.CENTER_VERTICAL);
            scroll.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            scrollParams.topMargin = 14;
            scrollParams.bottomMargin = 14;
            addView(scroll, scrollParams);
            empty = emptyBlock(context, R.drawable.ic_no_item_schedule, "No events today");
            addView(empty, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            refresh();
        }

        private void refresh() {
            list.removeAllViews();
            Calendar calendar = Calendar.getInstance();
            long now = calendar.getTimeInMillis();
            calendar.set(Calendar.HOUR_OF_DAY, 23);
            calendar.set(Calendar.MINUTE, 59);
            calendar.set(Calendar.SECOND, 59);
            long end = calendar.getTimeInMillis();
            Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(uri, now);
            ContentUris.appendId(uri, end);
            int count = 0;
            try (Cursor cursor = getContext().getContentResolver().query(uri.build(), COLUMNS, null, null, CalendarContract.Instances.BEGIN)) {
                while (cursor != null && cursor.moveToNext() && count < MAX_EVENTS) {
                    list.addView(row(cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getInt(3) != 0, cursor.getInt(4)));
                    count++;
                }
            } catch (RuntimeException failed) {
                Log.e(TAG, "Could not read the calendar", failed);
            }
            scroll.setVisibility(count > 0 ? VISIBLE : GONE);
            empty.setVisibility(count > 0 ? GONE : VISIBLE);
        }

        private View row(String title, long begin, long finish, boolean allDay, int color) {
            Context context = getContext();
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_VERTICAL);
            column.setPadding(12, 0, 12, 0);
            column.setBackground(rippleMask());
            LinearLayout top = new LinearLayout(context);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(context);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.alpha(color) == 0 ? TEXT_COLOR : color | 0xFF000000);
            dot.setBackground(shape);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(DOT_SIZE, DOT_SIZE);
            dotParams.rightMargin = 12;
            top.addView(dot, dotParams);
            TextView description = text(context, 30f, TEXT_COLOR);
            description.setText(TextUtils.isEmpty(title) ? "No title" : title);
            description.setMaxLines(1);
            description.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(description, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            column.addView(top);
            TextView time = text(context, 26f, DIM_COLOR);
            time.setMaxLines(1);
            time.setEllipsize(TextUtils.TruncateAt.END);
            time.setText(allDay ? "All day" : range(begin, finish));
            column.addView(time);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ROW_HEIGHT);
            params.leftMargin = 12;
            params.rightMargin = 12;
            params.bottomMargin = 14;
            column.setLayoutParams(params);
            return column;
        }

        private String range(long begin, long finish) {
            String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm a";
            return String.format(Locale.getDefault(), "%s - %s", DateFormat.format(pattern, begin), DateFormat.format(pattern, finish));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                getContext().getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, observer);
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Calendar provider is unavailable", unavailable);
            }
            refresh();
        }

        @Override
        protected void onDetachedFromWindow() {
            getContext().getContentResolver().unregisterContentObserver(observer);
            super.onDetachedFromWindow();
        }
    }

    static final class AddTile extends FrameLayout {
        private static final int SIZE = 224;
        private static final float RADIUS = 112f;

        AddTile(Context context, Runnable onClick) {
            super(context);
            LinearLayout button = new LinearLayout(context);
            button.setOrientation(LinearLayout.VERTICAL);
            button.setGravity(Gravity.CENTER);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(RADIUS);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask));
            button.setMinimumWidth(SIZE);
            button.setPadding(24, 0, 24, 0);
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.ic_add_button);
            button.addView(icon, new LinearLayout.LayoutParams(82, 82));
            TextView label = text(context, 30f, TEXT_COLOR);
            label.setText("Add Widget");
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = 24;
            button.addView(label, labelParams);
            button.setOnClickListener(view -> onClick.run());
            addView(button, new LayoutParams(LayoutParams.WRAP_CONTENT, SIZE, Gravity.CENTER));
        }
    }

    static final class SecureFrame extends FrameLayout {
        private final View cover;

        SecureFrame(Context context, View tile, CharSequence name) {
            super(context);
            addView(tile, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            LinearLayout overlay = new LinearLayout(context);
            overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setGravity(Gravity.CENTER);
            overlay.setBackgroundColor(Color.BLACK);
            overlay.setClickable(true);
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.ic_security);
            icon.setImageTintList(ColorStateList.valueOf(TEXT_COLOR));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(60, 60);
            iconParams.bottomMargin = 16;
            overlay.addView(icon, iconParams);
            TextView label = text(context, 26f, TEXT_COLOR);
            label.setGravity(Gravity.CENTER);
            label.setText(name);
            overlay.addView(label);
            TextView hint = text(context, 22f, DIM_COLOR);
            hint.setGravity(Gravity.CENTER);
            hint.setText("Unlock your phone to view");
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            hintParams.topMargin = 8;
            overlay.addView(hint, hintParams);
            cover = overlay;
            addView(cover, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void setLocked(boolean locked) {
            cover.setVisibility(locked ? VISIBLE : GONE);
        }
    }
}
