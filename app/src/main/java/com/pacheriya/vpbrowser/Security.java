package com.pacheriya.vpbrowser;

import android.net.Uri;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight, honest browser security helpers — NOT an antivirus.
 * - Blocks known malware / phishing / scam host patterns and dangerous download types.
 * - Flags plain-http logins.
 * The real protection is: encrypted passwords (Passwords.java), app-lock (biometric), HTTPS-first, and these checks.
 */
final class Security {

    // Known-bad host substrings and TLD patterns commonly used for phishing/scam/malware landing pages.
    private static final String[] BAD_PATTERNS = {
        "login-verify", "account-verify", "secure-login", "verify-account", "signin-secure",
        "-paypal.", "paypal-", "-bank.", "appleid-", "apple-id-", "-netflix.", "update-billing",
        "free-gift", "claim-reward", "you-won", "prize-claim", "gift-card-free",
        "crypto-doubler", "double-your", "elon-giveaway", "bitcoin-gift",
        "hack", "crack-", "keygen-", "warez", "nulled-"
    };
    // File extensions risky to download on Android.
    private static final String[] RISKY_EXT = { ".apk", ".exe", ".bat", ".cmd", ".scr", ".msi", ".dll", ".jar", ".vbs", ".ps1" };

    static boolean isDangerousHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            for (String p : BAD_PATTERNS) if (h.contains(p)) return true;
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    static boolean isRiskyDownload(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        for (String e : RISKY_EXT) if (u.endsWith(e)) return true;
        return false;
    }

    /** SHA-1 of a password, upper-case hex, for the HaveIBeenPwned k-anonymity range check (only the first 5 chars are sent). */
    static String sha1Upper(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
