package com.samsung.android.feature;

public class SemCscFeature {
    private static final SemCscFeature INSTANCE = new SemCscFeature();

    public static SemCscFeature getInstance() {
        return INSTANCE;
    }

    public boolean getBoolean(String tag) {
        return false;
    }

    public boolean getBoolean(String tag, boolean fallback) {
        return fallback;
    }

    public boolean getBoolean(int slot, String tag) {
        return false;
    }

    public boolean getBoolean(int slot, String tag, boolean fallback) {
        return fallback;
    }

    public int getInt(String tag, int fallback) {
        return fallback;
    }

    public String getString(String tag) {
        return "";
    }

    public String getString(String tag, String fallback) {
        return fallback;
    }

    public String getString(int slot, String tag, String fallback) {
        return fallback;
    }
}
