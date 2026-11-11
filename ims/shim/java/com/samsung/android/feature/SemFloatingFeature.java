package com.samsung.android.feature;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

public class SemFloatingFeature {
    private static final String TAG = "SemFloatingFeature";
    private static final String PATH = "/system/etc/floating_feature.xml";
    private static final SemFloatingFeature INSTANCE = new SemFloatingFeature();

    private final Map<String, String> features = new HashMap<>();

    private SemFloatingFeature() {
        try (FileInputStream in = new FileInputStream(PATH)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.getDepth() == 2) {
                    String tag = parser.getName();
                    if (parser.next() == XmlPullParser.TEXT) {
                        features.put(tag, parser.getText());
                    }
                }
                event = parser.next();
            }
        } catch (Exception failed) {
            Log.e(TAG, "Could not read " + PATH, failed);
        }
    }

    public static SemFloatingFeature getInstance() {
        return INSTANCE;
    }

    public boolean getBoolean(String tag) {
        return "TRUE".equalsIgnoreCase(features.get(tag));
    }

    public String getString(String tag) {
        String value = features.get(tag);
        return value == null ? "" : value;
    }
}
