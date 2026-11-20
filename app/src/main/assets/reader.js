(function () {
  if (window.__vpReader) return;
  var R = window.__vpReader = { on: false, tl: '', cbs: {}, n: 0 };
  var theme = 0, fs = 19;
  var TH = [['#fbfbf8', '#222', '#555'], ['#f4ecd8', '#3b3226', '#6b5d48'], ['#15161a', '#d8d8d8', '#9aa']];
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
  R.page = function (tl) {
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
  function best(doc) {
    var c = doc.querySelectorAll('article, main, [role=main], .post, .entry-content, .article, #content, .content, .chapter-content, .chapter, .reading-content, .text-left, .entry, section, div'), top = null, sc = 0;
    for (var i = 0; i < c.length; i++) {
      var ps = c[i].querySelectorAll('p'), s = 0, pcount = 0;
      for (var j = 0; j < ps.length; j++) { var t = txt(ps[j]); if (t.length > 40 && ps[j].closest('nav,footer,aside,header') === null) { s += t.length; pcount++; } }
      /* fallback: novel sites often use bare text / <br> lines with no <p>. Score the element's own direct text too. */
      if (pcount < 2) {
        var own = txt(c[i]).trim();
        if (own.length > 400 && c[i].querySelectorAll('a,nav,ul,form').length < 8 && !c[i].querySelector('article,main,section')) s = Math.max(s, own.length * 0.9);
      }
      var links = 0, as = c[i].querySelectorAll('a');
      for (var k = 0; k < as.length; k++) links += txt(as[k]).length;
      var tot = txt(c[i]).length || 1;
      s = s * (1 - Math.min(0.85, links / tot));
      if (c[i].tagName === 'ARTICLE') s *= 1.3;
      if (/chapter|reading|entry-content|post-content/i.test(c[i].className || '')) s *= 1.4;
      if (s > sc) { sc = s; top = c[i]; }
    }
    return sc > 180 ? top : null;
  }
  function nextUrl(doc, base) {
    /* common next-chapter ids/classes used by novel sites (novelfull: #next_chap, etc.) */
    var c = doc.querySelector('#next_chap[href], a#next[href], a.next_chapter[href], a[rel~=next][href], link[rel~=next][href], a[class*=next-chap][href], a[id*=next][href]'), i, as;
    if (!c) { as = doc.querySelectorAll('a[href]');
      for (i = 0; i < as.length && !c; i++) { var t = txt(as[i]).replace(/\s+/g, ' ').trim(); if (t.length < 26 && /^(next|next (page|chapter|part|post|episode)|older posts?|next ?[›»>→]+|[›»→])$/i.test(t)) c = as[i]; }
      for (i = 0; i < as.length && !c; i++) { if (/(^|[\s_-])next([\s_-]|$)/i.test((as[i].className || '') + ' ' + (as[i].id || '')) && !/disabled/i.test(as[i].className || '')) c = as[i]; }
      /* icon-only next button: an <a> whose only text is a > arrow or that holds a right-chevron icon */
      for (i = 0; i < as.length && !c; i++) { var tt = txt(as[i]).trim(); var cl = (as[i].className || '') + (as[i].querySelector && as[i].querySelector('i') ? as[i].querySelector('i').className : ''); if ((/^[>›»→]$/.test(tt) || /fa-(angle|chevron|arrow)-right|next/i.test(cl)) && as[i].getAttribute('href')) c = as[i]; } }
    if (!c) return '';
    try { var u = new URL(c.getAttribute('href'), base); if (u.origin !== location.origin || !/^https?:$/.test(u.protocol)) return ''; u.hash = ''; var cu = new URL(base); cu.hash = ''; return u.href === cu.href ? '' : u.href; } catch (e) { return ''; }
  }
  var ov, box, bar, seen = [], nxt = '', busy = false, loaded = {}, lastY = 0;
  function paint() {
    var t = TH[theme];
    ov.style.background = t[0]; ov.style.color = t[1];
    box.style.fontSize = fs + 'px';
    bar.style.background = t[0]; bar.style.borderBottom = '1px solid ' + t[2] + '55';
    Array.prototype.forEach.call(ov.querySelectorAll('.vpb'), function (b) { b.style.color = t[1]; b.style.borderColor = t[2]; });
    Array.prototype.forEach.call(ov.querySelectorAll('a'), function (a) { a.style.color = theme === 2 ? '#8ab4ff' : '#1a56c4'; });
  }
  function fill(el, base, title) {
    var added = [];
    if (title) { var H = document.createElement(box.children.length ? 'h2' : 'h1'); H.textContent = title; H.style.cssText = 'font-size:1.5em;line-height:1.3;margin:14px 0 18px'; H.__src = title; box.appendChild(H); added.push(H); }
    var kids = el.querySelectorAll('h2,h3,h4,p,li,blockquote,pre,img');
    Array.prototype.forEach.call(kids, function (k) {
      if (k.closest('nav,footer,aside,header,form,script,style,.comments,#comments')) return;
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
  /* endless reading: when the reader nears the end, the next page of the same site is fetched and added below */
  function more() {
    if (busy || !nxt || !R.on || loaded[nxt]) return;
    var u = nxt; busy = true; loaded[u] = 1;
    fetch(u, { credentials: 'include' }).then(function (r) { if (!r.ok) throw 0; return r.text(); }).then(function (h) {
      var d = new DOMParser().parseFromString(h, 'text/html'), el = best(d);
      nxt = nextUrl(d, u);
      if (!el) { nxt = ''; return; }
      var hr = document.createElement('hr'); hr.style.cssText = 'border:0;border-top:1px solid #8885;margin:28px 0'; box.appendChild(hr);
      var h1 = d.querySelector('h1'), add = fill(el, u, (h1 && txt(h1).trim()) || d.title || '');
      paint();
      if (R.tl) trList(add);
    }).catch(function () { nxt = ''; }).then(function () { busy = false; });
  }
  function trList(ps) { ps = ps.filter(function (c) { return c.__src; }); if (!ps.length) return; tr(ps.map(function (c) { return c.__src; }), R.tl, function (out) { ps.forEach(function (c, i) { if (out[i]) c.textContent = out[i]; }); }); }
  function chrome(h) { try { VPApp.chrome(h); } catch (e) {} }
  function on() { onTry(0); }
  /* Many sites load chapter text via JS after first render, so retry for a few seconds before giving up. */
  function onTry(attempt) {
    var el = best(document);
    if (!el) {
      if (attempt < 16) { setTimeout(function () { onTry(attempt + 1); }, 250); return; }
      try { VPApp.toast('No article text found on this page'); } catch (e) {} return;
    }
    start(el);
  }
  function start(el) {
    seen = []; loaded = {}; busy = false; lastY = 0;
    ov = document.createElement('div'); ov.id = '__vpRd';
    ov.style.cssText = 'position:fixed;inset:0;z-index:2147483646;overflow:auto;-webkit-overflow-scrolling:touch;font-family:Georgia,"Noto Serif",serif';
    bar = document.createElement('div');
    bar.style.cssText = 'position:sticky;top:0;display:flex;gap:8px;justify-content:center;padding:8px;font:14px sans-serif;z-index:2;transition:transform .2s';
    function btn(t, f) { var b = document.createElement('button'); b.className = 'vpb'; b.textContent = t; b.style.cssText = 'background:none;border:1px solid;border-radius:8px;padding:7px 12px;font:14px sans-serif'; b.onclick = function (e) { e.stopPropagation(); f(); }; bar.appendChild(b); return b; }
    btn('A-', function () { fs = Math.max(13, fs - 2); paint(); });
    btn('A+', function () { fs = Math.min(34, fs + 2); paint(); });
    btn('Theme', function () { theme = (theme + 1) % 3; paint(); });
    var tb = btn('Translate', function () {
      var cycle = ['', 'en', 'hi'];
      R.tl = cycle[(cycle.indexOf(R.tl) + 1) % 3];
      tb.textContent = R.tl ? 'Translate: ' + R.tl.toUpperCase() : 'Translate';
      doTr();
    });
    btn('✕', off);
    box = document.createElement('div');
    box.style.cssText = 'max-width:680px;margin:0 auto;padding:12px 18px 80px;line-height:1.75;word-wrap:break-word';
    var h1 = document.querySelector('h1');
    fill(el, location.href, (h1 && h1.innerText.trim()) || document.title);
    nxt = nextUrl(document, location.href);
    ov.appendChild(bar); ov.appendChild(box); document.documentElement.appendChild(ov);
    /* menus hide while reading: scrolling down hides the reader buttons, a tap or scrolling up brings them back */
    function showBar(s) { bar.style.transform = s ? 'none' : 'translateY(-110%)'; bar.__hid = !s; }
    ov.addEventListener('scroll', function () {
      var y = ov.scrollTop; if (y > lastY + 8 && y > 60) showBar(false); else if (y < lastY - 8) showBar(true); lastY = y;
      if (y + ov.clientHeight > ov.scrollHeight - 5000) more();
    }, { passive: true });
    box.addEventListener('click', function () { showBar(!!bar.__hid); });
    R.on = true; paint(); chrome(true);
    if (R.auto) { R.tl = R.auto; tb.textContent = 'Translate: ' + R.tl.toUpperCase(); doTr(); }
    setTimeout(more, 600);
  }
  function doTr() {
    var ps = Array.prototype.filter.call(box.children, function (c) { return c.__src; });
    if (!R.tl) { ps.forEach(function (c) { c.textContent = c.__src; }); return; }
    tr(ps.map(function (c) { return c.__src; }), R.tl, function (out) { ps.forEach(function (c, i) { if (out[i]) c.textContent = out[i]; }); });
  }
  function off() { if (ov && ov.parentNode) ov.parentNode.removeChild(ov); R.on = false; chrome(false); }
  R.toggle = function () { if (R.on) off(); else on(); };
})();
