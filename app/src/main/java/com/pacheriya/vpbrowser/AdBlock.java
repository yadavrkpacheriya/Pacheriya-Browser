package com.pacheriya.vpbrowser;

import android.net.Uri;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

final class AdBlock {
    private static final String[] HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "google-analytics.com", "2mdn.net", "moatads.com", "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "rubiconproject.com",
        "pubmatic.com", "openx.net", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com",
        "exoclick.com", "exosrv.com", "exdynsrv.com", "juicyads.com", "trafficjunky.com", "trafficjunky.net", "popads.net", "popcash.net",
        "propellerads.com", "onclickads.net", "adcash.com", "clickadu.com", "hilltopads.net", "a-ads.com", "adsterra.com", "mgid.com",
        "revcontent.com", "zedo.com", "smaato.net", "inmobi.com", "applovin.com", "unityads.unity3d.com", "mopub.com", "chartboost.com",
        "vungle.com", "adcolony.com", "ironsrc.com", "supersonicads.com", "adform.net", "casalemedia.com", "contextweb.com", "serving-sys.com",
        "advertising.com", "adtechus.com", "yieldmo.com", "sharethrough.com", "teads.tv", "smartadserver.com", "bidswitch.net", "adsafeprotected.com",
        "doubleverify.com", "tapad.com", "bluekai.com", "demdex.net", "krxd.net", "exelator.com", "mathtag.com", "turn.com", "everesttech.net",
        "adroll.com", "perfectaudience.com", "bidr.io", "33across.com", "lijit.com", "sovrn.com", "indexww.com", "conversantmedia.com",
        "tribalfusion.com", "adblade.com", "clicksor.com", "bidvertiser.com", "plugrush.com", "tsyndicate.com", "realsrv.com", "magsrv.com",
        "ad-maven.com", "admaven.com", "adspyglass.com", "ero-advertising.com", "eroadvertising.com", "adxpansion.com",
        "yllix.com", "monetag.com", "pushame.com", "richpush.co", "rtbsystem.com", "mc.yandex.ru", "adriver.ru", "hotjar.com", "mouseflow.com",
        "fullstory.com", "crazyegg.com", "luckyorange.com", "adtrue.com", "ad-score.com", "adskeeper.com", "adnium.com", "ads.yahoo.com",
        "ads.twitter.com", "analytics.twitter.com", "ads.linkedin.com", "adsymptotic.com", "adthrive.com", "tremorhub.com",
        "spotxchange.com", "springserve.com", "stickyadstv.com", "videohub.tv", "innovid.com", "flashtalking.com", "jwpltx.com", "imrworldwide.com",
        "outbrainimg.com", "tynt.com", "disqusads.com", "ads.pubmatic.com", "advertserve.com", "buysellads.com", "carbonads.net", "adbrite.com",
        "clickaine.com", "clk-sec.com", "pushnami.com", "notix.io", "evadav.com", "pushprofit.net"
    };
    private static final String[] PATHS = { "/pagead/", "/api/stats/ads", "/ptracking?" };
    private static final HashSet<String> SET = new HashSet<>(Arrays.asList(HOSTS));

    static boolean blocked(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            while (h.indexOf('.') >= 0) {
                if (SET.contains(h)) return true;
                h = h.substring(h.indexOf('.') + 1);
            }
            String low = url.toLowerCase(Locale.ROOT);
            for (String p : PATHS) if (low.contains(p)) return true;
        } catch (Exception e) { /* ignore */ }
        return false;
    }
}
