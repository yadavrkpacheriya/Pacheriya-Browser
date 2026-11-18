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

/** Checks the GitHub release for a newer build and installs it from inside the app. */
final class Updater {
    static final String API = "https://api.github.com/repos/yadavrkpacheriya/Pacheriya-Browser/releases/tags/latest";

    static void check(final Activity a, final boolean manual) {
        final SharedPreferences sp = a.getSharedPreferences("vpb", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - sp.getLong("lastCheck", 0) < 6L * 3600 * 1000) return;
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
                    StringBuilder sb = new StringBuilder();
                    InputStream in = h.getInputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                    in.close();
                    JSONObject o = new JSONObject(sb.toString());
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
                        a.runOnUiThread(new Runnable() { public void run() { prompt(a, url, latest); } });
                    } else if (manual) {
                        toast(a, "You have the latest version");
                    }
                } catch (final Exception e) {
                    if (manual) toast(a, "Update check failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private static void toast(final Activity a, final String s) {
        a.runOnUiThread(new Runnable() { public void run() { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } });
    }

    private static void prompt(final Activity a, final String url, int build) {
        new AlertDialog.Builder(a).setTitle("Update available")
            .setMessage("A new version of Video Plus Browser (build " + build + ") is ready. Update now?")
            .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { start(a, url); }
            })
            .setNegativeButton("Later", null).show();
    }

    private static void start(final Activity a, final String url) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow \"Install unknown apps\" for this browser, then tap Update again", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return;
        }
        Toast.makeText(a, "Downloading update...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    File f = new File(a.getCacheDir(), "update.apk");
                    HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    h.setInstanceFollowRedirects(true);
                    h.setRequestProperty("User-Agent", "VideoPlusBrowser");
                    if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                    InputStream in = h.getInputStream();
                    FileOutputStream fo = new FileOutputStream(f);
                    byte[] b = new byte[32768];
                    int n;
                    while ((n = in.read(b)) > 0) fo.write(b, 0, n);
                    fo.close();
                    in.close();
                    if (f.length() < 100000) throw new Exception("download incomplete");
                    install(a, f);
                } catch (Exception e) {
                    toast(a, "Update failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private static void install(Context c, File f) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        OutputStream os = s.openWrite("update.apk", 0, f.length());
        FileInputStream fi = new FileInputStream(f);
        byte[] b = new byte[32768];
        int n;
        while ((n = fi.read(b)) > 0) os.write(b, 0, n);
        fi.close();
        s.fsync(os);
        os.close();
        PendingIntent pend = PendingIntent.getBroadcast(c, id, new Intent(c, UpdateReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        s.commit(pend.getIntentSender());
    }
}
