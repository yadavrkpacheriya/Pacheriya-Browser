package com.pacheriya.vpbrowser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.WindowManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.webkit.ProfileStore;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static final String UPDATE_URL = "https://video-player-4wm.pages.dev/video-plus.user.js";
    static final String HOME = "file:///android_asset/home.html";
    static final Pattern MEDIA = Pattern.compile("(?i)^https?://[^?#]+\\.(mp4|m3u8|webm|mkv|mov|m4v|mp3|m4a|mpd)(\\?.*)?(#.*)?$");

    static class Tab {
        WebView wv;
        String title = "New tab";
        String url = "";
        final ArrayList<String> media = new ArrayList<String>();
        ScriptHandler sh;
        boolean incognito;
        String fallback;
        final ArrayList<Entry> entries = new ArrayList<Entry>();
        final java.util.HashSet<String> covered = new java.util.HashSet<String>();
        int gen, pending;
        boolean panelOpen, autoShown;
    }

    static class Opt { String label; String url; boolean hls; String tag = "video"; }
    static class Entry { String url; boolean hidden; List<Opt> opts; }

    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private Tab cur;
    private FrameLayout root, content, fsHost;
    private LinearLayout main, bar;
    private EditText urlBox;
    private TextView tabBtn, dlHead;
    private LinearLayout dlPanel, dlList, navBar;
    private FrameLayout dlIcon;
    private TextView dlBadge;
    private android.widget.ScrollView dlScroll;
    private final java.util.concurrent.ExecutorService resolver = java.util.concurrent.Executors.newFixedThreadPool(3);
    private ProgressBar pb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long lastPlayTs = 0;
    /* 18 Nov: the room's light (lux) for the Auto picture, and a full-brightness screen while a video plays in sunlight */
    private SensorManager sensors;
    private float lux = -1f;
    private long luxSentTs = 0;
    private boolean sunBoost = false;
    private final SensorEventListener luxListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            lux = e.values[0];
            long now = SystemClock.uptimeMillis();
            if (now - luxSentTs < 2000) return;
            luxSentTs = now;
            if (cur != null) { try { cur.wv.evaluateJavascript("window.__vpLux=" + Math.round(lux), null); } catch (Exception ex) { /* ignore */ } }
            boolean want = lux >= 3000 && now - lastPlayTs < 6000;
            if (want != sunBoost) {
                sunBoost = want;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = want ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };
    private boolean playing = false, inPip = false, desktop = false, adblock = true, scripts = true;
    private boolean autoPrev = true;
    private String readerJs = "";
    private String autoTr = "";
    private String injected = "", defaultUa = "", desktopUa = "";
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Integer>> ORIGINS = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Integer>>();
    private static String hostOf(String u) { try { return Uri.parse(u).getHost(); } catch (Exception e) { return null; } }
    private static void noteOrigin(String page, String req) {
        try {
            String ph = hostOf(page);
            Uri ru = Uri.parse(req);
            if (ph == null || ru.getHost() == null || ru.getScheme() == null || !ru.getScheme().startsWith("http")) return;
            String o = ru.getScheme() + "://" + ru.getHost();
            if (ru.getHost().equals(ph)) return;
            java.util.concurrent.ConcurrentHashMap<String, Integer> m = ORIGINS.get(ph);
            if (m == null) { m = new java.util.concurrent.ConcurrentHashMap<String, Integer>(); ORIGINS.put(ph, m); }
            Integer c = m.get(o);
            if (c == null && m.size() >= 40) return;
            m.put(o, c == null ? 1 : c + 1);
        } catch (Exception e) { /* ignore */ }
    }
    private static String topOrigins(String host) {
        java.util.concurrent.ConcurrentHashMap<String, Integer> m = ORIGINS.get(host);
        if (m == null) return "";
        List<java.util.Map.Entry<String, Integer>> es = new ArrayList<java.util.Map.Entry<String, Integer>>(m.entrySet());
        java.util.Collections.sort(es, new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
            public int compare(java.util.Map.Entry<String, Integer> a, java.util.Map.Entry<String, Integer> b) { return b.getValue() - a.getValue(); }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < es.size() && i < 8; i++) { if (i > 0) sb.append(','); sb.append(es.get(i).getKey()); }
        return sb.toString();
    }
    private boolean dark = false, httpsUp = true, saver = false;
    private int textZoom = 100;
    private String engine = "google";
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendPerm;
    private String[] pendRes;
    private LinearLayout findBar;
    private EditText findBox;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String asset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    private static String jq(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("</", "<\\/") + "\"";
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("vpb", MODE_PRIVATE);
        adblock = sp.getBoolean("adblock", true);
        scripts = sp.getBoolean("scripts", true);
        dark = sp.getBoolean("dark", false);
        saver = sp.getBoolean("saver", false);
        httpsUp = sp.getBoolean("https", true);
        textZoom = sp.getInt("zoom", 100);
        engine = sp.getString("engine", "google");

        String vp = sp.getString("vpjs", null);
        String vpa = asset("video-plus.user.js");
        /* the downloaded copy is used only when it is at least as new as the one inside the app (18 Nov: an older web
           copy was hiding the app's newer Auto picture) */
        if (vp == null || vp.length() < 10000 || scriptVer(vp) < scriptVer(vpa)) vp = vpa;
        String css = "(function(){var c=" + jq(asset("vp-cosmetic.css")) + ";function a(){var p=document.head||document.documentElement;if(!p){setTimeout(a,15);return}var s=document.createElement('style');s.textContent=c;p.appendChild(s)}a()})();";
        readerJs = asset("reader.js");
        autoPrev = sp.getBoolean("autoprev", true);
        autoTr = sp.getString("autotr", "");
        injected = asset("vp-core.js") + "\n" + css + "\n" + vp;

        String ua = WebSettings.getDefaultUserAgent(this);
        defaultUa = ua.replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        java.util.regex.Matcher cm = Pattern.compile("Chrome/([\\d.]+)").matcher(ua);
        String cv = cm.find() ? cm.group(1) : "120.0.0.0";
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + cv + " Safari/537.36";

        buildUi();
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        Uri d = getIntent() != null ? getIntent().getData() : null;
        if (d != null) newTab(d.toString());
        else {
            JSONArray saved = Store.get(sp, "tabs");
            if (saved.length() == 0) newTab(HOME);
            else for (int i = 0; i < saved.length(); i++) newTab(saved.optString(i));
        }

        ui.post(new Runnable() {
            public void run() {
                boolean p = SystemClock.uptimeMillis() - lastPlayTs < 4500;
                if (p != playing) setPlaying(p);
                ui.postDelayed(this, 2000);
            }
        });
        updateScriptInBackground();
        ui.postDelayed(new Runnable() { public void run() { Updater.check(MainActivity.this, false); } }, 4000);
    }

    private void updateScriptInBackground() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL(UPDATE_URL + "?t=" + System.currentTimeMillis()).openConnection();
                    h.setConnectTimeout(8000);
                    h.setReadTimeout(15000);
                    if (h.getResponseCode() != 200) return;
                    InputStream in = h.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] bb = new byte[16384];
                    int n;
                    while ((n = in.read(bb)) > 0) bo.write(bb, 0, n);
                    in.close();
                    String s = bo.toString("UTF-8");
                    if (s.startsWith("// ==UserScript==") && s.length() > 10000) sp.edit().putString("vpjs", s).apply();
                } catch (Exception e) { /* offline: keep the saved copy */ }
            }
        }).start();
    }

    // ---------------- UI ----------------
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1B1B1F);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView back = iconBtn("\u2039");
        back.setTextSize(28);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goBack(); }
        });

        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setHintTextColor(0xFF888888);
        urlBox.setHint("Search or type address");
        urlBox.setTextSize(15);
        urlBox.setSelectAllOnFocus(true);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF2B2B33);
        gd.setCornerRadius(dp(22));
        urlBox.setBackground(gd);
        urlBox.setPadding(dp(16), dp(8), dp(16), dp(8));
        urlBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                go(urlBox.getText().toString());
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
                urlBox.clearFocus();
                return true;
            }
        });

        tabBtn = iconBtn("1");
        tabBtn.setTextSize(14);
        tabBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTabs(); }
        });
        TextView menu = iconBtn("\u22EE");
        menu.setTextSize(24);
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(v); }
        });

        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        bar.addView(urlBox, new LinearLayout.LayoutParams(0, dp(42), 1f));
        dlIcon = new FrameLayout(this);
        TextView dlI = iconBtn("\u2B07");
        dlI.setTextSize(20);
        dlI.setTextColor(0xFFB9A8FF);
        dlIcon.addView(dlI, new FrameLayout.LayoutParams(dp(40), dp(40)));
        dlBadge = new TextView(this);
        dlBadge.setTextColor(Color.WHITE);
        dlBadge.setTextSize(10);
        dlBadge.setGravity(Gravity.CENTER);
        GradientDrawable bd = new GradientDrawable();
        bd.setShape(GradientDrawable.OVAL);
        bd.setColor(0xFFE53935);
        dlBadge.setBackground(bd);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(18), dp(18));
        bl.gravity = Gravity.TOP | Gravity.END;
        bl.setMargins(0, dp(2), dp(1), 0);
        dlIcon.addView(dlBadge, bl);
        dlIcon.setVisibility(View.GONE);
        dlIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (cur != null) { cur.panelOpen = !cur.panelOpen; rebuildPanel(); } }
        });
        bar.addView(dlIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        bar.addView(tabBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        bar.addView(menu, new LinearLayout.LayoutParams(dp(36), dp(40)));

        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(0xFF24242B);
        findBar.setVisibility(View.GONE);
        findBox = new EditText(this);
        findBox.setSingleLine(true);
        findBox.setTextColor(Color.WHITE);
        findBox.setHint("Find in page");
        findBox.setHintTextColor(0xFF888888);
        findBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { if (cur != null) { if (e.length() > 0) cur.wv.findAllAsync(e.toString()); else cur.wv.clearMatches(); } }
        });
        TextView fPrev = iconBtn("\u25B2"), fNext = iconBtn("\u25BC"), fClose = iconBtn("\u2715");
        fPrev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (cur != null) cur.wv.findNext(false); } });
        fNext.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (cur != null) cur.wv.findNext(true); } });
        fClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hideFind(); } });
        findBar.addView(findBox, new LinearLayout.LayoutParams(0, dp(44), 1f));
        findBar.addView(fPrev, new LinearLayout.LayoutParams(dp(44), dp(44)));
        findBar.addView(fNext, new LinearLayout.LayoutParams(dp(44), dp(44)));
        findBar.addView(fClose, new LinearLayout.LayoutParams(dp(44), dp(44)));

        pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setVisibility(View.INVISIBLE);

        dlPanel = new LinearLayout(this);
        dlPanel.setOrientation(LinearLayout.VERTICAL);
        dlPanel.setBackgroundColor(0xFF1B1B22);
        dlPanel.setVisibility(View.GONE);
        dlHead = new TextView(this);
        dlHead.setTextColor(0xFFB9A8FF);
        dlHead.setTextSize(14);
        dlHead.setTypeface(null, android.graphics.Typeface.BOLD);
        dlHead.setPadding(dp(16), dp(9), dp(16), dp(9));
        dlHead.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (cur != null) { cur.panelOpen = !cur.panelOpen; rebuildPanel(); } }
        });
        dlList = new LinearLayout(this);
        dlList.setOrientation(LinearLayout.VERTICAL);
        dlScroll = new android.widget.ScrollView(this) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(dp(230), View.MeasureSpec.AT_MOST)); }
        };
        dlScroll.addView(dlList);
        dlPanel.addView(dlHead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlPanel.addView(dlScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        main.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(findBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(dlPanel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        main.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(0xFF1B1B22);
        String[] nl = {"\u2039", "\u203A", "\u2302", "\u27F3"};
        for (int ni = 0; ni < nl.length; ni++) {
            final int nid2 = ni;
            TextView nb = iconBtn(nl[ni]);
            nb.setTextSize(nid2 == 3 ? 24 : 30);
            nb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (cur == null) return;
                    if (nid2 == 0) goBack();
                    else if (nid2 == 1) { if (cur.wv.canGoForward()) cur.wv.goForward(); else toast("No page to go forward to"); }
                    else if (nid2 == 2) cur.wv.loadUrl(HOME);
                    else cur.wv.reload();
                }
            });
            navBar.addView(nb, new LinearLayout.LayoutParams(0, dp(46), 1f));
        }
        main.addView(navBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));


        fsHost = new FrameLayout(this);
        fsHost.setBackgroundColor(Color.BLACK);
        fsHost.setVisibility(View.GONE);
        root.addView(fsHost, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView iconBtn(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    // ---------------- tabs ----------------
    private Tab newTab(String url) { return newTab(url, false); }

    private Tab newTab(String url, boolean incognito) {
        Tab t = new Tab();
        t.incognito = incognito;
        t.wv = makeWebView(t);
        tabs.add(t);
        select(t);
        if (url != null) t.wv.loadUrl(upgrade(t, url));
        return t;
    }

    private String upgrade(Tab t, String u) {
        if (httpsUp && u.startsWith("http://")) {
            Uri p = Uri.parse(u);
            String h = p.getHost();
            if (h != null && !h.equals("localhost") && !h.matches("[0-9.:]+") && !h.endsWith(".local")) {
                t.fallback = u;
                return "https://" + u.substring(7);
            }
        }
        return u;
    }

    private void select(Tab t) {
        if (cur != null && cur.wv.getParent() == content) content.removeView(cur.wv);
        cur = t;
        if (t.wv.getParent() != null) ((ViewGroup) t.wv.getParent()).removeView(t.wv);
        content.addView(t.wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        urlBox.setText(t.url.startsWith("file:") ? "" : t.url);
        bar.setBackgroundColor(t.incognito ? 0xFF2D2147 : 0xFF1B1B1F);
        tabBtn.setText(String.valueOf(tabs.size()));
        pb.setVisibility(View.INVISIBLE);
        refreshBadge();
    }

    private void closeTab(Tab t) {
        tabs.remove(t);
        if (t.wv.getParent() != null) ((ViewGroup) t.wv.getParent()).removeView(t.wv);
        t.wv.destroy();
        if (t.incognito) cleanupPrivate();
        if (tabs.isEmpty()) { cur = null; newTab(HOME); }
        else select(tabs.get(tabs.size() - 1));
    }

    private void showTabs() {
        String[] items = new String[tabs.size()];
        for (int i = 0; i < items.length; i++) {
            Tab t = tabs.get(i);
            items[i] = (t == cur ? "\u25B6 " : "") + (t.title == null || t.title.isEmpty() ? t.url : t.title);
        }
        new AlertDialog.Builder(this).setTitle("Tabs (" + tabs.size() + ")")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { select(tabs.get(which)); }
            })
            .setPositiveButton("New tab", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { newTab(HOME); }
            })
            .setNegativeButton("Close this tab", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { closeTab(cur); }
            }).show();
    }

    // ---------------- WebView ----------------
    private WebView makeWebView(final Tab t) {
        WebView w = new WebView(this);
        if (t.incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try { WebViewCompat.setProfile(w, "incognito"); } catch (Throwable e) { /* shared storage */ }
        }
        WebSettings s = w.getSettings();
        s.setTextZoom(textZoom);
        s.setCacheMode(saver ? WebSettings.LOAD_CACHE_ELSE_NETWORK : WebSettings.LOAD_DEFAULT);
        applyDark(s);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(desktop);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(desktop ? desktopUa : defaultUa);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Bridge(t), "VPApp");

        if (scripts && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            t.sh = WebViewCompat.addDocumentStartJavaScript(w, injected, Collections.singleton("*"));
        }

        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                final String u = r.getUrl().toString();
                if (!r.isForMainFrame() && t.url != null && !(adblock && AdBlock.blocked(u))) noteOrigin(t.url, u);
                if (adblock && AdBlock.blocked(u)) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                if (saver && !r.isForMainFrame()) {
                    String low = u.toLowerCase();
                    int qi = low.indexOf('?');
                    String path = qi > 0 ? low.substring(0, qi) : low;
                    if (path.matches(".*\\.(png|jpe?g|gif|webp|avif|woff2?|ttf|otf)$")) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                    }
                }
                if (MEDIA.matcher(u).matches() && !u.contains("googlevideo.com")) {
                    ui.post(new Runnable() { public void run() { addMedia(t, u); } });
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                if (r.isForMainFrame() && "http".equals(sc)) {
                    String up = upgrade(t, r.getUrl().toString());
                    if (!up.equals(r.getUrl().toString())) { v.loadUrl(up); return true; }
                }
                if ("http".equals(sc) || "https".equals(sc) || "file".equals(sc) || "about".equals(sc) || "data".equals(sc) || "blob".equals(sc)) return false;
                if ("magnet".equals(sc) || "mailto".equals(sc) || "tel".equals(sc)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) { toast("No app can open this link"); }
                }
                return true;
            }

            @Override public void onPageStarted(WebView v, String u, Bitmap f) {
                t.url = u;
                t.media.clear();
                t.entries.clear(); t.covered.clear(); t.gen++; t.pending = 0; t.panelOpen = false; t.autoShown = false;
                if (t == cur) {
                    urlBox.setText(u.startsWith("file:") ? "" : u);
                    refreshBadge();
                }
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (!r.isForMainFrame()) return;
                if (t.fallback != null && r.getUrl().toString().startsWith("https://")) {
                    String fb = t.fallback;
                    t.fallback = null;
                    v.loadUrl(fb);
                    return;
                }
                String fu = r.getUrl().toString();
                String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body style='background:#111;color:#eee;font-family:sans-serif;padding:28px'>"
                    + "<h2>Can't open this page</h2><p style='color:#f88'>" + android.text.TextUtils.htmlEncode(String.valueOf(e.getDescription())) + "</p>"
                    + "<p style='color:#999;word-break:break-all'>" + android.text.TextUtils.htmlEncode(fu) + "</p>"
                    + "<p><a style='color:#8ab4ff' href='" + android.text.TextUtils.htmlEncode(fu) + "'>Try again</a></p>"
                    + "<p style='color:#777;font-size:13px'>net::ERR_NAME_NOT_RESOLVED = the address was not found (no internet, or the network blocks it). ERR_CONNECTION_TIMED_OUT = too slow or blocked.</p></body></html>";
                v.loadDataWithBaseURL(fu, html, "text/html", "utf-8", fu);
            }

            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
                h.cancel();
                toast("Security certificate problem on this site");
            }

            @Override public void onPageFinished(WebView v, String u) {
                String ti = v.getTitle();
                t.title = (ti == null || ti.isEmpty()) ? u : ti;
                t.fallback = null;
                if (!t.incognito && u.startsWith("http")) Store.addHistory(sp, t.title, u);
                if (!autoTr.isEmpty() && u.startsWith("http")) v.evaluateJavascript(readerJs + ";window.__vpReader.page('" + autoTr + "')", null);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                if (t == cur) {
                    pb.setProgress(p);
                    pb.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }

            @Override public void onReceivedTitle(WebView v, String ti) { if (ti != null) t.title = ti; }

            @Override public Bitmap getDefaultVideoPoster() { return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); }

            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                fsHost.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fsHost.setVisibility(View.VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                hideBars(true);
                refreshBadge();
            }

            @Override public void onHideCustomView() { exitFullscreen(); }

            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (!isUserGesture && adblock) return false;
                Tab nt = newTab(null, t.incognito);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(nt.wv);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public void onPermissionRequest(final PermissionRequest r) {
                final String[] res = r.getResources();
                boolean cam = false, mic = false;
                for (String x : res) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(x)) cam = true;
                    else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x)) mic = true;
                }
                if (!cam && !mic) { r.grant(res); return; }
                final boolean fc = cam, fm = mic;
                final String what = (cam ? "camera" : "") + (cam && mic ? " and " : "") + (mic ? "microphone" : "");
                runOnUiThread(new Runnable() {
                    public void run() {
                        String host = r.getOrigin() == null ? "This site" : r.getOrigin().getHost();
                        new AlertDialog.Builder(MainActivity.this).setTitle("Allow " + host + " to use your " + what + "?")
                            .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { askRuntime(r, res, fc, fm); }
                            })
                            .setNegativeButton("Block", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { r.deny(); }
                            })
                            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface d) { r.deny(); }
                            }).show();
                    }
                });
            }

            @Override public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback cb) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Allow " + Uri.parse(origin).getHost() + " to see your location?")
                    .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 79);
                            }
                            cb.invoke(origin, true, false);
                        }
                    })
                    .setNegativeButton("Block", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { cb.invoke(origin, false, false); }
                    }).show();
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), 77);
                } catch (Exception e) {
                    fileCb = null;
                    toast("No file picker available");
                    return false;
                }
                return true;
            }
        });

        w.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { return longPress(t); }
        });
        w.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                Downloader.direct(MainActivity.this, url, ua, t.url, URLUtil.guessFileName(url, cd, mime));
            }
        });
        return w;
    }

    private void askRuntime(PermissionRequest r, String[] res, boolean cam, boolean mic) {
        ArrayList<String> need = new ArrayList<String>();
        if (cam && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (mic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (need.isEmpty()) { r.grant(res); return; }
        pendPerm = r;
        pendRes = res;
        requestPermissions(need.toArray(new String[0]), 78);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == 78 && pendPerm != null) {
            boolean ok = grants.length > 0;
            for (int g : grants) if (g != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendPerm.grant(pendRes); else pendPerm.deny();
            pendPerm = null;
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 77 && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    private void applyDark(WebSettings s) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try { WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, dark); } catch (Throwable e) { /* ignore */ }
        }
    }

    private void cleanupPrivate() {
        for (Tab t : tabs) if (t.incognito) return;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try { ProfileStore.getInstance().deleteProfile("incognito"); } catch (Throwable e) { /* still in use */ }
        }
    }

    private boolean longPress(final Tab t) {
        WebView.HitTestResult h = t.wv.getHitTestResult();
        final String ex = h.getExtra();
        int ty = h.getType();
        if (ex == null) return false;
        final boolean link = ty == WebView.HitTestResult.SRC_ANCHOR_TYPE || ty == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        final boolean img = ty == WebView.HitTestResult.IMAGE_TYPE;
        if (!link && !img) return false;
        String[] items = link
            ? new String[]{"Open in new tab", "Open in private tab", "Copy link", "Share link", "Download"}
            : new String[]{"Open image in new tab", "Save image", "Copy image address"};
        new AlertDialog.Builder(this).setTitle(ex.length() > 60 ? ex.substring(0, 60) + "..." : ex)
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (link) {
                        if (which == 0) newTab(ex);
                        else if (which == 1) newTab(ex, true);
                        else if (which == 2) copy(ex);
                        else if (which == 3) share(ex);
                        else Downloader.direct(MainActivity.this, ex, t.wv.getSettings().getUserAgentString(), t.url, null);
                    } else {
                        if (which == 0) newTab(ex);
                        else if (which == 1) Downloader.direct(MainActivity.this, ex, t.wv.getSettings().getUserAgentString(), t.url, null);
                        else copy(ex);
                    }
                }
            }).show();
        return true;
    }

    private void copy(String s) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("url", s));
        toast("Copied");
    }

    private void share(String s) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private void showFind() {
        findBar.setVisibility(View.VISIBLE);
        findBox.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(findBox, 0);
    }

    private void hideFind() {
        findBar.setVisibility(View.GONE);
        findBox.setText("");
        if (cur != null) cur.wv.clearMatches();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(findBox.getWindowToken(), 0);
    }

    private void showSaved(final String key, final String title) {
        final JSONArray a = Store.get(sp, key);
        final int n = a.length();
        if (n == 0) { toast("Nothing here yet"); return; }
        final String[] items = new String[n];
        final String[] urls = new String[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = a.optJSONObject(n - 1 - i);
            String t = o == null ? "" : o.optString("t", "");
            urls[i] = o == null ? "" : o.optString("u", "");
            items[i] = (t.isEmpty() ? urls[i] : t) + "\n" + urls[i];
        }
        new AlertDialog.Builder(this).setTitle(title)
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { if (cur != null) cur.wv.loadUrl(urls[which]); }
            })
            .setNegativeButton("Clear all", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { sp.edit().putString(key, "[]").apply(); toast("Cleared"); }
            })
            .setPositiveButton("Close", null).show();
    }

    private String searchBase() {
        if ("duck".equals(engine)) return "https://duckduckgo.com/?q=";
        if ("brave".equals(engine)) return "https://search.brave.com/search?q=";
        if ("bing".equals(engine)) return "https://www.bing.com/search?q=";
        return "https://www.google.com/search?q=";
    }

    private void showSettings() {
        final String[] items = {
            "Dark mode for sites: " + (dark ? "ON" : "off"),
            "Block ads: " + (adblock ? "ON" : "off"),
            "Always use secure https: " + (httpsUp ? "ON" : "off"),
            "Video Plus scripts: " + (scripts ? "ON" : "off"),
            "Desktop site: " + (desktop ? "ON" : "off"),
            "Text size: " + textZoom + "%",
            "Search engine: " + engine,
            "Clear browsing data"
        };
        new AlertDialog.Builder(this).setTitle("Settings")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    switch (which) {
                        case 0:
                            dark = !dark;
                            sp.edit().putBoolean("dark", dark).apply();
                            for (Tab t : tabs) applyDark(t.wv.getSettings());
                            cur.wv.reload();
                            break;
                        case 1:
                            adblock = !adblock;
                            sp.edit().putBoolean("adblock", adblock).apply();
                            cur.wv.reload();
                            break;
                        case 2:
                            httpsUp = !httpsUp;
                            sp.edit().putBoolean("https", httpsUp).apply();
                            break;
                        case 3:
                            toggleScripts();
                            break;
                        case 4:
                            desktop = !desktop;
                            cur.wv.getSettings().setUserAgentString(desktop ? desktopUa : defaultUa);
                            cur.wv.getSettings().setLoadWithOverviewMode(desktop);
                            cur.wv.reload();
                            break;
                        case 5: pickZoom(); return;
                        case 6: pickEngine(); return;
                        case 7:
                            sp.edit().putString("hist", "[]").apply();
                            CookieManager.getInstance().removeAllCookies(null);
                            cur.wv.clearCache(true);
                            toast("History, cookies and cache cleared");
                            break;
                    }
                    showSettings();
                }
            }).setPositiveButton("Close", null).show();
    }

    private void pickZoom() {
        final int[] v = {80, 100, 120, 150, 200};
        String[] l = {"80%", "100%", "120%", "150%", "200%"};
        new AlertDialog.Builder(this).setTitle("Text size").setItems(l, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                textZoom = v[which];
                sp.edit().putInt("zoom", textZoom).apply();
                for (Tab t : tabs) t.wv.getSettings().setTextZoom(textZoom);
                showSettings();
            }
        }).show();
    }

    private void pickEngine() {
        final String[] k = {"google", "duck", "brave", "bing"};
        String[] l = {"Google", "DuckDuckGo", "Brave Search", "Bing"};
        new AlertDialog.Builder(this).setTitle("Search engine").setItems(l, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                engine = k[which];
                sp.edit().putString("engine", engine).apply();
                showSettings();
            }
        }).show();
    }

    private void toggleScripts() {
        scripts = !scripts;
        sp.edit().putBoolean("scripts", scripts).apply();
        for (Tab tt : tabs) {
            if (!scripts && tt.sh != null) { tt.sh.remove(); tt.sh = null; }
            else if (scripts && tt.sh == null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                tt.sh = WebViewCompat.addDocumentStartJavaScript(tt.wv, injected, Collections.singleton("*"));
            }
        }
        toast(scripts ? "Video Plus scripts on" : "Video Plus scripts off");
        cur.wv.reload();
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fsHost.removeView(customView);
        fsHost.setVisibility(View.GONE);
        customView = null;
        if (customCb != null) { try { customCb.onCustomViewHidden(); } catch (Exception e) { /* ignore */ } }
        customCb = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        hideBars(false);
        refreshBadge();
    }

    private void hideBars(boolean hide) {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        if (hide) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            c.show(WindowInsets.Type.systemBars());
        }
    }

    // ---------------- JS bridge (only harmless calls) ----------------
    private class Bridge {
        final Tab t;
        Bridge(Tab t) { this.t = t; }

        @JavascriptInterface public void media(String u) {
            if (u == null || u.length() > 2000) return;
            final String uu = u;
            ui.post(new Runnable() { public void run() { addMedia(t, uu); } });
        }

        @JavascriptInterface public String pre(String host) { return topOrigins(host); }
        @JavascriptInterface public boolean prev() { return autoPrev; }
        @JavascriptInterface public void http(final int id, final String url) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || url == null || !url.startsWith("https://api.mangadex.org/")) return;
            new Thread(new Runnable() {
                public void run() {
                    boolean ok = false;
                    String body = "";
                    try {
                        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        h.setConnectTimeout(12000);
                        h.setReadTimeout(25000);
                        h.setRequestProperty("User-Agent", "VideoPlusBrowser/1.0 (personal reader)");
                        int code = h.getResponseCode();
                        java.io.InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[16384];
                        int n;
                        while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
                        body = bo.toString("UTF-8");
                        ok = code < 400;
                    } catch (Exception e) { ok = false; }
                    final boolean fok = ok;
                    final String fb = body;
                    ui.post(new Runnable() { public void run() { try { t.wv.evaluateJavascript("window.__vpHttp&&window.__vpHttp(" + id + "," + fok + "," + org.json.JSONObject.quote(fb) + ")", null); } catch (Exception e) { /* ignore */ } } });
                }
            }).start();
        }
        @JavascriptInterface public void img(final int id, final String url) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || url == null || !url.startsWith("https://")) return;
            String hh = Uri.parse(url).getHost();
            if (hh == null || !(hh.equals("uploads.mangadex.org") || hh.endsWith(".mangadex.network") || hh.endsWith(".mangadex.org"))) return;
            new Thread(new Runnable() {
                public void run() {
                    String res = "";
                    try {
                        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        h.setConnectTimeout(12000);
                        h.setReadTimeout(30000);
                        h.setRequestProperty("User-Agent", "VideoPlusBrowser/1.0 (personal reader)");
                        if (h.getResponseCode() < 400) {
                            java.io.InputStream in = h.getInputStream();
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] b = new byte[16384];
                            int n;
                            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                            String ct = h.getContentType();
                            if (ct == null || !ct.startsWith("image/")) ct = "image/jpeg";
                            res = "data:" + ct + ";base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
                        }
                    } catch (Exception e) { res = ""; }
                    final String fr = res;
                    ui.post(new Runnable() { public void run() { try { t.wv.evaluateJavascript("window.__vpImg&&window.__vpImg(" + id + "," + org.json.JSONObject.quote(fr) + ")", null); } catch (Exception e) { /* ignore */ } } });
                }
            }).start();
        }
        @JavascriptInterface public void toast(final String s) { ui.post(new Runnable() { public void run() { MainActivity.this.toast(s); } }); }
        @JavascriptInterface public void trAsync(final int id, final String text, final String tl) {
            new Thread(new Runnable() {
                public void run() {
                    String res = "";
                    try {
                        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=" + Uri.encode(tl)).openConnection();
                        h.setRequestMethod("POST");
                        h.setDoOutput(true);
                        h.setConnectTimeout(10000);
                        h.setReadTimeout(20000);
                        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                        h.setRequestProperty("User-Agent", "Mozilla/5.0");
                        java.io.OutputStream os = h.getOutputStream();
                        os.write(("q=" + java.net.URLEncoder.encode(text, "UTF-8")).getBytes("UTF-8"));
                        os.close();
                        if (h.getResponseCode() == 200) {
                            java.io.InputStream in = h.getInputStream();
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] b = new byte[8192];
                            int n;
                            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                            in.close();
                            org.json.JSONArray segs = new org.json.JSONArray(bo.toString("UTF-8")).getJSONArray(0);
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < segs.length(); i++) sb.append(segs.getJSONArray(i).optString(0, ""));
                            res = sb.toString();
                        }
                    } catch (Exception e) { res = ""; }
                    final String r = res;
                    ui.post(new Runnable() { public void run() { try { t.wv.evaluateJavascript("window.__vpTr&&window.__vpTr(" + id + "," + org.json.JSONObject.quote(r) + ")", null); } catch (Exception e) { /* ignore */ } } });
                }
            }).start();
        }
        @JavascriptInterface public void playing(boolean p) { if (p) lastPlayTs = SystemClock.uptimeMillis(); }
    }

    private void addMedia(final Tab t, final String u) {
        if (u.startsWith("blob:") || t.media.contains(u) || t.entries.size() >= 12) return;
        if (u.toLowerCase().matches(".*(preview|thumb|sprite|teaser|trailer|storyboard|/gif/|\\.gif).*")) return;
        t.media.add(u);
        if (t.media.size() > 40) t.media.remove(0);
        final Entry en = new Entry();
        en.url = u;
        t.entries.add(en);
        t.pending++;
        final int g = t.gen;
        final String ua = t.wv.getSettings().getUserAgentString();
        final String ref = t.url;
        if (t == cur) rebuildPanel();
        ui.postDelayed(new Runnable() { public void run() { resolver.execute(new Runnable() {
            public void run() {
                final List<Opt> opts = new ArrayList<Opt>();
                final List<String> vurls = new ArrayList<String>();
                boolean master = false;
                String low = u.toLowerCase();
                try {
                    if (low.contains(".m3u8")) {
                        List<Downloader.Variant> vs = Downloader.variants(u, ua, ref);
                        master = vs.size() > 1;
                        for (int i = 0; i < vs.size() && i < 8; i++) {
                            Downloader.Variant v = vs.get(i);
                            v.dur = Downloader.duration(v.url, ua, ref);
                            Opt o = new Opt();
                            o.hls = true; o.url = v.url; o.tag = v.q;
                            String sz = (v.bw > 0 && v.dur > 0) ? "  \u2022  ~" + mb((long) (v.bw / 8.0 * v.dur)) : "";
                            String br = v.bw > 0 ? "  \u2022  " + String.format(java.util.Locale.US, "%.1f", v.bw / 1000000.0) + " Mbps" : "";
                            o.label = ("Original".equals(v.q) ? "Video (best available)" : v.q) + br + sz + "  \u2022  stream";
                            opts.add(o);
                            if (master) vurls.add(v.url);
                        }
                    } else if (low.contains(".mpd")) {
                        // DASH not supported for saving yet: show nothing
                    } else {
                        long sz = Downloader.size(u, ua, ref);
                        if (sz < 0 || sz >= 300 * 1024) {
                            Opt o = new Opt();
                            o.url = u;
                            java.util.regex.Matcher m = Pattern.compile("(?i)(\\d{3,4})p").matcher(u);
                            String q = m.find() ? m.group(1) + "p" : "video";
                            o.tag = q;
                            String ext = "FILE";
                            java.util.regex.Matcher m2 = Pattern.compile("(?i)\\.([a-z0-9]{2,4})(\\?|#|$)").matcher(Uri.parse(u).getPath() == null ? "" : Uri.parse(u).getPath());
                            if (m2.find()) ext = m2.group(1).toUpperCase();
                            o.label = ext + ("video".equals(q) ? "" : "  \u2022  " + q) + (sz > 0 ? "  \u2022  " + mb(sz) : "");
                            opts.add(o);
                        }
                    }
                } catch (Exception ex) {
                    Opt o = new Opt();
                    o.url = u; o.hls = low.contains(".m3u8");
                    o.label = "Video (could not read qualities)";
                    opts.add(o);
                }
                final boolean fm = master;
                ui.post(new Runnable() { public void run() { applyResolved(t, g, en, opts, fm ? vurls : null); } });
            }
        }); } }, 1500);
    }

    private static String mb(long b) {
        if (b >= 1048576L * 1024) return String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0);
        return String.format(java.util.Locale.US, "%d MB", Math.max(1, b / 1048576L));
    }

    private void applyResolved(Tab t, int g, Entry e, List<Opt> opts, List<String> variantUrls) {
        if (t.gen != g) return;
        if (t.pending > 0) t.pending--;
        e.opts = opts;
        if (variantUrls != null) {
            t.covered.addAll(variantUrls);
            for (Entry x : t.entries) if (x != e && t.covered.contains(x.url)) x.hidden = true;
        } else if (t.covered.contains(e.url)) e.hidden = true;
        if (!t.autoShown && visibleOpts(t) > 0) { t.autoShown = true; t.panelOpen = true; }
        if (t == cur) rebuildPanel();
    }

    private int visibleOpts(Tab t) {
        int n = 0;
        for (Entry e : t.entries) if (!e.hidden && e.opts != null) n += e.opts.size();
        return n;
    }

    private void refreshBadge() { rebuildPanel(); }

    private void rebuildPanel() {
        if (dlPanel == null) return;
        final Tab t = cur;
        if (t == null || inPip || customView != null) { dlPanel.setVisibility(View.GONE); dlIcon.setVisibility(View.GONE); return; }
        int n = visibleOpts(t);
        dlIcon.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        dlBadge.setText(String.valueOf(n));
        if (n == 0 || !t.panelOpen) { dlPanel.setVisibility(View.GONE); return; }
        dlPanel.setVisibility(View.VISIBLE);
        dlHead.setText(n == 0 ? "\u2B07  Finding video qualities..." : "\u2B07  " + n + " download option" + (n > 1 ? "s" : "") + "   " + (t.panelOpen ? "\u25B2" : "\u25BC"));
        dlList.removeAllViews();
        dlScroll.setVisibility(t.panelOpen && n > 0 ? View.VISIBLE : View.GONE);
        if (!t.panelOpen) return;
        for (Entry e : t.entries) {
            if (e.hidden || e.opts == null) continue;
            for (final Opt o : e.opts) {
                TextView r = new TextView(this);
                r.setText(o.label);
                r.setTextColor(Color.WHITE);
                r.setTextSize(15);
                r.setPadding(dp(20), dp(13), dp(16), dp(13));
                r.setBackgroundColor(0xFF24242B);
                r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startOpt(t, o); } });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = 1;
                dlList.addView(r, lp);
            }
        }
    }

    private void startOpt(Tab t, Opt o) {
        String ua = t.wv.getSettings().getUserAgentString();
        String nm = safeName(t.title) + "_" + o.tag;
        if (o.hls) Downloader.hls(getApplicationContext(), o.url, ua, t.url, nm);
        else Downloader.direct(this, o.url, ua, t.url, null);
        t.panelOpen = false;
        rebuildPanel();
        toast("Download started - see notification");
    }

    private void setPlaying(boolean p) {
        playing = p;
        if (p) BgService.acquire(this); else BgService.release(this);
        updatePipParams();
    }

    private void updatePipParams() {
        try {
            PictureInPictureParams.Builder pb2 = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9));
            if (Build.VERSION.SDK_INT >= 31) pb2.setAutoEnterEnabled(playing);
            setPictureInPictureParams(pb2.build());
        } catch (Throwable e) { /* ignore */ }
    }

    // ---------------- downloads ----------------
    private static String shortName(String u) {
        try {
            Uri p = Uri.parse(u);
            String last = p.getLastPathSegment();
            return (last == null ? "video" : last) + "  \u2022  " + p.getHost();
        } catch (Exception e) { return u; }
    }

    private void showMediaList() {
        if (cur == null || cur.media.isEmpty()) return;
        final List<String> list = new ArrayList<String>(cur.media);
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = shortName(list.get(i));
        new AlertDialog.Builder(this).setTitle("Videos found on this page")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { startDownload(list.get(which)); }
            }).show();
    }

    private String safeName(String title) {
        String n = title == null ? "video" : title.replaceAll("[^A-Za-z0-9 _.()-]", "").trim();
        if (n.isEmpty()) n = "video";
        if (n.length() > 60) n = n.substring(0, 60);
        return n + "_" + (System.currentTimeMillis() % 100000);
    }

    private void startDownload(final String u) {
        final String ua = cur.wv.getSettings().getUserAgentString();
        final String ref = cur.url;
        final String name = safeName(cur.title);
        String low = u.toLowerCase();
        if (low.contains(".m3u8")) {
            toast("Reading stream...");
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final List<Downloader.Variant> vs = Downloader.variants(u, ua, ref);
                        ui.post(new Runnable() { public void run() { pickVariant(vs, ua, ref, name); } });
                    } catch (final Exception e) {
                        ui.post(new Runnable() { public void run() { toast("Cannot read stream: " + e.getMessage()); } });
                    }
                }
            }).start();
        } else if (low.contains(".mpd")) {
            toast("DASH (.mpd) streams are not supported yet");
        } else {
            Downloader.direct(this, u, ua, ref, null);
        }
    }

    private void pickVariant(final List<Downloader.Variant> vs, final String ua, final String ref, final String name) {
        if (vs.isEmpty()) { toast("No video found in stream"); return; }
        if (vs.size() == 1) {
            Downloader.hls(getApplicationContext(), vs.get(0).url, ua, ref, name);
            return;
        }
        String[] items = new String[vs.size()];
        for (int i = 0; i < items.length; i++) items[i] = vs.get(i).label;
        new AlertDialog.Builder(this).setTitle("Choose quality")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    Downloader.hls(getApplicationContext(), vs.get(which).url, ua, ref, name);
                }
            }).show();
    }

    // ---------------- navigation / menu ----------------
    private void go(String in) {
        String q = in.trim();
        q = q.contains(" ") ? q.replaceAll("[\\r\\n\\t]+", " ") : q.replaceAll("[\\r\\n\\t]", "");
        if (q.isEmpty() || cur == null) return;
        String url;
        if (q.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = q;
        else if (q.startsWith("magnet:")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(q))); } catch (Exception e) { toast("No torrent app installed"); }
            return;
        }
        else if (!q.contains(" ") && q.contains(".")) url = "https://" + q;
        else url = searchBase() + Uri.encode(q);
        final String hh = hostOf(url);
        if (hh != null) new Thread(new Runnable() { public void run() { try { java.net.InetAddress.getAllByName(hh); } catch (Exception e) { /* ignore */ } } }).start();
        cur.wv.loadUrl(upgrade(cur, url));
    }

    private void goBack() {
        if (cur != null && cur.wv.canGoBack()) cur.wv.goBack();
        else if (tabs.size() > 1) closeTab(cur);
    }

    private void showMenu(View anchor) {
        boolean bm = cur != null && Store.isBookmarked(sp, cur.url);
        final int[] ids = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21};
        final String[] labels = {"New tab", "Private tab", "Reload", "Forward", "Find in page", bm ? "Unbookmark" : "Bookmark",
            "Bookmarks", "History", "Float video", "Video Plus", "Translate", "Downloads", "Copy link", "Share", "Settings", "Update", "Exit", saver ? "Saver: ON" : "Data saver", "Reading mode", autoTr.isEmpty() ? "Auto-translate: off" : "Auto: " + autoTr.toUpperCase(), autoPrev ? "Preview: ON" : "Preview: off"};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF24242B);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        final PopupWindow pop = new PopupWindow(box, (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pop.setElevation(dp(8));
        for (int i = 0; i < ids.length; i += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                TextView tv = new TextView(this);
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(13);
                tv.setSingleLine(true);
                if (i + c < ids.length) {
                    final int id = ids[i + c];
                    tv.setText(labels[i + c]);
                    tv.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { pop.dismiss(); menuAction(id); }
                    });
                }
                row.addView(tv, new LinearLayout.LayoutParams(0, dp(44), 1f));
            }
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        pop.showAsDropDown(anchor, 0, 0, Gravity.END);
    }

    private void menuAction(int id) {
        switch (id) {
            case 1: newTab(HOME); break;
            case 2: newTab(HOME, true); toast("Private tab: no history saved"); break;
            case 3: cur.wv.reload(); break;
            case 4: if (cur.wv.canGoForward()) cur.wv.goForward(); break;
            case 5: showFind(); break;
            case 6:
                if (cur.url.startsWith("http")) toast(Store.toggleBookmark(sp, cur.title, cur.url) ? "Bookmark added" : "Bookmark removed");
                break;
            case 7: showSaved("bm", "Bookmarks"); break;
            case 8: showSaved("hist", "History"); break;
            case 9: if (playing) enterPip(); else toast("Start a video first"); break;
            case 10:
                cur.wv.evaluateJavascript("(function(){var r=document.querySelector('#vplus-root');if(r){r.style.display='block';var f=r.querySelector('#vp-fab');if(f)f.click();}})()", null);
                break;
            case 11:
                if (cur.url.startsWith("http")) cur.wv.loadUrl("https://translate.google.com/translate?sl=auto&tl=en&u=" + Uri.encode(cur.url));
                break;
            case 12:
                try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); } catch (Exception e) { toast("Open the Files app > Downloads > VideoPlus"); }
                break;
            case 13: copy(cur.url); break;
            case 14: share(cur.url); break;
            case 15: showSettings(); break;
            case 16: Updater.check(MainActivity.this, true); break;
            case 17: finishAndRemoveTask(); break;
            case 19:
                if (cur.url.startsWith("http")) cur.wv.evaluateJavascript(readerJs + ";window.__vpReader.auto='" + autoTr + "';window.__vpReader.toggle()", null);
                break;
            case 21:
                autoPrev = !autoPrev;
                sp.edit().putBoolean("autoprev", autoPrev).apply();
                for (Tab t : tabs) { try { t.wv.evaluateJavascript("window.__vpPrevOn=" + autoPrev, null); } catch (Exception e) { /* ignore */ } }
                toast(autoPrev ? "Video previews ON: the thumbnail in the middle of the screen plays" : "Video previews off");
                break;
            case 20:
                autoTr = autoTr.isEmpty() ? "en" : (autoTr.equals("en") ? "hi" : "");
                sp.edit().putString("autotr", autoTr).apply();
                toast(autoTr.isEmpty() ? "Auto-translate off" : "Pages will auto-translate to " + (autoTr.equals("en") ? "English" : "Hindi") + " (pages already in that language are skipped)");
                if (!autoTr.isEmpty() && cur.url.startsWith("http")) cur.wv.evaluateJavascript(readerJs + ";window.__vpReader.page('" + autoTr + "')", null);
                break;
            case 18:
                saver = !saver;
                sp.edit().putBoolean("saver", saver).apply();
                for (Tab t : tabs) t.wv.getSettings().setCacheMode(saver ? WebSettings.LOAD_CACHE_ELSE_NETWORK : WebSettings.LOAD_DEFAULT);
                toast(saver ? "Data saver ON: images and fonts are skipped, saved pages load from cache" : "Data saver off");
                cur.wv.reload();
                break;
        }
    }

    // ---------------- picture-in-picture ----------------
    private void enterPip() {
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
        } catch (Throwable e) { toast("Picture-in-picture not available"); }
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (playing && !isInPictureInPictureMode()) enterPip();
    }

    @Override public void onPictureInPictureModeChanged(boolean in, Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        inPip = in;
        bar.setVisibility(in ? View.GONE : View.VISIBLE);
        navBar.setVisibility(in ? View.GONE : View.VISIBLE);
        pb.setVisibility(View.GONE);
        refreshBadge();
        if (cur != null && customView == null) cur.wv.evaluateJavascript("window.__vpPip&&window.__vpPip(" + in + ")", null);
    }

    // ---------------- lifecycle ----------------
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Uri d = i.getData();
        if (d != null) newTab(d.toString());
    }

    @Override public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (cur != null && cur.wv.canGoBack()) { cur.wv.goBack(); return; }
        if (tabs.size() > 1) { closeTab(cur); return; }
        moveTaskToBack(true);
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (sensors != null) sensors.unregisterListener(luxListener); } catch (Exception e) { /* ignore */ }
        CookieManager.getInstance().flush();
        JSONArray saved = new JSONArray();
        for (Tab t : tabs) if (!t.incognito && t.url.startsWith("http") && saved.length() < 8) saved.put(t.url);
        sp.edit().putString("tabs", saved.toString()).apply();
        /* the page is deliberately NOT paused, so audio keeps playing in the background */
        setBg(true);
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            if (sensors == null) sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            Sensor ls = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (ls != null) sensors.registerListener(luxListener, ls, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) { /* no light sensor: the Auto picture uses the video alone */ }
        setBg(false);
        Updater.resume(this);
    }

    private void setBg(boolean on) {
        for (Tab t : tabs) { try { t.wv.evaluateJavascript("window.__vpBg=" + on, null); } catch (Exception e) { /* ignore */ } }
    }

    /** "@version 3.4" -> 3004 (major * 1000 + minor), 0 when missing. */
    static int scriptVer(String js) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("@version\\s+(\\d+)\\.(\\d+)").matcher(js);
            if (m.find()) return Integer.parseInt(m.group(1)) * 1000 + Integer.parseInt(m.group(2));
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    @Override protected void onDestroy() {
        if (playing) { playing = false; BgService.release(this); }
        for (Tab t : tabs) { try { t.wv.destroy(); } catch (Exception e) { /* ignore */ } }
        super.onDestroy();
    }
}
