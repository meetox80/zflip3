package dev.kotwicha.zflip3;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;

public final class ClockPage extends FrameLayout {
    static final int TYPE_HORIZONTAL = 2;
    static final int TYPE_TYPO = 9;
    static final int TYPE_TYPO_RIGHT = 12;
    static final int TYPE_BIG_TIME = 13;
    static final int TYPE_BIG_TIME_BOTTOM = 14;
    static final int TYPE_SMALL = 11;
    static final int TYPE_SMALL_BOTTOM = 15;
    static final int TYPE_ANALOG_NUMBER = 10000;
    static final int TYPE_ANALOG_DOT = 10002;
    static final int[] SELECTABLE = {
        TYPE_HORIZONTAL, TYPE_TYPO, TYPE_TYPO_RIGHT, TYPE_BIG_TIME, TYPE_BIG_TIME_BOTTOM,
        TYPE_SMALL, TYPE_SMALL_BOTTOM, TYPE_ANALOG_NUMBER, TYPE_ANALOG_DOT
    };
    static final int[] PALETTE = {
        0xFFFAFAFA, 0xFFF2EBC6, 0xFFF5CCCC, 0xFFC3CEF4, 0xFFC8C3ED,
        0xFFC0EBDE, 0xFFD6E6C8, 0xFFBDE6E9, 0xFFC1DEEC, 0xFFC2DCF6
    };

