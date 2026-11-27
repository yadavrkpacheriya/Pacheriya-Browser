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
import android.provider.MediaStore;
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
        int gen, pending, uaMode = -1; // -1 = follow global
        long loadStart;
        long lastTap;     // when the user last physically touched this page (any touch)
        long lastRealTap; // when the user last tapped a GENUINE link/button (set by the page script) — authorizes a redirect
        String pageHost;  // host of the page the user actually navigated to
        boolean opened;   // this tab was opened by a page (window.open), not by the user
        boolean firstNav = true; // still waiting for this tab's first navigation
        String altScheme;
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
        boolean panelOpen, autoShown;
    }

    static class Opt { String label; String url; boolean hls; String tag = "video"; }
    static class Entry { String url; boolean hidden; List<Opt> opts; }

    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private Tab cur;
    private FrameLayout root, content, fsHost;
    private LinearLayout main, bar;
    private EditText urlBox;
    private TextView tabBtn, dlHead, lockIcon;
    private LinearLayout dlPanel, dlList, navBar, dlOverlay;
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
    private float lastScale = 0f;
    private boolean immersive = false, picTr = true;
    private String ocrJs = "";
    private final java.util.concurrent.ExecutorService ocrPool = java.util.concurrent.Executors.newFixedThreadPool(2);

    private void setReadMode(boolean b) {
        immersive = b;
        if (inPip) return;
        bar.setVisibility(b ? View.GONE : View.VISIBLE);
        navBar.setVisibility(b ? View.GONE : View.VISIBLE);
        if (customView == null) hideBars(b);
        if (b) { barsHidden = false; ui.removeCallbacks(hideChromeR); if (handle != null) handle.setVisibility(View.GONE); }
        refreshBadge();
        if (!b) showChrome();
    }

    /* the address bar and the bottom buttons slide away after 3 seconds; scrolling up or the small arrow brings them back */
    private boolean barsHidden = false;
    private TextView handle;
    private final Runnable hideChromeR = new Runnable() { public void run() { hideChrome(); } };

    private void showChrome() {
        if (immersive || inPip || customView != null) return;
        barsHidden = false;
        bar.setVisibility(View.VISIBLE);
        navBar.setVisibility(View.VISIBLE);
        if (handle != null) handle.setVisibility(View.GONE);
        refreshBadge();
        ui.removeCallbacks(hideChromeR);
    }

    private void hideChrome() {
        if (barsHidden || immersive || inPip || customView != null || cur == null) return;
        if (urlBox.hasFocus() || (cur.panelOpen && dlPanel.getVisibility() == View.VISIBLE) || cur.url == null || !cur.url.startsWith("http")) { ui.removeCallbacks(hideChromeR); ui.postDelayed(hideChromeR, 3000); return; }
        barsHidden = true;
        bar.setVisibility(View.GONE);
        navBar.setVisibility(View.GONE);
        if (handle != null) handle.setVisibility(View.VISIBLE);
        refreshBadge();
    }
    private WebView subWv;
    private Tab subTab;

    private void subCall(String fn, String args) {
        try {
            if (subWv == null) {
                subWv = new WebView(this);
                WebSettings ss = subWv.getSettings();
                ss.setJavaScriptEnabled(true);
                ss.setDomStorageEnabled(true);
                ss.setAllowFileAccess(true);
                ss.setMediaPlaybackRequiresUserGesture(false);
                subWv.addJavascriptInterface(new SubBridge(), "SubApp");
                final androidx.webkit.WebViewAssetLoader al = new androidx.webkit.WebViewAssetLoader.Builder().addPathHandler("/assets/", new androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this)).build();
                subWv.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return al.shouldInterceptRequest(r.getUrl()); }
                });
                /* a real https origin lets the engine keep the AI model in the browser cache instead of downloading it every time */
                subWv.loadUrl("https://appassets.androidplatform.net/assets/sub.html");
                addContentView(subWv, new android.widget.FrameLayout.LayoutParams(1, 1));
            }
            subWv.evaluateJavascript("(function f(){if(window.__ok)window.__" + fn + "(" + args + ");else setTimeout(f,400)})()", null);
        } catch (Throwable e) { subPush("{\"t\":\"err\",\"msg\":\"engine could not start\"}"); }
    }

    private void subPush(final String json) {
        ui.post(new Runnable() { public void run() { try { if (subTab != null) subTab.wv.evaluateJavascript("window.__vpSub&&window.__vpSub(" + json + ")", null); } catch (Throwable e) { /* ignore */ } } });
    }

    private class SubBridge {
        @JavascriptInterface public void prog(int p) { subPush("{\"t\":\"prog\",\"p\":" + p + "}"); }
        @JavascriptInterface public void ready() { subPush("{\"t\":\"ready\"}"); }
        @JavascriptInterface public void err(String m) { subPush("{\"t\":\"err\",\"msg\":" + org.json.JSONObject.quote(m == null ? "" : m) + "}"); }
        @JavascriptInterface public void res(String j) { subPush(j); }
    }
    private boolean playing = false, inPip = false, desktop = false, adblock = true, scripts = true;
    private boolean autoPrev = true;
    private String readerJs = "", pwJs = "";
    private String autoTr = "";
    private String injected = "", defaultUa = "", desktopUa = "", UA_EDGE = "", UA_IE = "", UA_FF = "", authUa = "";
    private androidx.webkit.WebViewAssetLoader assetLoader;
    private final java.util.Map<String,String> pdfProxy = new java.util.concurrent.ConcurrentHashMap<String,String>();
    private int uaMode = 0; // 0 default,1 desktop chrome,2 edge,3 IE11,4 firefox
    private static final String[] UA_NAMES = {"Default (mobile)", "Desktop Chrome", "Microsoft Edge", "Internet Explorer 11", "Firefox"};
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
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
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
        Diag.init(this);
        try { BuildBuild.CODE = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Throwable e) { /* ignore */ }
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
        if (vp == null || vp.length() < 10000 || scriptVer(vp) <= scriptVer(vpa)) vp = vpa;
        String css = "(function(){var c=" + jq(asset("vp-cosmetic.css")) + ";function a(){var p=document.head||document.documentElement;if(!p){setTimeout(a,15);return}var s=document.createElement('style');s.textContent=c;p.appendChild(s)}a()})();";
        readerJs = asset("reader.js");
        ocrJs = asset("ocr.js");
        pwJs = asset("pw.js");
        picTr = sp.getBoolean("pictr", true);
        autoPrev = sp.getBoolean("autoprev", true);
        autoTr = sp.getString("autotr", "en");
        injected = asset("vp-core.js") + "\n" + css + "\n" + vp;

        String ua = WebSettings.getDefaultUserAgent(this);
        defaultUa = ua.replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        java.util.regex.Matcher cm = Pattern.compile("Chrome/([\\d.]+)").matcher(ua);
        String cv = cm.find() ? cm.group(1) : "120.0.0.0";
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + cv + " Safari/537.36";
        // Clean mobile-Chrome UA (no "wv", real Pixel) used ONLY on sign-in hosts so Google/MS accept the in-app WebView.
        authUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + cv + " Mobile Safari/537.36";
        UA_EDGE = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + cv + " Safari/537.36 Edg/" + cv;
        UA_IE = "Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko";
        UA_FF = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
        uaMode = sp.getInt("uamode", 0);

        assetLoader = new androidx.webkit.WebViewAssetLoader.Builder().addPathHandler("/assets/", new androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this)).build();
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
                if (!inPip && cur != null) { try { float sc = cur.wv.getScale(); if (sc > 0.2f && sc < 8f) lastScale = sc; } catch (Throwable e) { /* ignore */ } }
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
        bar.setBackgroundColor(0xFF15151A);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView back = iconBtn("\u2039");
        back.setTextSize(28);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goBack(); }
        });

        urlBox = new android.widget.AutoCompleteTextView(this);
        urlBox.setSingleLine(true);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setHintTextColor(0xFF888888);
        urlBox.setHint("Search or type address");
        urlBox.setTextSize(15);
        urlBox.setSelectAllOnFocus(true);
        // URI input type disables suggestions; use plain text + no-suggestions flag so the custom dropdown works and the keyboard still behaves.
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setBackground(null);
        urlBox.setPadding(dp(6), dp(8), dp(10), dp(8));
        setupUrlSuggestions();
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
        tabBtn.setTextSize(13);
        tabBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0x00000000); tb.setStroke(dp(2), 0xFFCFCFE0); tb.setCornerRadius(dp(6));
        tabBtn.setBackground(tb);
        tabBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTabs(); }
        });
        TextView menu = iconBtn("\u22EE");
        menu.setTextSize(24);
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(v); }
        });

        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        // address pill: lock icon + url, Chrome-style rounded container
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF26262F);
        gd.setCornerRadius(dp(22));
        pill.setBackground(gd);
        pill.setPadding(dp(12), 0, dp(6), 0);
        lockIcon = new TextView(this);
        lockIcon.setText("🔒"); lockIcon.setTextSize(12); lockIcon.setTextColor(0xFF9AA0AA);
        pill.addView(lockIcon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pill.addView(urlBox, new LinearLayout.LayoutParams(0, dp(42), 1f));
        bar.addView(pill, new LinearLayout.LayoutParams(0, dp(42), 1f));
        dlIcon = new FrameLayout(this);
        TextView dlI = iconBtn("\u2B07");
        dlI.setTextSize(20);
        dlI.setTextColor(0xFFE2B33C);
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
        GradientDrawable dpbg = new GradientDrawable();
        dpbg.setColor(0xFF23232E);
        dpbg.setCornerRadius(dp(16));
        dpbg.setStroke(dp(1), 0x33FFFFFF);
        dlPanel.setBackground(dpbg);
        dlPanel.setElevation(dp(18));
        dlPanel.setVisibility(View.GONE);
        dlHead = new TextView(this);
        dlHead.setTextColor(0xFFE2B33C);
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
        dlOverlay = dlPanel;
        main.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(findBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        main.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout.LayoutParams dol = new FrameLayout.LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * 0.68f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        dol.topMargin = dp(10); dol.rightMargin = dp(10);
        content.addView(dlPanel, dol);
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(0xFF15151A);
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
            if (nid2 == 3) { // long-press Reload = rescue a stuck/frozen page (stop autoplay, unlock scroll, kill overlays)
                nb.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (cur == null) return false;
                        if (customView != null) exitFullscreen();
                        cur.wv.evaluateJavascript("window.__vpUnstick&&window.__vpUnstick()", null);
                        toast("Unfroze page: autoplay stopped, scrolling restored");
                        return true;
                    }
                });
            }
            navBar.addView(nb, new LinearLayout.LayoutParams(0, dp(46), 1f));
        }
        main.addView(navBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));


        fsHost = new FrameLayout(this);
        fsHost.setBackgroundColor(Color.BLACK);
        fsHost.setVisibility(View.GONE);
        handle = new TextView(this);
        handle.setText("\u2303");
        handle.setTextColor(0xFFFFFFFF);
        handle.setTextSize(16);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0x99000000);
        hb.setCornerRadius(dp(14));
        handle.setBackground(hb);
        handle.setVisibility(View.GONE);
        handle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showChrome(); } });
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(54), dp(26), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(12);
        root.addView(handle, hlp);
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

    private boolean sameHostOtherScheme(String a, String b) {
        try { return Uri.parse(a).getHost() != null && Uri.parse(a).getHost().equals(Uri.parse(b).getHost()); } catch (Exception e) { return false; }
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
        if (cur != null && cur.swipe != null && cur.swipe.getParent() == content) content.removeView(cur.swipe);
        cur = t;
        if (immersive) setReadMode(false); else if (bar != null && navBar != null) showChrome();
        if (t.swipe == null) {
            final Tab ft = t;
            t.swipe = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this) {
                // Only allow pull-to-refresh when the WebView is really scrolled to the very top; otherwise let it scroll.
                @Override public boolean canChildScrollUp() {
                    return ft.wv != null && ft.wv.getScrollY() > 2;
                }
            };
            t.swipe.setColorSchemeColors(0xFFE2B33C);
            // Require a longer pull so a normal upward swipe doesn't accidentally refresh.
            try { t.swipe.setDistanceToTriggerSync(dp(140)); } catch (Throwable e) {}
            t.swipe.setProgressBackgroundColorSchemeColor(0xFF1E1E26);
            if (t.wv.getParent() != null) ((ViewGroup) t.wv.getParent()).removeView(t.wv);
            t.swipe.addView(t.wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.swipe.setOnRefreshListener(new androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener() {
                public void onRefresh() { ft.wv.reload(); ui.postDelayed(new Runnable() { public void run() { if (ft.swipe != null) ft.swipe.setRefreshing(false); } }, 1200); }
            });
        }
        if (t.swipe.getParent() != null) ((ViewGroup) t.swipe.getParent()).removeView(t.swipe);
        content.addView(t.swipe, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((android.widget.AutoCompleteTextView) urlBox).setText(t.url.startsWith("file:") ? "" : t.url, false);
        setLock(t.url);
        bar.setBackgroundColor(t.incognito ? 0xFF241A33 : 0xFF15151A);
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

    /** Capture a small thumbnail of a tab's current page for the tab switcher. */
    private android.graphics.Bitmap tabThumb(Tab t, int w, int h) {
        try {
            if (t.wv == null || t.wv.getWidth() == 0) return null;
            android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(t.wv.getWidth(), Math.min(t.wv.getHeight(), t.wv.getWidth() * h / Math.max(1, w)), android.graphics.Bitmap.Config.RGB_565);
            android.graphics.Canvas c = new android.graphics.Canvas(full);
            c.drawColor(Color.WHITE);
            t.wv.draw(c);
            android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(full, w, h, true);
            if (sc != full) full.recycle();
            return sc;
        } catch (Throwable e) { return null; }
    }

    /** Chrome-style grid tab switcher: 2-column thumbnail cards, each with a close \u2715; a New-tab bar at the bottom. */
    private void showTabs() {
        final android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout rootL = new LinearLayout(this);
        rootL.setOrientation(LinearLayout.VERTICAL);
        rootL.setBackgroundColor(0xFF101014);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(18), dp(14));
        TextView ht = new TextView(this);
        ht.setText(tabs.size() + (tabs.size() == 1 ? " tab" : " tabs"));
        ht.setTextColor(Color.WHITE); ht.setTextSize(20);
        ht.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = new TextView(this);
        done.setText("Done"); done.setTextColor(0xFFE2B33C); done.setTextSize(16);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setPadding(dp(12), dp(8), dp(4), dp(8));
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); } });
        head.addView(done);
        rootL.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // scrollable 2-column grid
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(10), 0, dp(10), dp(90));
        int tw = (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2;
        int th = tw * 5 / 4;
        for (int i = 0; i < tabs.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < tabs.size(); j++) {
                final Tab t = tabs.get(j);
                FrameLayout card = new FrameLayout(this);
                android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
                cg.setColor(0xFF1C1C24); cg.setCornerRadius(dp(14));
                if (t == cur) { cg.setStroke(dp(2), 0xFFE2B33C); }
                card.setBackground(cg);
                LinearLayout cc = new LinearLayout(this); cc.setOrientation(LinearLayout.VERTICAL);
                // title row
                LinearLayout tr = new LinearLayout(this); tr.setOrientation(LinearLayout.HORIZONTAL);
                tr.setGravity(Gravity.CENTER_VERTICAL); tr.setPadding(dp(10), dp(8), dp(6), dp(6));
                TextView tt = new TextView(this);
                tt.setText(t.title == null || t.title.isEmpty() ? (t.url == null ? "New tab" : t.url) : t.title);
                tt.setTextColor(0xFFDDDDE2); tt.setTextSize(12); tt.setSingleLine(true);
                tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tr.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView x = new TextView(this);
                x.setText("\u2715"); x.setTextColor(0xFF9A9AA6); x.setTextSize(15);
                x.setPadding(dp(6), dp(2), dp(8), dp(4));
                final android.app.Dialog fd = dlg;
                x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeTab(t); fd.dismiss(); if (!tabs.isEmpty()) showTabs(); } });
                tr.addView(x);
                cc.addView(tr);
                // thumbnail
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
                iv.setBackgroundColor(0xFF000000);
                android.graphics.Bitmap bm = tabThumb(t, tw, th);
                if (bm != null) iv.setImageBitmap(bm);
                cc.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, th));
                card.addView(cc);
                card.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { select(t); dlg.dismiss(); } });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(tw, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(dp(5), dp(5), dp(5), dp(5));
                row.addView(card, clp);
            }
            grid.addView(row);
        }
        sv.addView(grid);
        rootL.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // bottom new-tab bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackgroundColor(0xFF17171E);
        bottom.setPadding(dp(16), dp(14), dp(16), dp(16));
        TextView nt = new TextView(this);
        nt.setText("+  New tab"); nt.setTextColor(Color.WHITE); nt.setTextSize(16);
        nt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable ntb = new android.graphics.drawable.GradientDrawable();
        ntb.setColor(0xFFE2B33C); ntb.setCornerRadius(dp(24));
        nt.setBackground(ntb); nt.setGravity(Gravity.CENTER);
        nt.setTextColor(0xFF1A1405);
        nt.setPadding(dp(28), dp(12), dp(28), dp(12));
        nt.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { newTab(HOME); dlg.dismiss(); } });
        bottom.addView(nt);
        TextView inc = new TextView(this);
        inc.setText("Private"); inc.setTextColor(0xFFBBBBC4); inc.setTextSize(15);
        inc.setPadding(dp(20), dp(12), dp(10), dp(12));
        inc.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { newTab(HOME, true); toast("Private tab: no history saved"); dlg.dismiss(); } });
        bottom.addView(inc);
        rootL.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dlg.setContentView(rootL);
        dlg.show();
    }

    // ---------------- WebView ----------------
    private WebView makeWebView(final Tab t) {
        WebView w = new WebView(this);
        w.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) t.lastTap = System.currentTimeMillis();
                return false;
            }
        });
        w.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                if (barsHidden && oy - y > dp(14)) showChrome();
                if (t.swipe != null) t.swipe.setEnabled(y == 0);
            }
        });
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
        s.setUseWideViewPort(true);
        s.setGeolocationEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(uaFor(uaMode));
        s.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Bridge(t), "VPApp");

        if (scripts && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            t.sh = WebViewCompat.addDocumentStartJavaScript(w, injected, Collections.singleton("*"));
        }

        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                final String u = r.getUrl().toString();
                if (u.startsWith("https://appassets.androidplatform.net/")) {
                    java.util.regex.Matcher pm = java.util.regex.Pattern.compile("/assets/pdfjs/doc-([0-9a-f]+)\\.pdf$").matcher(r.getUrl().getPath());
                    if (pm.find()) {
                        String real = pdfProxy.get(pm.group(1));
                        if (real != null) return fetchPdf(real, t.url, v.getSettings().getUserAgentString());
                    }
                    return assetLoader.shouldInterceptRequest(r.getUrl());
                }
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
                if (!r.isForMainFrame() && !u.contains("googlevideo.com") && u.toLowerCase().matches("^[^?#]*\\.m3u8(\\?.*)?$")) {
                    String before = AutoQuality.lastInfo;
                    WebResourceResponse q = AutoQuality.handle(MainActivity.this, r, saver);
                    if (q != null) {
                        final String info = AutoQuality.lastInfo;
                        if (!info.equals(before) && !info.isEmpty()) ui.post(new Runnable() { public void run() { toast("Auto quality: " + info); } });
                        ui.post(new Runnable() { public void run() { addMedia(t, u); } });
                        return q;
                    }
                }
                if (MEDIA.matcher(u).matches() && !u.contains("googlevideo.com")) {
                    ui.post(new Runnable() { public void run() { addMedia(t, u); } });
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                // Safe-browsing: warn before loading a known phishing/scam/malware-pattern host (when protection is on).
                if (r.isForMainFrame() && ("http".equals(sc) || "https".equals(sc)) && sp.getBoolean("safebrowse", true) && Security.isDangerousHost(r.getUrl().toString())) {
                    final String bad = r.getUrl().toString();
                    if (!bad.equals(sp.getString("safebrowse_skip", ""))) {
                        ui.post(new Runnable() { public void run() { warnDanger(bad); } });
                        return true;
                    }
                    sp.edit().remove("safebrowse_skip").apply(); // one-time pass used
                }
                // Google/Microsoft/Apple block sign-in inside WebViews. Primary fix: present a clean Chrome UA on the auth
                // host so the in-app WebView is accepted (no other browser needed). We let the navigation proceed here;
                // the UA swap happens in onPageStarted. Custom Tab is only a manual fallback (menu) if a site still refuses.
                // Redirect guard: block a main-frame jump to a different site that wasn't driven by a real, recent tap
                // (idle redirects, refresh redirects, tap-hijack pop-unders). Only when the blocker is on.
                if (adblock && r.isForMainFrame() && ("http".equals(sc) || "https".equals(sc))) {
                    try {
                        String full = r.getUrl().toString();
                        String dest = r.getUrl().getHost();
                        boolean adHost = AdBlock.blocked(full), sketchy = AdBlock.sketchyHost(full);
                        // A page-opened (window.open) tab whose first stop is an ad / throwaway host is a pop-under: kill the tab.
                        if (t.opened && t.firstNav && (adHost || sketchy)) {
                            final Tab dead = t;
                            Diag.bump("popups_blocked");
                            ui.post(new Runnable() { public void run() { closeTab(dead); toast("Blocked a pop-up ad"); } });
                            return true;
                        }
                        long since = System.currentTimeMillis() - t.lastRealTap;
                        boolean recentTap = since >= 0 && since < 1200;
                        boolean crossSite = t.pageHost != null && dest != null && !reg(dest).equals(reg(t.pageHost));
                        // Allow legitimate sign-in / OAuth / SSO hops: these are cross-site redirects with no tap, but to a known
                        // auth host (Google, Cloudflare, GitHub, Okta, Auth0...) or coming back from one. Only AD/sketchy hosts are blocked here.
                        boolean authHop = isAuthHost(dest == null ? "" : dest.toLowerCase())
                            || (t.pageHost != null && isAuthHost(t.pageHost.toLowerCase()))
                            || (dest != null && (dest.endsWith(".cloudflareaccess.com") || dest.contains("oauth") || dest.contains("/sso") || dest.contains("saml")));
                        // Block only when it's an ad/sketchy host, OR a cross-site jump that is NOT a login hop and had no real tap.
                        if ((adHost || sketchy || (crossSite && !authHop)) && !recentTap && t.pageHost != null) {
                            Diag.bump("redirects_blocked");
                            final String blocked = dest;
                            ui.post(new Runnable() { public void run() { toast("Blocked a redirect to " + blocked); } });
                            return true;
                        }
                    } catch (Throwable ig) {}
                }
                if (r.isForMainFrame() && "http".equals(sc)) {
                    String up = upgrade(t, r.getUrl().toString());
                    if (!up.equals(r.getUrl().toString())) { v.loadUrl(up); return true; }
                }
                if (r.isForMainFrame() && ("http".equals(sc) || "https".equals(sc) || "file".equals(sc)) && isPdf(r.getUrl().toString())) {
                    v.loadUrl(pdfViewer(r.getUrl().toString())); return true;
                }
                if ("http".equals(sc) || "https".equals(sc) || "file".equals(sc) || "about".equals(sc) || "data".equals(sc) || "blob".equals(sc)) return false;
                return openExternal(v, r.getUrl().toString(), sc);
            }

            @Override public void onPageStarted(WebView v, String u, Bitmap f) {
                t.url = u;
                t.loadStart = System.currentTimeMillis();
                // Present a clean Chrome UA on sign-in hosts so Google/Microsoft accept the in-app WebView (no other browser needed).
                try {
                    String h0 = Uri.parse(u).getHost();
                    String want = (h0 != null && isAuthHost(h0.toLowerCase())) ? authUa : uaFor(t.uaMode >= 0 ? t.uaMode : uaMode);
                    if (!want.equals(v.getSettings().getUserAgentString())) v.getSettings().setUserAgentString(want);
                } catch (Throwable ig) {}
                // A pop-under tab whose first page is an ad / throwaway host: close it on sight.
                if (adblock && t.opened && t.firstNav && u != null && u.startsWith("http") && (AdBlock.blocked(u) || AdBlock.sketchyHost(u))) {
                    final Tab dead = t;
                    Diag.bump("popups_blocked");
                    try { v.stopLoading(); } catch (Throwable ig) {}
                    ui.post(new Runnable() { public void run() { closeTab(dead); toast("Blocked a pop-up ad"); } });
                    return;
                }
                t.firstNav = false;
                // Record the host the user legitimately landed on (first load, a recent tap, or an in-app load like the address bar / reload).
                try {
                    String h = Uri.parse(u).getHost();
                    if (h != null && u.startsWith("http")) {
                        long since = System.currentTimeMillis() - t.lastTap;
                        if (t.pageHost == null || (since >= 0 && since < 1500) || reg(h).equals(t.pageHost == null ? "" : reg(t.pageHost))) t.pageHost = h;
                    }
                } catch (Throwable ig) {}
                if (t.altScheme != null && !u.equals(t.altScheme) && !sameHostOtherScheme(u, t.altScheme)) t.altScheme = null;
                if (u.startsWith("http")) Diag.bump("pages_opened");
                if (t == cur && immersive) setReadMode(false);
                else if (t == cur) showChrome();
                t.media.clear();
                t.entries.clear(); t.covered.clear(); t.gen++; t.pending = 0; t.panelOpen = false; t.autoShown = false;
                if (t == cur) {
                    ((android.widget.AutoCompleteTextView) urlBox).setText(u.startsWith("file:") ? "" : u, false);
                    setLock(u);
                    refreshBadge();
                }
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (!r.isForMainFrame()) return;
                Diag.log("PAGE_ERROR", String.valueOf(e.getDescription()) + " :: " + r.getUrl());
                if (t.fallback != null && r.getUrl().toString().startsWith("https://")) {
                    String fb = t.fallback;
                    t.fallback = null;
                    v.loadUrl(fb);
                    return;
                }
                if (t.altScheme != null && r.isForMainFrame()) {
                    String alt = t.altScheme;
                    t.altScheme = null;
                    if (!alt.equals(r.getUrl().toString())) { v.loadUrl(alt); return; }
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
                if (u.startsWith("http") && t.loadStart > 0) { long ms = System.currentTimeMillis() - t.loadStart; if (ms > 8000) Diag.log("SLOW_PAGE", ms + "ms :: " + u); }
                String ti = v.getTitle();
                t.title = (ti == null || ti.isEmpty()) ? u : ti;
                t.fallback = null;
                if (!t.incognito && u.startsWith("http")) Store.addHistory(sp, t.title, u);
                if (u.startsWith("http")) ui.postDelayed(new Runnable() { public void run() { if (t == cur) fitZoom(t, 0); } }, 900);
                if (u.startsWith("http")) v.evaluateJavascript(readerJs + ";window.__vpReader.page('" + autoTr + "')", null);
                if (picTr && u.startsWith("http")) v.evaluateJavascript(ocrJs + ";window.__vpPic&&window.__vpPic.start()", null);
                if (u.startsWith("http") && sp.getBoolean("pwsave", true) && !t.incognito) v.evaluateJavascript(pwJs, null);
            }
            // SPA sites (e.g. WTR-LAB chapters) change the URL via JS without a full reload — catch those for history.
            @Override public void doUpdateVisitedHistory(WebView v, String u, boolean isReload) {
                t.url = u;
                if (!t.incognito && u != null && u.startsWith("http")) Store.addHistory(sp, v.getTitle(), u);
                if (t == cur) { ((android.widget.AutoCompleteTextView) urlBox).setText(u.startsWith("file:") ? "" : u, false); setLock(u); }
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                if (t == cur) {
                    pb.setProgress(p);
                    pb.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }

            @Override public void onReceivedTitle(WebView v, String ti) {
                if (ti != null) { t.title = ti; if (!t.incognito && t.url != null && t.url.startsWith("http")) Store.addHistory(sp, ti, t.url); }
            }

            @Override public Bitmap getDefaultVideoPoster() { return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); }

            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                fsHost.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                // Always-available app-level close button, so a stuck fullscreen video can never trap the user.
                TextView fx = new TextView(MainActivity.this);
                fx.setText("✕"); fx.setTextColor(Color.WHITE); fx.setTextSize(20);
                fx.setGravity(Gravity.CENTER);
                GradientDrawable fg = new GradientDrawable(); fg.setShape(GradientDrawable.OVAL); fg.setColor(0xAA000000);
                fx.setBackground(fg);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(42), dp(42));
                flp.gravity = Gravity.TOP | Gravity.END; flp.setMargins(0, dp(14), dp(14), 0);
                fx.setLayoutParams(flp);
                fx.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { exitFullscreen(); } });
                fsHost.addView(fx);
                fsHost.setVisibility(View.VISIBLE);
                // Follow the phone fully in fullscreen video: both landscape directions AND portrait, ignoring the rotation lock.
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                hideBars(true);
                refreshBadge();
            }

            @Override public void onHideCustomView() { exitFullscreen(); }

            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (adblock) {
                    // Pop-under / new-tab ad: a hijacked tap still reports a "gesture", so also require that the user
                    // really touched THIS page in the last ~1.2s AND that the opener wasn't itself an ad frame.
                    long since = System.currentTimeMillis() - t.lastRealTap;
                    boolean recentTap = since >= 0 && since < 1200;
                    if (!isUserGesture || !recentTap) { Diag.bump("popups_blocked"); return false; }
                    // Peek at the href the popup is trying to open; block if it's a known ad host or a throwaway popunder TLD.
                    String href = null;
                    try { android.os.Message m2 = v.getHandler().obtainMessage(); v.requestFocusNodeHref(m2); Object u = m2.getData().get("url"); if (u != null) href = u.toString(); } catch (Throwable ig) {}
                    if (href != null && (AdBlock.blocked(href) || AdBlock.sketchyHost(href))) { Diag.bump("popups_blocked"); return false; }
                }
                Tab nt = newTab(null, t.incognito);
                nt.opened = true;
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
                                geoOrigin = origin; geoCb = cb;
                                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 79);
                            } else {
                                cb.invoke(origin, true, true);
                            }
                        }
                    })
                    .setNegativeButton("Block", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { cb.invoke(origin, false, false); }
                    }).show();
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                return showFilePicker(p);
            }
        });

        w.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { return longPress(t); }
        });
        w.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                if ((mime != null && mime.contains("pdf")) || isPdf(url)) {
                    if (t == cur) t.wv.loadUrl(pdfViewer(url));
                    return;
                }
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
        if (code == 79 && geoCb != null) {
            boolean ok = false;
            for (int g : grants) if (g == PackageManager.PERMISSION_GRANTED) ok = true;
            geoCb.invoke(geoOrigin, ok, ok);
            geoCb = null; geoOrigin = null;
        }
    }

    private Uri captureUri;

    private boolean showFilePicker(WebChromeClient.FileChooserParams p) {
        try {
            String[] accept = p != null ? p.getAcceptTypes() : null;
            String acc = "";
            if (accept != null) for (String a : accept) if (a != null && !a.isEmpty()) acc += (acc.isEmpty() ? "" : ",") + a;
            boolean wantsImage = acc.contains("image") || acc.isEmpty();
            boolean wantsVideo = acc.contains("video") || acc.isEmpty();

            Intent content = (p != null) ? p.createIntent() : new Intent(Intent.ACTION_GET_CONTENT).setType("*/*");
            try {
                content.addCategory(Intent.CATEGORY_OPENABLE);
                if (content.getType() == null) content.setType("*/*");
                content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p != null && p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
            } catch (Exception e) { /* ignore */ }

            ArrayList<Intent> extras = new ArrayList<Intent>();
            captureUri = null;
            if ((wantsImage || wantsVideo) && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                try {
                    java.io.File dir = new java.io.File(getExternalCacheDir(), "shared");
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, "cap_" + System.currentTimeMillis() + (wantsVideo && !wantsImage ? ".mp4" : ".jpg"));
                    captureUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                    if (wantsImage) { Intent c = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE); c.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, captureUri); if (c.resolveActivity(getPackageManager()) != null) extras.add(c); }
                    if (wantsVideo && !wantsImage) { Intent c = new Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE); c.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, captureUri); if (c.resolveActivity(getPackageManager()) != null) extras.add(c); }
                } catch (Exception e) { captureUri = null; }
            }

            Intent chooser = new Intent(Intent.ACTION_CHOOSER);
            chooser.putExtra(Intent.EXTRA_INTENT, content);
            chooser.putExtra(Intent.EXTRA_TITLE, "Choose file");
            if (!extras.isEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toArray(new Intent[0]));
            startActivityForResult(chooser, 77);
            return true;
        } catch (Exception e) {
            try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 77); return true; }
            catch (Exception e2) { fileCb = null; toast("No file picker available"); return false; }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 77 && fileCb != null) {
            Uri[] out = null;
            if (res == RESULT_OK) {
                if (data == null || (data.getData() == null && data.getClipData() == null)) {
                    if (captureUri != null) out = new Uri[]{ captureUri };
                } else {
                    out = WebChromeClient.FileChooserParams.parseResult(res, data);
                }
            }
            fileCb.onReceiveValue(out);
            fileCb = null;
            captureUri = null;
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
        // Phone number (tel: link or a number WebView detected): offer Call / WhatsApp / SMS / Copy.
        if (ty == WebView.HitTestResult.PHONE_TYPE) {
            final String num = ex.replaceAll("[^+0-9]", "");
            new AlertDialog.Builder(this).setTitle(ex)
                .setItems(new String[]{"Call", "WhatsApp", "Send SMS", "Copy number"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        try {
                            if (which == 0) startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num)));
                            else if (which == 1) { String w = num.startsWith("+") ? num.substring(1) : num; startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + w))); }
                            else if (which == 2) startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + num)));
                            else copy(num);
                        } catch (Exception e) { toast("No app available for that"); }
                    }
                }).show();
            return true;
        }
        if (ty == WebView.HitTestResult.EMAIL_TYPE) {
            final String em = ex;
            new AlertDialog.Builder(this).setTitle(ex)
                .setItems(new String[]{"Send email", "Copy address"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        try { if (which == 0) startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + em))); else copy(em); }
                        catch (Exception e) { toast("No email app"); }
                    }
                }).show();
            return true;
        }
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

    /* upload bytes to a temp image host from native code (no browser CORS limits) and return the public URL */
    private String catbox(byte[] img) {
        String last = "no response";
        String[] endpoints = {"https://catbox.moe/user/api.php", "https://litterbox.catbox.moe/resources/internals/api.php"};
        for (int k = 0; k < endpoints.length; k++) {
            try {
                String boundary = "----vp" + System.nanoTime();
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(endpoints[k]).openConnection();
                h.setConnectTimeout(15000); h.setReadTimeout(40000);
                h.setDoOutput(true); h.setRequestMethod("POST");
                h.setRequestProperty("User-Agent", "Mozilla/5.0");
                h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                java.io.OutputStream os = h.getOutputStream();
                java.io.ByteArrayOutputStream pre = new java.io.ByteArrayOutputStream();
                String dash = "--";
                StringBuilder sb = new StringBuilder();
                sb.append(dash).append(boundary).append("\r\n").append("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n");
                if (k == 1) sb.append(dash).append(boundary).append("\r\n").append("Content-Disposition: form-data; name=\"time\"\r\n\r\n72h\r\n");
                sb.append(dash).append(boundary).append("\r\n").append("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"f.jpg\"\r\n").append("Content-Type: image/jpeg\r\n\r\n");
                os.write(sb.toString().getBytes("UTF-8"));
                os.write(img);
                os.write(("\r\n" + dash + boundary + dash + "\r\n").getBytes("UTF-8"));
                os.flush(); os.close();
                int rc = h.getResponseCode();
                if (rc != 200) { last = "HTTP" + rc; continue; }
                java.io.InputStream in = h.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String r = bo.toString("UTF-8").trim();
                if (r.startsWith("http")) return r;
            } catch (Throwable e) { last = e.getClass().getSimpleName(); }
        }
        return "ERR:" + last;
    }

    private WebResourceResponse fetchPdf(String url, String referer, String ua) {
        try {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            h.setInstanceFollowRedirects(true);
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            if (ua != null) h.setRequestProperty("User-Agent", ua);
            if (referer != null && referer.startsWith("http")) h.setRequestProperty("Referer", referer);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null && !ck.isEmpty()) h.setRequestProperty("Cookie", ck);
            java.util.Map<String,String> hd = new java.util.HashMap<String,String>();
            hd.put("Access-Control-Allow-Origin", "*");
            WebResourceResponse resp = new WebResourceResponse("application/pdf", null, h.getInputStream());
            resp.setResponseHeaders(hd);
            return resp;
        } catch (Throwable e) { return null; }
    }

    /* handle intent:// and app-scheme links the way Chrome/Brave/Edge do: open the app, else its web fallback, else the store */
    private boolean openExternal(WebView v, String u, String sc) {
        if ("intent".equals(sc)) {
            try {
                Intent in = Intent.parseUri(u, Intent.URI_INTENT_SCHEME);
                in.addCategory(Intent.CATEGORY_BROWSABLE);
                in.setComponent(null);
                in.setSelector(null);
                if (in.resolveActivity(getPackageManager()) != null) { startActivity(in); return true; }
                String fb = in.getStringExtra("browser_fallback_url");
                if (fb != null && fb.startsWith("http")) { v.loadUrl(fb); return true; }
                String pkg = in.getPackage();
                if (pkg != null) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))); } catch (Exception e) { toast("App not installed"); } return true; }
            } catch (Exception e) { /* fall through */ }
            toast("Couldn't open that link");
            return true;
        }
        // Phone / SMS / email: use the right action and do NOT require CATEGORY_BROWSABLE (dialer/SMS apps don't register it).
        try {
            if ("tel".equals(sc)) {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(u)));   // opens dialer with the number prefilled
                return true;
            }
            if ("sms".equals(sc) || "smsto".equals(sc) || "mailto".equals(sc)) {
                startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(u)));
                return true;
            }
        } catch (Exception e) { toast("No app available for this"); return true; }
        // magnet, and app schemes like upi://, whatsapp://, tg://, intent fallbacks, etc.
        try {
            Intent in = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            if (in.resolveActivity(getPackageManager()) != null) startActivity(in);
            else { startActivity(in); } // let Android show its own chooser/handler even if resolve is empty
        } catch (Exception e) {
            if (u.startsWith("whatsapp")) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp"))); } catch (Exception x) { toast("WhatsApp not installed"); } }
            else toast("No app can open this link");
        }
        return true;
    }

    private static boolean isPdf(String u) {
        if (u == null) return false;
        if (u.startsWith("https://appassets.androidplatform.net/")) return false;
        String p = u.split("[?#]", 2)[0].toLowerCase();
        return p.endsWith(".pdf");
    }

    private String pdfViewer(String u) {
        try {
            String id = Integer.toHexString(u.hashCode()) + Long.toHexString(System.nanoTime());
            String file;
            if (u.startsWith("file://") || u.startsWith("content://") || u.startsWith("https://appassets.androidplatform.net/")) {
                file = u;
            } else {
                pdfProxy.put(id, u);
                file = "https://appassets.androidplatform.net/assets/pdfjs/doc-" + id + ".pdf";
            }
            return "https://appassets.androidplatform.net/assets/pdfjs/view.html?file=" + java.net.URLEncoder.encode(file, "UTF-8");
        } catch (Exception e) { return u; }
    }

    private String uaFor(int m) {
        switch (m) { case 1: return desktopUa; case 2: return UA_EDGE; case 3: return UA_IE; case 4: return UA_FF; default: return defaultUa; }
    }

    private long lastCustomTab = 0;
    /** Identity-provider sign-in hosts that block the default WebView UA. */
    private boolean isAuthHost(String host) {
        if (host == null) return false;
        return host.equals("accounts.google.com") || host.equals("accounts.youtube.com") || host.endsWith(".google.com") && host.startsWith("accounts") ||
            host.equals("login.microsoftonline.com") || host.equals("login.live.com") || host.equals("login.microsoft.com") ||
            host.equals("appleid.apple.com") || host.endsWith(".okta.com") || host.equals("accounts.spotify.com") ||
            host.equals("dash.cloudflare.com") || host.equals("login.cloudflare.com") || // Cloudflare dashboard login blocks WebViews
            host.equals("github.com") || host.equals("gitlab.com") || host.equals("auth0.com") || host.endsWith(".auth0.com") ||
            host.equals("www.facebook.com") && false; // fb works in-webview; left for clarity
    }
    /** Open a URL in a Chrome Custom Tab (a real Chrome surface) so provider sign-in works; returns false if it can't. */
    private boolean openInCustomTab(String url) {
        // avoid loops: don't re-divert within 2s
        if (System.currentTimeMillis() - lastCustomTab < 2000) return false;
        try {
            androidx.browser.customtabs.CustomTabsIntent ci = new androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build();
            ci.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // prefer Chrome if present so Google accepts it
            if (getPackageManager().getLaunchIntentForPackage("com.android.chrome") != null) ci.intent.setPackage("com.android.chrome");
            ci.launchUrl(this, Uri.parse(url));
            lastCustomTab = System.currentTimeMillis();
            toast("Opening secure sign-in… finish here, then return to the app");
            return true;
        } catch (Throwable e) {
            // No Chrome / custom tabs available: fall back to opening the system browser.
            try { Intent in = new Intent(Intent.ACTION_VIEW, Uri.parse(url)); in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(in); lastCustomTab = System.currentTimeMillis(); return true; } catch (Throwable x) { return false; }
        }
    }

    private void applyUa(Tab t) {
        if (t == null) return;
        WebSettings s = t.wv.getSettings();
        int m = t.uaMode >= 0 ? t.uaMode : uaMode;
        boolean deskLayout = m != 0;
        s.setUserAgentString(uaFor(m));
        s.setLoadWithOverviewMode(deskLayout);
    }

    private void pickIdentity() {
        int cur0 = cur != null && cur.uaMode >= 0 ? cur.uaMode : uaMode;
        new AlertDialog.Builder(this).setTitle("Browser identity")
            .setSingleChoiceItems(UA_NAMES, cur0, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    uaMode = which;
                    sp.edit().putInt("uamode", which).apply();
                    if (cur != null) { cur.uaMode = which; applyUa(cur); cur.wv.reload(); }
                    d.dismiss();
                    toast(which == 3 ? "Internet Explorer 11 identity: camera login pages should open now (live video needs the camera app)" : "Identity: " + UA_NAMES[which]);
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private void showSettings() {
        final String[] items = {
            "Dark mode for sites: " + (dark ? "ON" : "off"),
            "Block ads: " + (adblock ? "ON" : "off"),
            "Always use secure https: " + (httpsUp ? "ON" : "off"),
            "Video Plus scripts: " + (scripts ? "ON" : "off"),
            "Browser identity: " + UA_NAMES[uaMode],
            "Text size: " + textZoom + "%",
            "Search engine: " + engine,
            "🔒 App lock (fingerprint): " + (sp.getBoolean("applock", false) ? "ON" : "off"),
            "🛡 Safe browsing (block scam sites): " + (sp.getBoolean("safebrowse", true) ? "ON" : "off"),
            "🔑 Save passwords: " + (sp.getBoolean("pwsave", true) ? "ON" : "off"),
            "Saved passwords & breach check",
            "⭐ Set as default browser",
            "Check for update",
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
                            pickIdentity();
                            return;
                        case 5: pickZoom(); return;
                        case 6: pickEngine(); return;
                        case 7: {
                            boolean on = !sp.getBoolean("applock", false);
                            sp.edit().putBoolean("applock", on).apply();
                            if (on) { unlocked = false; appLockGate(); toast("App lock on: fingerprint/PIN needed to open"); }
                            else toast("App lock off");
                            break;
                        }
                        case 8: {
                            boolean on = !sp.getBoolean("safebrowse", true);
                            sp.edit().putBoolean("safebrowse", on).apply();
                            toast(on ? "Safe browsing on" : "Safe browsing off");
                            break;
                        }
                        case 9: {
                            boolean on = !sp.getBoolean("pwsave", true);
                            sp.edit().putBoolean("pwsave", on).apply();
                            toast(on ? "Password saving on" : "Password saving off");
                            break;
                        }
                        case 10: showSavedPasswords(); return;
                        case 11: setAsDefaultBrowser(); return;
                        case 12: Updater.check(MainActivity.this, true); return;
                        case 13:
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
        fsHost.removeAllViews();   // removes the video view and the app close button
        fsHost.setVisibility(View.GONE);
        customView = null;
        if (customCb != null) { try { customCb.onCustomViewHidden(); } catch (Exception e) { /* ignore */ } }
        customCb = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        hideBars(false);
        refreshBadge();
        final Tab zt = cur;
        ui.postDelayed(new Runnable() { public void run() { if (zt == cur) fitZoom(zt, 0); } }, 800);
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
        @JavascriptInterface public boolean pic() { return picTr; }
        @JavascriptInterface public void ocr(final int id, final String url) {
            if (!picTr || url == null || url.length() > 3000 || !(url.startsWith("https://") || url.startsWith("http://"))) return;
            final String ref = t.url != null && t.url.startsWith("http") ? t.url : null;
            final String ck = CookieManager.getInstance().getCookie(url);
            final String tl = autoTr.isEmpty() ? "en" : autoTr;
            final int gen = t.gen;
            ocrPool.execute(new Runnable() {
                public void run() {
                    final String res = gen == t.gen || ref == null ? Ocr.run(url, ref, ck, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36", tl) : "";
                    if (res.isEmpty()) return;
                    ui.post(new Runnable() { public void run() { try { t.wv.evaluateJavascript("window.__vpOcr&&window.__vpOcr(" + id + "," + org.json.JSONObject.quote(res) + ")", null); } catch (Exception e) { /* ignore */ } } });
                }
            });
        }
        @JavascriptInterface public void chrome(final boolean hide) {
            ui.post(new Runnable() { public void run() { if (t == cur) setReadMode(hide); } });
        }
        /** Reader mode turns pull-to-refresh off (so scrolling up doesn't reload the page) and back on when it closes. */
        @JavascriptInterface public void swipeEnable(final boolean on) {
            ui.post(new Runnable() { public void run() { if (t.swipe != null) t.swipe.setEnabled(on); } });
        }
        @JavascriptInterface public void subInit(final String model) {
            ui.post(new Runnable() { public void run() { subTab = t; subCall("init", "'" + model.replaceAll("[^a-z]", "") + "'"); } });
        }
        @JavascriptInterface public void subGo(final double t0, final String b64) {
            ui.post(new Runnable() { public void run() { subTab = t; subCall("go", t0 + ",'" + b64 + "'"); } });
        }
        @JavascriptInterface public float getVol() {
            try { android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE); return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC); } catch (Throwable e) { return -1f; }
        }
        @JavascriptInterface public void setVol(float f) {
            try { android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE); int mx = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.max(0, Math.min(mx, Math.round(f * mx))), 0); } catch (Throwable e) { /* ignore */ }
        }
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
        @JavascriptInterface public void askMediaPerm() {
            ui.post(new Runnable() { public void run() {
                try {
                    String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
                    if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{ p }, 81);
                } catch (Throwable e) { /* ignore */ }
            }});
        }
        @JavascriptInterface public boolean hasMediaPerm() {
            try { String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE; return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; } catch (Throwable e) { return false; }
        }
        @JavascriptInterface public String scanAllVideos() {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) return "[]";
            org.json.JSONArray arr = new org.json.JSONArray();
            try {
                String[] proj = { MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH };
                android.database.Cursor c2 = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (c2 != null) {
                    int n = 0;
                    while (c2.moveToNext() && n < 2000) {
                        long idv = c2.getLong(0);
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("name", c2.getString(1));
                        o.put("size", c2.getLong(2));
                        o.put("mime", c2.getString(3) == null ? "video/*" : c2.getString(3));
                        o.put("dur", c2.getLong(4));
                        o.put("path", c2.getString(5));
                        o.put("uri", android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, idv).toString());
                        arr.put(o); n++;
                    }
                    c2.close();
                }
            } catch (Throwable e) { /* ignore */ }
            return arr.toString();
        }
        /** Returns a small base64 JPEG thumbnail for a video uri (data: URI), or "" if none. Called lazily per row so the list stays fast. */
        @JavascriptInterface public String thumb(String uriStr) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || uriStr == null) return "";
            android.graphics.Bitmap bm = null;
            try {
                android.net.Uri u = android.net.Uri.parse(uriStr);
                // API 29+: MediaStore.loadThumbnail is fast (uses cached thumbs when available)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try { bm = getContentResolver().loadThumbnail(u, new android.util.Size(320, 180), null); } catch (Throwable ig) {}
                }
                if (bm == null) {
                    android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                    try {
                        r.setDataSource(MainActivity.this, u);
                        bm = r.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } finally { try { r.release(); } catch (Throwable ig) {} }
                }
                if (bm == null) return "";
                // scale down to keep the data URI small
                int tw = 320, th = Math.max(1, (int)(bm.getHeight() * (320.0 / Math.max(1, bm.getWidth()))));
                android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(bm, tw, th, true);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
                if (sc != bm) sc.recycle(); bm.recycle();
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Throwable e) { if (bm != null) try { bm.recycle(); } catch (Throwable ig) {} return ""; }
        }
        /** A login form was submitted: offer to save the credential (any page may call this). */
        @JavascriptInterface public void pwOffer(final String user, final String pass) {
            if (t.incognito || user == null || pass == null || pass.isEmpty()) return;
            final String url = t.url;
            if (url == null || !url.startsWith("http")) return;
            if (!sp.getBoolean("pwsave", true)) return; // user turned saving off
            ui.post(new Runnable() { public void run() {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Save password?")
                    .setMessage("Save login for " + Passwords.host(url) + (user.isEmpty() ? "" : " (" + user + ")") + "?")
                    .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { Passwords.save(MainActivity.this, url, user, pass); toast("Password saved"); } })
                    .setNegativeButton("Never", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { } })
                    .setNeutralButton("Not now", null)
                    .show();
            } });
        }
        /** Page asks whether we have saved logins for it (returns [{u,p}] so the page can autofill). Guarded to real pages. */
        @JavascriptInterface public String pwGet() {
            if (t.incognito || t.url == null || !t.url.startsWith("http")) return "[]";
            return Passwords.forUrl(MainActivity.this, t.url).toString();
        }

        /** Let the app's own asset pages (manga, finder, library) record a readable history entry. Guarded to asset origin. */
        @JavascriptInterface public void histAdd(String title, String url) {
            if (t.incognito || t.url == null || !t.url.startsWith("file:///android_asset/")) return;
            if (title == null || url == null) return;
            Store.addHistory(sp, title, url);
        }

        /** Resolve a social/video page link to a direct video URL (og:video, twitter:player:stream, or a media .mp4 in the HTML). */
        @JavascriptInterface public void resolveVideo(final int id, final String link) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) { finderVid(id, ""); return; }
            new Thread(new Runnable() { public void run() {
                String found = "";
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(link).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(9000); c.setReadTimeout(9000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36");
                    c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln; int cap = 0;
                    while ((ln = br.readLine()) != null && cap < 4_000_000) { sb.append(ln).append('\n'); cap += ln.length(); }
                    br.close();
                    String html = sb.toString();
                    found = firstMatch(html,
                        "property=\"og:video\"[^>]*content=\"([^\"]+)\"",
                        "property=\"og:video:url\"[^>]*content=\"([^\"]+)\"",
                        "property=\"og:video:secure_url\"[^>]*content=\"([^\"]+)\"",
                        "name=\"twitter:player:stream\"[^>]*content=\"([^\"]+)\"",
                        "\"video_url\":\"([^\"]+\\.mp4[^\"]*)\"",
                        "\"playbackUrl\":\"([^\"]+)\"",
                        "\"contentUrl\":\"([^\"]+\\.mp4[^\"]*)\"",
                        "(https?:[^\"'\\s]+\\.mp4[^\"'\\s]*)");
                    if (found != null) found = found.replace("\\u0026", "&").replace("\\/", "/");
                } catch (Throwable e) { found = ""; }
                finderVid(id, found == null ? "" : found);
            } }).start();
        }
        private String firstMatch(String html, String... patterns) {
            for (String p : patterns) {
                try { java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(html); if (m.find()) return m.group(1); } catch (Throwable e) {}
            }
            return "";
        }
        private void finderVid(final int id, final String url) {
            final WebView w = t.wv;
            ui.post(new Runnable() { public void run() { try { w.evaluateJavascript("window.__vpVid&&window.__vpVid(" + id + "," + org.json.JSONObject.quote(url) + ")", null); } catch (Throwable e) {} } });
        }

        /** Active download tasks (for the in-app Downloads screen): [{id,name,done,total,state}]. */
        @JavascriptInterface public String dlTasks() {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) return "[]";
            return Downloader.tasksJson();
        }
        @JavascriptInterface public void dlPause(String id)  { if (t.url != null && t.url.startsWith("file:///android_asset/")) Downloader.pause(id); }
        @JavascriptInterface public void dlResume(String id) { if (t.url != null && t.url.startsWith("file:///android_asset/")) Downloader.resume(id, MainActivity.this); }
        @JavascriptInterface public void dlCancel(String id) { if (t.url != null && t.url.startsWith("file:///android_asset/")) Downloader.cancel(id); }

        /** Durable key/value store for the app's own asset pages (home site list, manga filters), kept in app prefs so updates never wipe it. */
        /** The page script calls this only when the user taps a real link/button — authorizes a cross-site navigation. */
        @JavascriptInterface public void realTap() { t.lastRealTap = System.currentTimeMillis(); }

        @JavascriptInterface public String prefGet(String key) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) return "";
            return sp.getString("kv_" + key, "");
        }
        @JavascriptInterface public void prefSet(String key, String val) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) return;
            sp.edit().putString("kv_" + key, val).apply();
        }

        @JavascriptInterface public String savedList(String key) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) return "[]";
            if (!"hist".equals(key) && !"bm".equals(key)) return "[]";
            return Store.get(sp, key).toString();
        }
        @JavascriptInterface public void savedOpen(String url) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || url == null) return;
            final String u = url;
            ui.post(new Runnable() { public void run() { if (cur != null) { cur.lastTap = System.currentTimeMillis(); cur.pageHost = null; cur.wv.loadUrl(u); } } });
        }
        @JavascriptInterface public void savedClear(String key) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) return;
            if ("hist".equals(key) || "bm".equals(key)) sp.edit().putString(key, "[]").apply();
        }
        @JavascriptInterface public String listDownloads() {
            if (t.url == null || !t.url.startsWith("file:///android_asset/")) return "[]";
            org.json.JSONArray arr = new org.json.JSONArray();
            try {
                String[] proj = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED };
                String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                android.database.Cursor cur2 = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, new String[]{ "%VideoPlus%" }, MediaStore.MediaColumns.DATE_ADDED + " DESC");
                if (cur2 != null) {
                    while (cur2.moveToNext()) {
                        long idv = cur2.getLong(0);
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("id", idv);
                        o.put("name", cur2.getString(1));
                        o.put("size", cur2.getLong(2));
                        o.put("mime", cur2.getString(3) == null ? "video/*" : cur2.getString(3));
                        o.put("uri", android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, idv).toString());
                        arr.put(o);
                    }
                    cur2.close();
                }
                // also scan the file folder for mp4 saved by the parallel downloader
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "VideoPlus");
                java.io.File[] fl = dir.listFiles();
                if (fl != null) for (java.io.File f : fl) {
                    if (!f.isFile()) continue;
                    boolean seen = false;
                    for (int i = 0; i < arr.length(); i++) if (f.getName().equals(arr.getJSONObject(i).optString("name"))) { seen = true; break; }
                    if (seen) continue;
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", f.getName()); o.put("size", f.length());
                    o.put("mime", f.getName().endsWith(".mp4") ? "video/mp4" : "video/*");
                    o.put("uri", androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f).toString());
                    arr.put(o);
                }
            } catch (Throwable e) { /* ignore */ }
            return arr.toString();
        }
        @JavascriptInterface public void playDownload(final String uri, final String mime) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || uri == null) return;
            ui.post(new Runnable() { public void run() {
                try {
                    Intent in = new Intent(MainActivity.this, PlayerActivity.class);
                    in.setData(Uri.parse(uri));
                    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(in);
                } catch (Throwable e) {
                    try { Intent x = new Intent(Intent.ACTION_VIEW); x.setDataAndType(Uri.parse(uri), mime == null ? "video/*" : mime); x.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(x, "Play with")); } catch (Throwable e2) { toast("No player available"); }
                }
            }});
        }
        @JavascriptInterface public void deleteDownload(String uri) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || uri == null) return;
            try { getContentResolver().delete(Uri.parse(uri), null, null); } catch (Throwable e) { try { new java.io.File(Uri.parse(uri).getPath()).delete(); } catch (Throwable x) {} }
        }
        @JavascriptInterface public String diag() { return (t.url != null && t.url.startsWith("file:///android_asset/")) ? Diag.report() : ""; }
        @JavascriptInterface public void diagClear() { if (t.url != null && t.url.startsWith("file:///android_asset/")) Diag.clear(); }
        @JavascriptInterface public void diagCopy(String txt) { if (t.url == null || !t.url.startsWith("file:///android_asset/")) return; try { android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", txt)); toast("Diagnostics copied - paste it to Claude"); } catch (Throwable e) { toast("Could not copy"); } }
        @JavascriptInterface public void webfetch(final int id, final String url) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || url == null) return;
            Diag.bump("assistant_searches");
            final WebView wv = t.wv;
            new Thread(new Runnable() {
                public void run() {
                    String out = "";
                    try { out = Assistant.fetch(url); } catch (Throwable e) { out = ""; }
                    final String fo = out == null ? "" : out;
                    ui.post(new Runnable() { public void run() { try { wv.evaluateJavascript("window.__vpWeb&&window.__vpWeb(" + id + "," + org.json.JSONObject.quote(fo) + ")", null); } catch (Exception e) { /* ignore */ } } });
                }
            }).start();
        }
        @JavascriptInterface public void frameText(final int id, final String b64) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || b64 == null) return;
            final WebView wv = t.wv;
            ocrPool.execute(new Runnable() {
                public void run() {
                    String out = "";
                    try { out = Ocr.readText(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)); } catch (Throwable e) { out = ""; }
                    final String fo = out == null ? "" : out;
                    ui.post(new Runnable() { public void run() { try { wv.evaluateJavascript("window.__vpTxt&&window.__vpTxt(" + id + "," + org.json.JSONObject.quote(fo) + ")", null); } catch (Exception e) { /* ignore */ } } });
                }
            });
        }
        @JavascriptInterface public void upload(final int id, final String b64) {
            if (t.url == null || !t.url.startsWith("file:///android_asset/") || b64 == null) return;
            final WebView wv = t.wv;
            new Thread(new Runnable() {
                public void run() {
                    String out = "";
                    try {
                        byte[] img = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        out = catbox(img);
                    } catch (Throwable e) { out = "ERR:" + e.getClass().getSimpleName(); }
                    final String fu = out == null ? "" : out;
                    ui.post(new Runnable() { public void run() { try { wv.evaluateJavascript("window.__vpUp&&window.__vpUp(" + id + "," + org.json.JSONObject.quote(fu) + ")", null); } catch (Exception e) { /* ignore */ } } });
                }
            }).start();
        }
        @JavascriptInterface public void dl(final String u) { if (u == null || u.isEmpty()) return; ui.post(new Runnable() { public void run() { Downloader.direct(MainActivity.this, u, t.wv.getSettings().getUserAgentString(), t.url, null); toast("Downloading PDF"); } }); }
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
        if (t == null || inPip || immersive || customView != null) { dlPanel.setVisibility(View.GONE); dlIcon.setVisibility(View.GONE); return; }
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
        else if (!q.contains(" ") && q.contains(".")) {
            /* local network addresses (cameras, routers, NAS) usually speak plain http, not https */
            String hostpart = q.split("[/:?#]", 2)[0];
            boolean local = hostpart.matches("(10|127)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
                || hostpart.matches("192\\.168\\.\\d{1,3}\\.\\d{1,3}")
                || hostpart.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}")
                || hostpart.endsWith(".local") || hostpart.equals("localhost")
                || hostpart.matches("[0-9.]+");
            url = (local ? "http://" : "https://") + q;
            if (local) cur.altScheme = "https://" + q;
        }
        else url = searchBase() + Uri.encode(q);
        final String hh = hostOf(url);
        if (hh != null) new Thread(new Runnable() { public void run() { try { java.net.InetAddress.getAllByName(hh); } catch (Exception e) { /* ignore */ } } }).start();
        cur.lastTap = System.currentTimeMillis(); cur.pageHost = null; // user-initiated: accept the destination
        cur.wv.loadUrl(upgrade(cur, url));
    }

    /** Registrable host (last two labels) so sub.site.com and site.com count as the same site for the redirect guard. */
    private String reg(String host) {
        if (host == null) return "";
        host = host.toLowerCase();
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    private void goBack() {
        if (cur != null && cur.wv.canGoBack()) cur.wv.goBack();
        else if (tabs.size() > 1) closeTab(cur);
    }

    private View menuItem(final PopupWindow pop, String icon, String label, final int id) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        android.util.TypedValue tvv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvv, true);
        row.setBackgroundResource(tvv.resourceId);
        TextView ic = new TextView(this);
        ic.setText(icon); ic.setTextSize(16); ic.setGravity(Gravity.CENTER); ic.setTextColor(0xFFE2B33C);
        TextView tx = new TextView(this);
        tx.setText(label); tx.setTextColor(id == 17 ? 0xFFFF8A80 : Color.WHITE); tx.setTextSize(15); tx.setSingleLine(true);
        row.addView(ic, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams txp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        txp.leftMargin = dp(12);
        row.addView(tx, txp);
        if (id != 0) row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pop.dismiss(); menuAction(id); } });
        return row;
    }

    /** Round quick-action icon button for the menu's top row (Chrome-style). */
    private TextView quickIcon(final PopupWindow pop, String glyph, int size, boolean enabled, final Runnable act) {
        TextView b = new TextView(this);
        b.setText(glyph); b.setTextSize(size); b.setGravity(Gravity.CENTER);
        b.setTextColor(enabled ? 0xFFE2B33C : 0xFF55555E);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0xFF26262F);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(dp(10), 0, dp(10), 0);
        b.setLayoutParams(lp);
        if (enabled) b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pop.dismiss(); act.run(); } });
        return b;
    }

    /** Address-bar autocomplete from history + bookmarks; tapping a suggestion loads it. */
    private void setupUrlSuggestions() {
        final android.widget.AutoCompleteTextView ac = (android.widget.AutoCompleteTextView) urlBox;
        final java.util.List<String[]> pool = new java.util.ArrayList<>(); // [display, url]
        final android.widget.ArrayAdapter<String[]> ad = new android.widget.ArrayAdapter<String[]>(this, 0) {
            android.widget.Filter f;
            @Override public int getCount() { return shown.size(); }
            @Override public String[] getItem(int i) { return shown.get(i); }
            java.util.List<String[]> shown = new java.util.ArrayList<>();
            @Override public View getView(int i, View cv, ViewGroup p) {
                LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(9), dp(14), dp(9)); row.setBackgroundColor(0xFF1C1C24);
                String[] it = shown.get(i);
                TextView a = new TextView(MainActivity.this); a.setText(it[0]); a.setTextColor(Color.WHITE); a.setTextSize(14); a.setSingleLine(true); a.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView b = new TextView(MainActivity.this); b.setText(it[1]); b.setTextColor(0xFF8A8A96); b.setTextSize(12); b.setSingleLine(true); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(a); row.addView(b); return row;
            }
            @Override public android.widget.Filter getFilter() {
                if (f == null) f = new android.widget.Filter() {
                    @Override protected FilterResults performFiltering(CharSequence cs) {
                        java.util.List<String[]> out = new java.util.ArrayList<>();
                        String q = cs == null ? "" : cs.toString().trim().toLowerCase();
                        if (!q.isEmpty()) {
                            java.util.Set<String> seen = new java.util.HashSet<>();
                            for (String[] e : pool) {
                                if (out.size() >= 8) break;
                                if ((e[0].toLowerCase().contains(q) || e[1].toLowerCase().contains(q)) && seen.add(e[1])) out.add(e);
                            }
                            // offer a web-search suggestion as the last row
                            out.add(new String[]{"Search “" + cs + "”", "__search__:" + cs});
                        }
                        FilterResults r = new FilterResults(); r.values = out; r.count = out.size(); return r;
                    }
                    @Override protected void publishResults(CharSequence cs, FilterResults r) {
                        shown = (java.util.List<String[]>) (r.values == null ? new java.util.ArrayList<>() : r.values);
                        notifyDataSetChanged();
                    }
                };
                return f;
            }
        };
        ac.setThreshold(1);
        ac.setAdapter(ad);
        ac.setDropDownBackgroundDrawable(new ColorDrawable(0xFF1C1C24));
        ac.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                String[] it = ad.getItem(pos);
                String u = it[1];
                if (u.startsWith("__search__:")) go(u.substring(11)); else go(u);
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
                urlBox.clearFocus();
            }
        });
        // (re)load the suggestion pool from history + bookmarks whenever the box gains focus
        ac.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (!has) return;
                pool.clear();
                try {
                    org.json.JSONArray bm = Store.get(sp, "bm");
                    for (int i = 0; i < bm.length(); i++) { org.json.JSONObject o = bm.optJSONObject(i); if (o != null) pool.add(new String[]{"★ " + o.optString("t", o.optString("u")), o.optString("u")}); }
                    org.json.JSONArray h = Store.get(sp, "hist");
                    for (int i = h.length() - 1; i >= 0 && pool.size() < 400; i--) { org.json.JSONObject o = h.optJSONObject(i); if (o != null) pool.add(new String[]{o.optString("t", o.optString("u")), o.optString("u")}); }
                } catch (Throwable e) { /* ignore */ }
            }
        });
    }

    /** Address-bar lock indicator: 🔒 for https, ⚠ for plain http, 🏠 for the home/file pages. */
    private void setLock(String u) {
        if (lockIcon == null) return;
        if (u == null || u.startsWith("file:")) { lockIcon.setText("🏠"); lockIcon.setTextColor(0xFF9AA0AA); }
        else if (u.startsWith("https:")) { lockIcon.setText("🔒"); lockIcon.setTextColor(0xFF7FbF7F); }
        else if (u.startsWith("http:")) { lockIcon.setText("⚠"); lockIcon.setTextColor(0xFFE2A33C); }
        else { lockIcon.setText("🌐"); lockIcon.setTextColor(0xFF9AA0AA); }
    }

    /** Warn before opening a flagged phishing/scam host; let the user back out or proceed. */
    private void warnDanger(final String url) {
        String host = Passwords.host(url);
        new AlertDialog.Builder(this)
            .setTitle("⚠ Dangerous site blocked")
            .setMessage("“" + host + "” looks like a phishing or scam site. Opening it could steal your passwords or money.\n\nGo back to safety?")
            .setPositiveButton("Back to safety", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { if (cur != null && cur.wv.canGoBack()) cur.wv.goBack(); else if (cur != null) cur.wv.loadUrl(HOME); } })
            .setNegativeButton("Open anyway (unsafe)", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { if (cur != null) { cur.lastTap = System.currentTimeMillis(); String old = sp.getString("safebrowse_skip", ""); sp.edit().putString("safebrowse_skip", url).apply(); cur.wv.loadUrl(url); } } })
            .show();
    }

    /** Saved-logins screen: list sites, delete entries, and run a safe breach check. */
    private void showSavedPasswords() {
        final org.json.JSONArray list = Passwords.list(MainActivity.this);
        if (list.length() == 0) { new AlertDialog.Builder(this).setTitle("Saved passwords").setMessage("No saved logins yet. When you sign in to a site, the app will offer to save it (encrypted by your phone's secure chip).").setPositiveButton("OK", null).show(); return; }
        final String[] labels = new String[list.length()];
        for (int i = 0; i < list.length(); i++) { org.json.JSONObject o = list.optJSONObject(i); labels[i] = o.optString("h") + (o.optString("u").isEmpty() ? "" : "  (" + o.optString("u") + ")"); }
        new AlertDialog.Builder(this).setTitle("Saved passwords (" + list.length() + ")")
            .setItems(labels, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, final int i) {
                    final org.json.JSONObject o = list.optJSONObject(i);
                    new AlertDialog.Builder(MainActivity.this).setTitle(o.optString("h"))
                        .setItems(new String[]{"Delete this login", "Check if breached"}, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dd, int w) {
                                if (w == 0) { Passwords.delete(MainActivity.this, o.optString("h"), o.optString("u")); toast("Login deleted"); }
                                else breachCheck(o.optString("h"), o.optString("u"));
                            }
                        }).show();
                }
            })
            .setNeutralButton("Check all for breaches", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { breachCheckAll(); }
            })
            .setPositiveButton("Close", null).show();
    }

    /** HaveIBeenPwned range API: only the first 5 chars of the password's SHA-1 are sent; the password never leaves the phone. */
    private void breachCheck(final String host, final String user) {
        toast("Checking… (your password is never sent)");
        new Thread(new Runnable() { public void run() {
            final int n = breachCount(host, user);
            ui.post(new Runnable() { public void run() {
                if (n < 0) toast("Couldn't check right now");
                else if (n == 0) new AlertDialog.Builder(MainActivity.this).setTitle("✅ Safe").setMessage("This password for " + host + " was not found in known breaches.").setPositiveButton("OK", null).show();
                else new AlertDialog.Builder(MainActivity.this).setTitle("⚠ Breached").setMessage("This password appeared in " + n + " known data breaches. Change it on " + host + " soon.").setPositiveButton("OK", null).show();
            } });
        } }).start();
    }
    private void breachCheckAll() {
        toast("Checking all logins…");
        new Thread(new Runnable() { public void run() {
            org.json.JSONArray list = Passwords.list(MainActivity.this);
            final java.util.List<String> bad = new java.util.ArrayList<>();
            for (int i = 0; i < list.length(); i++) { org.json.JSONObject o = list.optJSONObject(i); if (breachCount(o.optString("h"), o.optString("u")) > 0) bad.add(o.optString("h")); }
            ui.post(new Runnable() { public void run() {
                if (bad.isEmpty()) new AlertDialog.Builder(MainActivity.this).setTitle("✅ All clear").setMessage("None of your saved passwords were found in known breaches.").setPositiveButton("OK", null).show();
                else new AlertDialog.Builder(MainActivity.this).setTitle("⚠ Breached passwords").setMessage("Change your password on:\n\n" + android.text.TextUtils.join("\n", bad)).setPositiveButton("OK", null).show();
            } });
        } }).start();
    }
    /** Returns breach count for one saved credential, or -1 on error. Uses k-anonymity: sends only a 5-char hash prefix. */
    private int breachCount(String host, String user) {
        try {
            org.json.JSONArray creds = Passwords.forUrl(MainActivity.this, "https://" + host + "/");
            String pass = null;
            for (int i = 0; i < creds.length(); i++) { org.json.JSONObject o = creds.optJSONObject(i); if (user.equals(o.optString("u"))) { pass = o.optString("p"); break; } }
            if (pass == null || pass.isEmpty()) return -1;
            String sha = Security.sha1Upper(pass); if (sha.length() < 5) return -1;
            String prefix = sha.substring(0, 5), suffix = sha.substring(5);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.pwnedpasswords.com/range/" + prefix).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "VideoPlusBrowser");
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
            String line; int count = 0;
            while ((line = br.readLine()) != null) { int co = line.indexOf(':'); if (co > 0 && line.substring(0, co).equalsIgnoreCase(suffix)) { try { count = Integer.parseInt(line.substring(co + 1).trim()); } catch (Exception e) {} break; } }
            br.close();
            return count;
        } catch (Throwable e) { return -1; }
    }

    /** Biometric / device-credential lock shown over the app when app-lock is enabled. */
    private boolean unlocked = false;
    private void appLockGate() {
        if (unlocked || !sp.getBoolean("applock", false)) { unlocked = true; return; }
        try {
            // Framework BiometricPrompt (API 28+) works with a plain Activity; it falls back to device PIN/pattern.
            android.hardware.biometrics.BiometricPrompt.Builder b = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Unlock Video Plus Browser")
                .setSubtitle("Verify it's you to continue");
            if (android.os.Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            else b.setDeviceCredentialAllowed(true);
            android.hardware.biometrics.BiometricPrompt bp = b.build();
            bp.authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) { unlocked = true; }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        // User-cancel (10), negative-button (13) or system-cancel (5): close the app rather than leaving it open unlocked.
                        if (code == 10 || code == 13 || code == 5) finish();
                    }
                });
        } catch (Throwable e) { unlocked = true; }
    }

    private void showMenu(View anchor) {
        boolean bm = cur != null && Store.isBookmarked(sp, cur.url);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF51E1E26);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        box.setPadding(dp(6), dp(8), dp(6), dp(8));
        sv.addView(box);

        int ph = getResources().getDisplayMetrics().heightPixels;
        int pw = getResources().getDisplayMetrics().widthPixels;
        final PopupWindow pop = new PopupWindow(sv, (int) (pw * 0.80f), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pop.setElevation(dp(14));
        pop.setAnimationStyle(android.R.style.Animation_Dialog);

        /* Chrome-style quick-action icon row across the top: back, forward, bookmark, share */
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER);
        quick.setPadding(dp(6), dp(6), dp(6), dp(10));
        quick.addView(quickIcon(pop, "\u2039", 30, cur != null && cur.wv.canGoBack(), new Runnable() { public void run() { goBack(); } }));
        quick.addView(quickIcon(pop, "\u203A", 30, cur != null && cur.wv.canGoForward(), new Runnable() { public void run() { if (cur != null && cur.wv.canGoForward()) cur.wv.goForward(); } }));
        quick.addView(quickIcon(pop, bm ? "\u2605" : "\u2606", 24, true, new Runnable() { public void run() { if (cur != null && cur.url != null && cur.url.startsWith("http")) toast(Store.toggleBookmark(sp, cur.title, cur.url) ? "Bookmark added" : "Bookmark removed"); } }));
        quick.addView(quickIcon(pop, "\u21AA", 22, true, new Runnable() { public void run() { if (cur != null) share(cur.url); } }));
        box.addView(quick);
        View qdiv = new View(this); qdiv.setBackgroundColor(0x1AFFFFFF);
        box.addView(qdiv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        /* most-used, always visible */
        box.addView(menuItem(pop, "\u2728", "Assistant", 25));
        box.addView(menuItem(pop, "\uFF0B", "New tab", 1));
        box.addView(menuItem(pop, "\u27F3", "Reload", 3));

        /* collapsible groups */
        boolean desktop = cur != null && (cur.uaMode >= 0 ? cur.uaMode : uaMode) == 1;
        addGroup(pop, box, "\uD83D\uDCC4", "This page", new Object[][]{
            {"\uD83D\uDD12", "New private tab", 2}, {"\u21AA", "Share", 14}, {"\uD83D\uDD17", "Copy link", 13},
            {"\uD83D\uDD0D", "Find in page", 5}, {"\uD83D\uDCD6", "Reading mode", 19}, {"\uD83C\uDF10", "Translate page", 11},
            {"\uD83D\uDDA5", desktop ? "Desktop site: ON" : "Desktop site", 30}, {"\u2795", "Add to Home screen", 31}
        });
        addGroup(pop, box, "\uD83D\uDCDA", "Library", new Object[][]{
            {"\uD83D\uDCD1", "Bookmarks", 7}, {"\uD83D\uDD53", "History", 8}, {"\u2B07", "Downloads & videos", 12}
        });
        addGroup(pop, box, "\u25B6", "Video & tools", new Object[][]{
            {"\u25B6", "Video Plus panel", 10}, {"\u25A2", "Float video (PiP)", 9}, {"\uD83D\uDD0E", "Find full video", 23},
            {"\uD83D\uDCF9", "Camera live view", 24}, {"\uD83D\uDDBC", picTr ? "Picture translate: ON" : "Picture translate", 22},
            {"\u25B6", autoPrev ? "Video previews: ON" : "Video previews", 21},
            {"\uD83C\uDF10", autoTr.isEmpty() ? "Auto-translate: off" : "Auto-translate: " + autoTr.toUpperCase(), 20},
            {saver ? "\uD83D\uDCC9" : "\uD83D\uDCCA", saver ? "Data saver: ON" : "Data saver", 18}
        });

        /* bottom, always visible */
        View div = new View(this); div.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)); dl.topMargin = dp(4); dl.bottomMargin = dp(4);
        box.addView(div, dl);
        box.addView(menuItem(pop, "\u2699", "Settings", 15));
        box.addView(menuItem(pop, "\uD83E\uDE7A", "Diagnostics", 26));
        box.addView(menuItem(pop, "\u2715", "Exit", 17));

        pop.showAtLocation(root, Gravity.TOP | Gravity.END, dp(4), dp(52));
    }

    private void addGroup(final PopupWindow pop, final LinearLayout box, String icon, String title, final Object[][] items) {
        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(13), dp(16), dp(13));
        android.util.TypedValue tvv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvv, true);
        header.setBackgroundResource(tvv.resourceId);
        TextView ic = new TextView(this); ic.setText(icon); ic.setTextSize(16); ic.setGravity(Gravity.CENTER); ic.setTextColor(0xFFE2B33C);
        TextView tx = new TextView(this); tx.setText(title); tx.setTextColor(0xFFCFCFE0); tx.setTextSize(15); tx.setSingleLine(true);
        final TextView arrow = new TextView(this); arrow.setText("\u25BE"); arrow.setTextColor(0xFF8A8C93); arrow.setTextSize(13);
        header.addView(ic, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams txp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); txp.leftMargin = dp(12);
        header.addView(tx, txp);
        header.addView(arrow);
        box.addView(header);

        final LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.VERTICAL);
        sub.setVisibility(View.GONE);
        for (Object[] it : items) {
            View row = menuItem(pop, (String) it[0], (String) it[1], (Integer) it[2]);
            row.setPadding(dp(30), dp(12), dp(16), dp(12));
            sub.addView(row);
        }
        box.addView(sub);
        header.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = sub.getVisibility() != View.VISIBLE;
                sub.setVisibility(show ? View.VISIBLE : View.GONE);
                arrow.setText(show ? "\u25B4" : "\u25BE");
            }
        });
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
            case 7: if (cur != null) cur.wv.loadUrl("file:///android_asset/history.html#bm"); break;
            case 8: if (cur != null) cur.wv.loadUrl("file:///android_asset/history.html#hist"); break;
            case 9: if (playing) enterPip(); else toast("Start a video first"); break;
            case 10:
                cur.wv.evaluateJavascript("(function(){var r=document.querySelector('#vplus-root');if(r){r.style.display='block';var f=r.querySelector('#vp-fab');if(f)f.click();}})()", null);
                break;
            case 11:
                if (cur.url.startsWith("http")) cur.wv.loadUrl("https://translate.google.com/translate?sl=auto&tl=en&u=" + Uri.encode(cur.url));
                break;
            case 12:
                if (cur != null) cur.wv.loadUrl("file:///android_asset/library.html");
                break;
            case 13: copy(cur.url); break;
            case 14: share(cur.url); break;
            case 15: showSettings(); break;
            case 16: Updater.check(MainActivity.this, true); break;
            case 17: finishAndRemoveTask(); break;
            case 19:
                if (cur.url.startsWith("http")) cur.wv.evaluateJavascript(readerJs + ";window.__vpReader.auto='" + autoTr + "';window.__vpReader.toggle()", null);
                break;
            case 23:
                if (cur != null) cur.wv.loadUrl("file:///android_asset/finder.html");
                break;
            case 25:
                if (cur != null) cur.wv.loadUrl("file:///android_asset/assistant.html");
                break;
            case 26:
                if (cur != null) cur.wv.loadUrl("file:///android_asset/diag.html");
                break;
            case 24:
                if (cur != null) cur.wv.loadUrl("file:///android_asset/camera.html");
                break;
            case 22:
                picTr = !picTr;
                sp.edit().putBoolean("pictr", picTr).apply();
                if (cur != null) cur.wv.evaluateJavascript(picTr ? ocrJs + ";window.__vpPic&&window.__vpPic.start()" : "window.__vpPic&&window.__vpPic.stop()", null);
                toast(picTr ? "Picture translate ON: words inside comic pages are shown in English" : "Picture translate off");
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
            case 30: { // Desktop site toggle (per current tab)
                if (cur != null) {
                    int now = cur.uaMode >= 0 ? cur.uaMode : uaMode;
                    cur.uaMode = (now == 1) ? 0 : 1; // toggle Desktop Chrome <-> Default mobile
                    applyUa(cur); cur.wv.reload();
                    toast(cur.uaMode == 1 ? "Desktop site ON" : "Mobile site");
                }
                break;
            }
            case 31: addToHomeScreen(); break;
            case 32: setAsDefaultBrowser(); break;
        }
    }

    /** Pin the current page to the launcher as a shortcut (like Chrome's "Add to Home screen"). */
    private void addToHomeScreen() {
        if (cur == null || cur.url == null || !cur.url.startsWith("http")) { toast("Open a web page first"); return; }
        try {
            String title = (cur.title == null || cur.title.isEmpty()) ? Passwords.host(cur.url) : cur.title;
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null && sm.isRequestPinShortcutSupported()) {
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(cur.url)).setPackage(getPackageName());
                android.content.pm.ShortcutInfo si = new android.content.pm.ShortcutInfo.Builder(this, "site_" + cur.url.hashCode())
                    .setShortLabel(title.length() > 20 ? title.substring(0, 20) : title)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(open).build();
                sm.requestPinShortcut(si, null);
            } else toast("Your launcher doesn't support adding shortcuts");
        } catch (Throwable e) { toast("Couldn't add shortcut"); }
    }

    /** Open the system prompt to make this the default browser. */
    private void setAsDefaultBrowser() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) { toast("Already your default browser"); return; }
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 7788);
                    return;
                }
            }
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (Throwable e) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); toast("Open Apps → Default apps → Browser"); } catch (Throwable x) { toast("Open Settings → Default apps to set the browser"); }
        }
    }

    // ---------------- picture-in-picture ----------------
    private void enterPip() {
        try {
            if (cur != null && customView == null) cur.wv.evaluateJavascript("window.__vpPip&&window.__vpPip(true)", null);
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
        } catch (Throwable e) { toast("Picture-in-picture not available"); }
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (playing && !isInPictureInPictureMode()) enterPip();
    }

    private void fitZoom(final Tab ct, final int tries) {
        if (desktop || ct == null) return;
        try {
            ct.wv.evaluateJavascript("window.innerWidth", new android.webkit.ValueCallback<String>() {
                public void onReceiveValue(String sv) {
                    try {
                        float iw = Float.parseFloat(sv.replace("\"", ""));
                        float vw = ct.wv.getWidth() / getResources().getDisplayMetrics().density;
                        if (vw > 100 && iw > 100 && iw < vw * 0.88f) {
                            ct.wv.zoomBy(Math.max(0.3f, iw / vw));
                        }
                    } catch (Throwable e) { /* ignore */ }
                }
            });
        } catch (Throwable e) { /* ignore */ }
    }

    private void fixZoom(final Tab ct, final float want, final int n) {
        try {
            float now = ct.wv.getScale();
            if (now > 0f && Math.abs(now - want) / want > 0.03f) {
                ct.wv.zoomBy(Math.max(0.01f, Math.min(100f, want / now)));
                if (n < 3) ui.postDelayed(new Runnable() { public void run() { fixZoom(ct, want, n + 1); } }, 400);
            }
        } catch (Throwable e) { /* ignore */ }
    }

    @Override public void onPictureInPictureModeChanged(boolean in, Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        inPip = in;
        bar.setVisibility(in || immersive ? View.GONE : View.VISIBLE);
        navBar.setVisibility(in || immersive ? View.GONE : View.VISIBLE);
        pb.setVisibility(View.GONE);
        refreshBadge();
        if (!in && !immersive) showChrome();
        if (cur != null && customView == null) cur.wv.evaluateJavascript("window.__vpPip&&window.__vpPip(" + in + ")", null);
        if (!in && cur != null && lastScale > 0f) {
            final Tab ct = cur;
            final float want = lastScale;
            ui.postDelayed(new Runnable() { public void run() { fixZoom(ct, want, 0); fitZoom(ct, 0); } }, 450);
        }
    }

    // ---------------- lifecycle ----------------
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Uri d = i.getData();
        if (d != null) newTab(d.toString());
    }

    @Override public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (immersive && cur != null) { cur.wv.evaluateJavascript("(function(){if(window.__vpReader&&window.__vpReader.on){window.__vpReader.toggle();return 1}return 0})()", new android.webkit.ValueCallback<String>() { public void onReceiveValue(String r) { setReadMode(false); if (!"1".equals(r) && cur != null && cur.wv.canGoBack()) cur.wv.goBack(); } }); return; }
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
        // re-lock when the app goes to background (but not when a Custom Tab sign-in is in progress)
        if (sp.getBoolean("applock", false) && System.currentTimeMillis() - lastCustomTab > 5000) unlocked = false;
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            if (sensors == null) sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            Sensor ls = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (ls != null) sensors.registerListener(luxListener, ls, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) { /* no light sensor: the Auto picture uses the video alone */ }
        setBg(false);
        // Returning from a Custom Tab sign-in: sync cookies and reload so the now-authenticated session shows.
        if (lastCustomTab > 0 && System.currentTimeMillis() - lastCustomTab < 5 * 60 * 1000) {
            lastCustomTab = 0;
            try { android.webkit.CookieManager.getInstance().flush(); } catch (Throwable e) {}
            if (cur != null && cur.url != null && cur.url.startsWith("http")) { try { cur.wv.reload(); } catch (Throwable e) {} }
        }
        Updater.resume(this);
        Updater.check(this, false);
        appLockGate();
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
