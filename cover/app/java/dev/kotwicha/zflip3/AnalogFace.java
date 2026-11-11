package dev.kotwicha.zflip3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.Calendar;

final class AnalogFace extends View {
    private static final float SECOND_TURN = 6f;
    private static final float MINUTE_TURN = 6f;
    private static final float HOUR_TURN = 30f;
    private static final long TICK_MS = 1000;

    private final Drawable index;
    private final Drawable hour;
    private final Drawable minute;
    private final Drawable second;
    private final Drawable pin;
    private final Calendar calendar = Calendar.getInstance();
    private final Runnable tick = this::onTick;

    AnalogFace(Context context, boolean numbers) {
        super(context);
        index = load(context, numbers ? R.drawable.face_num_index : R.drawable.face_dot_index);
        hour = load(context, numbers ? R.drawable.face_num_hour : R.drawable.face_dot_hour);
        minute = load(context, numbers ? R.drawable.face_num_min : R.drawable.face_dot_min);
        second = load(context, numbers ? R.drawable.face_num_sec : R.drawable.face_dot_sec);
        pin = load(context, numbers ? R.drawable.face_num_pin : R.drawable.face_dot_pin);
    }

    private static Drawable load(Context context, int resource) {
        return context.getDrawable(resource).mutate();
    }

    void setColor(int color) {
        for (Drawable layer : new Drawable[] {index, hour, minute, second, pin}) {
            layer.setTint(color);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        index.setBounds(0, 0, w, h);
        place(hour, w, h);
        place(minute, w, h);
        place(second, w, h);
        place(pin, w, h);
    }

    private static void place(Drawable layer, int w, int h) {
        int width = layer.getIntrinsicWidth();
        int height = layer.getIntrinsicHeight();
        int left = (w - width) / 2;
        int top = (h - height) / 2;
        layer.setBounds(left, top, left + width, top + height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        calendar.setTimeInMillis(System.currentTimeMillis());
        float seconds = calendar.get(Calendar.SECOND);
        float minutes = calendar.get(Calendar.MINUTE) + seconds / 60f;
        float hours = calendar.get(Calendar.HOUR) + minutes / 60f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        index.draw(canvas);
        drawHand(canvas, hour, hours * HOUR_TURN, cx, cy);
        drawHand(canvas, minute, minutes * MINUTE_TURN, cx, cy);
        drawHand(canvas, second, seconds * SECOND_TURN, cx, cy);
        pin.draw(canvas);
    }

    private static void drawHand(Canvas canvas, Drawable hand, float angle, float cx, float cy) {
        canvas.save();
        canvas.rotate(angle, cx, cy);
        hand.draw(canvas);
        canvas.restore();
    }

    private void onTick() {
        invalidate();
        schedule();
    }

    private void schedule() {
        removeCallbacks(tick);
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
            postDelayed(tick, TICK_MS - System.currentTimeMillis() % TICK_MS);
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        schedule();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        schedule();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
