/* Picture translation: finds the words inside comic pages and other large pictures and lays the English text over them. */
(function () {
  if (window.__vpPic || window !== window.top || !window.VPApp || !VPApp.ocr) return;
  var P = window.__vpPic = { on: false, n: 0, cbs: {} }, io = null, mo = null;
  window.__vpOcr = function (id, json) { var f = P.cbs[id]; delete P.cbs[id]; if (f) f(json); };
  function place(img, box) {
    box.style.left = img.offsetLeft + 'px'; box.style.top = img.offsetTop + 'px';
    box.style.width = img.offsetWidth + 'px'; box.style.height = img.offsetHeight + 'px';
  }
  function draw(img, d) {
    if (!img.isConnected || !P.on) return;
    var box = document.createElement('div'); box.className = '__vpPicBox';
    box.style.cssText = 'position:absolute;pointer-events:none;z-index:5;margin:0;padding:0';
    var sc = img.offsetWidth / d.w;
    d.b.forEach(function (b) {
      var e = document.createElement('div'), w = b.w * sc + 8, h = b.h * sc + 6;
      var fs = Math.max(9, Math.min(20, Math.sqrt(w * h / Math.max(4, b.t.length)) * 0.82));
      e.textContent = b.t;
      e.style.cssText = 'position:absolute;pointer-events:auto;box-sizing:border-box;display:flex;align-items:center;justify-content:center;text-align:center;overflow:hidden;background:rgba(255,255,255,.95);color:#111;border-radius:5px;padding:1px 3px;line-height:1.12;font-family:sans-serif;font-weight:600;word-break:break-word;left:' + (b.x / d.w * 100) + '%;top:' + (b.y / d.h * 100) + '%;width:' + (b.w / d.w * 100) + '%;height:' + (b.h / d.h * 100) + '%;margin:-3px -4px;min-width:' + w + 'px;min-height:' + h + 'px;font-size:' + fs + 'px';
      e.onclick = function (ev) { ev.stopPropagation(); ev.preventDefault(); e.style.opacity = e.style.opacity === '0' ? '1' : '0'; };
      box.appendChild(e);
    });
    place(img, box);
    try { img.parentNode.insertBefore(box, img.nextSibling); } catch (x) { return; }
    img.__vpBox = box;
    try { new ResizeObserver(function () { if (box.isConnected) place(img, box); }).observe(img); } catch (x) {}
  }
  function go(img) {
    if (img.__vpPicDone || !P.on) return;
    var u = img.currentSrc || img.src || '';
    if (!/^https?:/.test(u) || /\.(gif|svg)(\?|#|$)/i.test(u)) { if (/^https?:/.test(u) || img.complete) img.__vpPicDone = 1; return; }
    if (!img.complete || !img.naturalWidth) { img.addEventListener('load', function () { go(img); }, { once: true }); return; }
    img.__vpPicDone = 1;
    if (img.naturalWidth < 300 || img.naturalHeight < 300 || img.offsetWidth < 180) return;
    var id = ++P.n;
    P.cbs[id] = function (json) { if (!json) return; try { draw(img, JSON.parse(json)); } catch (x) {} };
    try { VPApp.ocr(id, u); } catch (x) {}
  }
  function watch(root) { var l = (root.tagName === 'IMG') ? [root] : (root.querySelectorAll ? root.querySelectorAll('img') : []); for (var i = 0; i < l.length; i++) if (!l[i].__vpPicW) { l[i].__vpPicW = 1; io.observe(l[i]); } }
  P.start = function () {
    if (P.on) return; P.on = true;
    io = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting) { io.unobserve(e.target); go(e.target); } }); }, { rootMargin: '1600px 0px 2600px 0px' });
    watch(document);
    mo = new MutationObserver(function (ms) { ms.forEach(function (m) { for (var i = 0; i < m.addedNodes.length; i++) { var n = m.addedNodes[i]; if (n.nodeType === 1 && n.className !== '__vpPicBox') watch(n); } }); });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  };
  P.stop = function () {
    P.on = false; if (io) io.disconnect(); if (mo) mo.disconnect();
    var b = document.querySelectorAll('.__vpPicBox'); for (var i = 0; i < b.length; i++) b[i].remove();
    var l = document.querySelectorAll('img'); for (var j = 0; j < l.length; j++) { l[j].__vpPicW = 0; l[j].__vpPicDone = 0; }
  };
})();
