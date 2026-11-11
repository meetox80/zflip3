package dev.kotwicha.zflip3;

import android.view.animation.PathInterpolator;

final class Motion {
    static final PathInterpolator PAGE = new PathInterpolator(0.17f, 0.17f, 0.5f, 1f);
    static final PathInterpolator SCRIM = new PathInterpolator(0.17f, 0.17f, 0.4f, 1f);
    static final PathInterpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final PathInterpolator STANDARD_DECELERATE = new PathInterpolator(0f, 0f, 0f, 1f);
    static final long PAGE_MS = 300;
    private static final float[][] SCALE_CURVE = {{0f, 0.05f, 0.61f}, {0.61f, 0.999f, 1f}};

    private Motion() {
    }

    static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    static float scaleCurve(float input) {
        float t = clamp(input);
        int segments = SCALE_CURVE.length;
        int index = Math.min(segments - 1, (int) (segments * t));
        float u = (t - (float) index / segments) * segments;
        float[] s = SCALE_CURVE[index];
        return s[0] + u * (2f * (1f - u) * (s[1] - s[0]) + u * (s[2] - s[0]));
    }
}
