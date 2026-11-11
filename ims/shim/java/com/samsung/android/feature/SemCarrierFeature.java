package com.samsung.android.feature;

public class SemCarrierFeature {
    private static final SemCarrierFeature INSTANCE = new SemCarrierFeature();
    private static final int NO_CARRIER = -1;

    public static SemCarrierFeature getInstance() {
        return INSTANCE;
    }

    public int getCarrierId(int slot, boolean refresh) {
        return NO_CARRIER;
    }

    public boolean getBoolean(int slot, String tag, boolean fallback, boolean refresh) {
        return fallback;
    }

    public int getInt(int slot, String tag, int fallback, boolean refresh) {
        return fallback;
    }

    public String getString(int slot, String tag, String fallback, boolean refresh) {
        return fallback;
    }
}
