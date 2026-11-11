package dev.kotwicha.zflip3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

final class PillBar extends View {
    private static final float RADIUS = 23f;
    private static final int TRACK = 0xFF797979;
    private static final int FILL = 0xFFFFFFFF;
    private static final int ICON_TINT = 0xFF252525;
    private static final int ICON_SIZE = 36;
    private static final int ICON_MARGIN = 7;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private Drawable icon;
    private int iconResource;
    private float value;

    PillBar(Context context) {
        super(context);
        trackPaint.setColor(TRACK);
        fillPaint.setColor(FILL);
    }

    void setValue(float value) {
        this.value = Motion.clamp(value);
        invalidate();
    }

    void setIcon(int resource) {
        if (resource == iconResource) {
            return;
        }
        iconResource = resource;
        icon = getContext().getDrawable(resource).mutate();
        icon.setTint(ICON_TINT);
        placeIcon();
        invalidate();
    }

    private void placeIcon() {
        if (icon != null) {
            int top = (getHeight() - ICON_SIZE) / 2;
            icon.setBounds(ICON_MARGIN, top, ICON_MARGIN + ICON_SIZE, top + ICON_SIZE);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        clip.reset();
        rect.set(0, 0, w, h);
        clip.addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW);
        placeIcon();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(0, 0, w, h, trackPaint);
        canvas.drawRect(0, 0, h + (w - h) * value, h, fillPaint);
        canvas.restore();
        if (icon != null) {
            icon.draw(canvas);
        }
    }
}
