package dev.kotwicha.zflip3;

import android.animation.AnimatorListenerAdapter;
import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

final class SquigglyProgress extends Drawable {
    private static final float TRANSITION_PERIODS = 1.5f;
    private static final float MIN_WAVE_ENDPOINT = 0.2f;
    private static final float MATCHED_WAVE_ENDPOINT = 0.6f;
    private static final int LINE_ALPHA = 77;
    private static final float MAX_LEVEL = 10000f;
    private static final long WAVE_IN_DELAY_MS = 60;
    private static final long WAVE_IN_MS = 800;
    private static final long WAVE_OUT_MS = 550;
    private static final float TWO_PI = (float) (Math.PI * 2);

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float waveLength;
    private final float amplitude;
    private final float phaseSpeed;
    private final float strokeWidth;
    private final int color;
    private ValueAnimator heightAnimator;
    private float heightFraction;
    private float phaseOffset;
    private long lastFrameTime = -1;
    private boolean animating;
    private boolean transitionEnabled = true;

    SquigglyProgress(float waveLength, float amplitude, float phaseSpeed, float strokeWidth, int color) {
        this.waveLength = waveLength;
        this.amplitude = amplitude;
        this.phaseSpeed = phaseSpeed;
        this.strokeWidth = strokeWidth;
        this.color = color;
        for (Paint paint : new Paint[] {wavePaint, linePaint}) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(strokeWidth);
        }
        setAlpha(255);
    }

    void setAnimating(boolean value) {
        if (animating == value) {
            return;
        }
        animating = value;
        if (value) {
            lastFrameTime = SystemClock.uptimeMillis();
        }
        if (heightAnimator != null) {
            heightAnimator.cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(heightFraction, value ? 1f : 0f);
        if (value) {
            animator.setStartDelay(WAVE_IN_DELAY_MS);
            animator.setDuration(WAVE_IN_MS);
            animator.setInterpolator(Motion.EMPHASIZED_DECELERATE);
        } else {
            animator.setDuration(WAVE_OUT_MS);
            animator.setInterpolator(Motion.STANDARD_DECELERATE);
        }
        animator.addUpdateListener(update -> {
            heightFraction = (float) update.getAnimatedValue();
            invalidateSelf();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator ended) {
                if (heightAnimator == ended) {
                    heightAnimator = null;
                }
            }
        });
        heightAnimator = animator;
        animator.start();
        invalidateSelf();
    }

    void setTransitionEnabled(boolean value) {
        transitionEnabled = value;
        invalidateSelf();
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private static float lerpInverse(float start, float end, float value) {
        return start == end ? 0f : (value - start) / (end - start);
    }

    private float amplitudeAt(float x, float sign, float waveProgress) {
        if (!transitionEnabled) {
            return sign * heightFraction * amplitude;
        }
        float length = TRANSITION_PERIODS * waveLength;
        float coefficient = Motion.clamp(lerpInverse(waveProgress + length / 2f, waveProgress - length / 2f, x));
        return sign * heightFraction * amplitude * coefficient;
    }

    @Override
    public void draw(Canvas canvas) {
        if (animating) {
            invalidateSelf();
            long now = SystemClock.uptimeMillis();
            phaseOffset = (phaseOffset + (now - lastFrameTime) / 1000f * phaseSpeed) % waveLength;
            lastFrameTime = now;
        }
        float progress = getLevel() / MAX_LEVEL;
        float totalWidth = getBounds().width();
        float progressPx = totalWidth * progress;
        float endpoint = !transitionEnabled || progress > MATCHED_WAVE_ENDPOINT
            ? progress
            : lerp(MIN_WAVE_ENDPOINT, MATCHED_WAVE_ENDPOINT, lerpInverse(0f, MATCHED_WAVE_ENDPOINT, progress));
        float waveProgress = totalWidth * endpoint;
        float waveStart = -phaseOffset - waveLength / 2f;
        float waveEnd = transitionEnabled ? totalWidth : waveProgress;

        path.rewind();
        path.moveTo(waveStart, 0f);
        float x = waveStart;
        float sign = 1f;
        float currentAmplitude = amplitudeAt(x, sign, waveProgress);
        float step = waveLength / 2f;
        while (x < waveEnd) {
            sign = -sign;
            float nextX = x + step;
            float midX = x + step / 2f;
            float nextAmplitude = amplitudeAt(nextX, sign, waveProgress);
            path.cubicTo(midX, currentAmplitude, midX, nextAmplitude, nextX, nextAmplitude);
            currentAmplitude = nextAmplitude;
            x = nextX;
        }

        float clipTop = amplitude + strokeWidth;
        canvas.save();
        canvas.translate(getBounds().left, getBounds().centerY());
        canvas.save();
        canvas.clipRect(0f, -clipTop, progressPx, clipTop);
        canvas.drawPath(path, wavePaint);
        canvas.restore();
        if (transitionEnabled) {
            canvas.save();
            canvas.clipRect(progressPx, -clipTop, totalWidth, clipTop);
            canvas.drawPath(path, linePaint);
            canvas.restore();
        } else {
            canvas.drawLine(progressPx, 0f, totalWidth, 0f, linePaint);
        }
        float startAmplitude = (float) Math.cos(Math.abs(waveStart) / waveLength * TWO_PI);
        canvas.drawPoint(0f, startAmplitude * amplitude * heightFraction, wavePaint);
        canvas.restore();
    }

    @Override
    protected boolean onLevelChange(int level) {
        return animating;
    }

    @Override
    public void setAlpha(int alpha) {
        int base = color & 0x00FFFFFF;
        wavePaint.setColor(base | (alpha << 24));
        linePaint.setColor(base | ((int) (LINE_ALPHA * (alpha / 255f)) << 24));
    }

    @Override
    public int getAlpha() {
        return Color.alpha(wavePaint.getColor());
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        wavePaint.setColorFilter(filter);
        linePaint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
