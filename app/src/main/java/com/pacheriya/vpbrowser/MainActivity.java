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
import android.graphics.Bitmap;
import android.graphics.Color;
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
    }

    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private Tab cur;
    private FrameLayout root, content, fsHost;
    private LinearLayout main, bar;
    private EditText urlBox;
    private TextView tabBtn, dlBtn;
    private ProgressBar pb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long lastPlayTs = 0;
    private boolean playing = false, inPip = false, desktop = false, adblock = true, scripts = true;
    private String injected = "", defaultUa = "", desktopUa = "";
    private boolean dark = false, httpsUp = true;
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
        httpsUp = sp.getBoolean("https", true);
        textZoom = sp.getInt("zoom", 100);
        engine = sp.getString("engine", "google");

        String vp = sp.getString("vpjs", null);
        if (vp == null || vp.length() < 10000) vp = asset("video-plus.user.js");
        String css = "(function(){var c=" + jq(asset("vp-cosmetic.css")) + ";function a(){var p=document.head||document.documentElement;if(!p){setTimeout(a,15);return}var s=document.createElement('style');s.textContent=c;p.appendChild(s)}a()})();";
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

        content = new FrameLayout(this);
        main.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(findBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        main.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dlBtn = new TextView(this);
        dlBtn.setTextColor(Color.WHITE);
        dlBtn.setTextSize(15);
        dlBtn.setGravity(Gravity.CENTER);
        dlBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFF6C4DFF);
        pill.setCornerRadius(dp(24));
        dlBtn.setBackground(pill);
        dlBtn.setVisibility(View.GONE);
        dlBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMediaList(); }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(16), dp(90));
        root.addView(dlBtn, lp);

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
        applyDark(s);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
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
                if (adblock && AdBlock.blocked(u)) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
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
                toast("Cannot open page: " + e.getDescription());
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

        @JavascriptInterface public void playing(boolean p) { if (p) lastPlayTs = SystemClock.uptimeMillis(); }
    }

    private void addMedia(Tab t, String u) {
        if (u.startsWith("blob:") || t.media.contains(u)) return;
        t.media.add(u);
        if (t.media.size() > 40) t.media.remove(0);
        if (t == cur) refreshBadge();
    }

    private void refreshBadge() {
        int n = cur == null ? 0 : cur.media.size();
        dlBtn.setText("\u2B07  " + n);
        dlBtn.setVisibility(n > 0 && !inPip && customView == null ? View.VISIBLE : View.GONE);
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
        if (q.isEmpty() || cur == null) return;
        String url;
        if (q.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = q;
        else if (q.startsWith("magnet:")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(q))); } catch (Exception e) { toast("No torrent app installed"); }
            return;
        }
        else if (!q.contains(" ") && q.contains(".")) url = "https://" + q;
        else url = searchBase() + Uri.encode(q);
        cur.wv.loadUrl(upgrade(cur, url));
    }

    private void goBack() {
        if (cur != null && cur.wv.canGoBack()) cur.wv.goBack();
        else if (tabs.size() > 1) closeTab(cur);
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        boolean bm = cur != null && Store.isBookmarked(sp, cur.url);
        m.getMenu().add(0, 1, 0, "New tab");
        m.getMenu().add(0, 2, 1, "New private tab");
        m.getMenu().add(0, 3, 2, "Reload");
        m.getMenu().add(0, 4, 3, "Forward");
        m.getMenu().add(0, 5, 4, "Find in page");
        m.getMenu().add(0, 6, 5, bm ? "Remove bookmark" : "Add bookmark");
        m.getMenu().add(0, 7, 6, "Bookmarks");
        m.getMenu().add(0, 8, 7, "History");
        m.getMenu().add(0, 9, 8, "Float video (picture-in-picture)");
        m.getMenu().add(0, 10, 9, "Video Plus panel");
        m.getMenu().add(0, 11, 10, "Translate page to English");
        m.getMenu().add(0, 12, 11, "Downloaded files");
        m.getMenu().add(0, 13, 12, "Copy link");
        m.getMenu().add(0, 14, 13, "Share link");
        m.getMenu().add(0, 15, 14, "Settings");
        m.getMenu().add(0, 16, 15, "Check for app update");
        m.getMenu().add(0, 17, 16, "Exit");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                switch (it.getItemId()) {
                    case 1: newTab(HOME); break;
                    case 2: newTab(HOME, true); toast("Private tab: no history saved"); break;
                    case 3: cur.wv.reload(); break;
                    case 4: if (cur.wv.canGoForward()) cur.wv.goForward(); break;
                    case 5: showFind(); break;
                    case 6: {
                        if (cur.url.startsWith("http")) toast(Store.toggleBookmark(sp, cur.title, cur.url) ? "Bookmark added" : "Bookmark removed");
                        break;
                    }
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
                }
                return true;
            }
        });
        m.show();
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
        CookieManager.getInstance().flush();
        JSONArray saved = new JSONArray();
        for (Tab t : tabs) if (!t.incognito && t.url.startsWith("http") && saved.length() < 8) saved.put(t.url);
        sp.edit().putString("tabs", saved.toString()).apply();
        /* the page is deliberately NOT paused, so audio keeps playing in the background */
        setBg(true);
    }

    @Override protected void onResume() {
        super.onResume();
        setBg(false);
    }

    private void setBg(boolean on) {
        for (Tab t : tabs) { try { t.wv.evaluateJavascript("window.__vpBg=" + on, null); } catch (Exception e) { /* ignore */ } }
    }

    @Override protected void onDestroy() {
        if (playing) { playing = false; BgService.release(this); }
        for (Tab t : tabs) { try { t.wv.destroy(); } catch (Exception e) { /* ignore */ } }
        super.onDestroy();
    }
}
