// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * A user-visible foreground lifetime for an In-Kino session. It must be started while MainActivity
 * is visible and RECORD_AUDIO is already granted, because Android restricts microphone foreground
 * services created from the background.
 *
 * <p>The Activity still owns the matcher and the player. The service and wake lock keep them
 * running with the screen off or the phone in a pocket, as long as the Activity survives. A session
 * does not yet survive process death or the Activity being destroyed.
 */
public final class SessionForegroundService extends Service {
    public static final String ACTION_START = "org.cinemaaccesslab.inkino.SESSION_START";
    public static final String ACTION_STOP = "org.cinemaaccesslab.inkino.SESSION_STOP";
    public static final String ACTION_STOP_UI = "org.cinemaaccesslab.inkino.SESSION_STOP_UI";
    private static final String CHANNEL = "in_kino_active";
    private static final int NOTIFICATION_ID = 4201;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL,
                        getString(R.string.notification_channel),
                        NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Notification Stop must stop the live player, not just the notification.
            Intent stop = new Intent(ACTION_STOP_UI).setPackage(getPackageName());
            sendBroadcast(stop);
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent open =
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent view =
                PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, SessionForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stopAction =
                PendingIntent.getService(
                        this,
                        1,
                        stop,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_sync)
                        .setContentTitle(getString(R.string.notification_title))
                        .setContentText(getString(R.string.notification_text))
                        .setContentIntent(view)
                        .addAction(
                                new Notification.Action.Builder(
                                                android.R.drawable.ic_media_pause,
                                                getString(R.string.action_stop_sync),
                                                stopAction)
                                        .build())
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setOngoing(true)
                        .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else if (Build.VERSION.SDK_INT >= 29) {
            // The explicit microphone foreground-service type was added in API 30.
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InKino:LiveSession");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
