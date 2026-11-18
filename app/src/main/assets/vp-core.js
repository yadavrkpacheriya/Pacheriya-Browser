(function () {
  if (window.__vpCore) return; window.__vpCore = 1;
  /* 0. speed: warm connections to the sites this page used last time, and prefetch a link when it is touched */
  try {
    if (window === window.top) {
      var pre = (window.VPApp && VPApp.pre) ? VPApp.pre(location.hostname) : '';
      if (pre) pre.split(',').forEach(function (x, i) {
        var l = document.createElement('link'); l.rel = i < 4 ? 'preconnect' : 'dns-prefetch'; l.href = x;
        (document.head || document.documentElement).appendChild(l);
      });
    }
    document.addEventListener('touchstart', function (e) {
      var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
      if (!a || a.__vpPf) return;
      var h = a.href; if (!/^https?:/.test(h) || h === location.href) return;
      a.__vpPf = 1;
      var l = document.createElement('link'); l.rel = 'prefetch'; l.href = h;
      (document.head || document.documentElement).appendChild(l);
    }, { passive: true, capture: true });
  } catch (e) {}
  /* 1. background play: only while the app is in the background, pages are told they are still visible */
  window.__vpBg = false;
  var bg = function () { try { return window.__vpBg || window.top.__vpBg; } catch (e) { return window.__vpBg; } };
  try {
    var dh = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden'), dv = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
    Object.defineProperty(document, 'hidden', { get: function () { return bg() ? false : (dh && dh.get ? dh.get.call(document) : false); }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function () { return bg() ? 'visible' : (dv && dv.get ? dv.get.call(document) : 'visible'); }, configurable: true });
    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (e) { document.addEventListener(e, function (ev) { if (bg()) ev.stopImmediatePropagation(); }, true); });
    window.addEventListener('blur', function (ev) { if (bg() && (ev.target === window || ev.target === document)) ev.stopImmediatePropagation(); }, true);
    window.addEventListener('pagehide', function (ev) { if (bg()) ev.stopImmediatePropagation(); }, true);
  } catch (e) {}
  /* 2. tell the app what is playing and which video files the page uses */
  setInterval(function () {
    try {
      if (!window.VPApp) return;
      var vs = document.getElementsByTagName('video'), pl = false;
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (!v.paused && !v.ended && v.readyState > 2) pl = true;
        var s = v.currentSrc;
        if (s && s.indexOf('http') === 0 && !/googlevideo\.com/.test(s)) VPApp.media(s);
      }
      var as = document.getElementsByTagName('audio');
      for (var j = 0; j < as.length; j++) { if (!as[j].paused && !as[j].ended && as[j].readyState > 2) pl = true; }
      VPApp.playing(pl);
    } catch (e) {}
  }, 1500);
  /* 3. picture-in-picture: make the playing video fill the small window */
  window.__vpPip = function (on) {
    var old = document.getElementById('__vpPipStyle'); if (old) old.remove();
    var marked = document.querySelectorAll('.__vpPipV'); for (var k = 0; k < marked.length; k++) marked[k].classList.remove('__vpPipV');
    if (!on) return;
    var best = null, ba = 0, vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) { var r = vs[i].getBoundingClientRect(), a = r.width * r.height; if (!vs[i].paused && a >= ba) { best = vs[i]; ba = a; } }
    if (!best) return;
    best.classList.add('__vpPipV');
    var st = document.createElement('style'); st.id = '__vpPipStyle';
    st.textContent = 'html,body{overflow:hidden!important}.__vpPipV{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;max-width:none!important;max-height:none!important;transform:none!important}';
    document.documentElement.appendChild(st);
  };
  /* 5. remove common page restrictions: forced no-zoom, blocked copy / select */
  try {
    ['copy', 'contextmenu', 'selectstart'].forEach(function (e) { document.addEventListener(e, function (ev) { ev.stopImmediatePropagation(); }, true); });
    /* page zoom is left exactly as each site sets it: forcing zoom on made pages shrink or overflow */
  } catch (e) {}
  /* 4. YouTube: strip ad data and skip whatever still shows (best effort - YouTube changes often) */
  if (/(^|\.)youtube\.com$/.test(location.hostname)) {
    var strip = function (o) {
      try {
        if (!o || typeof o !== 'object') return o;
        if (o.adPlacements) o.adPlacements = [];
        if (o.playerAds) o.playerAds = [];
        if (o.adSlots) o.adSlots = [];
        if (o.playerResponse) strip(o.playerResponse);
        if (o.auxiliaryUi && o.auxiliaryUi.messageRenderers) { /* keep */ }
      } catch (e) {}
      return o;
    };
    try {
      var _p = JSON.parse;
      JSON.parse = function (t, r) { var o = _p.call(this, t, r); try { if (o && typeof o === 'object' && (o.adPlacements || o.playerAds || o.adSlots || o.playerResponse)) strip(o); } catch (e) {} return o; };
    } catch (e) {}
    try {
      var _f = window.fetch;
      window.fetch = function () {
        var a = arguments, u = a[0] && a[0].url ? a[0].url : a[0], p = _f.apply(this, a);
        if (typeof u === 'string' && u.indexOf('/youtubei/v1/player') > -1) {
          return p.then(function (r) {
            return r.clone().text().then(function (t) {
              try { var o = _p.call(JSON, t); strip(o); t = JSON.stringify(o); } catch (e) {}
              return new Response(t, { status: r.status, statusText: r.statusText, headers: r.headers });
            });
          });
        }
        return p;
      };
    } catch (e) {}
    try {
      var val;
      Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true, get: function () { return val; }, set: function (v) { val = strip(v); } });
    } catch (e) {}
    setInterval(function () {
      try {
        var pl = document.querySelector('.html5-video-player');
        if (pl && pl.classList.contains('ad-showing')) {
          var sk = document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-container button');
          if (sk) sk.click();
          var v = document.querySelector('video.html5-main-video');
          if (v && isFinite(v.duration) && v.duration > 0) { v.muted = true; v.playbackRate = 16; v.currentTime = Math.max(0, v.duration - 0.05); }
        }
      } catch (e) {}
    }, 400);
  }
})();