    private static final int TYPO_MIN_WIDTH = 424;
    private static final int ANALOG_ROW_WIDTH = 425;
    private static final int ANALOG_SIZE = 204;
    private static final int ANALOG_COLUMN_PADDING = 28;
    private static final int BATTERY_ICON_WIDTH = 12;
    private static final int BATTERY_ICON_HEIGHT = 21;
    private static final int BATTERY_ICON_MARGIN_END = 7;
    private static final float BATTERY_TEXT_SIZE = 24f;
    private static final float BATTERY_ALPHA = 0.93f;
    private static final int BATTERY_TINT_ALPHA = 178;
    private static final int DATE_WEIGHT = 400;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                updateBattery(intent);
            } else {
                updateClock();
            }
        }
    };

    private TextView time;
    private TextView date;
    private TextView batteryText;
    private BatteryIcon batteryIcon;
    private AnalogFace analog;
    private int paletteIndex;
    private int type = -1;
    private Intent lastBattery;

    public ClockPage(Context context) {
        this(context, TYPE_HORIZONTAL, 0);
    }

    public ClockPage(Context context, int type, int paletteIndex) {
        super(context);
        this.paletteIndex = paletteIndex;
        setFace(type);
    }

    void setFace(int newType) {
        if (newType == type) {
            return;
        }
        type = newType;
        removeAllViews();
        time = null;
        analog = null;
        switch (type) {
            case TYPE_TYPO:
                buildTypo(Gravity.START);
                break;
            case TYPE_TYPO_RIGHT:
                buildTypo(Gravity.END);
                break;
            case TYPE_BIG_TIME:
                buildBigTime(false);
                break;
            case TYPE_BIG_TIME_BOTTOM:
                buildBigTime(true);
                break;
            case TYPE_SMALL:
                buildSmall(false);
                break;
            case TYPE_SMALL_BOTTOM:
                buildSmall(true);
                break;
            case TYPE_ANALOG_NUMBER:
                buildAnalog(true);
                break;
            case TYPE_ANALOG_DOT:
                buildAnalog(false);
                break;
            default:
                buildHorizontal();
                break;
        }
        setPaletteIndex(paletteIndex);
        updateClock();
        if (lastBattery != null) {
            updateBattery(lastBattery);
        }
    }

    int getFace() {
        return type;
    }

    private TextView text(float size, Typeface typeface) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTypeface(typeface);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Typeface clockFont() {
        return CoverFonts.clock(getContext());
    }

    private Typeface textFont() {
        return CoverFonts.oneUi(getContext(), DATE_WEIGHT);
    }

    private LinearLayout batteryRow() {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setAlpha(BATTERY_ALPHA);
        batteryIcon = new BatteryIcon(context);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(BATTERY_ICON_WIDTH, BATTERY_ICON_HEIGHT);
        iconParams.rightMargin = BATTERY_ICON_MARGIN_END;
        row.addView(batteryIcon, iconParams);
        batteryText = text(BATTERY_TEXT_SIZE, textFont());
        row.addView(batteryText, wrap());
        return row;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout column(int gravity) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(gravity);
        return column;
    }

    private void buildHorizontal() {
        LinearLayout column = column(Gravity.CENTER);
        time = text(100f, clockFont());
        LinearLayout.LayoutParams timeParams = wrap();
        timeParams.bottomMargin = 2;
        column.addView(time, timeParams);
        date = text(28f, textFont());
        column.addView(date, wrap());
        LinearLayout.LayoutParams rowParams = wrap();
        rowParams.topMargin = 10;
        column.addView(batteryRow(), rowParams);
        addView(column, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private void buildTypo(int side) {
        LinearLayout column = column(side);
        column.setMinimumWidth(TYPO_MIN_WIDTH);
        time = text(78f, clockFont());
        LinearLayout.LayoutParams timeParams = wrap();
        timeParams.bottomMargin = 2;
        column.addView(time, timeParams);
        date = text(28f, textFont());
        LinearLayout.LayoutParams dateParams = wrap();
        dateParams.bottomMargin = 3;
        column.addView(date, dateParams);
        LinearLayout.LayoutParams rowParams = wrap();
        if (side == Gravity.START) {
            rowParams.leftMargin = 2;
        }
        column.addView(batteryRow(), rowParams);
        addView(column, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private void buildBigTime(boolean bottom) {
        int side = bottom ? Gravity.END : Gravity.START;
        LinearLayout column = column(side);
        LinearLayout dateRow = new LinearLayout(getContext());
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        date = text(24f, textFont());
        LinearLayout battery = batteryRow();
        LinearLayout.LayoutParams batteryParams = wrap();
        if (bottom) {
            batteryParams.rightMargin = 11;
            dateRow.addView(battery, batteryParams);
            dateRow.addView(date, wrap());
        } else {
            batteryParams.leftMargin = 11;
            dateRow.addView(date, wrap());
            dateRow.addView(battery, batteryParams);
        }
        time = text(50f, textFont());
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        if (bottom) {
            LinearLayout.LayoutParams timeParams = wrap();
            timeParams.bottomMargin = 2;
            column.addView(time, timeParams);
            column.addView(dateRow, wrap());
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.bottomMargin = 24;
            params.rightMargin = 32;
        } else {
            LinearLayout.LayoutParams rowParams = wrap();
            rowParams.bottomMargin = 2;
            column.addView(dateRow, rowParams);
            column.addView(time, wrap());
            params.gravity = Gravity.TOP | Gravity.START;
            params.topMargin = 24;
            params.leftMargin = 32;
        }
        addView(column, params);
    }

    private void buildSmall(boolean bottom) {
        int edge = bottom ? Gravity.BOTTOM : Gravity.TOP;
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        time = text(24f, textFont());
        LinearLayout.LayoutParams timeParams = wrap();
        timeParams.rightMargin = 12;
        row.addView(time, timeParams);
        date = text(24f, textFont());
        row.addView(date, wrap());
        LayoutParams rowParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, edge | Gravity.START);
        rowParams.leftMargin = 28;
        LayoutParams batteryParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, edge | Gravity.END);
        batteryParams.rightMargin = 28;
        if (bottom) {
            rowParams.bottomMargin = 18;
            batteryParams.bottomMargin = 18;
        } else {
            rowParams.topMargin = 18;
            batteryParams.topMargin = 18;
        }
        addView(row, rowParams);
        addView(batteryRow(), batteryParams);
    }

    private void buildAnalog(boolean numbers) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        analog = new AnalogFace(context, numbers);
        row.addView(analog, new LinearLayout.LayoutParams(ANALOG_SIZE, ANALOG_SIZE));
        LinearLayout info = column(Gravity.START);
        info.setPadding(ANALOG_COLUMN_PADDING, 0, 0, 0);
        date = text(30f, textFont());
        info.addView(date, wrap());
        info.addView(batteryRow(), wrap());
        row.addView(info, wrap());
        addView(row, new LayoutParams(ANALOG_ROW_WIDTH, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public void setPaletteIndex(int index) {
        paletteIndex = Math.max(0, Math.min(PALETTE.length - 1, index));
        int color = PALETTE[paletteIndex];
        if (time != null) {
            time.setTextColor(color);
        }
        if (analog != null) {
            analog.setColor(color);
        }
        date.setTextColor(color);
        batteryText.setTextColor(color);
        batteryIcon.setColor((color & 0x00FFFFFF) | (BATTERY_TINT_ALPHA << 24));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = getContext().registerReceiver(receiver, filter);
        if (sticky != null) {
            updateBattery(sticky);
        }
        updateClock();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(receiver);
        super.onDetachedFromWindow();
    }

    private void updateClock() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        if (time != null) {
            String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm";
            time.setText(DateFormat.format(pattern, now));
        }
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), now));
    }

    private void updateBattery(Intent intent) {
        lastBattery = intent;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        if (level < 0 || scale <= 0) {
            return;
        }
        int percent = level * 100 / scale;
        batteryText.setText(String.format(Locale.getDefault(), "%d%%", percent));
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        batteryIcon.setBattery(percent / 100f, charging);
    }
}
