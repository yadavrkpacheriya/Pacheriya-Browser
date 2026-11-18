package com.pacheriya.vpbrowser;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Picks the video quality that fits the current network speed by trimming HLS master playlists. */
final class AutoQuality {
    private AutoQuality() {}

    static volatile String lastInfo = "";
    private static volatile double mbps = -1;
    private static volatile long at = 0;
    private static final Set<String> plain = new HashSet<>();

    private static volatile boolean measuring = false;

    /** Never blocks the video: answers at once from the last test (or the phone's own estimate) and re-tests in the background. */
    static double speed(Context c) {
        long now = System.currentTimeMillis();
        if ((mbps <= 0 || now - at > 90000) && !measuring) {
            measuring = true;
            new Thread(new Runnable() { public void run() { try { double m = measure(); if (m > 0) { mbps = m; at = System.currentTimeMillis(); } } finally { measuring = false; } } }).start();
        }
        if (mbps > 0 && now - at < 600000) return mbps;
        double m = -1;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network nw = cm.getActiveNetwork();
            NetworkCapabilities nc = nw == null ? null : cm.getNetworkCapabilities(nw);
            if (nc != null) m = Math.max(1.0, nc.getLinkDownstreamBandwidthKbps() / 1000.0 * 0.4);
        } catch (Throwable e) { m = 5; }
        return m > 0 ? m : 5;
    }

    private static double measure() {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=700000").openConnection();
            h.setConnectTimeout(2500);
            h.setReadTimeout(3000);
            long t0 = System.nanoTime();
            InputStream in = h.getInputStream();
            byte[] b = new byte[16384];
            long n = 0;
            int r;
            while ((r = in.read(b)) > 0) { n += r; if (System.nanoTime() - t0 > 3000000000L) break; }
            double s = (System.nanoTime() - t0) / 1e9;
            if (n > 100000 && s > 0.05) return n * 8.0 / s / 1e6;
        } catch (Throwable e) { /* fall through */ }
        return -1;
    }

    private static String absolutize(String body, String base) {
        try {
            URL bu = new URL(base);
            String[] ln = body.split("\r?\n", -1);
            StringBuilder sb = new StringBuilder();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");
            for (int i = 0; i < ln.length; i++) {
                String l = ln[i];
                if (l.startsWith("#")) {
                    java.util.regex.Matcher m = p.matcher(l);
                    StringBuffer o = new StringBuffer();
                    while (m.find()) m.appendReplacement(o, java.util.regex.Matcher.quoteReplacement("URI=\"" + new URL(bu, m.group(1)) + "\""));
                    m.appendTail(o);
                    l = o.toString();
                } else if (!l.trim().isEmpty()) {
                    l = new URL(bu, l.trim()).toString();
                }
                sb.append(l);
                if (i < ln.length - 1) sb.append('\n');
            }
            return sb.toString();
        } catch (Throwable e) { return body; }
    }

    static int capHeight(double m, boolean saver) {
        if (saver) m *= 0.5;
        if (m >= 25) return 100000;
        if (m >= 10) return 1080;
        if (m >= 4.5) return 720;
        if (m >= 2) return 480;
        return 360;
    }

    private static int attr(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) return -1;
        int s = i + key.length() + 1, e = s;
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        try { return Integer.parseInt(line.substring(s, e)); } catch (Exception x) { return -1; }
    }

    private static int height(String line) {
        int i = line.indexOf("RESOLUTION=");
        if (i < 0) return -1;
        int x = line.indexOf('x', i);
        if (x < 0) return -1;
        int e = x + 1;
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        try { return Integer.parseInt(line.substring(x + 1, e)); } catch (Exception ex) { return -1; }
    }

    private static boolean allowed(String tag, int cap) {
        int h = height(tag);
        if (h > 0) return h <= cap + 40;
        int bw = attr(tag, "BANDWIDTH");
        if (bw < 0) return true;
        long lim = cap >= 100000 ? Long.MAX_VALUE : cap >= 1080 ? 7000000L : cap >= 720 ? 3600000L : cap >= 480 ? 1800000L : 1000000L;
        return bw <= lim;
    }

    static String trim(String body, int cap) {
        String[] ln = body.split("\r?\n", -1);
        Set<Integer> drop = new HashSet<>();
        int kept = 0, total = 0, minIdx = -1, minH = Integer.MAX_VALUE;
        for (int i = 0; i < ln.length; i++) {
            if (!ln[i].startsWith("#EXT-X-STREAM-INF")) continue;
            total++;
            int h = height(ln[i]);
            if (h < 0) h = attr(ln[i], "BANDWIDTH") / 3000;
            if (h < minH) { minH = h; minIdx = i; }
            if (allowed(ln[i], cap)) kept++;
        }
        if (total < 2) return body;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ln.length; i++) {
            String l = ln[i];
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                boolean ok = allowed(l, cap) || (kept == 0 && i == minIdx);
                if (!ok) {
                    int j = i + 1;
                    while (j < ln.length && (ln[j].startsWith("#") || ln[j].trim().isEmpty())) j++;
                    for (int k = i; k <= j && k < ln.length; k++) drop.add(k);
                }
            } else if (l.startsWith("#EXT-X-I-FRAME-STREAM-INF") && !allowed(l, cap)) {
                drop.add(i);
            }
        }
        for (int i = 0; i < ln.length; i++) {
            if (drop.contains(i)) continue;
            sb.append(ln[i]);
            if (i < ln.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /** Returns a trimmed playlist response, or null to let the WebView load the URL itself. */
    static WebResourceResponse handle(Context c, WebResourceRequest r, boolean saver) {
        String u = r.getUrl().toString();
        if (!"GET".equals(r.getMethod())) return null;
        synchronized (plain) { if (plain.contains(u)) return null; }
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(15000);
            Map<String, String> rh = r.getRequestHeaders();
            String origin = null;
            if (rh != null) for (Map.Entry<String, String> e : rh.entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("Accept-Encoding") || k.equalsIgnoreCase("Cookie")) continue;
                if (k.equalsIgnoreCase("Origin")) origin = e.getValue();
                h.setRequestProperty(k, e.getValue());
            }
            String ck = CookieManager.getInstance().getCookie(u);
            if (ck != null && !ck.isEmpty()) h.setRequestProperty("Cookie", ck);
            int code = h.getResponseCode();
            if (code != 200) return null;
            InputStream in = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) { bo.write(b, 0, n); if (bo.size() > 3000000) return null; }
            String body = bo.toString("UTF-8");
            String fin = h.getURL().toString();
            boolean moved = !fin.equals(u);
            boolean master = body.startsWith("#EXTM3U") && body.contains("#EXT-X-STREAM-INF");
            if (moved && !master) { synchronized (plain) { plain.add(u); } return null; }
            if (moved) body = absolutize(body, fin);
            String out = body;
            if (master) {
                double m = speed(c);
                int cap = capHeight(m, saver);
                out = trim(body, cap);
                lastInfo = (cap >= 100000 ? "Best quality" : cap + "p max") + " (" + Math.round(m) + " Mbps)";
            } else {
                synchronized (plain) { if (plain.size() > 600) plain.clear(); plain.add(u); }
            }
            Map<String, String> hd = new HashMap<>();
            hd.put("Access-Control-Allow-Origin", origin != null ? origin : "*");
            if (origin != null) hd.put("Access-Control-Allow-Credentials", "true");
            hd.put("Access-Control-Allow-Headers", "*");
            WebResourceResponse resp = new WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", new ByteArrayInputStream(out.getBytes("UTF-8")));
            resp.setStatusCodeAndReasonPhrase(200, "OK");
            resp.setResponseHeaders(hd);
            return resp;
        } catch (Throwable e) {
            return null;
        }
    }
}
