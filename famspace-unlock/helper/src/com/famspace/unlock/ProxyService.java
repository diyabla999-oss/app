package com.famspace.unlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that keeps the local rewrite proxy alive.
 *
 * Everything here is wrapped: a failure in the foreground notification must
 * never take the process (and with it the Activity) down.
 */
public class ProxyService extends Service {

    private static final String TAG = "FamUnlock";
    public static final int PORT = 888;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundCompat();
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed (continuing without notification)", t);
        }
        try {
            ProxyHolder.ensureStarted();
        } catch (Throwable t) {
            Log.e(TAG, "proxy start failed", t);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel("famunlock", "Famspace Unlock",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            n = new Notification.Builder(this, "famunlock")
                    .setContentTitle("Famspace Unlock")
                    .setContentText("Local rewrite proxy on 127.0.0.1:" + PORT)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("Famspace Unlock")
                    .setContentText("Local rewrite proxy on 127.0.0.1:" + PORT)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(true)
                    .build();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
    }
}
