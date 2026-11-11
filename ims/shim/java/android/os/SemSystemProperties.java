package android.os;

import java.lang.reflect.Method;

public class SemSystemProperties {
    private static final String COUNTRY_KEY = "ro.csc.countryiso_code";
    private static final Method GET;
    private static final Method SET;

    static {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            GET = properties.getMethod("get", String.class, String.class);
            SET = properties.getMethod("set", String.class, String.class);
        } catch (ReflectiveOperationException failed) {
            throw new ExceptionInInitializerError(failed);
        }
    }

    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String fallback) {
        try {
            return (String) GET.invoke(null, key, fallback);
        } catch (ReflectiveOperationException failed) {
            return fallback;
        }
    }

    public static boolean getBoolean(String key, boolean fallback) {
        String value = get(key, "");
        switch (value) {
            case "1":
            case "true":
            case "y":
            case "yes":
            case "on":
                return true;
            case "0":
            case "false":
            case "n":
            case "no":
            case "off":
                return false;
            default:
                return fallback;
        }
    }

    public static int getInt(String key, int fallback) {
        try {
            return Integer.decode(get(key, ""));
        } catch (NumberFormatException notSet) {
            return fallback;
        }
    }

    public static void set(String key, String value) {
        try {
            SET.invoke(null, key, value);
        } catch (ReflectiveOperationException failed) {
            android.util.Log.e("SemSystemProperties", "Could not set " + key, failed);
        }
    }

    public static String getCountryIso() {
        return get(COUNTRY_KEY, "");
    }
}
