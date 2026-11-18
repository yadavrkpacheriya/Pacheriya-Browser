package com.pacheriya.vpbrowser;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Saves videos the page already plays: direct files (system download manager) and plain HLS streams. */
final class Downloader {
    static class Variant { String label; String url; int bw; }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static int nid = 100;

    static void toast(final Context c, final String s) {
        UI.post(new Runnable() { public void run() { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); } });
    }

    static String fileName(String url, String forced) {
        String n = forced;
        if (n == null || n.isEmpty()) {
            try { n = Uri.parse(url).getLastPathSegment(); } catch (Exception e) { n = null; }
        }
        if (n == null || n.isEmpty()) n = "video";
        n = n.replaceAll("[^A-Za-z0-9 _.()-]", "_");
        if (n.length() > 80) n = n.substring(n.length() - 80);
        if (!n.matches("(?i).*\\.(mp4|webm|mkv|mov|m4v|mp3|m4a|ts)$")) n = n + ".mp4";
        return n;
    }

    static void direct(Context c, String url, String ua, String referer, String name) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String fn = fileName(url, name);
            r.setTitle(fn);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VideoPlus/" + fn);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            if (referer != null && referer.startsWith("http")) r.addRequestHeader("Referer", referer);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            toast(c, "Downloading to Downloads/VideoPlus");
        } catch (Exception e) {
            toast(c, "Download failed: " + e.getMessage());
        }
    }

    // ---------- HLS ----------
    private static HttpURLConnection open(String u, String ua, String ref) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(true);
        if (ua != null) h.setRequestProperty("User-Agent", ua);
        if (ref != null && ref.startsWith("http")) h.setRequestProperty("Referer", ref);
        String ck = CookieManager.getInstance().getCookie(u);
        if (ck != null) h.setRequestProperty("Cookie", ck);
        return h;
    }

    private static byte[] getBytes(String u, String ua, String ref) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                HttpURLConnection h = open(u, ua, ref);
                if (h.getResponseCode() >= 400) throw new Exception("HTTP " + h.getResponseCode());
                InputStream in = h.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                return bo.toByteArray();
            } catch (Exception e) { last = e; }
        }
        throw last;
    }

    private static String getText(String u, String ua, String ref) throws Exception {
        return new String(getBytes(u, ua, ref), "UTF-8");
    }

    private static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    private static String attr(String line, String key) {
        Matcher m = Pattern.compile("(?:^|[:,])" + key + "=(\"[^\"]*\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        String v = m.group(1);
        if (v.startsWith("\"")) v = v.substring(1, v.length() - 1);
        return v;
    }

    static List<Variant> variants(String master, String ua, String ref) throws Exception {
        String txt = getText(master, ua, ref);
        List<Variant> out = new ArrayList<Variant>();
        if (!txt.contains("#EXT-X-STREAM-INF")) {
            Variant v = new Variant(); v.label = "Original quality"; v.url = master; out.add(v);
            return out;
        }
        String[] lines = txt.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            int bw = 0;
            try { bw = Integer.parseInt(attr(l, "BANDWIDTH")); } catch (Exception e) { /* none */ }
            String res = attr(l, "RESOLUTION");
            int j = i + 1;
            while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].startsWith("#"))) j++;
            if (j >= lines.length) continue;
            Variant v = new Variant();
            v.bw = bw;
            v.url = resolve(master, lines[j].trim());
            String q = (res != null && res.indexOf('x') > 0) ? res.substring(res.indexOf('x') + 1) + "p" : "stream";
            v.label = q + "  (" + (bw / 1000) + " kbps)";
            out.add(v);
        }
        Collections.sort(out, new Comparator<Variant>() {
            public int compare(Variant a, Variant b) { return b.bw - a.bw; }
        });
        return out;
    }

    private static byte[] ivFor(String ivAttr, long seq) {
        byte[] iv = new byte[16];
        if (ivAttr != null && ivAttr.toLowerCase().startsWith("0x")) {
            String hex = ivAttr.substring(2);
            while (hex.length() < 32) hex = "0" + hex;
            for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        } else {
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) ((seq >> (8 * i)) & 0xff);
        }
        return iv;
    }

    static void hls(final Context ctx, final String mediaUrl, final String ua, final String ref, final String baseName) {
        BgService.acquire(ctx);
        new Thread(new Runnable() {
            public void run() {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW));
                final int id = nid++;
                String title = baseName == null || baseName.isEmpty() ? "video" : baseName;
                try {
                    String txt = getText(mediaUrl, ua, ref);
                    if (txt.contains("#EXT-X-MAP")) throw new Exception("this stream type (fMP4) is not supported yet");
                    String[] lines = txt.split("\r?\n");
                    long seq = 0;
                    List<String> segs = new ArrayList<String>();
                    List<String> segKeyUri = new ArrayList<String>();
                    List<String> segKeyIv = new ArrayList<String>();
                    List<Long> segSeq = new ArrayList<Long>();
                    String curKey = null, curIv = null;
                    for (String raw : lines) {
                        String l = raw.trim();
                        if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) { try { seq = Long.parseLong(l.substring(22).trim()); } catch (Exception e) { /* keep */ } }
                        else if (l.startsWith("#EXT-X-KEY")) {
                            String m = attr(l, "METHOD");
                            if ("NONE".equals(m)) { curKey = null; curIv = null; }
                            else if ("AES-128".equals(m)) { curKey = resolve(mediaUrl, attr(l, "URI")); curIv = attr(l, "IV"); }
                            else throw new Exception("protected stream (" + m + ") cannot be saved");
                        } else if (!l.isEmpty() && !l.startsWith("#")) {
                            segs.add(resolve(mediaUrl, l));
                            segKeyUri.add(curKey);
                            segKeyIv.add(curIv);
                            segSeq.add(seq++);
                        }
                    }
                    if (segs.isEmpty()) throw new Exception("no video parts found");
                    String fn = title.replaceAll("[^A-Za-z0-9 _.()-]", "_");
                    if (fn.length() > 70) fn = fn.substring(0, 70);
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fn + ".ts");
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2t");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoPlus");
                    Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("cannot create file");
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    String lastKeyUri = null;
                    byte[] keyBytes = null;
                    int total = segs.size();
                    for (int i = 0; i < total; i++) {
                        byte[] data = getBytes(segs.get(i), ua, ref);
                        String ku = segKeyUri.get(i);
                        if (ku != null) {
                            if (!ku.equals(lastKeyUri)) { keyBytes = getBytes(ku, ua, ref); lastKeyUri = ku; }
                            Cipher ci = Cipher.getInstance("AES/CBC/PKCS5Padding");
                            ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivFor(segKeyIv.get(i), segSeq.get(i))));
                            data = ci.doFinal(data);
                        }
                        os.write(data);
                        Notification n = new Notification.Builder(ctx, "dl").setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle(fn).setContentText((i + 1) + " / " + total).setProgress(total, i + 1, false).setOngoing(true).build();
                        nm.notify(id, n);
                    }
                    os.close();
                    Notification done = new Notification.Builder(ctx, "dl").setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(fn).setContentText("Saved in Downloads/VideoPlus (.ts file - play with VLC or MX Player)").build();
                    nm.notify(id, done);
                    toast(ctx, "Saved: Downloads/VideoPlus/" + fn + ".ts");
                } catch (Exception e) {
                    Notification err = new Notification.Builder(ctx, "dl").setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("Download failed").setContentText(String.valueOf(e.getMessage())).build();
                    nm.notify(id, err);
                    toast(ctx, "Download failed: " + e.getMessage());
                } finally {
                    BgService.release(ctx);
                }
            }
        }).start();
    }
}
