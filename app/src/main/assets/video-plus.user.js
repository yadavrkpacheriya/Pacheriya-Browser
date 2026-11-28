// ==UserScript==
// @name         Video Plus
// @namespace    pacheriya
// @version      3.6
// @description  Built-in ad and popup blocker, theater look, picture enhancer, swipe gestures, speed, PiP and subtitles for videos on any website
// @match        *://*/*
// @run-at       document-start
// @updateURL    https://video-player-4wm.pages.dev/video-plus.user.js
// @downloadURL  https://video-player-4wm.pages.dev/video-plus.user.js
// @grant        none
// ==/UserScript==
(function () {
  'use strict';
  if (window.__vplus) return;
  window.__vplus = 1;

  var S = { auto: true, sharp: 0, ct: 1, sat: 1, warm: 0, br: 1, gest: true, theater: false, autofs: true, autorot: true, adblock: true, boost: true, rate: 1, gain: 1, hdx: false, shp: 0.7, model: 'base', asub: true, aspect: 0 };
  var hdMap = new WeakMap();
  try { Object.assign(S, JSON.parse(localStorage.getItem('vplus2') || '{}')); } catch (e) {}
  var save = function () { try { localStorage.setItem('vplus2', JSON.stringify(S)); } catch (e) {} };
  /* ---------- built-in ad and popup blocker (light; ON by default) ---------- */
  if (S.adblock !== false) {
    var HOSTS = ['doubleclick.net', 'googlesyndication.com', 'googleadservices.com', 'exoclick.com', 'exosrv.com', 'juicyads.com', 'trafficjunky.net', 'trafficstars.com', 'adsterra.com', 'popads.net', 'propellerads.com', 'popcash.net', 'clickadu.com', 'hilltopads.net', 'adnxs.com', 'taboola.com', 'outbrain.com', 'mgid.com'];
    var bad = function (u) {
      try { var h = new URL(u, location.href).hostname; return HOSTS.some(function (d) { return h === d || h.slice(-d.length - 1) === '.' + d; }); } catch (e) { return false; }
    };
    var SKETCHY = /\.(cfd|sbs|click|top|lol|cyou|rest|quest|bond|icu|monster|makeup|skin|hair|boats|autos|mom|beauty|fun|buzz)$/i;
    function sketchy(u) { try { return SKETCHY.test(new URL(u, location.href).hostname); } catch (e) { return false; } }
    var _open = window.open;
    window.open = function (u) {
      try {
        // an empty / about:blank popup the page fills in itself (sign-in flows): only right after a genuine tap
        if (!u || /^about:blank/i.test(u)) return (Date.now() - lastReal < 1500) ? _open.apply(this, arguments) : null;
        if (bad(u)) return null;
        if (sameSite(u)) return _open.apply(this, arguments);
        // another site: only right after a genuine tap on a real link/button, and never to a throwaway ad domain
        if (!sketchy(u) && Date.now() - lastReal < 1500) return _open.apply(this, arguments);
      } catch (e) {}
      return null;
    };
    // block target=_blank / cross-site anchor taps that open pop-under ad tabs
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (!a || !a.href) return;
      try {
        var h = new URL(a.href, location.href).hostname;
        var off = h !== location.hostname && h.slice(-location.hostname.length - 1) !== '.' + location.hostname;
        // Block only real ad links: known ad hosts, or a NEW-TAB jump to a throwaway domain on another site.
        // Ordinary links (including new-tab links to other sites, and every link inside a .top/.fun site) must keep working.
        if (bad(a.href) || (off && a.target === '_blank' && sketchy(a.href))) { e.preventDefault(); e.stopPropagation(); }
      } catch (x) {}
    }, true);
    [HTMLScriptElement, HTMLIFrameElement, HTMLImageElement].forEach(function (C) {
      var d = Object.getOwnPropertyDescriptor(C.prototype, 'src');
      if (!d || !d.set) return;
      Object.defineProperty(C.prototype, 'src', { get: d.get, enumerable: d.enumerable, configurable: true, set: function (v) { return d.set.call(this, bad(v) ? 'data:,' : v); } });
    });
    var _sa = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function (n, v) {
      if (n === 'src' && /^(SCRIPT|IFRAME|IMG)$/.test(this.tagName) && bad(v)) v = 'data:,';
      return _sa.call(this, n, v);
    };
    var _f = window.fetch;
    if (_f) window.fetch = function (r) { var u = typeof r === 'string' ? r : r && r.url; if (u && bad(u)) return Promise.reject(new TypeError('blocked')); return _f.apply(this, arguments); };
    var _xo = XMLHttpRequest.prototype.open, _xs = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) { this.__vpbad = bad(u); return _xo.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function () { if (this.__vpbad) { try { this.abort(); } catch (e) {} return; } return _xs.apply(this, arguments); };
    var ast = document.createElement('style');
    ast.textContent = 'ins.adsbygoogle,[class~="adsbygoogle"],[id^="google_ads"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="exoclick"],iframe[src*="juicyads"],iframe[src*="trafficjunky"],iframe[src*="adsterra"],iframe[src*="popads"],[class~="ad-banner"],[class~="ads-container"],[class*="adsbox"],[class*="ad-slot"],[class*="ad-container"],[class*="ad-wrapper"],[class*="advertisement"],[id*="ad-slot"],[id*="ad-container"],[data-ad],[data-ad-slot],.google-auto-placed,.adsbygoogle-noablate{display:none!important;height:0!important;min-height:0!important}';
    document.documentElement.appendChild(ast);

    /* ---- block autoplay + give every video play/pause controls — EVENT-DRIVEN (no polling, so it can't hang) ---- */
    var userPlayed = 0, tapAt = null;
    // Only a real TAP counts (finger down and up in the same spot) - a scroll swipe must not let videos start by themselves.
    function tapped(x, y) {
      userPlayed = Date.now();
      try { [].forEach.call(document.querySelectorAll('video'), function (v) { var r = v.getBoundingClientRect(); if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) v.__vpUserOk = 1; }); } catch (x2) {}
    }
    document.addEventListener('touchstart', function (e) { var t = e.touches && e.touches[0]; tapAt = (t && e.touches.length === 1) ? { x: t.clientX, y: t.clientY, t: Date.now() } : null; }, { passive: true, capture: true });
    document.addEventListener('touchend', function (e) {
      var t = e.changedTouches && e.changedTouches[0], k = tapAt; tapAt = null;
      if (t && k && Math.abs(t.clientX - k.x) < 14 && Math.abs(t.clientY - k.y) < 14 && Date.now() - k.t < 900) tapped(t.clientX, t.clientY);
    }, { passive: true, capture: true });
    document.addEventListener('click', function (e) { tapped(e.clientX, e.clientY); }, true);
    document.addEventListener('keydown', function () { userPlayed = Date.now(); }, true);
    // The 'play' event fires the instant any video (even one added later) starts — catch autoplay right here.
    document.addEventListener('play', function (e) {
      var v = e.target; if (!v || v.tagName !== 'VIDEO') return;
      // give it visible controls once, if the site stripped them and it isn't inside a known player skin
      if (!v.__vpCtl) {
        v.__vpCtl = 1;
        try { if (!v.closest('[class*="player"],[class*="plyr"],[class*="jw"],[class*="video-js"],[class*="shaka"]')) { v.setAttribute('controls', ''); v.controls = true; } } catch (x) {}
      }
      // if play wasn't started by a recent real tap, it's autoplay → pause it so it waits for you
      // (4s window: many players fetch the stream first and only start a moment after your tap)
      if (Date.now() - userPlayed > 4000 && !v.__vpUserOk && !v.__vpNoCore) {
        try { v.pause(); } catch (x) {}   // pause only - never mute, or the video stays silent when you do press play
        // if the page locked scrolling for this autoplay takeover, free it
        try {
          var bs = getComputedStyle(document.body), hs = getComputedStyle(document.documentElement);
          if (bs.overflow === 'hidden' || hs.overflow === 'hidden' || bs.position === 'fixed') unlockScroll();
        } catch (x) {}
      }
    }, true);
    // when the user actually taps a video, mark it OK so it can play freely from then on
    document.addEventListener('click', function (e) {
      try { var v = e.target && e.target.closest && e.target.closest('video'); if (v) v.__vpUserOk = 1; } catch (x) {}
    }, true);
    // tag any videos already present at load so the first paint gets controls (one pass, not a loop)
    try { [].forEach.call(document.querySelectorAll('video'), function (v) { if (!v.hasAttribute('controls') && !v.closest('[class*="player"],[class*="plyr"],[class*="jw"],[class*="video-js"]')) { v.setAttribute('controls', ''); } }); } catch (x) {}
    function unlockScroll() {
      try {
        [document.body, document.documentElement].forEach(function (el) {
          if (!el) return;
          el.style.setProperty('overflow', 'auto', 'important');
          el.style.setProperty('position', 'static', 'important');
          el.style.setProperty('height', 'auto', 'important');
          el.style.removeProperty('touch-action');
        });
      } catch (x) {}
    }
    // expose a manual rescue the app can call (long-press refresh) to force-unfreeze any stuck page
    window.__vpUnstick = function () {
      unlockScroll();
      try { [].forEach.call(document.querySelectorAll('video'), function (v) { v.pause(); }); } catch (x) {}
      // remove full-viewport fixed overlays that block taps
      try {
        [].forEach.call(document.querySelectorAll('div,section,aside'), function (d) {
          var s = getComputedStyle(d); var r = d.getBoundingClientRect();
          if (s.position === 'fixed' && r.width >= innerWidth * 0.95 && r.height >= innerHeight * 0.9 && (+s.zIndex || 0) > 100) {
            d.style.setProperty('display', 'none', 'important');
          }
        });
      } catch (x) {}
    };

    /* ---- collapse leftover ad "black boxes" — conservative: only clear ad units, never the page's own content ---- */
    // ad-specific class/id patterns with word boundaries (won't match "header", "loading", "download", "read", etc.)
    var AD_CLS = /(^|[\s_-])(ad|ads|adbox|ad-box|adslot|ad-slot|adunit|ad-unit|adzone|ad-zone|adframe|ad-wrapper|ad-container|advertisement|adsbygoogle|google-ad|googlead|sponsor|sponsored)($|[\s_-])/i;
    // don't run heavy DOM scans while the user is actively scrolling — keeps scrolling smooth
    var scrolling = 0;
    window.addEventListener('scroll', function () { scrolling = Date.now(); }, { passive: true });
    function killAdBoxes() {
      if (Date.now() - scrolling < 400) return; // defer during scroll
      try {
        // iframes pointing at a known ad/sketchy host only
        [].forEach.call(document.querySelectorAll('iframe'), function (f) {
          var s = f.getAttribute('src') || '';
          if (s && bad(s)) collapse(f);   // known ad hosts only: video players are often embedded from odd domains
        });
        // elements with an ad-specific class/id — but NEVER if they contain the page's real text or are large content blocks
        [].forEach.call(document.querySelectorAll('ins.adsbygoogle,[class*="adsbygoogle"],[id^="google_ads"],[id*="ad-slot"],[id*="ad-container"],[data-ad],[data-ad-slot]'), function (d) { collapse(d); });
        [].forEach.call(document.querySelectorAll('div,ins,aside'), function (d) {
          var cls = ((d.id || '') + ' ' + (typeof d.className === 'string' ? d.className : '')).toLowerCase();
          if (!AD_CLS.test(cls)) return;
          // safety: don't hide anything that holds a meaningful amount of text (that's content, not an ad)
          if ((d.innerText || '').trim().length > 40) return;
          collapse(d);
        });
        // ad iframes/scripts from ad hosts, by element src (already blocked at load, this cleans visible frames)
      } catch (e) {}
    }
    function collapse(el) { try { el.style.setProperty('display', 'none', 'important'); el.style.setProperty('height', '0', 'important'); el.style.setProperty('min-height', '0', 'important'); } catch (e) {} }
    killAdBoxes();
    // Debounce: on a busy page (LinkedIn, feeds) the DOM mutates constantly; running killAdBoxes on every mutation
    // saturates the main thread and makes scrolling hang. Coalesce bursts into one run shortly after they settle.
    var adT = 0;
    var ric = window.requestIdleCallback ? function (f) { window.requestIdleCallback(f, { timeout: 1000 }); } : function (f) { setTimeout(f, 0); };
    try {
      new MutationObserver(function () {
        if (adT) return;
        adT = setTimeout(function () { adT = 0; ric(killAdBoxes); }, 600);
      }).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    setInterval(function () { ric(killAdBoxes); }, 4000);

    /* ---- redirect guard (page side): stop tap-hijack and idle/timer redirects ----
       Key idea: a tap only counts as real navigation INTENT if it lands on a genuine interactive element
       (a link, button or form control). A tap on empty space or an ad overlay must NOT authorize a redirect. */
    var lastReal = 0;
    function realIntent(t) {
      try {
        if (!t || !t.closest) return false;
        var el = t.closest('a[href],button,[role=button],input,select,textarea,[onclick],[tabindex]');
        if (!el) return false;
        // ignore an element that covers most of the screen (classic full-page ad overlay)
        var r = el.getBoundingClientRect();
        if (r.width > innerWidth * 0.9 && r.height > innerHeight * 0.7) return false;
        // ignore links pointing off-site to an ad/sketchy host
        if (el.tagName === 'A' && el.href && (bad(el.href) || (sketchy(el.href) && !sameSite(el.href)))) return false;
        return true;
      } catch (e) { return false; }
    }
    ['pointerdown', 'touchstart', 'mousedown', 'click', 'keydown'].forEach(function (ev) {
      document.addEventListener(ev, function (e) {
        if (ev === 'keydown' || realIntent(e.target)) { lastReal = Date.now(); try { VPApp.realTap && VPApp.realTap(); } catch (x) {} }
      }, true);
    });
    function sameSite(u) { try { var h = new URL(u, location.href).hostname, p = location.hostname; return h === p || h.slice(-p.length - 1) === '.' + p || p.slice(-h.length - 1) === '.' + h; } catch (e) { return true; } }
    // block scripted navigations to another site that weren't triggered by a genuine tap in the last ~1.2s
    try {
      var _assign = location.assign.bind(location), _replace = location.replace.bind(location);
      location.assign = function (u) { if (sameSite(u) || Date.now() - lastReal < 1200) return _assign(u); };
      location.replace = function (u) { if (sameSite(u) || Date.now() - lastReal < 1200) return _replace(u); };
    } catch (e) {}
    // block meta-refresh redirects to other sites
    try {
      new MutationObserver(function (ms) {
        ms.forEach(function (m) { for (var i = 0; i < m.addedNodes.length; i++) { var n = m.addedNodes[i]; if (n.tagName === 'META' && /refresh/i.test(n.getAttribute('http-equiv') || '')) { var c = n.getAttribute('content') || ''; var mu = c.match(/url=(.+)$/i); if (mu && !sameSite(mu[1].trim())) n.parentNode.removeChild(n); } } });
      }).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    // neutralize full-page overlay anchors that hijack the first tap to an ad site
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (a && a.href && !sameSite(a.href)) {
        var r = a.getBoundingClientRect();
        if (r.width > innerWidth * 0.85 && r.height > innerHeight * 0.5) { e.preventDefault(); e.stopPropagation(); } // page-covering ad link
      }
    }, true);
  }

  var PRE = {
    Auto: { auto: true },
    Original: { auto: false, sharp: 0, ct: 1, sat: 1, warm: 0, br: 1 },
    Cinema: { auto: false, sharp: 0, ct: 1.06, sat: 1.08, warm: 0, br: 1.02 },
    Vivid: { auto: false, sharp: 0, ct: 1.05, sat: 1.22, warm: 0, br: 1.04 },
    Punchy: { auto: false, sharp: 0, ct: 1.09, sat: 1.12, warm: 0, br: 1.03 },
    Night: { auto: false, sharp: 0, ct: 1, sat: 0.95, warm: 0.2, br: 1.18 }
  };

  /* ---------- UI ---------- */
  var root = document.createElement('div');
  root.id = 'vplus-root';
  root.style.cssText = 'all:initial;position:fixed;z-index:2147483647;right:10px;bottom:96px;font:14px system-ui,sans-serif;display:none';
  root.innerHTML =
    '<style>#vplus-root *{box-sizing:border-box;font-family:system-ui,sans-serif}' +
    '#vp-fab{transition:opacity .3s;width:48px;height:48px;border-radius:50%;border:0;background:#d9b45a;color:#1a1405;font-size:22px;box-shadow:0 2px 10px #000a;cursor:pointer}' +
    '#vp-panel{display:none;position:fixed;left:0;right:0;bottom:0;background:#14161cf2;color:#fff;padding:14px;border-radius:16px 16px 0 0;max-height:70vh;overflow:auto}' +
    '#vp-panel.on{display:block}#vp-panel b{display:block;margin-bottom:8px}' +
    '#vp-panel button{background:#262a36;color:#fff;border:0;border-radius:10px;padding:10px 12px;margin:3px;font-size:14px}' +
    '#vp-panel button.on{background:#d9b45a;color:#1a1405}' +
    '#vp-panel label{display:flex;align-items:center;gap:8px;margin:8px 0;font-size:13px;color:#cfd3df}#vp-panel label span{width:78px}' +
    '#vp-panel input[type=range]{flex:1;accent-color:#d9b45a}' +
    '#vp-bar{position:fixed;display:none;align-items:center;gap:8px;background:#000c;color:#fff;padding:4px 10px;border-radius:12px;font-size:13px;box-sizing:border-box;z-index:2147483647;touch-action:none}#vp-bar input{flex:1;min-width:0;height:30px;accent-color:#d9b45a}#vp-bar b{font-size:20px;padding:2px 6px}#vp-bar.vp-s{padding:0 8px;gap:5px;font-size:11px;border-radius:8px}#vp-bar.vp-s input{height:18px}#vp-bar.vp-s b{font-size:13px;padding:0 3px}' +
    '#vp-cap{position:fixed;left:50%;bottom:14%;transform:translateX(-50%);max-width:90vw;text-align:center;background:#000b;color:#fff;padding:6px 12px;border-radius:8px;font-size:17px;line-height:1.3;display:none;pointer-events:none;z-index:2147483647}' +
    '#vp-hud{position:fixed;left:50%;top:22%;transform:translateX(-50%);background:#000a;color:#fff;padding:9px 16px;border-radius:12px;font-size:18px;display:none;z-index:2147483647}</style>' +
    '<button id="vp-fab" title="Video Plus">\uD83C\uDFAC</button>' +
    '<div id="vp-panel"></div><div id="vp-hud"></div><div id="vp-cap"></div><div id="vp-bar"><b id="vp-bpp">\u23EF</b><span id="vp-bc">0:00</span><input id="vp-br" type="range" min="0" max="1000" value="0"><span id="vp-bd">0:00</span></div>';
  document.documentElement.appendChild(root);
  /* lift video captions off the very bottom edge so they are not clipped in fullscreen/landscape */
  try {
    var cueStyle = document.createElement('style');
    cueStyle.textContent = 'video::cue{font-size:3.2vw;line-height:1.3;background:rgba(0,0,0,.6)!important;white-space:normal}video::-webkit-media-text-track-display{bottom:8% !important;top:auto !important}video::-webkit-media-text-track-container{bottom:6% !important;max-height:86% !important}';
    (document.head || document.documentElement).appendChild(cueStyle);
  } catch (e) {}
  var $ = function (s) { return root.querySelector(s); };
  var panel = $('#vp-panel'), hud = $('#vp-hud'), hudT;
  function say(t, ms) { hud.textContent = t; hud.style.display = 'block'; clearTimeout(hudT); hudT = setTimeout(function () { hud.style.display = 'none'; }, ms || 800); }

  

  /* when a site (YouTube etc.) fullscreens the bare <video>, redirect to its container so Video Plus stays on top */
  (function () {
    try {
      var proto = HTMLVideoElement.prototype, orig = proto.requestFullscreen || proto.webkitRequestFullscreen || proto.mozRequestFullScreen;
      if (!orig) return;
      var name = proto.requestFullscreen ? 'requestFullscreen' : (proto.webkitRequestFullscreen ? 'webkitRequestFullscreen' : 'mozRequestFullScreen');
      proto[name] = function () {
        try {
          var box = (window.__vpBox ? window.__vpBox(this) : this);
          if (box && box !== this) { var f = box.requestFullscreen || box.webkitRequestFullscreen || box.mozRequestFullScreen; if (f) return f.call(box); }
        } catch (e) {}
        return orig.apply(this, arguments);
      };
    } catch (e) {}
  })();

  /* ---------- target video ---------- */
  var vids = [];
  function scan() {
    vids = Array.prototype.filter.call(document.querySelectorAll('video'), function (v) {
      var r = v.getBoundingClientRect();
      return r.width >= 200 && r.height >= 110;
    });
    if (S.boost !== false) vids.forEach(function (v) { if (v.preload !== 'auto') { try { v.preload = 'auto'; } catch (e) {} } });
    root.style.display = vids.length ? 'block' : 'none';
  }
  setInterval(scan, 1500); scan();

  /* keep a buffer ahead (~90s where the site allows) so playback does not stall on brief WiFi dips */
  function bufferedAhead(v) {
    try { for (var i = v.buffered.length - 1; i >= 0; i--) { if (v.buffered.start(i) <= v.currentTime + 0.1 && v.buffered.end(i) >= v.currentTime) return v.buffered.end(i) - v.currentTime; } } catch (e) {}
    return 0;
  }
  function markStall(e) { var v = e.target; if (v instanceof HTMLVideoElement) { v.__vpStallAt = Date.now(); } }
  document.addEventListener('waiting', markStall, true);
  setInterval(function () {
    if (S.boost === false) return;
    vids.forEach(function (v) {
      if (v.paused || !isFinite(v.duration) || v.duration < 60) return;
      try {
        if (v.preload !== 'auto') v.preload = 'auto';
        /* only recover from a genuine stall that has lasted a few seconds, and only on a VOD (not live) */
        var live = !isFinite(v.duration) || v.seekable && v.seekable.length && (v.seekable.end(v.seekable.length - 1) - v.currentTime) < 0 ;
        if (v.__vpStallAt && Date.now() - v.__vpStallAt > 4000 && v.readyState < 3 && bufferedAhead(v) < 1 && !v.__vpNudge) {
          v.__vpNudge = 1; v.__vpStallAt = 0;
          try { v.load && v.networkState === 3 ? 0 : (v.currentTime = v.currentTime); } catch (e) {}
          setTimeout(function () { v.__vpNudge = 0; }, 4000);
        }
      } catch (e) {}
    });
  }, 1500);
  function target() {
    var best = null, bs = 0;
    vids.forEach(function (v) {
      var r = v.getBoundingClientRect();
      var vis = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0)) * Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
      var sc = vis + (v.paused ? 0 : 1e9);
      if (sc > bs) { bs = sc; best = v; }
    });
    return best;
  }

  /* ---------- AUTO look (18 Nov): a natural, bright "4K cinema" picture that adapts by itself ----------
     Reads how dark the playing picture really is (a tiny 32x18 sample once a second, where the site allows it) and how
     bright the room is (the phone's light sensor, sent by the app as window.__vpLux). Dark scenes are lifted, bright
     ones are left alone so whites do not burn out; in sunlight it adds brightness and contrast, in a dark room it stays
     gentle. Changes glide slowly so the picture never flickers. */
  var AUTO = { ct: 1.08, sat: 1.14, br: 1.12 }, cvs = null, cx = null, luma = null;
  function sampleLuma(v) {
    try {
      if (!v || v.readyState < 2 || !v.videoWidth) return null;
      cvs = cvs || document.createElement('canvas'); cvs.width = 32; cvs.height = 18;
      cx = cx || cvs.getContext('2d', { willReadFrequently: true });
      cx.drawImage(v, 0, 0, 32, 18);
      var d = cx.getImageData(0, 0, 32, 18).data, t = 0;
      for (var i = 0; i < d.length; i += 4) t += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
      return t / (d.length / 4) / 255;
    } catch (e) { return null; }                       /* the site does not allow reading the picture: use the light sensor only */
  }
  function autoTick() {
    if (!S.auto) return;
    var v = target(); if (!v || v.paused) return;
    var L = sampleLuma(v); if (L !== null) luma = luma === null ? L : luma * 0.6 + L * 0.4;
    var lux = typeof window.__vpLux === 'number' ? window.__vpLux : null;
    var br = luma === null ? 1.14 : Math.min(1.32, Math.max(1.0, Math.pow(0.42 / Math.max(luma, 0.06), 0.45)));
    if (luma !== null && luma > 0.5) br = 1.0;          /* already bright: never burn the whites */
    var ct = 1.08, sat = 1.14;
    if (lux !== null && lux >= 3000) { br *= 1.08; ct += 0.07; sat += 0.05; }          /* sunlight */
    else if (lux !== null && lux >= 400) { br *= 1.03; ct += 0.03; }                  /* bright room */
    else if (lux !== null && lux < 15) { br = Math.min(br, 1.15); ct = 1.04; sat = 1.08; }   /* dark room: easy on the eyes */
    br = Math.min(1.4, br);
    AUTO.br += (br - AUTO.br) * 0.25; AUTO.ct += (ct - AUTO.ct) * 0.25; AUTO.sat += (sat - AUTO.sat) * 0.25;
    if (Math.abs(AUTO.br - (AUTO.lb || 0)) > 0.025 || Math.abs(AUTO.ct - (AUTO.lc || 0)) > 0.025 || Math.abs(AUTO.sat - (AUTO.ls || 0)) > 0.025) { AUTO.lb = AUTO.br; AUTO.lc = AUTO.ct; AUTO.ls = AUTO.sat; applyLook(true); }
  }
  setInterval(autoTick, 1000);

  /* ---------- look ---------- */
  /* safety net: if a playing video stops showing new frames, take every picture effect off it for good */
  function guard(v) {
    if (v.__vpG || !v.requestVideoFrameCallback) return;
    v.__vpG = 1;
    var t = Date.now(), cb = function () { t = Date.now(); v.requestVideoFrameCallback(cb); };
    v.requestVideoFrameCallback(cb);
    setInterval(function () {
      if (!v.paused && !v.ended && v.readyState >= 3 && Date.now() - t > 2500 && !v.__vpSafe) {
        v.__vpSafe = 1; v.style.removeProperty('filter'); v.style.removeProperty('box-shadow');
      }
    }, 1000);
  }
  function applyLook(quiet) {
    var L = S.auto ? { ct: +AUTO.ct.toFixed(3), sat: +AUTO.sat.toFixed(3), br: +AUTO.br.toFixed(3), warm: 0 } : S;
    var neutral = L.ct === 1 && L.sat === 1 && L.warm === 0 && L.br === 1;
    var f = 'contrast(' + L.ct + ') saturate(' + L.sat + ') brightness(' + L.br + ')' + (L.warm > 0 ? ' sepia(' + (L.warm * 0.25) + ')' : '');
    vids.forEach(function (v) {
      guard(v);
      if (v.__vpSafe || v.clientHeight < 200) { if (v.style.getPropertyValue('filter')) v.style.removeProperty('filter'); return; }
      if (hdMap.has(v)) { if (v.style.getPropertyValue('filter')) v.style.removeProperty('filter'); }
      else if (neutral) { if (v.style.getPropertyValue('filter')) v.style.removeProperty('filter'); } else if (v.style.getPropertyValue('filter') !== f) v.style.setProperty('filter', f, 'important');
      if (S.theater) v.style.setProperty('box-shadow', '0 0 0 100vmax rgba(0,0,0,.93)', 'important'); else v.style.removeProperty('box-shadow');
    });
    if (!quiet) save();
  }
  setInterval(function () { applyLook(true); }, 2500);

  /* ---------- volume boost above 100% (Web Audio gain + limiter) ---------- */
  var AC = null, gains = new WeakMap();
  function boostable(v) {
    var u = v.currentSrc || v.src || ''; if (!u) return false;
    if (u.indexOf('blob:') === 0 || v.__vpcors) return true;
    try { return new URL(u, location.href).origin === location.origin; } catch (e) { return false; }
  }
  function graph(v) {
    AC = AC || new (window.AudioContext || window.webkitAudioContext)();
    var n = gains.get(v);
    if (!n) {
      var src = AC.createMediaElementSource(v), gn = AC.createGain(), cp = AC.createDynamicsCompressor();
      cp.threshold.value = -6; cp.knee.value = 6; cp.ratio.value = 20; cp.attack.value = 0.002; cp.release.value = 0.2;
      var an = AC.createAnalyser(); an.fftSize = 2048; src.connect(an);
      src.connect(gn); gn.connect(cp); cp.connect(AC.destination); n = { gn: gn, src: src, an: an, buf: new Float32Array(2048), cur: 1, rms: 0 }; gains.set(v, n);
    }
    return n;
  }
  /* cross-domain video: if its server allows CORS, reload it in "anonymous" mode so the page may use its audio/picture */
  function ensureBoostable(v, cb) {
    if (boostable(v)) return cb(true);
    if (v.__vpbusy) return;
    var u = v.currentSrc || v.src; if (!u || v.__vptried) return cb(false);
    v.__vptried = v.__vpbusy = true; say('Trying to unlock audio boost...', 2500);
    fetch(u, { mode: 'cors', credentials: 'omit', headers: { Range: 'bytes=0-1' } }).then(function (r) {
      if (!r.ok && r.status !== 206) throw new Error('no');
      var t = v.currentTime, was = !v.paused;
      v.crossOrigin = 'anonymous'; v.__vpcors = true;
      var done = function () { v.removeEventListener('loadedmetadata', done); try { v.currentTime = t; } catch (e) {} if (was) { try { v.play(); } catch (e) {} } v.__vpbusy = false; cb(true); };
      v.addEventListener('loadedmetadata', done);
      v.addEventListener('error', function () { v.__vpcors = false; v.__vpbusy = false; v.removeAttribute('crossorigin'); cb(false); }, { once: true });
      v.src = u; v.load();
    }).catch(function () { v.__vpbusy = false; cb(false); });
  }
  document.addEventListener('touchend', function () { if (AC && AC.state === 'suspended') { try { AC.resume(); } catch (e) {} } }, true);
  document.addEventListener('click', function () { if (AC && AC.state === 'suspended') { try { AC.resume(); } catch (e) {} } }, true);
  /* auto loudness: quiet videos are lifted toward a comfortable level, loud ones are left alone */
  setInterval(function () {
    if (!(S.gain > 1) || !AC) return;
    vids.forEach(function (v) {
      var n = gains.get(v); if (!n || v.paused) return;
      var r = 0; try { n.an.getFloatTimeDomainData(n.buf); for (var i = 0; i < n.buf.length; i++) r += n.buf[i] * n.buf[i]; r = Math.sqrt(r / n.buf.length); } catch (e) { return; }
      if (r < 0.0003) return;
      n.rms = n.rms ? n.rms * 0.8 + r * 0.2 : r;
      var want = Math.min(30, Math.max(1, 0.12 * (S.gain / 3) / n.rms));
      n.cur += (want - n.cur) * (want < n.cur ? 0.5 : 0.12);
      n.gn.gain.setTargetAtTime(Math.max(1, n.cur), AC.currentTime, 0.15);
    });
  }, 250);
  function setGain(v, g) {
    if (g <= 1 && !gains.has(v)) return true;
    if (!boostable(v)) return false;
    try {
      var n = graph(v);
      if (g <= 1) { n.gn.gain.value = 1; n.cur = 1; } else if (n.cur <= 1) { n.gn.gain.value = g; n.cur = g; }
      if (AC.state === 'suspended') AC.resume();
      return true;
    } catch (e) { return false; }
  }
  document.addEventListener('play', function (e) { var v = e.target; if (v instanceof HTMLVideoElement && S.gain > 1 && !v.muted && (!isFinite(v.duration) || v.duration > 30)) setGain(v, S.gain); }, true);

  /* ---------- HD enhancer: GPU bicubic upscale + sharpen (only where the site lets the page read the video) ---------- */
  var HD_VS = 'attribute vec2 p;varying vec2 t;void main(){t=vec2(p.x*.5+.5,.5-p.y*.5);gl_Position=vec4(p,0.,1.);}';
  var HD_FS = 'precision highp float;uniform sampler2D u;uniform vec2 sz;uniform vec2 sc;uniform vec4 c;uniform float k;varying vec2 t;' +
    'vec4 cr(vec2 uv){vec2 pos=uv*sz;vec2 c0=floor(pos-.5)+.5;vec2 f=pos-c0;' +
    'vec2 w0=f*(-.5+f*(1.-.5*f));vec2 w1=1.+f*f*(-2.5+1.5*f);vec2 w2=f*(.5+f*(2.-1.5*f));vec2 w3=f*f*(-.5+.5*f);' +
    'vec2 w12=w1+w2;vec2 t0=(c0-1.)/sz;vec2 t3=(c0+2.)/sz;vec2 t12=(c0+w2/w12)/sz;' +
    'vec4 r=texture2D(u,vec2(t0.x,t0.y))*w0.x*w0.y+texture2D(u,vec2(t12.x,t0.y))*w12.x*w0.y+texture2D(u,vec2(t3.x,t0.y))*w3.x*w0.y' +
    '+texture2D(u,vec2(t0.x,t12.y))*w0.x*w12.y+texture2D(u,vec2(t12.x,t12.y))*w12.x*w12.y+texture2D(u,vec2(t3.x,t12.y))*w3.x*w12.y' +
    '+texture2D(u,vec2(t0.x,t3.y))*w0.x*w3.y+texture2D(u,vec2(t12.x,t3.y))*w12.x*w3.y+texture2D(u,vec2(t3.x,t3.y))*w3.x*w3.y;return r;}' +
    'void main(){vec2 uv=(t-.5)*sc+.5;if(uv.x<0.||uv.x>1.||uv.y<0.||uv.y>1.){gl_FragColor=vec4(0.,0.,0.,1.);return;}' +
    'vec3 col=cr(uv).rgb;vec2 d=1./sz;' +
    'vec3 b=(texture2D(u,uv+vec2(d.x,0.)).rgb+texture2D(u,uv-vec2(d.x,0.)).rgb+texture2D(u,uv+vec2(0.,d.y)).rgb+texture2D(u,uv-vec2(0.,d.y)).rgb)*.25;' +
    'vec3 dt=col-b;col+=clamp(dt*k,-.12,.12);' +
    'col=(col-.5)*c.x+.5;float l=dot(col,vec3(.299,.587,.114));col=mix(vec3(l),col,c.y);col*=c.z;col.r+=c.w*.03;col.b-=c.w*.03;' +
    'gl_FragColor=vec4(clamp(col,0.,1.),1.);}';
  function hdStop(v, bad) {
    var st = hdMap.get(v); if (bad) v.__vpbad = true;
    if (st) { if (st.cv.parentNode) st.cv.parentNode.removeChild(st.cv); hdMap.delete(v); }
  }
  function hdPlace(v, st) {
    var w = v.offsetWidth, h = v.offsetHeight;
    if (w < 60 || h < 40) { st.cv.style.display = 'none'; return false; }
    var dpr = Math.min(window.devicePixelRatio || 1, 2.5), cw = Math.min(Math.round(w * dpr), 2560), ch = Math.round(h * cw / w);
    var cs = st.cv.style; cs.display = st.ok ? 'block' : 'none'; cs.left = v.offsetLeft + 'px'; cs.top = v.offsetTop + 'px'; cs.width = w + 'px'; cs.height = h + 'px';
    if (st.cv.width !== cw || st.cv.height !== ch) { st.cv.width = cw; st.cv.height = ch; }
    st.w = w; st.h = h; return true;
  }
  /* compare what the GPU drew against a plain copy of the same frame; a big colour mismatch means the phone mangles the upload */
  function hdCheck(v, st, sx, sy) {
    try {
      var gl = st.gl, W = st.cv.width, H = st.cv.height, c = st.ref || (st.ref = document.createElement('canvas'));
      c.width = 16; c.height = 16; var cx = c.getContext('2d'); cx.drawImage(v, 0, 0, 16, 16);
      var d = 0, n = 0, px = new Uint8Array(4), pts = [0.25, 0.5, 0.75];
      for (var a = 0; a < 3; a++) for (var b = 0; b < 3; b++) {
        var ux = pts[b], uy = pts[a], tx = (ux - 0.5) / sx + 0.5, ty = (uy - 0.5) / sy + 0.5;
        gl.readPixels(Math.max(0, Math.min(W - 1, Math.round(tx * W))), Math.max(0, Math.min(H - 1, Math.round((1 - ty) * H))), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        var r = cx.getImageData(Math.floor(ux * 16), Math.floor(uy * 16), 1, 1).data;
        d += Math.abs(px[0] - r[0]) + Math.abs(px[1] - r[1]) + Math.abs(px[2] - r[2]); n += 3;
      }
      return d / n;
    } catch (e) { return 0; }
  }
  function hdFail(v) { hdStop(v, true); applyLook(true); say('HD boost off - this phone/video shows it wrongly', 3500); }
  function hdDraw(v, st) {
    if (v.readyState < 2 || !v.videoWidth) return;
    if (!hdPlace(v, st)) return;
    var gl = st.gl, fit = getComputedStyle(v).objectFit, va = v.videoWidth / v.videoHeight, ba = st.w / st.h, sx = 1, sy = 1;
    if (fit !== 'fill') { var cov = fit === 'cover'; if ((ba > va) !== cov) sx = ba / va; else sy = va / ba; }
    try {
      gl.viewport(0, 0, st.cv.width, st.cv.height);
      gl.bindTexture(gl.TEXTURE_2D, st.tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, v);
    } catch (e) { hdStop(v, true); applyLook(true); say('HD boost not possible on this site'); return; }
    gl.uniform2f(st.l.sz, v.videoWidth, v.videoHeight); gl.uniform2f(st.l.sc, sx, sy);
    gl.uniform4f(st.l.c, S.ct, S.sat, S.br, S.warm); gl.uniform1f(st.l.k, S.shp === undefined ? 0.7 : S.shp);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    if (st.ok && ((st.n = (st.n || 0) + 1) % 90 === 0) && hdCheck(v, st, sx, sy) > 55) { hdFail(v); return; }
    if (!st.ok) {
      /* show the canvas only after a real (non-black) picture was drawn; otherwise give the video back untouched */
      var px = new Uint8Array(4), seen = false, W = st.cv.width, H = st.cv.height;
      for (var yy = 1; yy <= 3 && !seen; yy++) for (var xx = 1; xx <= 3 && !seen; xx++) {
        gl.readPixels(Math.floor(W * xx / 4), Math.floor(H * yy / 4), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        if (px[0] + px[1] + px[2] > 30) seen = true;
      }
      if (seen) { if (hdCheck(v, st, sx, sy) > 55) { hdFail(v); return; } st.ok = true; st.cv.style.display = 'block'; }
      else if (!st.t0) st.t0 = v.currentTime;
      else if (v.currentTime - st.t0 > 2.5) { hdStop(v, true); applyLook(true); say('HD boost off - this video cannot be enhanced', 3000); }
    }
  }
  function hdLoop(v, st) {
    if (hdMap.get(v) !== st) return;
    hdDraw(v, st);
    if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(function () { hdLoop(v, st); }); else requestAnimationFrame(function () { hdLoop(v, st); });
  }
  function hdStart(v) {
    if (hdMap.has(v) || v.__vpbad || !boostable(v)) return;
    var cv = document.createElement('canvas'), gl = null;
    try { gl = cv.getContext('webgl', { alpha: false, antialias: false, powerPreference: 'high-performance' }); } catch (e) {}
    if (!gl) { v.__vpbad = true; return; }
    function sh(t, src) { var o = gl.createShader(t); gl.shaderSource(o, src); gl.compileShader(o); return gl.getShaderParameter(o, gl.COMPILE_STATUS) ? o : null; }
    var vs = sh(gl.VERTEX_SHADER, HD_VS), fs = sh(gl.FRAGMENT_SHADER, HD_FS), pr = gl.createProgram();
    if (!vs || !fs) { v.__vpbad = true; return; }
    gl.attachShader(pr, vs); gl.attachShader(pr, fs); gl.linkProgram(pr);
    if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) { v.__vpbad = true; return; }
    gl.useProgram(pr);
    var bf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, bf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    var pl = gl.getAttribLocation(pr, 'p'); gl.enableVertexAttribArray(pl); gl.vertexAttribPointer(pl, 2, gl.FLOAT, false, 0, 0);
    var tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    var l = {}; ['sz', 'sc', 'c', 'k'].forEach(function (n) { l[n] = gl.getUniformLocation(pr, n); });
    gl.uniform1i(gl.getUniformLocation(pr, 'u'), 0);
    var z = getComputedStyle(v).zIndex;
    cv.style.cssText = 'position:absolute;pointer-events:none;margin:0;padding:0;border:0;display:none;' + (z && z !== 'auto' ? 'z-index:' + z + ';' : '');
    v.parentNode.insertBefore(cv, v.nextSibling);
    var st = { cv: cv, gl: gl, tex: tex, l: l, w: 0, h: 0, ok: false, t0: 0 };
    cv.addEventListener('webglcontextlost', function () { hdStop(v, true); applyLook(true); }, false);
    hdMap.set(v, st); applyLook(true); hdLoop(v, st);
  }
  setInterval(function () {
    vids.forEach(function (v) {
      var st = hdMap.get(v), off = S.hdx !== true || (mini && mini.v === v) || document.fullscreenElement === v;
      if (st && (off || !v.isConnected || st.cv.parentNode !== v.parentNode)) { hdStop(v); applyLook(true); st = null; }
      if (!st && !off && !v.paused && v.readyState >= 2) hdStart(v);
      if (st && v.paused) hdDraw(v, st);
    });
  }, 800);

  function statText() {
    var v = target(); if (!v) return 'No video found on this page';
    var r = v.videoWidth ? v.videoWidth + 'x' + v.videoHeight : '?';
    var hd = S.hdx !== true ? 'OFF (switched off - tap HD boost to try)' : hdMap.has(v) ? (hdMap.get(v).ok ? 'ON' : 'checking...') : v.__vpbad ? 'OFF (site blocks it)' : !boostable(v) ? 'OFF (video comes from another domain)' : v.paused ? 'ready (press play)' : 'starting...';
    var vo = !boostable(v) ? (v.__vptried ? 'locked by this site' : 'tap Loud audio to try') : 'available';
    var gn = gains.get(v);
    if (gn) { var pk = 0; try { gn.an.getFloatTimeDomainData(gn.buf); for (var q = 0; q < gn.buf.length; q++) pk = Math.max(pk, Math.abs(gn.buf[q])); } catch (e) {}
      vo = 'ON auto x' + (Math.round((gn.cur || 1) * 10) / 10) + ' (engine ' + (AC ? AC.state : '?') + ', ' + (v.muted ? 'video MUTED' : 'video vol ' + Math.round(v.volume * 100) + '%') + ', sound signal ' + (pk > 0.0005 ? 'yes' : 'none') + ')'; }
    return 'Source ' + r + ' | HD boost: ' + hd + ' | Volume boost: ' + vo + ' | Subtitles: ' + subSt;
  }
  setInterval(function () { var e = $('#vp-st'); if (e && panel.classList.contains('on')) e.textContent = statText(); }, 1000);

  /* ---------- live English subtitles (experimental): audio -> Whisper in a worker -> English text ---------- */
  var sub = null, subSt = 'off';
  var SUB_WORKER = "import {pipeline,env} from 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3';env.allowLocalModels=false;var asr=null;" +
    "self.onmessage=async function(e){var m=e.data;try{if(m.t==='init'){asr=await pipeline('automatic-speech-recognition','onnx-community/whisper-'+m.model,{device:'wasm',dtype:'q8',progress_callback:function(p){if(p.status==='progress')self.postMessage({t:'prog',p:Math.round(p.progress)})}});self.postMessage({t:'ready'});}" +
    "else{var t1=Date.now();var o=await asr(m.a,{task:'translate',return_timestamps:true});self.postMessage({t:'res',t0:m.t0,dur:m.a.length/16000,ms:Date.now()-t1,chunks:o.chunks||[],text:o.text||''});}}catch(err){self.postMessage({t:'err',msg:String((err&&err.message)||err)})}};";
  if (window === window.top) window.__vpSub = function (m) { window.__vpSubH && window.__vpSubH(m); var fr = document.getElementsByTagName('iframe'); for (var q = 0; q < fr.length; q++) { try { fr[q].contentWindow.postMessage({ __vpSubRes: 1, m: m }, '*'); } catch (e) {} } };
  window.addEventListener('message', function (e) { var d = e.data; if (d && d.__vpSubRes && window.__vpSubH) window.__vpSubH(d.m); });
  function subOff(msg) {
    if (sub) { try { sub.sp.disconnect(); } catch (e) {} try { sub.w.terminate(); } catch (e) {} sub = null; }
    subSt = msg || 'off'; var c = $('#vp-cap'); if (c) c.style.display = 'none';
  }
  function subOn(v) {
    if (sub) { subOff(); say('Subtitles off'); return; }
    if (!boostable(v)) { subSt = 'not possible (site does not let the page read its audio)'; say('Subtitles not possible on this site', 2500); return; }
    var n; try { n = graph(v); if (AC.state === 'suspended') AC.resume(); } catch (e) { subSt = 'not possible (audio blocked)'; say('Subtitles not possible on this site', 2500); return; }
    var w;
    if (window.VPApp && VPApp.subInit) {
      /* engine runs inside the app itself, so site security rules cannot block it */
      w = { terminate: function () { window.__vpSubH = null; }, postMessage: function (m) {
        if (m.t === 'init') VPApp.subInit(m.model);
        else if (m.t === 'go') { var a = m.a, n = a.length, u = new Uint8Array(n * 2), s = '', C = 0x8000;
          for (var i = 0; i < n; i++) { var x = Math.max(-1, Math.min(1, a[i])); x = (x < 0 ? x * 32768 : x * 32767) | 0; u[2 * i] = x & 255; u[2 * i + 1] = (x >> 8) & 255; }
          for (var j = 0; j < u.length; j += C) s += String.fromCharCode.apply(null, u.subarray(j, j + C));
          VPApp.subGo(m.t0, btoa(s)); } } };
      window.__vpSubH = function (m) { if (w.onmessage) w.onmessage({ data: m }); };
    } else {
      try { w = new Worker(URL.createObjectURL(new Blob([SUB_WORKER], { type: 'text/javascript' })), { type: 'module' }); }
      catch (e) { subSt = 'blocked by this site security'; say('This site blocks the subtitle engine', 2500); return; }
    }
    var tr = v.addTextTrack('subtitles', 'AI English', 'en'); tr.mode = 'showing'; tr.__vpLift = true;
    var sp = AC.createScriptProcessor(4096, 1, 1), ratio = AC.sampleRate / 16000, parts = [], len = 0, t0 = 0, busy = false, queue = [], ready = false, capT, run = 0, quiet = 0, lastTx = '', model = S.model || 'base';
    sub = { w: w, sp: sp, v: v, tr: tr }; subSt = 'loading ' + model + ' engine...';
    var cap = $('#vp-cap');
    function show(t) { cap.textContent = t; cap.style.display = 'block'; clearTimeout(capT); capT = setTimeout(function () { cap.style.display = 'none'; }, 7000); }
    function pump() { if (busy || !ready || !queue.length) return; var j = queue.shift(); busy = true; subSt = 'translating (' + queue.length + ' waiting)'; w.postMessage({ t: 'go', a: j.a, t0: j.t0 }, [j.a.buffer]); }
    w.onerror = function () { subOff('blocked by this site security (or no internet)'); say('Subtitle engine could not start', 3000); };
    w.onmessage = function (e) {
      var m = e.data;
      if (m.t === 'prog') subSt = 'downloading ' + model + ' model ' + m.p + '% (first time only)';
      else if (m.t === 'ready') { ready = true; subSt = 'listening'; pump(); }
      else if (m.t === 'err') { subOff('error: ' + m.msg); say('Subtitle error', 3000); }
      else if (m.t === 'res') {
        busy = false; var shown = [];
        m.chunks.forEach(function (c) {
          var tx = (c.text || '').trim(); if (tx.length < 2 || tx === lastTx) return; lastTx = tx;
          var s0 = m.t0 + (c.timestamp[0] || 0), en = c.timestamp[1] == null ? Math.min(m.t0 + m.dur, s0 + 4) : m.t0 + c.timestamp[1];
          try { var cu = new VTTCue(s0, Math.max(en, s0 + 1.2), tx); try { cu.line = -3; cu.snapToLines = true; } catch (e2) {} tr.addCue(cu); } catch (x) {} shown.push(tx);
        });
        if (!shown.length && m.text && m.text.trim().length > 1 && m.text.trim() !== lastTx) { lastTx = m.text.trim(); try { var cu2 = new VTTCue(m.t0, m.t0 + m.dur, lastTx); try { cu2.line = -3; } catch (e3) {} tr.addCue(cu2); } catch (x) {} shown.push(lastTx); }
        if (shown.length) show(shown.join(' '));
        if (m.ms > m.dur * 1000 * 1.1) subSt = 'slower than video (' + queue.length + ' waiting) - try the Fast model'; else subSt = 'listening';
        pump();
      }
    };
    w.postMessage({ t: 'init', model: model });
    /* cut the audio at natural pauses (6-25 s) instead of at fixed times, so words are not split */
    sp.onaudioprocess = function (e) {
      if (v.paused || v.seeking) return;
      var d = e.inputBuffer.getChannelData(0), o = new Float32Array(Math.floor(d.length / ratio)), bs = 0;
      for (var i = 0; i < o.length; i++) { var a = Math.floor(i * ratio), b = Math.floor((i + 1) * ratio), sum = 0; for (var k = a; k < b; k++) sum += d[k]; o[i] = sum / (b - a); bs += o[i] * o[i]; }
      var r = Math.sqrt(bs / Math.max(1, o.length)); run = run ? run * 0.97 + r * 0.03 : r;
      quiet = r < Math.max(0.0015, run * 0.25) ? quiet + 1 : 0;
      if (!len) t0 = v.currentTime; parts.push(o); len += o.length;
      if ((len >= 16000 * 6 && quiet >= 5) || len >= 16000 * 25) {
        var all = new Float32Array(len), p = 0; parts.forEach(function (x) { all.set(x, p); p += x.length; }); parts = []; len = 0; quiet = 0;
        var sq = 0; for (var q = 0; q < all.length; q++) sq += all[q] * all[q]; var rm = Math.sqrt(sq / all.length);
        if (rm > 0.0004) {
          var g = Math.min(40, 0.1 / rm); for (var z = 0; z < all.length; z++) { var y = all[z] * g; all[z] = y > 1 ? 1 : y < -1 ? -1 : y; }
          queue.push({ a: all, t0: t0 }); while (queue.length > 8) queue.shift(); pump();
        }
      }
    };
    n.src.connect(sp); sp.connect(AC.destination);
    say('Subtitles starting - first time downloads the AI model', 3500);
  }
  function subSave() {
    if (!sub || !sub.tr.cues || !sub.tr.cues.length) { say('No subtitles yet'); return; }
    var ts = function (t) { var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), s = Math.floor(t % 60), ms = Math.floor((t % 1) * 1000), z = function (x, n) { return ('000' + x).slice(-n); }; return z(h, 2) + ':' + z(m, 2) + ':' + z(s, 2) + ',' + z(ms, 3); };
    var out = [].slice.call(sub.tr.cues).sort(function (a, b) { return a.startTime - b.startTime; }).map(function (c, i) { return (i + 1) + '\n' + ts(c.startTime) + ' --> ' + ts(c.endTime) + '\n' + c.text + '\n'; }).join('\n');
    var a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([out], { type: 'text/plain' })); a.download = 'subtitles.srt'; document.documentElement.appendChild(a); a.click(); a.remove(); say('Saved subtitles.srt');
  }

  /* ---------- panel ---------- */
  function build() {
    var h = '<b>Video Plus</b><div id="vp-st" style="font-size:12px;opacity:.85;margin:4px 0 8px"></div><div>';
    Object.keys(PRE).forEach(function (k) { h += '<button data-p="' + k + '" class="' + ((k === 'Auto') === !!S.auto ? (k === 'Auto' ? 'on' : '') : '') + '">' + (k === 'Auto' ? 'Auto (4K cinema)' : k) + '</button>'; });
    h += '</div>';
    [['ct', 'Contrast', 0.8, 1.4, 0.02], ['sat', 'Color', 0.6, 1.6, 0.02], ['warm', 'Warmth', -1, 1, 0.1], ['br', 'Brightness', 0.6, 1.6, 0.02]].forEach(function (a) {
      h += '<label><span>' + a[1] + '</span><input type="range" data-k="' + a[0] + '" min="' + a[2] + '" max="' + a[3] + '" step="' + a[4] + '" value="' + S[a[0]] + '"></label>';
    });
    h += '<label><span>Sharpness</span><input type="range" data-sh min="0" max="1.5" step="0.05" value="' + (S.shp === undefined ? 0.7 : S.shp) + '"></label>';
    h += '<label><span>Volume boost</span><input type="range" data-g min="1" max="6" step="0.1" value="' + (S.gain || 1) + '"></label>';
    h += '<div><span style="opacity:.7;margin-right:4px">Speed</span>';
    [0.5, 1, 1.25, 1.5, 2, 3, 4].forEach(function (r) { h += '<button data-s="' + r + '" class="' + ((S.rate || 1) === r ? 'on' : '') + '">' + r + 'x</button>'; });
    h += '</div>';
    h += '<div><button id="vp-loud" class="' + (S.gain > 1 ? 'on' : '') + '">Loud audio (3x)</button><button id="vp-hd" class="' + (S.hdx === true ? 'on' : '') + '">HD boost (sharpen)</button><button id="vp-fl">Fullscreen + landscape</button><button id="vp-asp">Aspect: fit</button><button id="vp-th" class="' + (S.theater ? 'on' : '') + '">Theater dim</button><button id="vp-ge" class="' + (S.gest ? 'on' : '') + '">Swipe gestures</button><button id="vp-bo" class="' + (S.boost !== false ? 'on' : '') + '">Load booster</button><button id="vp-ab" class="' + (S.adblock !== false ? 'on' : '') + '">Block ads</button><button id="vp-af" class="' + (S.autofs ? 'on' : '') + '">Auto fullscreen on play</button><button id="vp-ar" class="' + (S.autorot ? 'on' : '') + '">Auto landscape</button><button id="vp-pip">Mini player (in page)</button><button id="vp-bg">Float over apps (PiP)</button><button id="vp-lp">Loop</button><button id="vp-as" class="' + (S.asub !== false ? 'on' : '') + '">Subtitles always on</button><button id="vp-ai" class="' + (sub ? 'on' : '') + '">Auto English subtitles</button><button id="vp-md">AI model: ' + ({ tiny: 'Fast', base: 'Better', small: 'Best (slow)' }[S.model || 'base']) + '</button><button id="vp-ss">Save subtitles (.srt)</button><button id="vp-srt">Load subtitles (.srt)</button><button id="vp-lc">All apps: Live Caption (subtitles)</button><button id="vp-sa">All apps: Sound Amplifier (loud)</button><button id="vp-x">Close</button></div>';
    panel.innerHTML = h; var se = $('#vp-st'); if (se) se.textContent = statText();
    panel.querySelectorAll('[data-p]').forEach(function (b) { b.onclick = function () { Object.assign(S, PRE[b.dataset.p]); applyLook(); build(); panel.classList.add('on'); }; });
    panel.querySelectorAll('[data-sh]').forEach(function (i) { i.oninput = function () { S.shp = parseFloat(i.value); save(); }; });
    $('#vp-md').onclick = function () { var o = ['tiny', 'base', 'small'], i = (o.indexOf(S.model || 'base') + 1) % 3; S.model = o[i]; save(); this.textContent = 'AI model: ' + { tiny: 'Fast', base: 'Better', small: 'Best (slow)' }[S.model]; say('Model ' + S.model + ' - applies next time you start subtitles', 2500); };
    $('#vp-ss').onclick = subSave;
    /* other apps cannot be reached by a page script - hand over to the Pixel system features */
    function sysOpen(action, steps) {
      var gone = false, f = function () { if (document.hidden) gone = true; };
      document.addEventListener('visibilitychange', f);
      try { location.href = 'intent:#Intent;action=' + action + ';end'; } catch (e) {}
      setTimeout(function () { document.removeEventListener('visibilitychange', f); if (!gone) say(steps, 9000); }, 1500);
    }
    $('#vp-lc').onclick = function () { sysOpen('android.settings.ACCESSIBILITY_SETTINGS', 'Live Caption: press a volume key, tap the caption icon. Or Settings > Accessibility > Live Caption'); };
    $('#vp-sa').onclick = function () { sysOpen('android.settings.ACCESSIBILITY_SETTINGS', 'Sound Amplifier: Settings > Accessibility > Sound Amplifier. Plug in headphones first'); };
    $('#vp-as').onclick = function () { S.asub = S.asub === false; save(); this.classList.toggle('on', S.asub !== false); say('Auto subtitles ' + (S.asub !== false ? 'on (any language to English)' : 'off')); };
    $('#vp-ai').onclick = function () { var v = target(); if (!v) { say('No video found'); return; } subOn(v); this.classList.toggle('on', !!sub); };
    $('#vp-loud').onclick = function () {
      var v = target(), btn = this; if (!v) { say('No video found'); return; }
      var g = S.gain > 1 ? 1 : 3;
      ensureBoostable(v, function (ok) {
        if (!ok || !setGain(v, g)) { say('Not possible: this site keeps its audio locked. Use phone volume or headphones.', 3500); return; }
        v.muted = false; try { v.volume = 1; } catch (e) {} S.gain = g; save(); btn.classList.toggle('on', g > 1);
        var sl = panel.querySelector('[data-g]'); if (sl) sl.value = g; say('\uD83D\uDD0A boost ' + Math.round(g * 100) + '%');
      });
    };
    $('#vp-hd').onclick = function () { S.hdx = !(S.hdx === true); save(); this.classList.toggle('on', S.hdx); say('HD boost ' + (S.hdx ? 'on' : 'off')); };
    panel.querySelectorAll('[data-g]').forEach(function (i) { i.oninput = function () {
      var g = parseFloat(i.value), v = target(); S.gain = g; save();
      if (!v) { say('No video found'); return; }
      ensureBoostable(v, function (ok) {
        if (ok && setGain(v, g)) { v.muted = false; try { v.volume = 1; } catch (e) {} say('\uD83D\uDD0A boost ' + Math.round(g * 100) + '%'); } else say('Boost not possible: this site keeps its audio locked. Use phone volume or headphones.', 3500);
      });
    }; });
    panel.querySelectorAll('[data-k]').forEach(function (i) { i.oninput = function () { if (S.auto) { S.ct = AUTO.ct; S.sat = AUTO.sat; S.br = AUTO.br; S.warm = 0; S.auto = false; } S[i.dataset.k] = parseFloat(i.value); applyLook(); }; });
    $('#vp-th').onclick = function () { S.theater = !S.theater; applyLook(); this.classList.toggle('on', S.theater); };
    $('#vp-ge').onclick = function () { S.gest = !S.gest; save(); this.classList.toggle('on', S.gest); say('Gestures ' + (S.gest ? 'on' : 'off')); };
    $('#vp-bo').onclick = function () { S.boost = !(S.boost !== false); save(); this.classList.toggle('on', S.boost); say('Booster ' + (S.boost ? 'on' : 'off')); };
    $('#vp-ab').onclick = function () { S.adblock = !(S.adblock !== false); save(); this.classList.toggle('on', S.adblock); say('Ad block ' + (S.adblock ? 'on' : 'off') + ' - reload page'); };
    $('#vp-af').onclick = function () { S.autofs = !S.autofs; save(); this.classList.toggle('on', S.autofs); };
    $('#vp-ar').onclick = function () { S.autorot = !S.autorot; save(); this.classList.toggle('on', S.autorot); };
    function setFit(v, f) { try { v.style.setProperty('object-fit', f, 'important'); v.style.setProperty('transform', 'none', 'important'); } catch (e) {} }
    var ASPECTS = [
      ['fit',     function (v) { setFit(v, 'contain'); }],
      ['fill',    function (v) { setFit(v, 'cover'); }],
      ['stretch', function (v) { setFit(v, 'fill'); }],
      ['16:9',    function (v) { ratio(v, 16/9); }],
      ['4:3',     function (v) { ratio(v, 4/3); }],
      ['18:9',    function (v) { ratio(v, 18/9); }],
      ['20:9',    function (v) { ratio(v, 20/9); }],
      ['21:9',    function (v) { ratio(v, 21/9); }]
    ];
    function ratio(v, want) {
      // Crop to the chosen ratio: fill the box on the long axis, scale the other axis so the visible frame matches `want`.
      try { v.style.setProperty('object-fit', 'cover', 'important'); } catch (e) {}
      var r = v.getBoundingClientRect(); if (!r.width || !r.height) { try { v.style.setProperty('transform', 'none', 'important'); } catch (e) {} return; }
      var cur = r.width / r.height, sx = 1, sy = 1;
      // object-fit:cover already fills; we crop toward `want` by shrinking the longer side so the ratio reads clearly.
      if (want > cur) sy = cur / want; else sx = want / cur;
      try { v.style.setProperty('transform', 'scale(' + sx.toFixed(4) + ',' + sy.toFixed(4) + ')', 'important'); } catch (e) {}
    }
    function applyAspect(v) { if (!v) return; try { v.style.setProperty('transform-origin', 'center center', 'important'); ASPECTS[(S.aspect || 0) % ASPECTS.length][1](v); } catch (e) {} }
    window.__vpAspect = applyAspect;
    // Re-apply the chosen aspect continuously — sites reset <video> styles on play/seek/fullscreen, which is why it "looked the same".
    // (started once only: this used to add another timer every time the panel was opened)
    if (!window.__vpAspTimer) window.__vpAspTimer = setInterval(function () {
      try {
        if (!S.aspect) return;
        var v = (document.fullscreenElement && document.fullscreenElement.tagName === 'VIDEO') ? document.fullscreenElement : target();
        if (v && v.tagName === 'VIDEO') applyAspect(v);
      } catch (e) {}
    }, 1000);
    $('#vp-asp').onclick = function () {
      S.aspect = ((S.aspect || 0) + 1) % ASPECTS.length; save();
      var v = target(); applyAspect(v);
      this.textContent = 'Aspect: ' + ASPECTS[S.aspect][0];
      say('Aspect: ' + ASPECTS[S.aspect][0]);
    };
    (function () { var b = $('#vp-asp'); if (b) b.textContent = 'Aspect: ' + ASPECTS[(S.aspect || 0) % ASPECTS.length][0]; var v = target(); if (v && S.aspect) applyAspect(v); })();
    $('#vp-fl').onclick = function () { var v = target(); if (!v) { say('No video found'); return; } var box = playerBox(v), fn = box.requestFullscreen || box.webkitRequestFullscreen || box.mozRequestFullScreen; panel.classList.remove('on'); try { var pr = fn.call(box); if (pr && pr.catch) pr.catch(function () { say('Fullscreen blocked'); }); } catch (e) { say('Fullscreen blocked'); } };
    $('#vp-pip').onclick = function () { var v = target(); panel.classList.remove('on'); if (mini) { miniOff(); return; } if (!v) { say('No video found'); return; } miniOn(v); };
    $('#vp-bg').onclick = function () {
      var v = target(); panel.classList.remove('on'); if (!v) { say('No video found'); return; }
      if (mini) miniOff();
      var go = function () {
        var fn = v.requestFullscreen || v.webkitRequestFullscreen || v.mozRequestFullScreen;
        try {
          if (v.requestPictureInPicture && document.pictureInPictureEnabled) { v.requestPictureInPicture().catch(function () { say('PiP blocked - tap the site fullscreen button, then press Home', 3500); }); return; }
          var pr = fn.call(v);
          if (pr && pr.then) pr.then(function () { say('Now press the Home button - only the video will float', 4000); }, function () { say('Fullscreen blocked - tap the site fullscreen button, then press Home', 4000); });
          else say('Now press the Home button - only the video will float', 4000);
        } catch (e) { say('Not allowed - tap the site fullscreen button, then press Home', 4000); }
      };
      if (document.fullscreenElement) { unrot(); try { document.exitFullscreen(); } catch (e) {} setTimeout(go, 350); } else go();
    };
    panel.querySelectorAll('[data-s]').forEach(function (b) { b.onclick = function () {
      var r = parseFloat(b.dataset.s); S.rate = r; save();
      Array.prototype.forEach.call(document.querySelectorAll('video'), function (x) { try { x.playbackRate = r; } catch (e) {} });
      panel.querySelectorAll('[data-s]').forEach(function (o) { o.classList.toggle('on', o === b); }); say('Speed ' + r + 'x');
    }; });
    $('#vp-lp').onclick = function () { var v = target(); if (!v) return; v.loop = !v.loop; this.classList.toggle('on', v.loop); };
    $('#vp-x').onclick = function () { panel.classList.remove('on'); };
    $('#vp-srt').onclick = function () {
      var inp = document.createElement('input'); inp.type = 'file'; inp.accept = '.srt,.vtt,text/plain';
      inp.onchange = function () {
        var f = inp.files[0], v = target(); if (!f || !v) return;
        f.text().then(function (txt) {
          var tp = function (x) { var m = x.trim().replace(',', '.').split(':').map(parseFloat); return m.length === 3 ? m[0] * 3600 + m[1] * 60 + m[2] : m[0] * 60 + m[1]; };
          var tr = v.addTextTrack('subtitles', 'Video Plus', 'en'), n = 0;
          txt.replace(/\r/g, '').split(/\n\n+/).forEach(function (b) {
            var L = b.split('\n').filter(Boolean), i = L.findIndex(function (l) { return l.indexOf('-->') > -1; });
            if (i < 0) return; var p = L[i].split('-->');
            try { tr.addCue(new VTTCue(tp(p[0]), tp(p[1].split(' ')[0]), L.slice(i + 1).join('\n'))); n++; } catch (e) {}
          });
          tr.mode = 'showing'; say(n + ' subtitle lines loaded');
        });
      };
      inp.click();
    };
  }
  var fab = $('#vp-fab'), idleT, panelT;
  function wake() { fab.style.opacity = '1'; clearTimeout(idleT); idleT = setTimeout(function () { if (!panel.classList.contains('on')) fab.style.opacity = '0.12'; }, 3000); }
  function armPanel() { clearTimeout(panelT); panelT = setTimeout(function () { panel.classList.remove('on'); wake(); }, 6000); }
  fab.onclick = function () { if (panel.classList.contains('on')) { panel.classList.remove('on'); wake(); } else { build(); panel.classList.add('on'); fab.style.opacity = '1'; armPanel(); } };
  panel.addEventListener('input', armPanel); panel.addEventListener('click', armPanel);
  ['touchstart', 'mousedown', 'scroll', 'keydown'].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      if (panel.classList.contains('on') && !root.contains(e.target)) { panel.classList.remove('on'); }
      if (panel.classList.contains('on')) { armPanel(); } else { wake(); }
    }, { passive: true, capture: true });
  });
  document.addEventListener('play', wake, true); wake();

  /* ---------- mini player: floats only the video, draggable ---------- */
  var mini = null;
  function miniOff() {
    if (!mini) return; var v = mini.v;
    if (mini.st === null) v.removeAttribute('style'); else v.setAttribute('style', mini.st);
    if (mini.tick) v.removeEventListener('timeupdate', mini.tick); if (mini.bar.parentNode) mini.bar.parentNode.removeChild(mini.bar); mini = null;
  }
  function miniOn(v) {
    if (document.fullscreenElement) { say('Exit fullscreen first'); return; }
    var w = Math.round(Math.min(innerWidth, innerHeight) * 0.62), h = Math.round(w * 9 / 16), bh = 62;
    mini = { v: v, st: v.getAttribute('style'), x: innerWidth - w - 8, y: innerHeight - h - bh - 110 };
    var bar = document.createElement('div'); mini.bar = bar;
    bar.style.cssText = 'all:initial;position:fixed;z-index:2147483647;height:' + bh + 'px;width:' + w + 'px;background:#14161c;color:#fff;font:14px system-ui,sans-serif;display:block;border-radius:8px 8px 0 0;touch-action:none;box-sizing:border-box;padding:0 8px';
    bar.innerHTML = '<div style="display:flex;align-items:center;justify-content:space-between;height:34px"><span style="opacity:.8">\u2630 drag</span><span><b data-a="pp" style="padding:6px 10px">\u23EF</b><b data-a="big" style="padding:6px 10px">\u2922</b><b data-a="x" style="padding:6px 10px">\u2715</b></span></div><div style="display:flex;align-items:center;gap:6px;height:26px;font-size:12px"><span data-t="c">0:00</span><input data-r type="range" min="0" max="1000" value="0" style="flex:1;min-width:0;height:20px;accent-color:#d9b45a"><span data-t="d">0:00</span></div>';
    var rg = bar.querySelector('[data-r]'), tc = bar.querySelector('[data-t="c"]'), td = bar.querySelector('[data-t="d"]'), seeking = false;
    function fm(t) { if (!isFinite(t)) return '--:--'; t = Math.floor(t); var hh = Math.floor(t / 3600), mm = Math.floor(t % 3600 / 60), ss = t % 60; return (hh ? hh + ':' + (mm < 10 ? '0' : '') : '') + mm + ':' + (ss < 10 ? '0' : '') + ss; }
    function tick() { tc.textContent = fm(v.currentTime); td.textContent = isFinite(v.duration) ? '-' + fm(v.duration - v.currentTime) : 'LIVE'; if (!seeking && isFinite(v.duration) && v.duration > 0) rg.value = Math.round(v.currentTime / v.duration * 1000); }
    v.addEventListener('timeupdate', tick); mini.tick = tick; tick();
    ['touchstart', 'touchmove'].forEach(function (ev) { rg.addEventListener(ev, function (e) { e.stopPropagation(); }, { passive: true }); });
    rg.addEventListener('input', function () { seeking = true; if (isFinite(v.duration)) { v.currentTime = rg.value / 1000 * v.duration; tc.textContent = fm(v.currentTime); } });
    rg.addEventListener('change', function () { seeking = false; });
    document.documentElement.appendChild(bar);
    function place() {
      mini.x = Math.max(0, Math.min(innerWidth - w, mini.x)); mini.y = Math.max(0, Math.min(innerHeight - h - bh, mini.y));
      bar.style.left = mini.x + 'px'; bar.style.top = mini.y + 'px';
      v.style.cssText = (mini.st || '') + ';position:fixed!important;left:' + mini.x + 'px!important;top:' + (mini.y + bh) + 'px!important;width:' + w + 'px!important;height:' + h + 'px!important;max-width:none!important;max-height:none!important;margin:0!important;transform:none!important;z-index:2147483646!important;background:#000!important;object-fit:contain!important;';
    }
    place();
    var d = null;
    bar.addEventListener('touchstart', function (e) { var t = e.touches[0]; d = { x: t.clientX - mini.x, y: t.clientY - mini.y }; }, { passive: true });
    bar.addEventListener('touchmove', function (e) { if (!d) return; var t = e.touches[0]; mini.x = t.clientX - d.x; mini.y = t.clientY - d.y; place(); e.preventDefault(); }, { passive: false });
    bar.addEventListener('touchend', function () { d = null; });
    bar.addEventListener('click', function (e) {
      var a = e.target.getAttribute && e.target.getAttribute('data-a');
      if (a === 'pp') { v.paused ? v.play() : v.pause(); }
      else if (a === 'x') { miniOff(); }
      else if (a === 'big') { miniOff(); try { var b = playerBox(v), fn = b.requestFullscreen || b.webkitRequestFullscreen; fn && fn.call(b); } catch (x) {} }
    });
  }
  /* keep the chosen playback speed (sites often reset it) */
  ['loadedmetadata', 'play'].forEach(function (ev) {
    document.addEventListener(ev, function (e) { var v = e.target; if (v instanceof HTMLVideoElement && S.rate && S.rate !== 1 && v.playbackRate !== S.rate) { try { v.playbackRate = S.rate; } catch (x) {} } }, true);
  });

  /* ---------- auto player mode: starts when a video plays ---------- */
  window.__vpBox = playerBox;
  function playerBox(v) {
    var b = v, r = v.getBoundingClientRect(), p = v.parentElement;
    while (p && p !== document.body && p !== document.documentElement) {
      var q = p.getBoundingClientRect();
      if (q.width > r.width * 1.12 || q.height > r.height * 1.12) break;
      b = p; p = p.parentElement;
    }
    return b;
  }
  document.addEventListener('play', function (e) {
    var v = e.target;
    if (!(v instanceof HTMLVideoElement)) return;
    var r = v.getBoundingClientRect();
    if (r.width < 200 || r.height < 110) return;
    scan(); applyLook();
    if (S.boost !== false) {
      Array.prototype.forEach.call(document.querySelectorAll('video'), function (o) { if (o !== v) { try { if (!o.paused) o.pause(); o.preload = 'none'; } catch (x) {} } });
      try { v.preload = 'auto'; } catch (x) {}
    }
    if (!S.autofs || document.fullscreenElement || mini) return;
    if (isFinite(v.duration) && v.duration < 20) return;            // skip short previews
    if (v.muted && v.loop) return;                                     // skip looping previews
    if (navigator.userActivation && !navigator.userActivation.isActive) return;  // only after a real tap
    var box = playerBox(v), fn = box.requestFullscreen || box.webkitRequestFullscreen || box.mozRequestFullScreen;
    if (!fn) return;
    try {
      var pr = fn.call(box);
      if (pr && pr.catch) pr.catch(function () { say('Auto fullscreen blocked - use the site fullscreen button'); });
    } catch (err) {}
  }, true);
  /* forced landscape that works even when the phone's auto-rotate is off */
  var rot = false, rotEl = null;
  function forceLandscape(el) {
    if (rot || !el || innerWidth > innerHeight) return;
    rotEl = el; el.__vps = el.getAttribute('style');
    el.style.cssText += ';position:fixed!important;top:0!important;left:0!important;width:100vh!important;height:100vw!important;max-width:none!important;max-height:none!important;margin:0!important;transform-origin:0 0!important;transform:translateX(100vw) rotate(90deg)!important;';
    rot = true;
  }
  function unrot() {
    if (rot && rotEl) { if (rotEl.__vps === null || rotEl.__vps === undefined) rotEl.removeAttribute('style'); else rotEl.setAttribute('style', rotEl.__vps); }
    rot = false; rotEl = null;
  }
  function lockLand() {
    var fe = document.fullscreenElement; if (!fe || fe.tagName === 'VIDEO') return;
    var vid = fe.tagName === 'VIDEO' ? fe : fe.querySelector('video');
    if (vid && vid.videoHeight > vid.videoWidth) return;
    var done = function () { setTimeout(function () { if (document.fullscreenElement === fe && innerHeight > innerWidth) forceLandscape(fe); }, 400); };
    try { if (screen.orientation && screen.orientation.lock) screen.orientation.lock('landscape').then(done, done); else done(); } catch (e) { done(); }
  }
  document.addEventListener('fullscreenchange', function () {
    setTimeout(function () { try { if (window.__vpAspect && S.aspect) window.__vpAspect(target()); } catch (e) {} }, 300);
    var fe = document.fullscreenElement;
    if (fe && fe.tagName !== 'VIDEO') fe.appendChild(root); else document.documentElement.appendChild(root);
    if (fe) { if (S.autorot) lockLand(); }
    else { unrot(); if (screen.orientation && screen.orientation.unlock) { try { screen.orientation.unlock(); } catch (err) {} } }
  });
  window.addEventListener('resize', function () {
    if (rot && innerWidth > innerHeight) unrot();
    else if (!rot && S.autorot && document.fullscreenElement && innerHeight > innerWidth) lockLand();
  });

  /* ---------- own player bar: time, time left and seek - shows when you tap the video (works in fullscreen / landscape too) ---------- */
  var barEl = $('#vp-bar'), barR = $('#vp-br'), barC = $('#vp-bc'), barD = $('#vp-bd'), barV = null, barT, barDrag = false, tap = null;
  function fmt(t) { if (!isFinite(t)) return '--:--'; t = Math.floor(Math.max(0, t)); var hh = Math.floor(t / 3600), mm = Math.floor(t % 3600 / 60), ss = t % 60; return (hh ? hh + ':' + (mm < 10 ? '0' : '') : '') + mm + ':' + (ss < 10 ? '0' : '') + ss; }
  function barUpdate() {
    var v = barV; if (!v) return;
    barC.textContent = fmt(v.currentTime);
    barD.textContent = isFinite(v.duration) ? '-' + fmt(v.duration - v.currentTime) : 'LIVE';
    if (!barDrag && isFinite(v.duration) && v.duration > 0) barR.value = Math.round(v.currentTime / v.duration * 1000);
  }
  function isPrev(v) { return !!(v.loop || v.__vpNoCore || (v.muted && v.autoplay) || (isFinite(v.duration) && v.duration < 90)); }
  function barPlace(v) {
    var st = barEl.style, sm = isPrev(v) && !document.fullscreenElement && !rot;
    barEl.classList.toggle('vp-s', sm);
    if (sm) {
      var q = v.getBoundingClientRect(), qw = Math.max(150, q.width - 16), ql = Math.max(0, Math.min(q.left + 8, innerWidth - qw));
      st.left = ql + 'px'; st.right = 'auto'; st.width = qw + 'px'; st.bottom = 'auto'; st.height = '28px'; st.top = Math.min(innerHeight - 32, Math.max(0, q.bottom - 34)) + 'px';
      return;
    }
    st.height = '';
    if (document.fullscreenElement || rot) { st.left = '8px'; st.right = '8px'; st.width = 'auto'; st.top = 'auto'; st.bottom = '14px'; }
    else {
      var r = v.getBoundingClientRect(), w = Math.max(220, Math.min(r.width, innerWidth)), l = Math.max(0, Math.min(r.left, innerWidth - w));
      var tp = Math.min(innerHeight - 52, Math.max(0, r.bottom - 52));
      st.left = l + 'px'; st.right = 'auto'; st.width = w + 'px'; st.bottom = 'auto'; st.top = tp + 'px';
    }
  }
  function showBar(v, ms) {
    if (!v) return; barV = v; barPlace(v); barEl.style.display = 'flex'; barUpdate();
    clearTimeout(barT); barT = setTimeout(function () { if (!barDrag) barEl.style.display = 'none'; }, ms || 4500);
  }
  setInterval(function () {
    if (barEl.style.display !== 'flex' || !barV) return;
    var r = barV.getBoundingClientRect();
    if (r.bottom < 40 || r.top > innerHeight - 40) { if (!barDrag) barEl.style.display = 'none'; return; }
    barPlace(barV);
  }, 120);
  barR.addEventListener('input', function () { barDrag = true; var v = barV; if (v && isFinite(v.duration)) { v.currentTime = barR.value / 1000 * v.duration; barUpdate(); } showBar(v, 6000); });
  barR.addEventListener('change', function () { barDrag = false; });
  $('#vp-bpp').onclick = function () { var v = barV; if (v) { v.paused ? v.play() : v.pause(); showBar(v); } };
  document.addEventListener('timeupdate', function (e) { if (e.target === barV && barEl.style.display === 'flex') barUpdate(); }, true);
  document.addEventListener('touchstart', function (e) { var t = e.touches[0]; tap = (e.touches.length === 1 && t) ? { x: t.clientX, y: t.clientY, t: Date.now(), in: root.contains(e.target) } : null; }, { passive: true, capture: true });
  document.addEventListener('touchend', function (e) {
    if (!tap || tap.in) { tap = null; return; } var t = e.changedTouches[0], k = tap; tap = null;
    if (!t || Math.abs(t.clientX - k.x) > 12 || Math.abs(t.clientY - k.y) > 12 || Date.now() - k.t > 500) return;
    var h = hit(t); if (h) showBar(h.v);
  }, { passive: true, capture: true });
  document.addEventListener('play', function (e) {
    var v = e.target; if (!(v instanceof HTMLVideoElement) || S.asub === false || v.__vpAsub) return;
    v.__vpAsub = 1;
    setTimeout(function () {
      if (sub || v.paused || v.loop || (v.muted && v.autoplay)) { v.__vpAsub = 0; return; }
      var r = v.getBoundingClientRect(); if (!document.fullscreenElement && (r.height < innerHeight * 0.3 || r.width < 200)) { v.__vpAsub = 0; return; }
      try { subOn(v); } catch (x) {}
    }, 3000);
  }, true);
  document.addEventListener('play', function (e) { if (e.target instanceof HTMLVideoElement && !isPrev(e.target) && e.target.getBoundingClientRect().width > 200) showBar(e.target, 3000); }, true);
  document.addEventListener('fullscreenchange', function () {
    setTimeout(function () { try { if (window.__vpAspect && S.aspect) window.__vpAspect(target()); } catch (e) {} }, 300); var v = barV || target(); if (v) setTimeout(function () { showBar(v, 3500); }, 500); });

  /* ---------- gestures (work even if the site covers the video with its own layers) ---------- */
  var g = null, lastTap = 0, lastX = 0;
  function layoutRect(v) {
    /* bounding box ignoring any aspect scale transform, so gestures cover the whole video */
    var r = v.getBoundingClientRect();
    var tr = v.style.transform;
    if (tr && tr.indexOf('scale') >= 0) {
      var m = tr.match(/scale\(([^,\)]+),?([^\)]*)\)/);
      if (m) {
        var sx = parseFloat(m[1]) || 1, sy = parseFloat(m[2] || m[1]) || 1;
        var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        var fw = r.width / sx, fh = r.height / sy;
        return { left: cx - fw / 2, right: cx + fw / 2, top: cy - fh / 2, bottom: cy + fh / 2, width: fw, height: fh };
      }
    }
    return r;
  }
  function hit(t) {
    for (var i = 0; i < vids.length; i++) {
      var r = layoutRect(vids[i]);
      if (t.clientX >= r.left && t.clientX <= r.right && t.clientY >= r.top && t.clientY <= r.bottom) return { v: vids[i], r: r };
    }
    return null;
  }
  document.addEventListener('touchstart', function (e) {
    if (!S.gest || e.touches.length !== 1 || root.contains(e.target)) { g = null; return; }
    var t = e.touches[0], h = hit(t); if (!h) { g = null; return; }
    /* tall videos in a swipe feed (shorts, reels): the swipe belongs to the site, so gestures stay out of the way */
    if (!document.fullscreenElement && !rot && h.r.height > h.r.width * 1.2 && h.r.height > innerHeight * 0.6) { g = null; return; }
    g = { v: h.v, r: h.r, x: t.clientX, y: t.clientY, mode: null, t0: h.v.currentTime, vol: h.v.volume, br: S.br, left: (rot ? (t.clientY - h.r.top) < h.r.height / 2 : (t.clientX - h.r.left) < h.r.width / 2), rot: rot, dt: 0, sys: -1,
      lock: !!(document.fullscreenElement || rot || (h.r.width > h.r.height * 1.2 && h.r.height >= innerHeight * 0.4)) };
    try { if (window.VPApp && VPApp.getVol) g.sys = VPApp.getVol(); } catch (e) {}
  }, { passive: true, capture: true });
  document.addEventListener('touchmove', function (e) {
    if (!g) return; var t = e.touches[0], sx = t.clientX - g.x, sy = t.clientY - g.y, dx = g.rot ? sy : sx, dy = g.rot ? -sx : sy;
    if (g.lock && e.cancelable) e.preventDefault();
    if (!g.mode) {
      if (Math.abs(dx) < 16 && Math.abs(dy) < 16) return;
      var horiz = Math.abs(dx) > Math.abs(dy);
      // A small video inside a page: an up/down swipe is a normal page scroll, and a sideways swipe only seeks while it is playing.
      if (!g.lock && (!horiz || g.v.paused)) { g = null; return; }
      g.mode = horiz ? 'seek' : (g.left ? 'bright' : 'vol');
    }
    e.preventDefault(); e.stopPropagation();
    if (g.mode === 'seek' && g.v.duration) { g.dt = dx / (g.rot ? g.r.height : g.r.width) * 120; say((g.dt >= 0 ? '+' : '-') + Math.abs(Math.round(g.dt)) + 's'); }
    else if (g.mode === 'vol') {
      var dv = -dy / ((g.rot ? g.r.width : g.r.height) * 0.8);
      g.v.muted = false;
      if (g.sys >= 0) { try { g.v.volume = 1; } catch (e) {} var nv = Math.min(1, Math.max(0, g.sys + dv)); VPApp.setVol(nv); say('\uD83D\uDD0A ' + Math.round(nv * 100) + '%'); }
      else { g.v.volume = Math.min(1, Math.max(0, g.vol + dv)); say('\uD83D\uDD0A ' + Math.round(g.v.volume * 100) + '%'); }
    }
    else if (g.mode === 'bright') { S.br = Math.min(1.8, Math.max(0.2, g.br - dy / ((g.rot ? g.r.width : g.r.height) * 0.8))); applyLook(); say('\u2600 ' + Math.round(S.br * 100) + '%'); }
  }, { passive: false, capture: true });
  document.addEventListener('touchend', function (e) {
    if (!g) return; var m = g.mode, gg = g; g = null;
    if (m === 'seek' && gg.v.duration) { gg.v.currentTime = Math.min(gg.v.duration, Math.max(0, gg.t0 + gg.dt)); return; }
    if (m) return;
    var t = e.changedTouches[0], now = Date.now(), x = gg.rot ? (t.clientY - gg.r.top) / gg.r.height : (t.clientX - gg.r.left) / gg.r.width;
    if (now - lastTap < 320 && Math.abs(x - lastX) < 0.2 && (x < 0.35 || x > 0.65)) {
      gg.v.currentTime = Math.max(0, gg.v.currentTime + (x < 0.5 ? -10 : 10)); say(x < 0.5 ? '\u23EA -10s' : '+10s \u23E9'); lastTap = 0;
    } else { lastTap = now; lastX = x; }
  }, { passive: true, capture: true });
})();
