package dev.kotwicha.zflip3;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CoverNotificationListener extends NotificationListenerService {
    interface Observer {
        void onNotificationsChanged();
    }

    interface AlertTarget {
        void onNotificationAlert(StatusBarNotification item);
    }

    private static final List<Observer> OBSERVERS = new ArrayList<>();
    private static final long PENDING_ALERT_MAX_AGE_MS = 6000;
    private static final int SEEN_LIMIT = 200;
    private static CoverNotificationListener instance;
    private static AlertTarget alertTarget;
    private static StatusBarNotification pendingAlert;
    private static long pendingAlertTime;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> seenContent = new HashMap<>();
    private SongTheme songTheme;

    static void setAlertTarget(AlertTarget target) {
        alertTarget = target;
    }

    static StatusBarNotification takePendingAlert() {
        StatusBarNotification item = pendingAlert;
        pendingAlert = null;
        if (item == null || SystemClock.elapsedRealtime() - pendingAlertTime > PENDING_ALERT_MAX_AGE_MS) {
            return null;
        }
        return item;
    }

    static void addObserver(Observer observer) {
        OBSERVERS.add(observer);
    }

    static void removeObserver(Observer observer) {
        OBSERVERS.remove(observer);
    }

    static List<StatusBarNotification> visibleNotifications() {
        List<StatusBarNotification> result = new ArrayList<>();
        CoverNotificationListener listener = instance;
        if (listener == null) {
            return result;
        }
        StatusBarNotification[] active;
        RankingMap rankingMap;
        try {
            active = listener.getActiveNotifications();
            rankingMap = listener.getCurrentRanking();
        } catch (RuntimeException notBound) {
            return result;
        }
        if (active == null || rankingMap == null) {
            return result;
        }
        Map<String, StatusBarNotification> byKey = new HashMap<>();
        Map<String, Integer> groupSizes = new HashMap<>();
        for (StatusBarNotification item : active) {
            byKey.put(item.getKey(), item);
            if (item.getGroupKey() != null) {
                groupSizes.merge(item.getGroupKey(), 1, Integer::sum);
            }
        }
        String[] order = rankingMap.getOrderedKeys();
        List<String> keys = order != null ? Arrays.asList(order) : new ArrayList<>(byKey.keySet());
        Ranking ranking = new Ranking();
        for (String key : keys) {
            StatusBarNotification item = byKey.get(key);
            if (item != null && isShown(item, groupSizes, rankingMap, ranking)) {
                result.add(item);
            }
        }
        return result;
    }

    private static boolean isShown(StatusBarNotification item, Map<String, Integer> groupSizes, RankingMap rankingMap, Ranking ranking) {
        Notification notification = item.getNotification();
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && groupSizes.getOrDefault(item.getGroupKey(), 1) > 1) {
            return false;
        }
        String template = notification.extras.getString(Notification.EXTRA_TEMPLATE);
        if (template != null && template.contains("MediaStyle")) {
            return false;
        }
        return !(rankingMap.getRanking(item.getKey(), ranking) && ranking.isAmbient());
    }

    static boolean shouldHideContent(StatusBarNotification item) {
        CoverNotificationListener listener = instance;
        if (listener == null) {
            return true;
        }
        KeyguardManager keyguard = listener.getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            return false;
        }
        if (item.getNotification().visibility == Notification.VISIBILITY_SECRET) {
            return true;
        }
        return Settings.Secure.getInt(listener.getContentResolver(), "lock_screen_allow_private_notifications", 1) == 0;
    }

    static CharSequence appLabel(Context context, StatusBarNotification item) {
        PackageManager packages = context.getPackageManager();
        try {
            return packages.getApplicationLabel(packages.getApplicationInfo(item.getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException gone) {
            return item.getPackageName();
        }
    }

    static void dismiss(StatusBarNotification item) {
        CoverNotificationListener listener = instance;
        if (listener != null) {
            listener.cancelNotification(item.getKey());
        }
    }

    static void clearAll() {
        CoverNotificationListener listener = instance;
        if (listener != null) {
            listener.cancelAllNotifications();
        }
    }

    private void notifyObservers() {
        handler.post(() -> {
            for (Observer observer : new ArrayList<>(OBSERVERS)) {
                observer.onNotificationsChanged();
            }
        });
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        songTheme = new SongTheme(this, new ComponentName(this, CoverNotificationListener.class));
        songTheme.start();
        notifyObservers();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        if (songTheme != null) {
            songTheme.stop();
            songTheme = null;
        }
        notifyObservers();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification item, RankingMap ranking) {
        notifyObservers();
        if (shouldAlert(item, ranking)) {
            handler.post(() -> deliverAlert(item));
        }
    }

    private boolean shouldAlert(StatusBarNotification item, RankingMap rankingMap) {
        Notification notification = item.getNotification();
        if (item.getPackageName().equals(getPackageName()) || item.isOngoing()
            || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0
            || notification.visibility == Notification.VISIBILITY_SECRET) {
            return false;
        }
        String template = notification.extras.getString(Notification.EXTRA_TEMPLATE);
        if (template != null && template.contains("MediaStyle")) {
            return false;
        }
        Ranking ranking = new Ranking();
        if (rankingMap == null || !rankingMap.getRanking(item.getKey(), ranking)
            || !ranking.matchesInterruptionFilter() || ranking.isSuspended() || ranking.isAmbient()
            || ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            return false;
        }
        int content = (String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE)) + notification.extras.getCharSequence(Notification.EXTRA_TEXT)).hashCode();
        Integer previous = seenContent.get(item.getKey());
        if (seenContent.size() > SEEN_LIMIT) {
            seenContent.clear();
        }
        seenContent.put(item.getKey(), content);
        boolean alertOnce = (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        return previous == null || (!alertOnce && previous != content);
    }

    private void deliverAlert(StatusBarNotification item) {
        if (alertTarget != null) {
            alertTarget.onNotificationAlert(item);
            return;
        }
        pendingAlert = item;
        pendingAlertTime = SystemClock.elapsedRealtime();
        CoverCommands.write(this, CoverCommands.COMMAND, System.currentTimeMillis() + " wake_alert");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification item, RankingMap ranking) {
        notifyObservers();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap ranking) {
        notifyObservers();
    }
}
