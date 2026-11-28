(function () {
  if (window.__vpReader) return;
  var R = window.__vpReader = { on: false, tl: '', cbs: {}, n: 0 };
  var theme = 0, fs = 19;
  /* [background, text, border, linkColor, name] */
  var TH = [
    ['#fbfbf8', '#222222', '#555555', '#1a56c4', 'Light'],
    ['#f4ecd8', '#3b3226', '#6b5d48', '#8a5a1a', 'Sepia'],
    ['#15161a', '#d8d8d8', '#9aa0aa', '#8ab4ff', 'Dark'],
    ['#000000', '#c8c8c8', '#444444', '#6ea8ff', 'Black (OLED)'],
    ['#e8f0e4', '#24301f', '#5a6b4f', '#2c6e2c', 'Mint'],
    ['#fff8e1', '#2b2416', '#8a7a4a', '#9a5a00', 'Cream']
  ];
  /* ---- translation helper (native does the request) ---- */
  window.__vpTr = function (id, txt) { var f = R.cbs[id]; delete R.cbs[id]; if (f) f(txt); };
  function tr(lines, tl, done) {
    var chunks = [], cur = [], len = 0;
    lines.forEach(function (l, i) { if (len + l.length > 3000 && cur.length) { chunks.push(cur); cur = []; len = 0; } cur.push(i); len += l.length + 1; });
    if (cur.length) chunks.push(cur);
    var out = new Array(lines.length), left = chunks.length;
    if (!left) return done(out);
    chunks.forEach(function (idxs) {
      var id = ++R.n;
      R.cbs[id] = function (res) {
        var parts = (res || '').split('\n');
        idxs.forEach(function (k, j) { out[k] = parts.length === idxs.length ? parts[j] : null; });
        if (parts.length !== idxs.length) idxs.forEach(function (k) { out[k] = null; });
        if (--left === 0) done(out);
      };
      try { VPApp.trAsync(id, idxs.map(function (k) { return lines[k].replace(/\n/g, ' '); }).join('\n'), tl); } catch (e) { R.cbs[id](''); }
    });
  }
  /* ---- translate the page in place ---- */
  R.translatePage = function (tl) {
    if (!tl) return;
    var l = (document.documentElement.lang || '').toLowerCase();
    if (l && l.slice(0, 2) === tl) return;
    var nodes = [], w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    while (w.nextNode()) {
      var n = w.currentNode, p = n.parentNode;
      if (!p || /^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT|CODE|PRE)$/.test(p.tagName)) continue;
      var t = n.nodeValue.trim();
      if (t.length < 2 || !/[^\s\d\W]/.test(t)) continue;
      if (n.__vpOrig === undefined) nodes.push(n);
      if (nodes.length > 1500) break;
    }
    if (!nodes.length) return;
    tr(nodes.map(function (n) { return n.nodeValue.trim(); }), tl, function (out) {
      nodes.forEach(function (n, i) { if (out[i]) { n.__vpOrig = n.nodeValue; n.nodeValue = n.nodeValue.replace(n.nodeValue.trim(), out[i]); } });
    });
  };
  /* ---- reading mode ---- */
  function txt(e) { return e.innerText || e.textContent || ''; }
  /* ad-blocker nags / paywall notices that masquerade as the article — never pick these */
  var JUNK = /disable your ad ?block|turn off your ad ?block|whitelist|adblock detected|please support our (web)?site|continue enjoying free|subscribe to (read|continue)|you (have|'ve) reached your|create a free account to (read|continue)|log ?in to (read|continue)/i;
  function isJunk(el) {
    if (el.matches && el.matches('[class*="ad-"],[class*="-ad"],[class*="adsbygoogle"],[id*="ad-"],[data-ad],.google-auto-placed')) return true;
    var t = (el.innerText || el.textContent || '').trim(); return t.length < 600 && JUNK.test(t);
  }
  var AD_SEL = 'ins.adsbygoogle,[class*="adsbygoogle"],[class*="ad-slot"],[class*="ad-container"],[class*="ad-wrapper"],[class*="advertisement"],[id*="ad-slot"],[id*="ad-container"],[data-ad],.google-auto-placed';
  function best(doc, relax) {
    var c = doc.querySelectorAll('article, main, [role=main], .post, .entry-content, .article, #content, .content, .chapter-content, .chapter-body, #chapter-content, .chapter, .reading-content, .text-left, .entry, section, div'), top = null, sc = 0;
    for (var i = 0; i < c.length; i++) {
      if (isJunk(c[i])) continue;
      // never pick the browser's own injected UI (it holds CSS/script text that would show as gibberish)
      if (c[i].id && /^vp(lus)?-/.test(c[i].id)) continue;
      if (c[i].closest && c[i].closest('#vplus-root,#vp-bar,#vp-panel,#vp-fab,style,script')) continue;
      if (c[i].querySelector && c[i].querySelector('style,script') && (c[i].innerText || '').indexOf('{') >= 0 && /box-sizing|font-family|border-radius/.test(c[i].innerText || '')) continue;
      var ps = c[i].querySelectorAll('p'), s = 0, pcount = 0;
      for (var j = 0; j < ps.length; j++) { var t = txt(ps[j]); if (t.length > 40 && ps[j].closest('nav,footer,aside,header') === null) { s += t.length; pcount++; } }
      /* fallback: novel sites often use bare text / <br> lines with no <p>. Score the element's own direct text too. */
      if (pcount < 2) {
        var own = txt(c[i]).trim();
        // reject CSS/JS-looking blobs (e.g. an injected <style>): lots of {};: and no sentence spaces
        var cssLike = (own.match(/[{};]/g) || []).length > 8 && /\{[^}]*:[^}]*\}/.test(own);
        if (!cssLike && own.length > 400 && c[i].querySelectorAll('a,nav,ul,form').length < 8 && !c[i].querySelector('article,main,section')) s = Math.max(s, own.length * 0.9);
      }
      var links = 0, as = c[i].querySelectorAll('a');
      for (var k = 0; k < as.length; k++) links += txt(as[k]).length;
      var tot = txt(c[i]).length || 1;
      s = s * (1 - Math.min(0.85, links / tot));
      if (c[i].tagName === 'ARTICLE') s *= 1.3;
      if (/chapter|reading|entry-content|post-content/i.test(c[i].className || '')) s *= 1.4;
      if (s > sc) { sc = s; top = c[i]; }
    }
    /* require a real chapter's worth of text; otherwise signal "keep waiting" so onTry retries for the lazy content */
    return (top && txt(top).trim().length > (relax ? 200 : 500)) ? top : null;
  }
  /* derive a sibling chapter URL by stepping the last number in the path (chapter-12 -> chapter-13, /82723/5 -> /82723/6) */
  function bumpChapter(base, dir) {
    try {
      var u = new URL(base); var p = u.pathname;
      // match the last run of digits in the path (optionally after chapter/ch/c- separators)
      var m = p.match(/(\d+)(?!.*\d)/);
      if (!m) return '';
      var n = parseInt(m[1], 10) + dir;
      if (n < 1) return '';
      var np = p.slice(0, m.index) + n + p.slice(m.index + m[1].length);
      u.pathname = np; u.hash = '';
      return u.href;
    } catch (e) { return ''; }
  }
  function nextUrl(doc, base) {
    /* common next-chapter ids/classes used by novel sites (novelfull: #next_chap, etc.) */
    var c = doc.querySelector('#next_chap[href], a#next[href], a.next_chapter[href], a[rel~=next][href], link[rel~=next][href], a[class*=next-chap][href], a[id*=next][href]'), i, as;
    if (!c) { as = doc.querySelectorAll('a[href]');
      for (i = 0; i < as.length && !c; i++) { var t = txt(as[i]).replace(/\s+/g, ' ').trim(); if (t.length < 26 && /^(next|next (page|chapter|part|post|episode)|older posts?|next ?[›»>→]+|[›»→])$/i.test(t)) c = as[i]; }
      for (i = 0; i < as.length && !c; i++) { if (/(^|[\s_-])next([\s_-]|$)/i.test((as[i].className || '') + ' ' + (as[i].id || '')) && !/disabled/i.test(as[i].className || '')) c = as[i]; }
      /* icon-only next button: an <a> whose only text is a > arrow or that holds a right-chevron icon */
      for (i = 0; i < as.length && !c; i++) { var tt = txt(as[i]).trim(); var cl = (as[i].className || '') + (as[i].querySelector && as[i].querySelector('i') ? as[i].querySelector('i').className : ''); if ((/^[>›»→]$/.test(tt) || /fa-(angle|chevron|arrow)-right|next/i.test(cl)) && as[i].getAttribute('href')) c = as[i]; } }
    if (!c) return bumpChapter(base, 1);
    try { var u = new URL(c.getAttribute('href'), base); if (u.origin !== location.origin || !/^https?:$/.test(u.protocol)) return bumpChapter(base, 1); u.hash = ''; var cu = new URL(base); cu.hash = ''; return u.href === cu.href ? bumpChapter(base, 1) : u.href; } catch (e) { return bumpChapter(base, 1); }
  }
  function prevUrl(doc, base) {
    var c = doc.querySelector('#prev_chap[href], a#prev[href], a.prev_chapter[href], a[rel~=prev][href], link[rel~=prev][href], a[class*=prev-chap][href], a[id*=prev][href]'), i, as;
    if (!c) { as = doc.querySelectorAll('a[href]');
      for (i = 0; i < as.length && !c; i++) { var t = txt(as[i]).replace(/\s+/g, ' ').trim(); if (t.length < 26 && /^(prev|prev(ious)?( (page|chapter|part|post|episode))?|newer posts?|[‹«<←] ?prev|[‹«←])$/i.test(t)) c = as[i]; }
      for (i = 0; i < as.length && !c; i++) { if (/(^|[\s_-])prev(ious)?([\s_-]|$)/i.test((as[i].className || '') + ' ' + (as[i].id || '')) && !/disabled/i.test(as[i].className || '')) c = as[i]; }
      for (i = 0; i < as.length && !c; i++) { var tt = txt(as[i]).trim(); var cl = (as[i].className || '') + (as[i].querySelector && as[i].querySelector('i') ? as[i].querySelector('i').className : ''); if ((/^[<‹«←]$/.test(tt) || /fa-(angle|chevron|arrow)-left|prev/i.test(cl)) && as[i].getAttribute('href')) c = as[i]; } }
    if (!c) return bumpChapter(base, -1);
    try { var u = new URL(c.getAttribute('href'), base); if (u.origin !== location.origin || !/^https?:$/.test(u.protocol)) return bumpChapter(base, -1); u.hash = ''; var cu = new URL(base); cu.hash = ''; return u.href === cu.href ? bumpChapter(base, -1) : u.href; } catch (e) { return bumpChapter(base, -1); }
  }
  var ov, box, bar, seen = [], nxt = '', busy = false, loaded = {}, lastY = 0;
  function paint() {
    var t = TH[theme];
    ov.style.background = t[0]; ov.style.color = t[1];
    box.style.fontSize = fs + 'px';
    bar.style.background = t[0]; bar.style.borderBottom = '1px solid ' + t[2] + '55';
    Array.prototype.forEach.call(ov.querySelectorAll('.vpb'), function (b) { b.style.color = t[1]; b.style.borderColor = t[2]; });
    Array.prototype.forEach.call(ov.querySelectorAll('a'), function (a) { a.style.color = t[3]; });
  }
  function fill(el, base, title) {
    var added = [];
    if (title) { var H = document.createElement(box.children.length ? 'h2' : 'h1'); H.textContent = title; H.style.cssText = 'font-size:1.5em;line-height:1.3;margin:14px 0 18px'; H.__src = title; box.appendChild(H); added.push(H); }
    var kids = el.querySelectorAll('h2,h3,h4,p,li,blockquote,pre,img');
    Array.prototype.forEach.call(kids, function (k) {
      if (k.closest('nav,footer,aside,header,form,script,style,.comments,#comments')) return;
      if (k.closest(AD_SEL)) return;
      var ktx = (k.innerText || k.textContent || '').trim();
      if (ktx && ktx.length < 600 && JUNK.test(ktx)) return; // drop ad-blocker nag lines inside the container
      if (k.tagName === 'IMG') {
        var raw = k.getAttribute('data-src') || k.getAttribute('data-lazy-src') || k.getAttribute('data-original') || k.getAttribute('src'), s = '';
        try { s = k.currentSrc && k.ownerDocument === document && !/^data:/.test(k.currentSrc) && !k.getAttribute('data-src') ? k.currentSrc : new URL(raw, base).href; } catch (e) {}
        if (!s || /^data:/.test(s) || (k.naturalWidth && k.naturalWidth < 200) || seen.indexOf(s) >= 0) return; seen.push(s);
        var im = document.createElement('img'); im.loading = 'lazy'; im.src = s; im.style.cssText = 'max-width:100%;height:auto;border-radius:6px;margin:10px 0;display:block'; box.appendChild(im); return;
      }
      var tx = txt(k).trim(); if (tx.length < 2) return;
      if (k.tagName === 'LI' && k.querySelector('p')) return;
      if (k.tagName !== 'PRE' && k.parentElement && k.parentElement.closest('blockquote,li') && k.tagName === 'P') return;
      var c = document.createElement(k.tagName === 'LI' ? 'p' : k.tagName.toLowerCase());
      c.textContent = (k.tagName === 'LI' ? '• ' : '') + tx; c.__src = c.textContent;
      if (k.tagName === 'PRE') c.style.cssText = 'white-space:pre-wrap;font:13px monospace;overflow:auto';
      if (/^H/.test(k.tagName)) c.style.cssText = 'font-size:1.2em;margin:1.2em 0 .4em';
      else if (k.tagName === 'BLOCKQUOTE') c.style.cssText = 'border-left:3px solid #8886;margin:1em 0;padding-left:14px';
      else c.style.margin = '0 0 1.05em';
      box.appendChild(c); added.push(c);
    });
    /* fallback: container had no <p>/<li> blocks (bare-text novel page) — split its raw text into paragraphs by line breaks. */
    if (!added.length || (added.length === 1 && title)) {
      var raw = (el.innerText || el.textContent || '').replace(/\r/g, '');
      var parts = raw.split(/\n{1,}/).map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 1; });
      parts.forEach(function (tx) {
        var c = document.createElement('p'); c.textContent = tx; c.__src = tx; c.style.margin = '0 0 1.05em';
        box.appendChild(c); added.push(c);
      });
    }
    return added;
  }
  /* endless reading, two strategies tried in order:
     1) LIVE: nudge the real page (which the overlay normally blocks from scrolling) so the site's OWN infinite scroll
        loads the next chapter, then pull the newly-added paragraphs into the reader. Works on WTR-LAB etc.
     2) FETCH: if the site has no built-in endless scroll, fetch the next chapter URL and append it.
     3) If that next page is JS-rendered (empty shell), navigate to it; reader reopens itself. */
  var liveSeen = null, liveTries = 0;
  function snapshotLive() {
    // remember the text we've already shown so we can detect what the site adds later
    liveSeen = {};
    Array.prototype.forEach.call(box.children, function (c) { if (c.__src) liveSeen[c.__src.slice(0, 60)] = 1; });
  }
  function harvestLive() {
    // scan the whole live page for readable paragraphs the site has added and that aren't in the reader yet
    var src = document.querySelectorAll('article p, main p, [class*="chapter"] p, [class*="content"] p, [class*="reading"] p, p');
    var addedAny = false, buf = [];
    Array.prototype.forEach.call(src, function (p) {
      if (p.closest('#__vpRd')) return;                       // skip the reader's own copies
      if (p.closest('nav,footer,aside,header,form,' + AD_SEL)) return;
      var t = (p.innerText || p.textContent || '').trim();
      if (t.length < 2) return;
      if (t.length < 600 && JUNK.test(t)) return;             // skip ad/contributor footer lines
      if (liveSeen[t.slice(0, 60)]) return;
      liveSeen[t.slice(0, 60)] = 1; buf.push(t);
    });
    if (buf.length) {
      var hr = document.createElement('hr'); hr.style.cssText = 'border:0;border-top:1px solid #8885;margin:22px 0'; box.appendChild(hr); hr.__sep = 1;
      var add = [];
      buf.forEach(function (t) { var c = document.createElement('p'); c.textContent = t; c.__src = t; c.style.margin = '0 0 1.05em'; box.appendChild(c); add.push(c); });
      paint(); if (R.tl) trList(add); addedAny = true;
    }
    return addedAny;
  }
  // Gentle endless reading: never hammer the site. Load the NEXT chapter only, only when the reader is genuinely
  // near the end, and never more often than once every few seconds — so WTR-LAB's Cloudflare check isn't tripped.
  var lastFetch = 0;
  function more() {
    if (busy || !R.on) return;
    if (Date.now() - lastFetch < 4000) return;   // cooldown: at most one chapter every ~4s (human-paced)
    busy = true;
    if (liveSeen === null) snapshotLive();
    // First try harvesting any content the site itself already loaded underneath (no scroll-jerking, no extra requests).
    var got = harvestLive();
    busy = false;
    if (got) { lastFetch = Date.now(); return; }
    // Otherwise fetch exactly the next chapter, once.
    fetchNext();
  }
  function fetchNext() {
    if (!nxt || loaded[nxt]) return;
    var u = nxt; loaded[u] = 1; busy = true; lastFetch = Date.now();
    var gone = false;
    fetch(u, { credentials: 'include' }).then(function (r) { if (r.status === 404 || r.status === 410) { gone = true; throw 0; } if (!r.ok) throw 0; return r.text(); }).then(function (h) {
      var d = new DOMParser().parseFromString(h, 'text/html'), el = best(d);
      if (!el) { navTo(u); return; }
      nxt = nextUrl(d, u);
      var hr = document.createElement('hr'); hr.style.cssText = 'border:0;border-top:1px solid #8885;margin:28px 0'; box.appendChild(hr);
      var h1 = d.querySelector('h1'), add = fill(el, u, (h1 && txt(h1).trim()) || d.title || '');
      paint();
      if (R.tl) trList(add);
    }).catch(function () {
      // 404 = there simply is no next chapter (last one, or a guessed address): stay in the book instead of jumping to an error page
      if (gone) { nxt = ''; var e = document.createElement('p'); e.textContent = '— End of the available chapters —'; e.style.cssText = 'text-align:center;opacity:.6;margin:30px 0'; box.appendChild(e); return; }
      navTo(u);
    }).then(function () { busy = false; });
  }
  /* navigate to a chapter and have reader mode open itself again on the new page (seamless: user never leaves reader) */
  function navTo(u) {
    if (!u) return;
    try { sessionStorage.setItem('__vpReopen', '1'); } catch (e) {}
    try { if (R.tl) sessionStorage.setItem('__vpReopenTl', R.tl); } catch (e) {}
    location.href = u;
  }
  function trList(ps) { ps = ps.filter(function (c) { return c.__src; }); if (!ps.length) return; tr(ps.map(function (c) { return c.__src; }), R.tl, function (out) { ps.forEach(function (c, i) { if (out[i]) c.textContent = out[i]; }); }); }
  function chrome(h) { try { VPApp.chrome(h); } catch (e) {} }
  function on() { onTry(0); }
  /* Many sites load chapter text via JS after first render, so retry for a few seconds before giving up. */
  function onTry(attempt) {
    var el = best(document);
    if (!el) {
      if (attempt < 32) { setTimeout(function () { onTry(attempt + 1); }, 250); return; }
      el = best(document, true); /* last resort: accept a shorter non-junk block so the reader still opens */
      if (!el) { try { VPApp.toast('No article text found on this page'); } catch (e) {} return; }
    }
    start(el);
  }
  function start(el) {
    seen = []; loaded = {}; busy = false; lastY = 0;
    ov = document.createElement('div'); ov.id = '__vpRd';
    ov.style.cssText = 'position:fixed;inset:0;z-index:2147483646;overflow:auto;-webkit-overflow-scrolling:touch;font-family:Georgia,"Noto Serif",serif';
    bar = document.createElement('div');
    bar.style.cssText = 'position:sticky;top:0;display:flex;gap:6px;justify-content:flex-start;align-items:center;padding:8px;font:14px sans-serif;z-index:2;transition:transform .2s;overflow-x:auto;-webkit-overflow-scrolling:touch;white-space:nowrap';
    function btn(t, f) { var b = document.createElement('button'); b.className = 'vpb'; b.textContent = t; b.style.cssText = 'background:none;border:1px solid;border-radius:8px;padding:7px 12px;font:14px sans-serif;flex:none'; b.onclick = function (e) { e.stopPropagation(); f(); }; bar.appendChild(b); return b; }
    var prv = prevUrl(document, location.href);
    nxt = nextUrl(document, location.href);
    var pB = btn('‹ Prev', function () { if (prv) { try { VPApp.toast && VPApp.toast('Loading previous chapter…'); } catch (e) {} navTo(prv); } });
    if (!prv) pB.style.opacity = '.4';
    btn('A-', function () { fs = Math.max(13, fs - 2); paint(); });
    btn('A+', function () { fs = Math.min(34, fs + 2); paint(); });
    var thB = btn('Theme: ' + TH[theme][4], function () { theme = (theme + 1) % TH.length; thB.textContent = 'Theme: ' + TH[theme][4]; paint(); });
    var tb = btn('Translate', function () {
      var cycle = ['', 'en', 'hi'];
      R.tl = cycle[(cycle.indexOf(R.tl) + 1) % 3];
      tb.textContent = R.tl ? 'Translate: ' + R.tl.toUpperCase() : 'Translate';
      doTr();
    });
    var nB = btn('Next ›', function () { if (nxt) { try { VPApp.toast && VPApp.toast('Loading next chapter…'); } catch (e) {} navTo(nxt); } });
    if (!nxt) nB.style.opacity = '.4';
    btn('✕', off);
    box = document.createElement('div');
    box.style.cssText = 'max-width:680px;margin:0 auto;padding:12px 18px 80px;line-height:1.75;word-wrap:break-word';
    var h1 = document.querySelector('h1');
    fill(el, location.href, (h1 && h1.innerText.trim()) || document.title);
    ov.appendChild(bar); ov.appendChild(box); document.documentElement.appendChild(ov);
    /* menus hide while reading: scrolling down hides the reader buttons, a tap or scrolling up brings them back */
    function showBar(s) { bar.style.transform = s ? 'none' : 'translateY(-110%)'; bar.__hid = !s; }
    ov.addEventListener('scroll', function () {
      var y = ov.scrollTop; if (y > lastY + 8 && y > 60) showBar(false); else if (y < lastY - 8) showBar(true); lastY = y;
      // only load the next chapter when the reader is genuinely near the bottom (~1200px), not pages early
      if (y + ov.clientHeight > ov.scrollHeight - 1200) more();
    }, { passive: true });
    box.addEventListener('click', function () { showBar(!!bar.__hid); });
    R.on = true; paint(); chrome(true);
    try { VPApp.swipeEnable(false); } catch (e) {} // stop pull-to-refresh reloading the page when scrolling up in the reader
    if (R.auto) { R.tl = R.auto; tb.textContent = 'Translate: ' + R.tl.toUpperCase(); doTr(); }
    /* do NOT prefetch on open — wait until the user actually scrolls near the end (avoids bot-like rapid requests) */
  }
  function doTr() {
    var ps = Array.prototype.filter.call(box.children, function (c) { return c.__src; });
    if (!R.tl) { ps.forEach(function (c) { c.textContent = c.__src; }); return; }
    tr(ps.map(function (c) { return c.__src; }), R.tl, function (out) { ps.forEach(function (c, i) { if (out[i]) c.textContent = out[i]; }); });
  }
  function off() { if (ov && ov.parentNode) ov.parentNode.removeChild(ov); R.on = false; chrome(false); try { VPApp.swipeEnable(true); } catch (e) {} try { sessionStorage.removeItem('__vpReopen'); } catch (e) {} }
  R.toggle = function () { if (R.on) off(); else on(); };
  /* page(): called on every load. If the user was reading and tapped Next/Prev (or we navigated for them),
     reopen reader mode automatically so they never leave it. Retries while the JS page builds its content. */
  R.page = function (autoTl) {
    if (autoTl) R.auto = autoTl;
    var reopen = false;
    try { reopen = sessionStorage.getItem('__vpReopen') === '1'; } catch (e) {}
    /* Auto-translate setting: translate the normal page in place (this was silently skipped before). */
    if (autoTl && !reopen && !R.on) { try { R.translatePage(autoTl); } catch (e) {} }
    if (!reopen || R.on) return;
    try { var tl = sessionStorage.getItem('__vpReopenTl'); if (tl) R.auto = tl; } catch (e) {}
    var tries = 0;
    (function waitAndOpen() {
      if (R.on) return;
      var el = best(document);
      if (el) { on(); return; }
      if (++tries < 40) setTimeout(waitAndOpen, 300);   // keep trying ~12s while the chapter renders
    })();
  };
})();
