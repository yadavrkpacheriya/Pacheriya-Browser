package com.pacheriya.vpbrowser;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
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

    /* ---- 1DM-style task registry: pause / resume / cancel / queue ---- */
    static class Task {
        String id, name, url, ua, ref;
        long total, done;
        volatile boolean paused, cancelled, running, finished;
        int notifId;
        java.io.File out;
    }
    static final java.util.LinkedHashMap<String, Task> TASKS = new java.util.LinkedHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_CONCURRENT = 3;          // queue: run up to 3 at once (1DM default-ish)
    private static final java.util.ArrayDeque<Runnable> QUEUE = new java.util.ArrayDeque<>();

    static synchronized void pause(String id)  { Task t = TASKS.get(id); if (t != null) t.paused = true; }
    static synchronized void resume(String id, Context c) {
        Task t = TASKS.get(id); if (t == null || t.running || t.finished) return;
        t.paused = false; t.cancelled = false;
        startParallel(c, t);   // resumes from t.done via range requests
    }
    static synchronized void cancel(String id) { Task t = TASKS.get(id); if (t != null) { t.cancelled = true; t.paused = false; } }
    static synchronized String tasksJson() {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Task t : TASKS.values()) {
            if (!first) b.append(","); first = false;
            String st = t.finished ? "done" : t.cancelled ? "cancelled" : t.paused ? "paused" : t.running ? "running" : "queued";
            b.append("{\"id\":\"").append(t.id).append("\",\"name\":").append(org.json.JSONObject.quote(t.name == null ? "" : t.name))
             .append(",\"done\":").append(t.done).append(",\"total\":").append(t.total)
             .append(",\"state\":\"").append(st).append("\"}");
        }
        return b.append("]").toString();
    }
    private static synchronized void enqueue(Runnable r) {
        if (ACTIVE.get() < MAX_CONCURRENT) { ACTIVE.incrementAndGet(); new Thread(r).start(); }
        else QUEUE.add(r);
    }
    private static synchronized void slotFreed() {
        ACTIVE.decrementAndGet();
        Runnable r = QUEUE.poll();
        if (r != null) { ACTIVE.incrementAndGet(); new Thread(r).start(); }
    }

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
                   parallel connections AND register it as a pausable/resumable task; else system downloader */
                if (total > 3_000_000L) {
                    Task t = new Task();
                    t.id = "d" + System.nanoTime(); t.name = fn; t.url = url; t.ua = ua; t.ref = ref;
                    t.total = total; t.done = 0; t.notifId = (int) (System.nanoTime() & 0x3fffffff);
                    synchronized (Downloader.class) { TASKS.put(t.id, t); }
                    if (startParallel(c, t)) return;
                    synchronized (Downloader.class) { TASKS.remove(t.id); }
                }
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

    /**
     * 1DM-style resumable, pausable parallel download, queued (max 3 concurrent). Runs on a worker thread.
     * Returns true if it took ownership of the download (whether it finishes now, is paused, or queued).
     */
    private static boolean startParallel(final Context c, final Task t) {
        // quick capability check: server must support range requests
        try {
            HttpURLConnection probe = open(t.url, t.ua, t.ref);
            probe.setRequestProperty("Range", "bytes=0-0");
            int pc = probe.getResponseCode();
            probe.disconnect();
            if (pc != 206) return false; // no range support → caller falls back to system downloader
        } catch (Throwable e) { return false; }

        enqueue(new Runnable() { public void run() {
            final int N = 32;
            final NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            try { nm.createNotificationChannel(new NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW)); } catch (Throwable e) {}
            java.util.concurrent.ExecutorService pool = null;
            try {
                java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VideoPlus");
                dir.mkdirs();
                final java.io.File out = new java.io.File(dir, t.name);
                t.out = out; t.running = true;
                final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(out, "rw");
                if (raf.length() != t.total) raf.setLength(t.total);
                final long startedAt = t.done;
                final long remaining = t.total - t.done;
                final long chunk = (remaining + N - 1) / N;
                final java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(false);
                pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(N, 32));
                List<java.util.concurrent.Future<?>> fs = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < N; i++) {
                    final long start = startedAt + i * chunk;
                    if (start >= t.total) break;
                    final long end = Math.min(t.total - 1, start + chunk - 1);
                    fs.add(pool.submit(new Runnable() { public void run() {
                        try {
                            HttpURLConnection h = open(t.url, t.ua, t.ref);
                            h.setRequestProperty("Range", "bytes=" + start + "-" + end);
                            if (h.getResponseCode() != 206) throw new Exception("no range");
                            InputStream in = h.getInputStream();
                            byte[] buf = new byte[65536]; long pos = start; int n;
                            while ((n = in.read(buf)) > 0) {
                                if (t.cancelled || t.paused) { in.close(); return; }
                                synchronized (raf) { raf.seek(pos); raf.write(buf, 0, n); }
                                pos += n; synchronized (t) { t.done += n; }
                            }
                            in.close();
                        } catch (Exception e) { if (!t.paused && !t.cancelled) fail.set(true); }
                    }}));
                }
                // progress + pause/resume/cancel notification
                final java.util.concurrent.ExecutorService fpool = pool;
                new Thread(new Runnable() { public void run() {
                    while (t.running && !fail.get()) {
                        try {
                            Intent pi = new Intent(c, DlActionReceiver.class).setAction(t.paused ? "resume" : "pause").putExtra("id", t.id);
                            Intent ci = new Intent(c, DlActionReceiver.class).setAction("cancel").putExtra("id", t.id);
                            int fl = android.os.Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
                            android.app.PendingIntent pp = android.app.PendingIntent.getBroadcast(c, t.notifId, pi, android.app.PendingIntent.FLAG_UPDATE_CURRENT | fl);
                            android.app.PendingIntent cp = android.app.PendingIntent.getBroadcast(c, t.notifId + 1, ci, android.app.PendingIntent.FLAG_UPDATE_CURRENT | fl);
                            Notification n = new Notification.Builder(c, "dl").setSmallIcon(android.R.drawable.stat_sys_download)
                                .setContentTitle(out.getName())
                                .setContentText((t.done / 1048576) + " / " + (t.total / 1048576) + " MB" + (t.paused ? " — paused" : " (32 parts)"))
                                .setProgress(100, (int) (t.done * 100 / Math.max(1, t.total)), false).setOngoing(!t.paused)
                                .addAction(0, t.paused ? "Resume" : "Pause", pp).addAction(0, "Cancel", cp).build();
                            nm.notify(t.notifId, n);
                        } catch (Throwable e) {}
                        if (t.done >= t.total || t.paused || t.cancelled) break;
                        try { Thread.sleep(700); } catch (Exception e) { break; }
                    }
                }}).start();
                for (java.util.concurrent.Future<?> f : fs) f.get();
                pool.shutdownNow();
                raf.close();
                t.running = false;

                if (t.cancelled) { out.delete(); nm.cancel(t.notifId); synchronized (Downloader.class) { TASKS.remove(t.id); } return; }
                if (t.paused) {
                    // leave the partial file; a notification with Resume stays
                    return;
                }
                if (fail.get() || t.done < t.total) { // transient failure: mark paused so user can resume
                    t.paused = true; return;
                }
                t.finished = true;
                try { android.media.MediaScannerConnection.scanFile(c, new String[]{ out.getAbsolutePath() }, null, null); } catch (Throwable e) {}
                Notification dn = new Notification.Builder(c, "dl").setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(out.getName()).setContentText("Saved to Downloads/VideoPlus").setAutoCancel(true).build();
                nm.notify(t.notifId, dn);
                toast(c, "Saved: " + out.getName());
            } catch (Throwable e) {
                t.running = false; t.paused = true; // allow resume
            } finally {
                if (pool != null) try { pool.shutdownNow(); } catch (Throwable e) {}
                slotFreed();
            }
        }});
        return true;
    }

    /** Remux a raw .ts (transport stream) into a standard .mp4 using Android's own MediaMuxer (no FFmpeg). */
    private static boolean remuxToMp4(Context ctx, java.io.File ts, String fn) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaMuxer mux = null;
        java.io.File outFile = null;
        try {
            ex.setDataSource(ts.getAbsolutePath());
            int tracks = ex.getTrackCount();
            if (tracks == 0) return false;
            java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VideoPlus");
            dir.mkdirs();
            outFile = new java.io.File(dir, fn + ".mp4");
            mux = new android.media.MediaMuxer(outFile.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] map = new int[tracks];
            long maxBuf = 1024 * 1024;
            for (int i = 0; i < tracks; i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    ex.selectTrack(i);
                    map[i] = mux.addTrack(f);
                    if (f.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) maxBuf = Math.max(maxBuf, f.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE));
                } else { map[i] = -1; }
            }
            mux.start();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) maxBuf);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            while (true) {
                int tk = ex.getSampleTrackIndex();
                if (tk < 0) break;
                int sz = ex.readSampleData(buf, 0);
                if (sz < 0) break;
                info.offset = 0; info.size = sz; info.presentationTimeUs = ex.getSampleTime();
                info.flags = (ex.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                if (map[tk] >= 0) mux.writeSampleData(map[tk], buf, info);
                ex.advance();
            }
            mux.stop();
            try { android.media.MediaScannerConnection.scanFile(ctx, new String[]{ outFile.getAbsolutePath() }, null, null); } catch (Throwable e) {}
            return true;
        } catch (Throwable e) {
            if (outFile != null) try { outFile.delete(); } catch (Throwable x) {}
            return false;
        } finally {
            try { ex.release(); } catch (Throwable e) {}
            if (mux != null) try { mux.release(); } catch (Throwable e) {}
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
                    java.io.File tmp = new java.io.File(ctx.getCacheDir(), "dl_" + System.nanoTime() + ".ts");
                    OutputStream os = new java.io.FileOutputStream(tmp);
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
                    Notification muxing = new Notification.Builder(ctx, "dl").setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(fn).setContentText("Converting to MP4...").setProgress(0, 0, true).setOngoing(true).build();
                    nm.notify(id, muxing);
                    boolean mp4ok = remuxToMp4(ctx, tmp, fn);
                    String savedAs;
                    if (mp4ok) { tmp.delete(); savedAs = fn + ".mp4"; }
                    else {
                        // keep the raw .ts if remux failed
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fn + ".ts");
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2t");
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoPlus");
                        Uri u2 = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (u2 != null) { OutputStream o2 = ctx.getContentResolver().openOutputStream(u2); java.io.FileInputStream fis = new java.io.FileInputStream(tmp); byte[] bb = new byte[65536]; int rn; while ((rn = fis.read(bb)) > 0) o2.write(bb, 0, rn); fis.close(); o2.close(); }
                        tmp.delete(); savedAs = fn + ".ts";
                    }
                    Notification done = new Notification.Builder(ctx, "dl").setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(fn).setContentText("Saved in Downloads/VideoPlus (" + savedAs + ")").setAutoCancel(true).build();
                    nm.notify(id, done);
                    toast(ctx, "Saved: " + savedAs);
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
