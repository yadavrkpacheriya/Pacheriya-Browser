package com.pacheriya.vpbrowser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Reads the text inside a picture (comic page) on the phone and translates it. */
final class Ocr {
    private Ocr() {}

    private static final TextRecognizer[] REC = new TextRecognizer[4];
    private static final Map<String, Integer> HOST_SCRIPT = new HashMap<>();

    private static synchronized TextRecognizer rec(int i) {
        if (REC[i] == null) {
            if (i == 0) REC[i] = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            else if (i == 1) REC[i] = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            else if (i == 2) REC[i] = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            else REC[i] = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        return REC[i];
    }

    private static final class Blk { Rect r; String t; }

    private static int score(List<Blk> l, int script) {
        int n = 0;
        for (Blk b : l) for (int i = 0; i < b.t.length(); i++) {
            char c = b.t.charAt(i);
            if (script == 0) { if (Character.isLetter(c) && c < 0x250) n++; }
            else if (script == 1) { if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)) n += (c <= 0x30FF ? 2 : 1); }
            else if (script == 2) { if (c >= 0xAC00 && c <= 0xD7A3) n += 2; }
            else { if (c >= 0x4E00 && c <= 0x9FFF) n++; }
        }
        return n;
    }

    private static List<Blk> read(Bitmap bm, int script, int yOff) throws Exception {
        Text t = Tasks.await(rec(script).process(InputImage.fromBitmap(bm, 0)), 20, TimeUnit.SECONDS);
        List<Blk> out = new ArrayList<>();
        for (Text.TextBlock tb : t.getTextBlocks()) {
            Rect r = tb.getBoundingBox();
            String s = tb.getText();
            if (r == null || s == null) continue;
            s = s.replace('\n', script == 0 || script == 2 ? ' ' : '​').replace("​", "").trim();
            if (s.length() < 2) continue;
            Blk b = new Blk();
            b.r = new Rect(r.left, r.top + yOff, r.right, r.bottom + yOff);
            b.t = s;
            out.add(b);
        }
        return out;
    }

    static String translate(String text, String tl) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=" + URLEncoder.encode(tl, "UTF-8")).openConnection();
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setConnectTimeout(10000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            h.setRequestProperty("User-Agent", "Mozilla/5.0");
            h.getOutputStream().write(("q=" + URLEncoder.encode(text, "UTF-8")).getBytes("UTF-8"));
            if (h.getResponseCode() != 200) return null;
            InputStream in = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray segs = new JSONArray(bo.toString("UTF-8")).getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.length(); i++) sb.append(segs.getJSONArray(i).optString(0, ""));
            return sb.toString();
        } catch (Throwable e) { return null; }
    }

    /** Returns {"w":..,"h":..,"b":[{"x","y","w","h","t"}]} or "" when there is nothing to translate. */
    static String run(String url, String referer, String cookie, String ua, String tl) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(25000);
            if (ua != null) h.setRequestProperty("User-Agent", ua);
            if (referer != null) h.setRequestProperty("Referer", referer);
            if (cookie != null && !cookie.isEmpty()) h.setRequestProperty("Cookie", cookie);
            if (h.getResponseCode() >= 400) return "";
            InputStream in = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 12000000) return ""; }
            byte[] data = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth < 250 || o.outHeight < 250) return "";
            int W = o.outWidth, H = o.outHeight, samp = 1;
            while (W / samp > 2400) samp *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = samp;
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bm == null) return "";
            int bw = bm.getWidth(), bh = bm.getHeight();
            String host = new URL(url).getHost();
            Integer known;
            synchronized (HOST_SCRIPT) { known = HOST_SCRIPT.get(host); }
            int slice = Math.max(1200, bw * 2);
            List<Blk> all = new ArrayList<>();
            int script = known == null ? -1 : known;
            for (int y = 0; y < bh; y += slice) {
                int hh = Math.min(slice, bh - y);
                if (hh < 40) break;
                Bitmap part = (y == 0 && hh == bh) ? bm : Bitmap.createBitmap(bm, 0, y, bw, hh);
                if (script < 0) {
                    List<Blk> best = read(part, 0, y);
                    int bs = score(best, 0), bi = 0;
                    if (bs < 25) {
                        for (int s = 1; s <= 3; s++) {
                            List<Blk> l = read(part, s, y);
                            int sc = score(l, s);
                            if (sc > bs) { bs = sc; best = l; bi = s; }
                        }
                    }
                    if (bs >= 6) { script = bi; synchronized (HOST_SCRIPT) { HOST_SCRIPT.put(host, bi); } }
                    all.addAll(best);
                } else {
                    all.addAll(read(part, script, y));
                }
                if (all.size() > 80) break;
            }
            if (all.isEmpty()) return "";
            StringBuilder q = new StringBuilder();
            for (int i = 0; i < all.size(); i++) { if (i > 0) q.append('\n'); q.append(all.get(i).t); }
            String tr = translate(q.toString(), tl);
            if (tr == null) return "";
            String[] parts = tr.split("\n", -1);
            if (parts.length != all.size()) {
                if (all.size() > 25) return "";
                parts = new String[all.size()];
                for (int i = 0; i < parts.length; i++) { String one = translate(all.get(i).t, tl); parts[i] = one == null ? "" : one; }
            }
            JSONArray arr = new JSONArray();
            float k = (float) W / bw;
            for (int i = 0; i < all.size(); i++) {
                String t = parts[i].trim(), src = all.get(i).t;
                if (t.isEmpty() || t.equalsIgnoreCase(src) || t.replaceAll("[^\\p{L}]", "").equalsIgnoreCase(src.replaceAll("[^\\p{L}]", ""))) continue;
                Rect r = all.get(i).r;
                JSONObject j = new JSONObject();
                j.put("x", Math.round(r.left * k)); j.put("y", Math.round(r.top * k));
                j.put("w", Math.round(r.width() * k)); j.put("h", Math.round(r.height() * k));
                j.put("t", t);
                arr.put(j);
            }
            if (arr.length() == 0) return "";
            JSONObject res = new JSONObject();
            res.put("w", W); res.put("h", H); res.put("b", arr);
            return res.toString();
        } catch (Throwable e) {
            return "";
        }
    }
}
