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
    static class Variant { String label; String url; int bw; String q = "Original"; double dur; }

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

    static void direct(final Context c, final String url, final String ua, final String referer, final String name) {
        final String fn = fileName(url, name);
        final String ref = (referer != null && referer.startsWith("http")) ? referer : null;
        new Thread(new Runnable() {
            public void run() {
                long total = size(url, ua, ref);
                /* 1DM-style: if the server supports ranges and the file is big enough, fetch it with many
                   parallel connections; otherwise fall back to the system downloader */
                if (total > 3_000_000L && parallelDirect(c, url, ua, ref, fn, total)) return;
                try {
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setTitle(fn);
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VideoPlus/" + fn);
                    if (ua != null) r.addRequestHeader("User-Agent", ua);
                    if (ref != null) r.addRequestHeader("Referer", ref);
                    String ck = CookieManager.getInstance().getCookie(url);
                    if (ck != null) r.addRequestHeader("Cookie", ck);
                    ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                    toast(c, "Downloading to Downloads/VideoPlus");
                } catch (Exception e) { toast(c, "Download failed: " + e.getMessage()); }
            }
        }).start();
    }

    /** Download one file with up to 32 parallel range connections (1DM style). Returns false to fall back. */
    private static boolean parallelDirect(final Context c, final String url, final String ua, final String ref, String fn, final long total) {
        final int N = 32;
        try {
            java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VideoPlus");
            dir.mkdirs();
            final java.io.File out = new java.io.File(dir, fn);
            final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(out, "rw");
            raf.setLength(total);
            final long chunk = (total + N - 1) / N;
            final NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            try { nm.createNotificationChannel(new NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW)); } catch (Throwable e) { /* ignore */ }
            final int nid = (int) (System.nanoTime() & 0x3fffffff);
            final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong(0);
            final java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(N);
            List<java.util.concurrent.Future<?>> fs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < N; i++) {
                final long start = i * chunk;
                if (start >= total) break;
                final long end = Math.min(total - 1, start + chunk - 1);
                fs.add(pool.submit(new Runnable() {
                    public void run() {
                        try {
                            HttpURLConnection h = open(url, ua, ref);
                            h.setRequestProperty("Range", "bytes=" + start + "-" + end);
                            if (h.getResponseCode() != 206) throw new Exception("no range");
                            InputStream in = h.getInputStream();
                            byte[] buf = new byte[65536]; long pos = start; int n;
                            while ((n = in.read(buf)) > 0) {
                                synchronized (raf) { raf.seek(pos); raf.write(buf, 0, n); }
                                pos += n; done.addAndGet(n);
                            }
                            in.close();
                        } catch (Exception e) { fail.set(true); }
                    }
                }));
            }
            // progress
            new Thread(new Runnable() { public void run() {
                while (!fail.get()) {
                    long d = done.get();
                    try {
                        Notification n = new Notification.Builder(c, "dl").setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle(out.getName()).setContentText((d / 1048576) + " / " + (total / 1048576) + " MB (32 parts)")
                            .setProgress(100, (int) (d * 100 / Math.max(1, total)), false).setOngoing(true).build();
                        nm.notify(nid, n);
                    } catch (Throwable e) { /* ignore */ }
                    if (d >= total) break;
                    try { Thread.sleep(700); } catch (Exception e) { break; }
                }
            }}).start();
            for (java.util.concurrent.Future<?> f : fs) f.get();
            pool.shutdownNow();
            raf.close();
            if (fail.get()) { out.delete(); return false; }
            try { android.media.MediaScannerConnection.scanFile(c, new String[]{ out.getAbsolutePath() }, null, null); } catch (Throwable e) {}
            Notification dn = new Notification.Builder(c, "dl").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(out.getName()).setContentText("Saved to Downloads/VideoPlus").setAutoCancel(true).build();
            nm.notify(nid, dn);
            toast(c, "Saved: " + out.getName());
            return true;
        } catch (Throwable e) { return false; }
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

    static double duration(String u, String ua, String ref) {
        try {
            String txt = getText(u, ua, ref);
            Matcher m = Pattern.compile("#EXTINF:([0-9.]+)").matcher(txt);
            double s = 0;
            while (m.find()) s += Double.parseDouble(m.group(1));
            return s;
        } catch (Exception e) { return 0; }
    }

    static long size(String u, String ua, String ref) {
        try {
            HttpURLConnection h = open(u, ua, ref);
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setRequestProperty("Range", "bytes=0-0");
            int code = h.getResponseCode();
            long total = -1;
            String cr = h.getHeaderField("Content-Range");
            if (cr != null && cr.indexOf('/') > 0) { try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception e) { /* unknown */ } }
            if (total < 0 && code == 200) total = h.getContentLengthLong();
            try { h.getInputStream().close(); } catch (Exception e) { /* ignore */ }
            h.disconnect();
            return total;
        } catch (Exception e) { return -1; }
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
            v.q = q;
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

    private static byte[] fetchSeg(String url, String ku, String iv, long seq, String ua, String ref, java.util.concurrent.ConcurrentHashMap<String, byte[]> keys) throws Exception {
        byte[] data = null;
        Exception last = null;
        for (int t = 0; t < 3; t++) {
            try { data = getBytes(url, ua, ref); last = null; break; } catch (Exception e) { last = e; }
        }
        if (last != null) throw last;
        if (ku != null) {
            byte[] kb = keys.get(ku);
            if (kb == null) { kb = getBytes(ku, ua, ref); keys.put(ku, kb); }
            Cipher ci = Cipher.getInstance("AES/CBC/PKCS5Padding");
            ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kb, "AES"), new IvParameterSpec(ivFor(iv, seq)));
            data = ci.doFinal(data);
        }
        return data;
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
                    final int total = segs.size();
                    final java.util.concurrent.ConcurrentHashMap<String, byte[]> keys = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
                    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(32);
                    List<java.util.concurrent.Future<byte[]>> fs = new ArrayList<java.util.concurrent.Future<byte[]>>();
                    int w = 0;
                    try {
                        for (int i = 0; i <= total; i++) {
                            if (i < total) {
                                final String su = segs.get(i), ku = segKeyUri.get(i), iv = segKeyIv.get(i);
                                final long sq = segSeq.get(i);
                                fs.add(pool.submit(new java.util.concurrent.Callable<byte[]>() {
                                    public byte[] call() throws Exception { return fetchSeg(su, ku, iv, sq, ua, ref, keys); }
                                }));
                            }
                            while (w < fs.size() && (fs.size() - w >= 48 || i == total)) {
                                byte[] data;
                                try { data = fs.get(w).get(); }
                                catch (java.util.concurrent.ExecutionException ee) { throw new Exception(String.valueOf(ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage())); }
                                os.write(data);
                                fs.set(w, null);
                                w++;
                                Notification n = new Notification.Builder(ctx, "dl").setSmallIcon(android.R.drawable.stat_sys_download)
                                    .setContentTitle(fn).setContentText(w + " / " + total).setProgress(total, w, false).setOngoing(true).build();
                                nm.notify(id, n);
                            }
                        }
                    } finally { pool.shutdownNow(); }
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
