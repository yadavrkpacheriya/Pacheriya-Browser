package com.pacheriya.vpbrowser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the Pause / Resume / Cancel buttons on a download's notification. */
public class DlActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String id = i.getStringExtra("id");
        String a = i.getAction();
        if (id == null || a == null) return;
        if ("pause".equals(a)) Downloader.pause(id);
        else if ("resume".equals(a)) Downloader.resume(id, c.getApplicationContext());
        else if ("cancel".equals(a)) Downloader.cancel(id, c.getApplicationContext());
    }
}
