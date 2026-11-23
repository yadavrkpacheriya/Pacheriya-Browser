package com.pacheriya.vpbrowser;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tiny bookmark / history / tab storage. */
final class Store {
    static JSONArray get(SharedPreferences sp, String key) {
        try { return new JSONArray(sp.getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void addHistory(SharedPreferences sp, String title, String url) {
        try {
            if (url == null || url.isEmpty()) return;
            JSONArray a = get(sp, "hist");
            // If the same URL appears among the last few entries, just update its title/time instead of adding a duplicate.
            // (Fixes double entries when a page's real title arrives after the first load, and SPA re-fires.)
            for (int i = a.length() - 1, seen = 0; i >= 0 && seen < 5; i--, seen++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && url.equals(p.optString("u"))) {
                    if (title != null && !title.isEmpty() && !title.equals(url)) p.put("t", title);
                    p.put("ts", System.currentTimeMillis());
                    sp.edit().putString("hist", a.toString()).apply();
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("t", title == null || title.isEmpty() ? url : title);
            o.put("u", url);
            o.put("ts", System.currentTimeMillis());
            a.put(o);
            if (a.length() > 1200) {
                JSONArray b = new JSONArray();
                for (int i = a.length() - 1000; i < a.length(); i++) b.put(a.get(i));
                a = b;
            }
            sp.edit().putString("hist", a.toString()).apply();
        } catch (Exception e) { /* ignore */ }
    }

    static boolean isBookmarked(SharedPreferences sp, String url) {
        JSONArray a = get(sp, "bm");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && url.equals(o.optString("u"))) return true;
        }
        return false;
    }

    /** returns true when added, false when removed */
    static boolean toggleBookmark(SharedPreferences sp, String title, String url) {
        try {
            JSONArray a = get(sp, "bm");
            JSONArray b = new JSONArray();
            boolean found = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && url.equals(o.optString("u"))) found = true; else b.put(o);
            }
            if (!found) {
                JSONObject o = new JSONObject();
                o.put("t", title);
                o.put("u", url);
                b.put(o);
            }
            sp.edit().putString("bm", b.toString()).apply();
            return !found;
        } catch (Exception e) { return false; }
    }
}
