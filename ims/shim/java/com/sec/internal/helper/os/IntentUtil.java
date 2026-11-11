package com.sec.internal.helper.os;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

public class IntentUtil {
    public static final int FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000;
    private static final String TAG = "IntentUtil";

    public static void sendBroadcast(Context context, Intent intent) {
        intent.addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND);
        try {
            context.sendBroadcast(intent);
        } catch (SecurityException denied) {
            Log.w(TAG, "Could not send " + intent.getAction() + ": " + denied.getMessage());
        }
    }

    public static void sendBroadcast(Context context, Intent intent, UserHandle user) {
        intent.addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND);
        try {
            context.sendBroadcastAsUser(intent, user);
        } catch (SecurityException denied) {
            Log.w(TAG, "Could not send " + intent.getAction() + ": " + denied.getMessage());
        }
    }

    public static void sendBroadcast(Context context, Intent intent, UserHandle user, String permission) {
        intent.addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND);
        try {
            context.sendBroadcastAsUser(intent, user, permission);
        } catch (SecurityException denied) {
            Log.w(TAG, "Could not send " + intent.getAction() + ": " + denied.getMessage());
        }
    }
}
