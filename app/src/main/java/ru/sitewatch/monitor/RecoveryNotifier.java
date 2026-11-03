package ru.sitewatch.monitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

public final class RecoveryNotifier {
    private static final String CHANNEL_ID = "site_recovered";
    private static final Object PRIVACY_LOCK = new Object();

    private RecoveryNotifier() {}

    public static void prepare(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        SharedPreferences privacy = context.getSharedPreferences("notification_privacy", Context.MODE_PRIVATE);
        synchronized (PRIVACY_LOCK) {
            if (!privacy.getBoolean("host_only_v14", false)) {
                // Remove notifications posted by older versions with complete URLs.
                manager.cancelAll();
                privacy.edit().putBoolean("host_only_v14", true).apply();
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Сайты снова доступны", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Уведомление, когда недоступный сайт заработал");
            manager.createNotificationChannel(channel);
        }
    }

    public static void show(Context context, String url) {
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return;
        prepare(context);
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification.Builder publicBuilder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification publicNotification = publicBuilder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Сайт снова доступен")
                .setContentText("Подробности в приложении")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("✓ Сайт снова доступен")
                .setContentText(UrlPolicy.notificationHost(url))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify(url.hashCode(), notification);
    }
}
