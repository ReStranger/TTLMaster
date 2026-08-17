package ru.antiyotazapret.yotatetherttl.services;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import net.orange_box.storebox.StoreBox;

import ru.antiyotazapret.yotatetherttl.Android;
import ru.antiyotazapret.yotatetherttl.Preferences;
import ru.antiyotazapret.yotatetherttl.R;
import ru.antiyotazapret.yotatetherttl.ui.MainActivity;

public class ChangeDeviceTtlService extends IntentService {

    private static final String CHANNEL_ID = "ttlmaster.boot";
    private static final int FOREGROUND_ID = 100;

    private final Android android = new Android();
    private final String TTL = "ttl";
    private final int NOTIFY_OK = 1;
    private final int NOTIFY_ERRR = 2;


    public ChangeDeviceTtlService() {
        super(ChangeDeviceTtlService.class.getSimpleName());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        startForegroundIfNeeded();

        final Preferences preferences = StoreBox.create(this, Preferences.class);
        (new ChangeTask()).attach(new Task.OnResult<Void>() {
            @Override
            public void onResult(Void r) {
                if (preferences.showToastsOnBoot()) {
                    fireNotification(NOTIFY_OK, R.string.app_name, R.string.notification_boot_message);
                }
            }

            @Override
            public void onError(Exception e) {
                if (preferences.showToastsOnBoot()) {
                    fireNotification(NOTIFY_ERRR, R.string.app_name, R.string.notification_boot_error_message);
                }
            }
        }).runInForeground(new ChangeTask.ChangeTaskParameters(preferences, this));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    private void fireNotification(int id, int titleRes, int contentRes) {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(ChangeDeviceTtlService.this, CHANNEL_ID).
                setSmallIcon(R.drawable.ic_notify).
                setAutoCancel(true).
                setContentTitle(getResources().getString(titleRes)).
                setContentText(getResources().getString(contentRes));

        Intent resultIntent = new Intent(this, MainActivity.class);

        mBuilder.setContentIntent(PendingIntent.getActivity(this, 0, resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        NotificationManager notifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        notifyMgr.notify(id, mBuilder.build());
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.applying))
                .setOngoing(true)
                .build();

        startForeground(FOREGROUND_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager notifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
        );
        notifyMgr.createNotificationChannel(channel);
    }

}
