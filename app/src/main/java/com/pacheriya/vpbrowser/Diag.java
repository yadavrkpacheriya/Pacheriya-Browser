package com.pacheriya.vpbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Lightweight diagnostics log: records app events (errors, slow pages, crashes, assistant use) for a copy-paste report. */
final class Diag {
    private Diag() {}

    private static SharedPreferences sp;
    private static final String KEY = "diaglog";
    private static final int MAX = 400;

    static void init(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("vp_diag", Context.MODE_PRIVATE);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StackTraceElement[] st = e.getStackTrace();
                    String where = st != null && st.length > 0 ? st[0].toString() : "";
                    log("CRASH", e.getClass().getSimpleName() + ": " + e.getMessage() + " @ " + where);
                } catch (Throwable ignore) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        bump("launches");
    }

    static synchronized void log(String type, String detail) {
        if (sp == null) return;
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            JSONObject o = new JSONObject();
            o.put("t", new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()));
            o.put("k", type);
            o.put("d", detail == null ? "" : (detail.length() > 240 ? detail.substring(0, 240) : detail));
            arr.put(o);
            while (arr.length() > MAX) arr.remove(0);
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable e) { /* ignore */ }
    }

    static synchronized void bump(String counter) {
        if (sp == null) return;
        sp.edit().putInt("c_" + counter, sp.getInt("c_" + counter, 0) + 1).apply();
    }

    static synchronized void clear() {
        if (sp == null) return;
        SharedPreferences.Editor e = sp.edit();
        e.putString(KEY, "[]");
        for (String k : sp.getAll().keySet()) if (k.startsWith("c_")) e.remove(k);
        e.apply();
    }

    /** Full plain-text report for copy-paste. */
    static synchronized String report() {
        if (sp == null) return "No diagnostics yet.";
        StringBuilder sb = new StringBuilder();
        sb.append("=== Video Plus Browser diagnostics ===\n");
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n");
        sb.append("App build: ").append(BuildBuild.CODE).append("\n\n");

        sb.append("-- Counters --\n");
        int launches = 0, crashes = 0, pageErr = 0, slow = 0, ask = 0;
        for (String k : sp.getAll().keySet()) {
            if (!k.startsWith("c_")) continue;
            int v = sp.getInt(k, 0);
            String name = k.substring(2);
            sb.append(name).append(": ").append(v).append("\n");
        }
        sb.append("\n-- Recent events (newest last) --\n");
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            int start = Math.max(0, arr.length() - 120);
            for (int i = start; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                sb.append(o.optString("t")).append("  ").append(o.optString("k")).append("  ").append(o.optString("d")).append("\n");
            }
            if (arr.length() == 0) sb.append("(no events logged)\n");
        } catch (Throwable e) { sb.append("(log unreadable)\n"); }
        sb.append("=== end ===\n");
        return sb.toString();
    }
}
