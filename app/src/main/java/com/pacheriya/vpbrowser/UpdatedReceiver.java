package com.pacheriya.vpbrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After an update replaces the app, reopen it (or show a tap-to-open notice if Android blocks that). */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent launch = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(launch); } catch (Exception e) { /* blocked in background */ }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("upd", "App updates", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, "upd").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Video Plus Browser updated").setContentText("Tap to open").setContentIntent(pi).setAutoCancel(true).build();
            nm.notify(777, n);
        } catch (Exception e) { /* ignore */ }
    }
}
