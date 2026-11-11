package dev.kotwicha.zflip3;

import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class PreviewService extends Service {
    private static final String TAG = "Flip3Cover";
    private static final int WIDTH = 512;
    private static final int HEIGHT = 260;
    private static final long RENDER_WAIT_MS = 1200;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        float page = intent != null ? intent.getFloatExtra("page", 1f) : 1f;
        float panel = intent != null ? intent.getFloatExtra("panel", 0f) : 0f;
        String name = intent != null && intent.hasExtra("name") ? intent.getStringExtra("name") : "preview";
        ImageReader reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
        DisplayManager displays = getSystemService(DisplayManager.class);
        VirtualDisplay virtual = displays.createVirtualDisplay("flip3-cover-preview", WIDTH, HEIGHT, DisplayMetrics.DENSITY_MEDIUM, reader.getSurface(), 0);
        Presentation presentation = new Presentation(this, virtual.getDisplay(), R.style.Cover);
        Context base = presentation.getContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.densityDpi = DisplayMetrics.DENSITY_MEDIUM;
        config.fontScale = 1f;
        CoverRoot root = new CoverRoot(base.createConfigurationContext(config), new QuickPanel.Host() {
            @Override
            public void onBrightnessLevel(int level, boolean committed) {
            }

            @Override
            public void onSystemCommand(String command) {
            }
        });
        root.startWidgets();
        presentation.setContentView(root);
        presentation.show();
        root.post(() -> root.showPageForPreview(page, panel));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            capture(reader, name);
            root.stopWidgets();
            presentation.dismiss();
            virtual.release();
            reader.close();
            stopSelf(startId);
        }, RENDER_WAIT_MS);
        return START_NOT_STICKY;
    }

    private void capture(ImageReader reader, String name) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            Log.e(TAG, "Preview produced no frame");
            return;
        }
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int rowWidth = plane.getRowStride() / plane.getPixelStride();
        Bitmap wide = Bitmap.createBitmap(rowWidth, HEIGHT, Bitmap.Config.ARGB_8888);
        wide.copyPixelsFromBuffer(pixels);
        image.close();
        Bitmap frame = Bitmap.createBitmap(wide, 0, 0, WIDTH, HEIGHT);
        File out = new File(getCacheDir(), name + ".png");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            frame.compress(Bitmap.CompressFormat.PNG, 100, stream);
            Log.i(TAG, "Preview saved to " + out);
        } catch (IOException failed) {
            Log.e(TAG, "Could not save preview", failed);
        }
    }
}
