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
  function best() {
    var c = document.querySelectorAll('article, main, [role=main], .post, .entry-content, .article, #content, .content, section, div'), top = null, sc = 0;
    for (var i = 0; i < c.length; i++) {
      var ps = c[i].querySelectorAll('p'), s = 0;
      for (var j = 0; j < ps.length; j++) { var t = ps[j].innerText || ''; if (t.length > 60 && ps[j].closest('nav,footer,aside,header') === null) s += t.length; }
      var links = 0, as = c[i].querySelectorAll('a');
      for (var k = 0; k < as.length; k++) links += (as[k].innerText || '').length;
      var tot = (c[i].innerText || '').length || 1;
      s = s * (1 - Math.min(0.8, links / tot));
      if (c[i].tagName === 'ARTICLE') s *= 1.3;
      if (s > sc) { sc = s; top = c[i]; }
    }
    return sc > 200 ? top : null;
  }
  var ov, box, bar;
  function paint() {
    var t = TH[theme];
    ov.style.background = t[0]; ov.style.color = t[1];
    box.style.fontSize = fs + 'px';
    bar.style.background = t[0]; bar.style.borderBottom = '1px solid ' + t[2] + '55';
    Array.prototype.forEach.call(ov.querySelectorAll('.vpb'), function (b) { b.style.color = t[1]; b.style.borderColor = t[2]; });
    Array.prototype.forEach.call(ov.querySelectorAll('a'), function (a) { a.style.color = theme === 2 ? '#8ab4ff' : '#1a56c4'; });
  }
  function on() {
    var el = best();
    if (!el) { try { VPApp.toast('No article text found on this page'); } catch (e) {} return; }
    ov = document.createElement('div'); ov.id = '__vpRd';
    ov.style.cssText = 'position:fixed;inset:0;z-index:2147483646;overflow:auto;-webkit-overflow-scrolling:touch;font-family:Georgia,"Noto Serif",serif';
    bar = document.createElement('div');
    bar.style.cssText = 'position:sticky;top:0;display:flex;gap:8px;justify-content:center;padding:8px;font:14px sans-serif;z-index:2';
    function btn(txt, f) { var b = document.createElement('button'); b.className = 'vpb'; b.textContent = txt; b.style.cssText = 'background:none;border:1px solid;border-radius:8px;padding:7px 12px;font:14px sans-serif'; b.onclick = f; bar.appendChild(b); return b; }
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
    var h1 = document.querySelector('h1'), title = (h1 && h1.innerText.trim()) || document.title;
    var H = document.createElement('h1'); H.textContent = title; H.style.cssText = 'font-size:1.5em;line-height:1.3;margin:14px 0 18px'; box.appendChild(H);
    var kids = el.querySelectorAll('h2,h3,h4,p,li,blockquote,pre,figure img,img'), seen = [];
    Array.prototype.forEach.call(kids, function (k) {
      if (k.closest('nav,footer,aside,header,form,script,style,.comments,#comments')) return;
      if (k.tagName === 'IMG') {
        var s = k.currentSrc || k.src; if (!s || (k.naturalWidth && k.naturalWidth < 200) || seen.indexOf(s) >= 0) return; seen.push(s);
        var im = document.createElement('img'); im.src = s; im.style.cssText = 'max-width:100%;height:auto;border-radius:6px;margin:10px 0;display:block'; box.appendChild(im); return;
      }
      var tx = (k.innerText || '').trim(); if (tx.length < 2) return;
      if (k.tagName === 'LI' && k.querySelector('p')) return;
      if (k.tagName !== 'PRE' && k.parentElement && k.parentElement.closest('blockquote,li') && k.tagName === 'P') return;
      var c = document.createElement(k.tagName === 'LI' ? 'p' : k.tagName.toLowerCase());
      c.textContent = (k.tagName === 'LI' ? '• ' : '') + tx; c.__src = c.textContent;
      if (k.tagName === 'PRE') c.style.cssText = 'white-space:pre-wrap;font:13px monospace;overflow:auto';
      if (/^H/.test(k.tagName)) c.style.cssText = 'font-size:1.2em;margin:1.2em 0 .4em';
      else if (k.tagName === 'BLOCKQUOTE') c.style.cssText = 'border-left:3px solid #8886;margin:1em 0;padding-left:14px';
      else c.style.margin = '0 0 1.05em';
      box.appendChild(c);
    });
    ov.appendChild(bar); ov.appendChild(box); document.documentElement.appendChild(ov);
    R.on = true; paint();
    if (R.auto) { R.tl = R.auto; tb.textContent = 'Translate: ' + R.tl.toUpperCase(); doTr(); }
  }
  function doTr() {
    var ps = Array.prototype.filter.call(box.children, function (c) { return c.__src; });
    if (!R.tl) { ps.forEach(function (c) { c.textContent = c.__src; }); return; }
    tr(ps.map(function (c) { return c.__src; }), R.tl, function (out) { ps.forEach(function (c, i) { if (out[i]) c.textContent = out[i]; }); });
  }
  function off() { if (ov && ov.parentNode) ov.parentNode.removeChild(ov); R.on = false; }
  R.toggle = function () { if (R.on) off(); else on(); };
})();
