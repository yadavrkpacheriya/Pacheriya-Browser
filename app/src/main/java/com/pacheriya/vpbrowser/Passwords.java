package com.pacheriya.vpbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.KeyStore;

/**
 * Local saved-logins store for the browser.
 * Credentials are encrypted with an AES key held in the Android Keystore (hardware-backed where available),
 * so the saved passwords are not readable as plain text on disk. Everything stays on the device; nothing is uploaded.
 */
final class Passwords {
    private static final String PREF = "vp_pw";
    private static final String KEY_ALIAS = "vp_pw_key";
    private static final String ANDROID_KS = "AndroidKeyStore";

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    private static SecretKey key() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KS); ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
            KeyGenerator kg = KeyGenerator.getInstance("AES", ANDROID_KS);
            android.security.keystore.KeyGenParameterSpec spec = new android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build();
            kg.init(spec);
            return kg.generateKey();
        } catch (Throwable e) { return null; }
    }

    private static String enc(String plain) {
        try {
            SecretKey k = key(); if (k == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable e) { return null; }
    }

    private static String dec(String b64) {
        try {
            SecretKey k = key(); if (k == null) return null;
            byte[] in = Base64.decode(b64, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(in, 0, iv, 0, 12);
            byte[] ct = new byte[in.length - 12];
            System.arraycopy(in, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new javax.crypto.spec.GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable e) { return null; }
    }

    static String host(String url) {
        try { String h = android.net.Uri.parse(url).getHost(); return h == null ? "" : h.toLowerCase(); } catch (Exception e) { return ""; }
    }

    /** Save or update one credential for a site. */
    static void save(Context c, String url, String user, String pass) {
        try {
            String h = host(url); if (h.isEmpty() || pass == null || pass.isEmpty()) return;
            String ep = enc(pass); if (ep == null) return;
            JSONArray a = all(c);
            JSONArray b = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && h.equals(o.optString("h")) && user.equals(o.optString("u"))) {
                    o.put("p", ep); b.put(o); replaced = true;
                } else if (o != null) b.put(o);
            }
            if (!replaced) { JSONObject o = new JSONObject(); o.put("h", h); o.put("u", user); o.put("p", ep); b.put(o); }
            sp(c).edit().putString("list", b.toString()).apply();
        } catch (Throwable e) { /* ignore */ }
    }

    private static JSONArray all(Context c) {
        try { return new JSONArray(sp(c).getString("list", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** Return [{u,p}] decrypted credentials for a site's host, for autofill. */
    static JSONArray forUrl(Context c, String url) {
        JSONArray out = new JSONArray();
        try {
            String h = host(url); if (h.isEmpty()) return out;
            JSONArray a = all(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && h.equals(o.optString("h"))) {
                    String p = dec(o.optString("p"));
                    if (p != null) { JSONObject r = new JSONObject(); r.put("u", o.optString("u")); r.put("p", p); out.put(r); }
                }
            }
        } catch (Throwable e) { /* ignore */ }
        return out;
    }

    /** List saved logins (host + username only, no passwords) for a settings screen. */
    static JSONArray list(Context c) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = all(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) { JSONObject r = new JSONObject(); r.put("h", o.optString("h")); r.put("u", o.optString("u")); out.put(r); }
            }
        } catch (Throwable e) { /* ignore */ }
        return out;
    }

    static void delete(Context c, String h, String user) {
        try {
            JSONArray a = all(c); JSONArray b = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !(h.equals(o.optString("h")) && user.equals(o.optString("u")))) b.put(o);
            }
            sp(c).edit().putString("list", b.toString()).apply();
        } catch (Throwable e) { /* ignore */ }
    }
}
