package com.pacheriya.vpbrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the app alive (audio keeps playing, downloads keep running) when the screen is off or another app is open. */
public class BgService extends Service {
    private static int users = 0;
    private PowerManager.WakeLock wl;

    static synchronized void acquire(Context c) {
        users++;
        if (users == 1) {
            try { c.startForegroundService(new Intent(c, BgService.class)); } catch (Exception e) { users = 0; }
        }
    }

    static synchronized void release(Context c) {
        if (users > 0) users--;
        if (users == 0) c.stopService(new Intent(c, BgService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bg", "Background play and downloads", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "bg")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Video Plus Browser")
            .setContentText("Playing or downloading in the background")
            .setContentIntent(pi).setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vpbrowser:bg");
            wl.acquire();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
