package com.famspace.unlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that hosts the local rewrite proxy on the phone.
 */
public class ProxyService extends Service {

    private static final String TAG = "FamUnlock";
    public static final int PORT = 888;
    private LocalProxy proxy;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground();
        if (proxy == null) {
            proxy = new LocalProxy(new String[]{"https://famspace.disoms.net", "https://www.famspace.us"}, PORT, "http://127.0.0.1:" + PORT);
            try {
                proxy.start();
                Log.i(TAG, "proxy listening on 127.0.0.1:" + LocalProxy.PORT);
            } catch (Exception e) {
                Log.e(TAG, "cannot start proxy", e);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (proxy != null) {
            proxy.stop();
        }
        super.onDestroy();
    }

    private void startForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("famunlock", "Famspace Unlock",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, "famunlock")
                .setContentTitle("Famspace Unlock")
                .setContentText("Local rewrite proxy on 127.0.0.1:" + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build();
        startForeground(1, n);
    }
}
