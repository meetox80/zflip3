package dev.kotwicha.zflip3;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

final class CoverFonts {
    private static final String CLOCK = "fonts/Clock2021.ttf";
    private static final String ONE_UI = "fonts/OneUISans-VF.ttf";

    private static Typeface clock;
    private static final SparseArray<Typeface> ONE_UI_WEIGHTS = new SparseArray<>();

    private CoverFonts() {
    }

    static synchronized Typeface clock(Context context) {
        if (clock == null) {
            clock = Typeface.createFromAsset(context.getAssets(), CLOCK);
        }
        return clock;
    }

    static synchronized Typeface oneUi(Context context, int weight) {
        Typeface typeface = ONE_UI_WEIGHTS.get(weight);
        if (typeface == null) {
            typeface = new Typeface.Builder(context.getAssets(), ONE_UI)
                .setFontVariationSettings("'wght' " + weight)
                .setWeight(weight)
                .build();
            ONE_UI_WEIGHTS.put(weight, typeface);
        }
        return typeface;
    }
}
