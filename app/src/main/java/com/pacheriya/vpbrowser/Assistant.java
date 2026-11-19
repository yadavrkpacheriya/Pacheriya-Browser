package com.pacheriya.vpbrowser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** Fetches a web page from native code (no browser CORS limit) and returns readable text for the assistant. */
final class Assistant {
    private Assistant() {}

    static String fetch(String url) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(true);
            h.setConnectTimeout(12000);
            h.setReadTimeout(20000);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36");
            h.setRequestProperty("Accept", "text/html,application/json,application/xhtml+xml");
            h.setRequestProperty("Accept-Encoding", "gzip");
            h.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int code = h.getResponseCode();
            if (code >= 400) return "";
            InputStream in = h.getInputStream();
            if ("gzip".equalsIgnoreCase(h.getContentEncoding())) in = new GZIPInputStream(in);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n, total = 0;
            while ((n = br.read(buf)) > 0) { sb.append(buf, 0, n); total += n; if (total > 1500000) break; }
            String ct = h.getContentType();
            if (ct != null && ct.contains("json")) return sb.toString();
            return clean(sb.toString());
        } catch (Throwable e) { return ""; }
    }

    /** Strip HTML to readable text. */
    private static String clean(String html) {
        try {
            html = html.replaceAll("(?is)<script.*?</script>", " ");
            html = html.replaceAll("(?is)<style.*?</style>", " ");
            html = html.replaceAll("(?is)<noscript.*?</noscript>", " ");
            html = html.replaceAll("(?is)<head.*?</head>", " ");
            html = html.replaceAll("(?is)<!--.*?-->", " ");
            html = html.replaceAll("(?is)<(br|/p|/div|/li|/h[1-6])>", "\n");
            html = html.replaceAll("(?s)<[^>]+>", " ");
            html = html.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                       .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'");
            html = html.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
            html = html.replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");
            if (html.length() > 60000) html = html.substring(0, 60000);
            return html.trim();
        } catch (Throwable e) { return html; }
    }
}
