package dev.kotwicha.zflip3;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class CoverCommands {
    static final String EXTRA_SHOW_VOLUME = "show_volume";
    static final String EXTRA_VOLUME_WOKE = "volume_woke";

    private static final String TAG = "Flip3Cover";
    static final String BRIGHTNESS = "brightness";
    static final String COMMAND = "command";

    private CoverCommands() {
    }

    static void write(Context context, String name, String value) {
        Context storage = context.createDeviceProtectedStorageContext();
        File dir = storage.getFilesDir();
        File temp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            Log.e(TAG, "Could not write " + name, failed);
            return;
        }
        if (!temp.renameTo(new File(dir, name))) {
            Log.e(TAG, "Could not publish " + name);
        }
    }
}
