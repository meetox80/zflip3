package dev.kotwicha.zflip3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class BatteryIcon extends View {
    private static final float NUB_HEIGHT = 2f;
    private static final float NUB_WIDTH = 6f;
    private static final float STROKE = 1.5f;
    private static final float INSET = 3f;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path boltPath = new Path();
    private float level;
    private boolean charging;

    BatteryIcon(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(STROKE);
        fill.setStyle(Paint.Style.FILL);
        bolt.setStyle(Paint.Style.FILL);
        bolt.setColor(Color.BLACK);
    }

    void setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    void setBattery(float level, boolean charging) {
        this.level = Math.max(0f, Math.min(1f, level));
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        rect.set((w - NUB_WIDTH) / 2f, 0f, (w + NUB_WIDTH) / 2f, NUB_HEIGHT + 1f);
        canvas.drawRoundRect(rect, 1f, 1f, fill);
        float half = STROKE / 2f;
        rect.set(half, NUB_HEIGHT + half, w - half, h - half);
        canvas.drawRoundRect(rect, 2.5f, 2.5f, stroke);
        float top = NUB_HEIGHT + INSET;
        float bottom = h - INSET;
        float fillTop = bottom - (bottom - top) * level;
        rect.set(INSET, fillTop, w - INSET, bottom);
        canvas.drawRoundRect(rect, 1f, 1f, fill);
        if (charging) {
            float cx = w / 2f;
            float cy = (top + bottom) / 2f;
            boltPath.reset();
            boltPath.moveTo(cx + 1.2f, cy - 5f);
            boltPath.lineTo(cx - 2.8f, cy + 0.8f);
            boltPath.lineTo(cx - 0.3f, cy + 0.8f);
            boltPath.lineTo(cx - 1.2f, cy + 5f);
            boltPath.lineTo(cx + 2.8f, cy - 0.8f);
            boltPath.lineTo(cx + 0.3f, cy - 0.8f);
            boltPath.close();
            canvas.drawPath(boltPath, bolt);
        }
    }
}
