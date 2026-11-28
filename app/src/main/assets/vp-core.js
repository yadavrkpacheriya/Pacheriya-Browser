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
    /* preconnect to the hosts this page's own resources use, so images/scripts start without a fresh handshake */
    function warm(h){ if(!h||warm.s[h])return; warm.s[h]=1; var l=document.createElement('link'); l.rel='preconnect'; l.href=h; (document.head||document.documentElement).appendChild(l); }
    warm.s={};
    if (window === window.top) {
      try {
        var seen=0;
        document.querySelectorAll('script[src],link[href],img[src]').forEach(function(n){ if(seen>12)return; var u=n.src||n.href; try{ var o=new URL(u,location.href); if(o.origin!==location.origin){ warm(o.origin); seen++; } }catch(e){} });
      } catch(e){}
    }
    /* touch or hover a link: start fetching it before the tap completes */
    /* only the CONNECTION is warmed (never the page itself): fetching a link you merely touched could trigger log-out / delete / add-to-cart links */
    function pf(a){ if(!a||a.__vpPf)return; var h=a.href; if(!/^https?:/.test(h))return; a.__vpPf=1; try{ var o=new URL(h,location.href); if(o.origin!==location.origin) warm(o.origin); }catch(e){} }
    document.addEventListener('touchstart', function (e) { var a=e.target&&e.target.closest?e.target.closest('a[href]'):null; pf(a); }, { passive: true, capture: true });
    document.addEventListener('mouseover', function (e) { var a=e.target&&e.target.closest?e.target.closest('a[href]'):null; pf(a); }, { passive: true, capture: true });
  } catch (e) {}
  /* 0b. video previews on touch screens: the thumbnail nearest the middle of the screen plays its preview, like a mouse hover would */
  try {
    var pvOn = function () { if (typeof window.__vpPrevOn === 'boolean') return window.__vpPrevOn; try { return !!(window.VPApp && VPApp.prev && VPApp.prev()); } catch (e) { return false; } };
    var DUR = /(^|\s)\d{1,2}:\d{2}(:\d{2})?(\s|$)/;
    var cur = null, ov = null, cands = [], busy = 0;
    var fire = function (el, types) {
      var r = el.getBoundingClientRect();
      types.forEach(function (t) {
        try {
          var bub = !(t === 'mouseenter' || t === 'mouseleave' || t === 'pointerenter' || t === 'pointerleave');
          var o = { bubbles: bub, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, pointerType: 'mouse' };
          el.dispatchEvent(t.indexOf('pointer') === 0 && window.PointerEvent ? new PointerEvent(t, o) : new MouseEvent(t, o));
        } catch (e) {}
      });
    };
    var isCard = function (a) {
      if (!a || a.__vpNo) return false;
      var img = a.querySelector('img'); if (!img) return false;
      var r = img.getBoundingClientRect(); if (r.width < 110 || r.height < 70 || r.width / r.height < 1.1 || r.width / r.height > 2.3) return false;
      var box = a.parentElement && a.parentElement.parentElement ? a.parentElement.parentElement : a;
      return DUR.test(a.innerText || '') || DUR.test(box.innerText || '') || !!a.querySelector('video') || /preview|webm|mp4|thumbs/i.test(a.outerHTML.slice(0, 600));
    };
    var previewUrl = function (a) {
      var img = a.querySelector('img'), els = [img, a, a.parentElement, a.parentElement && a.parentElement.parentElement];
      for (var i = 0; i < els.length; i++) {
        var e = els[i]; if (!e || !e.attributes) continue;
        for (var j = 0; j < e.attributes.length; j++) {
          var v = e.attributes[j].value;
          if (v && v.length < 600 && /\.(mp4|webm)(\?|#|$)/i.test(v) && !/^javascript/i.test(v)) { try { return new URL(v, location.href).href; } catch (x) {} }
        }
      }
      return '';
    };
    var stop = function () {
      if (!cur) return;
      var a = cur; cur = null;
      if (ov && ov.parentNode) ov.parentNode.removeChild(ov); ov = null;
      Array.prototype.forEach.call(a.querySelectorAll('video'), function (v) { try { v.pause(); } catch (e) {} });
      var img = a.querySelector('img');
      [img, a, a.parentElement].forEach(function (el) { if (el) fire(el, ['pointerout', 'pointerleave', 'mouseout', 'mouseleave']); });
    };
    var start = function (a) {
      stop(); cur = a;
      var img = a.querySelector('img');
      [img, a, a.parentElement].forEach(function (el) { if (el) fire(el, ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'mousemove']); });
      var vids = a.querySelectorAll('video');
      Array.prototype.forEach.call(vids, function (v) { try { v.muted = true; v.loop = true; v.playsInline = true; v.__vpUserOk = 1; v.play(); } catch (e) {} });
      var url = previewUrl(a);
      if (url && !vids.length) {
        var host = img.parentElement || a;
        if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
        var v = document.createElement('video');
        v.src = url; v.muted = true; v.loop = true; v.autoplay = true; v.playsInline = true; v.setAttribute('playsinline', '');
        v.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover;z-index:5;pointer-events:none;background:#000';
        v.__vpNoCore = 1; host.appendChild(v); ov = v;
        try { v.play(); } catch (e) {}
      }
    };
    var scan = function () {
      cands = Array.prototype.filter.call(document.querySelectorAll('a[href]'), function (a) { return a.__vpCard !== undefined ? a.__vpCard : (a.__vpCard = isCard(a)); });
    };
    var pick = function () {
      if (!pvOn() || document.hidden) { stop(); return; }
      var vh = window.innerHeight, best = null, bd = 1e9;
      for (var i = 0; i < cands.length; i++) {
        var r = cands[i].getBoundingClientRect();
        if (r.bottom < vh * 0.2 || r.top > vh * 0.8 || r.height < 40) continue;
        var d = Math.abs((r.top + r.bottom) / 2 - vh / 2);
        if (d < bd) { bd = d; best = cands[i]; }
      }
      if (best !== cur) { if (best) start(best); else stop(); }
    };
    var sched = function () { if (busy) return; busy = 1; setTimeout(function () { busy = 0; pick(); }, 250); };
    var rescan = function () { cands.forEach(function (a) { a.__vpCard = undefined; }); scan(); sched(); };
    if (window === window.top) {
      window.addEventListener('scroll', sched, { passive: true });
      document.addEventListener('touchend', sched, { passive: true, capture: true });
      document.addEventListener('DOMContentLoaded', function () { setTimeout(rescan, 600); });
      window.addEventListener('load', function () { setTimeout(rescan, 800); });
      setInterval(function () { if (pvOn()) { scan(); pick(); } }, 2500);
    }
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
        if (!v.paused && !v.ended && v.readyState > 2 && !v.loop && !(v.muted && !(v.duration > 90))) pl = true;
        var s = v.currentSrc;
        if (s && s.indexOf('http') === 0 && !/googlevideo\.com/.test(s)) VPApp.media(s);
      }
      var as = document.getElementsByTagName('audio');
      for (var j = 0; j < as.length; j++) { if (!as[j].paused && !as[j].ended && as[j].readyState > 2) pl = true; }
      VPApp.playing(pl);
    } catch (e) {}
  }, 1500);
  ['play','playing'].forEach(function (ev) { document.addEventListener(ev, function (e) { try { var v = e.target; if (window.VPApp && v && v.tagName === 'VIDEO' && !v.loop && !(v.muted && !(v.duration > 90))) VPApp.playing(true); } catch (x) {} }, true); });
  /* 3. picture-in-picture: make the playing video fill the small window */
  var PIPC = '__vpPipV', PIPA = '__vpPipA', PIPH = '__vpPipH';
  function pipClear() {
    var old = document.getElementById('__vpPipStyle'); if (old) old.remove();
    [PIPC, PIPA, PIPH].forEach(function (c) { var m = document.querySelectorAll('.' + c); for (var k = 0; k < m.length; k++) m[k].classList.remove(c); });
  }
  function pipFor(el) {
    pipClear();
    el.classList.add(PIPC);
    for (var p = el; p && p !== document.documentElement; p = p.parentElement) {
      if (p !== el) p.classList.add(PIPA);
      var par = p.parentElement; if (!par) break;
      for (var q = par.firstElementChild; q; q = q.nextElementSibling) if (q !== p && !/^(SCRIPT|STYLE|LINK|META)$/.test(q.tagName)) q.classList.add(PIPH);
    }
    var st = document.createElement('style'); st.id = '__vpPipStyle';
    st.textContent = 'html,body{overflow:hidden!important;background:#000!important}.' + PIPH + '{display:none!important}.' + PIPA + '{transform:none!important;filter:none!important;contain:none!important;perspective:none!important;will-change:auto!important;overflow:visible!important;clip-path:none!important;margin:0!important;padding:0!important;border:0!important;opacity:1!important;visibility:visible!important}.' + PIPC + '{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;margin:0!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;max-width:none!important;max-height:none!important;transform:none!important;border:0!important;display:block!important;visibility:visible!important}';
    document.documentElement.appendChild(st);
  }
  function pipSelf() {
    var best = null, ba = 0, vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) { var v = vs[i], r = v.getBoundingClientRect(), a = r.width * r.height; if (!v.paused && !v.loop && !(v.muted && !(v.duration > 90)) && a >= ba) { best = v; ba = a; } }
    if (!best) return false;
    pipFor(best);
    try { if (window.parent !== window) window.parent.postMessage({ __vpPipUp: 1 }, '*'); } catch (e) {}
    return true;
  }
  window.__vpPip = function (on) {
    if (!on) pipClear(); else pipSelf();
    try { var fr = document.getElementsByTagName('iframe'); for (var i = 0; i < fr.length; i++) { try { fr[i].contentWindow.postMessage({ __vpPipSet: on ? 1 : 0 }, '*'); } catch (e) {} } } catch (e) {}
  };
  window.addEventListener('message', function (e) {
    var d = e.data; if (!d || typeof d !== 'object') return;
    if (d.__vpPipSet !== undefined) { window.__vpPip(!!d.__vpPipSet); }
    else if (d.__vpPipUp) {
      var fr = document.getElementsByTagName('iframe');
      for (var i = 0; i < fr.length; i++) if (fr[i].contentWindow === e.source) { pipFor(fr[i]); try { if (window.parent !== window) window.parent.postMessage({ __vpPipUp: 1 }, '*'); } catch (x) {} break; }
    }
  });
  /* 3b. in-player video ads: skip them the moment they appear ("Visit advertiser" / "Skip ad" overlays).
     Runs only while the page has a short video, so ordinary pages pay nothing. */
  setInterval(function () {
    try {
      var vs = document.getElementsByTagName('video'), sv = [];
      for (var a = 0; a < vs.length; a++) { var d = vs[a].duration; if (isFinite(d) && d > 0 && d < 130 && !vs[a].loop && !vs[a].paused) sv.push(vs[a]); }
      if (!sv.length) return;
      var els = document.querySelectorAll('a,button,span,div'), hit = null;
      for (var i = 0; i < els.length && !hit; i++) {
        var e = els[i]; if (e.childElementCount > 2) continue;
        var tx = (e.textContent || '').trim();
        if (tx.length > 22 || tx.length < 6) continue;
        if (/^(skip ads?( ?[\u203a>\u00bb])?|close ad)$/i.test(tx)) { var r0 = e.getBoundingClientRect(); if (r0.width > 0 && r0.height > 0) { try { e.click(); } catch (x) {} } }
        else if (/^(visit advertiser|ad \d+ of \d+)$/i.test(tx)) { var r1 = e.getBoundingClientRect(); if (r1.width > 0 && r1.height > 0) hit = e; }
      }
      if (!hit) return;
      var p = hit, vid = null;
      for (var k = 0; k < 7 && p && !vid; k++) { p = p.parentElement; if (p) { for (var j = 0; j < sv.length; j++) if (p.contains(sv[j])) { vid = sv[j]; break; } } }
      if (vid) { vid.muted = true; if (vid.duration - vid.currentTime > 0.3) { try { vid.currentTime = vid.duration - 0.1; } catch (x) {} } }
    } catch (e) {}
  }, 500);
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
