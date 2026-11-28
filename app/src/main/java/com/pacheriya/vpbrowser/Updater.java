package com.pacheriya.vpbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checks the GitHub release for a newer build, downloads it inside the app and installs it through PackageInstaller. */
final class Updater {
    static final String API = "https://api.github.com/repos/yadavrkpacheriya/Pacheriya-Browser/releases/tags/latest";
    private static String pendUrl;
    private static int pendBuild;
    private static boolean busy;

    static void check(final Activity a, final boolean manual) {
        final SharedPreferences sp = a.getSharedPreferences("vpb", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - sp.getLong("lastCheck", 0) < 45L * 60 * 1000) return;
        if (busy) { if (manual) toast(a, "An update is already downloading or installing"); return; }
        sp.edit().putLong("lastCheck", now).apply();
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
                    h.setConnectTimeout(10000);
                    h.setReadTimeout(15000);
                    h.setRequestProperty("Accept", "application/vnd.github+json");
                    h.setRequestProperty("User-Agent", "VideoPlusBrowser");
                    if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                    java.io.ByteArrayOutputStream sb = new java.io.ByteArrayOutputStream();
                    InputStream in = h.getInputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) sb.write(b, 0, n);   // decode once at the end so multi-byte characters are never split
                    in.close();
                    JSONObject o = new JSONObject(sb.toString("UTF-8"));
                    Matcher m = Pattern.compile("build:(\\d+)").matcher(o.optString("body", ""));
                    if (!m.find()) throw new Exception("no version info");
                    final int latest = Integer.parseInt(m.group(1));
                    JSONArray assets = o.getJSONArray("assets");
                    String dl = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject as = assets.getJSONObject(i);
                        if (as.optString("name").endsWith(".apk")) dl = as.getString("browser_download_url");
                    }
                    if (dl == null) throw new Exception("no apk");
                    final String url = dl;
                    @SuppressWarnings("deprecation")
                    final int mine = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
                    if (latest > mine) {
                        a.runOnUiThread(new Runnable() { public void run() { begin(a, url, latest, manual); } });
                    } else if (manual) {
                        toast(a, "You have the latest version");
                    }
                } catch (final Exception e) {
                    if (manual) toast(a, "Update check failed: " + e.getMessage());
                }
            }
        }).start();
    }

    /** Called from onResume: continue after the user allowed installs from this app. */
    static void resume(Activity a) {
        if (pendUrl != null && a.getPackageManager().canRequestPackageInstalls()) {
            String u = pendUrl; int b = pendBuild;
            pendUrl = null;
            begin(a, u, b, true);
        }
    }

    /** Called by InstallReceiver when an install did not complete, so the next check is not blocked forever. */
    static void installFailed() { busy = false; }

    private static void toast(final Activity a, final String s) {
        a.runOnUiThread(new Runnable() { public void run() { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } });
    }

    private static void begin(final Activity a, final String url, final int build, boolean manual) {
        if (busy) return;
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            pendUrl = url; pendBuild = build;
            new AlertDialog.Builder(a).setTitle("One-time setup")
                .setMessage("Update " + build + " is ready. Allow Video Plus Browser to install its own updates (one switch, only once). After that, updates install by themselves.")
                .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
                        catch (Exception e) { toast(a, "Open Settings > Apps > Video Plus Browser > Install unknown apps"); }
                    }
                })
                .setNegativeButton("Later", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { pendUrl = null; } }).show();
            return;
        }
        download(a, url, build, manual);
    }

    private static void download(final Activity a, final String url, final int build, final boolean manual) {
        busy = true;
        final ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        final AlertDialog dlg = manual ? new AlertDialog.Builder(a).setTitle("Downloading update " + build).setView(pb).setCancelable(false).show() : null;
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    h.setInstanceFollowRedirects(true);
                    h.setRequestProperty("User-Agent", "VideoPlusBrowser");
                    if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                    final long total = h.getContentLengthLong();
                    File f = new File(a.getCacheDir(), "update.apk");
                    InputStream in = h.getInputStream();
                    OutputStream os = new FileOutputStream(f);
                    byte[] b = new byte[65536];
                    long got = 0;
                    int n, last = -1;
                    while ((n = in.read(b)) > 0) {
                        os.write(b, 0, n);
                        got += n;
                        if (total > 0) {
                            final int pc = (int) (got * 100 / total);
                            if (pc != last) { last = pc; a.runOnUiThread(new Runnable() { public void run() { pb.setProgress(pc); } }); }
                        }
                    }
                    os.close();
                    in.close();
                    if (total > 0 && f.length() != total) throw new Exception("download incomplete");
                    if (f.length() < 100000) throw new Exception("file too small");
                    final File apk = f;
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            try { if (dlg != null) dlg.dismiss(); } catch (Throwable e) {}
                            if (a.isFinishing() || a.isDestroyed()) { busy = false; return; }   // screen is gone: try again next launch
                            if (manual) install(a, apk);
                            else new AlertDialog.Builder(a).setTitle("Update " + build + " ready")
                                .setMessage("The app will close for a second and reopen updated. Your tabs are kept.")
                                .setPositiveButton("Update now", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { install(a, apk); } })
                                .setNegativeButton("Later", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { busy = false; } })
                                .setOnCancelListener(new DialogInterface.OnCancelListener() { public void onCancel(DialogInterface d) { busy = false; } }).show();
                        }
                    });
                } catch (final Exception e) {
                    busy = false;
                    a.runOnUiThread(new Runnable() { public void run() { try { if (dlg != null) dlg.dismiss(); } catch (Throwable x) {} } });
                    toast(a, "Update failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private static void install(final Activity a, final File apk) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    p.setAppPackageName(a.getPackageName());
                    if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                    int id = pi.createSession(p);
                    PackageInstaller.Session s = pi.openSession(id);
                    OutputStream os = s.openWrite("update", 0, apk.length());
                    InputStream in = new FileInputStream(apk);
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) os.write(b, 0, n);
                    in.close();
                    s.fsync(os);
                    os.close();
                    Intent it = new Intent(a, InstallReceiver.class).setAction("com.pacheriya.vpbrowser.INSTALL_DONE");
                    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pend = PendingIntent.getBroadcast(a, id, it, fl);
                    toast(a, "Updating... the app will restart for a second");
                    s.commit(pend.getIntentSender());
                } catch (Exception e) {
                    busy = false;
                    toast(a, "Update failed: " + e.getMessage());
                }
            }
        }).start();
    }
}
