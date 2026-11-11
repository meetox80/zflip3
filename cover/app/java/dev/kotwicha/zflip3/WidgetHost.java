package dev.kotwicha.zflip3;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

final class WidgetHost {
    private static final String TAG = "Flip3Cover";
    private static final int HOST_ID = 1911;
    private static final int WIDGET_DENSITY = 240;
    private static final int PANEL_WIDTH = 512;
    private static final int PANEL_HEIGHT = 260;
    private static final int CATEGORY_COVER = 2048;

    private final Context widgetContext;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;

    WidgetHost(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.densityDpi = WIDGET_DENSITY;
        widgetContext = context.createConfigurationContext(config);
        manager = AppWidgetManager.getInstance(widgetContext);
        host = new AppWidgetHost(widgetContext, HOST_ID);
    }

    void startListening() {
        host.startListening();
    }

    void stopListening() {
        host.stopListening();
    }

    private static int toWidgetDp(int pixels) {
        return pixels * 160 / WIDGET_DENSITY;
    }

    private static int pageDp(int panelPixels) {
        return panelPixels * 160 / WIDGET_DENSITY;
    }

    List<AppWidgetProviderInfo> available() {
        List<AppWidgetProviderInfo> usable = new ArrayList<>();
        for (AppWidgetProviderInfo info : manager.getInstalledProvidersForProfile(Process.myUserHandle())) {
            boolean category = (info.widgetCategory & (AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN | AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD | CATEGORY_COVER)) != 0;
            boolean fits = toWidgetDp(info.minWidth) <= pageDp(PANEL_WIDTH) && toWidgetDp(info.minHeight) <= pageDp(PANEL_HEIGHT);
            boolean needsSetup = info.configure != null && (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0;
            if (category && fits && !needsSetup) {
                usable.add(info);
            }
        }
        return usable;
    }

    CharSequence label(AppWidgetProviderInfo info) {
        return info.loadLabel(widgetContext.getPackageManager());
    }

    android.graphics.drawable.Drawable preview(AppWidgetProviderInfo info) {
        android.graphics.drawable.Drawable preview = info.loadPreviewImage(widgetContext, 0);
        return preview != null ? preview : info.loadIcon(widgetContext, 0);
    }

    int bind(ComponentName provider) {
        int id = host.allocateAppWidgetId();
        if (manager.bindAppWidgetIdIfAllowed(id, provider)) {
            return id;
        }
        Log.e(TAG, "Not allowed to bind " + provider);
        host.deleteAppWidgetId(id);
        return -1;
    }

    void delete(int id) {
        host.deleteAppWidgetId(id);
    }

    AppWidgetProviderInfo info(int id) {
        return manager.getAppWidgetInfo(id);
    }

    View createView(Context pageContext, int id) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null) {
            return null;
        }
        int inflatedDensity = pageContext.getApplicationContext().getResources().getConfiguration().densityDpi;
        float scale = inflatedDensity / (float) WIDGET_DENSITY;
        AppWidgetHostView view = host.createView(widgetContext, id, info);
        view.setPadding(0, 0, 0, 0);
        view.updateAppWidgetSize(null, pageDp(PANEL_WIDTH), pageDp(PANEL_HEIGHT), pageDp(PANEL_WIDTH), pageDp(PANEL_HEIGHT));
        FrameLayout page = new FrameLayout(pageContext);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.round(PANEL_WIDTH * scale), Math.round(PANEL_HEIGHT * scale));
        view.setPivotX(0f);
        view.setPivotY(0f);
        view.setScaleX(1f / scale);
        view.setScaleY(1f / scale);
        page.addView(view, params);
        page.setClipChildren(true);
        return page;
    }
}
