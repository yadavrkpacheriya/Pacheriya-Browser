package com.pacheriya.vpbrowser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives the result of the in-app update install. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent conf = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (conf != null) {
                conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(conf); } catch (Exception e) { /* ignore */ }
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            int legacy = i.getIntExtra("android.content.pm.extra.LEGACY_STATUS", 0);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            // -7 = INSTALL_FAILED_UPDATE_INCOMPATIBLE (signature mismatch with the installed build)
            if (legacy == -7 || (msg != null && msg.contains("INCONSISTENT")) || (msg != null && msg.contains("signatures"))) {
                Toast.makeText(c, "Update needs a one-time reinstall: uninstall Video Plus Browser, then install this build once. Future updates will be automatic.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(c, "Update failed (" + legacy + "): " + msg, Toast.LENGTH_LONG).show();
            }
        }
    }
}
