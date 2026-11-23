/* Saved-logins helper: offers to save on submit, and offers to fill saved credentials on load.
   Talks to the native Passwords store via VPApp.pwOffer / VPApp.pwGet. Runs only on real http(s) pages. */
(function () {
  if (window.__vpPw) return; window.__vpPw = 1;
  if (!/^https?:$/.test(location.protocol)) return;
  function pfields() { return [].slice.call(document.querySelectorAll('input[type=password]')).filter(function (p) { return p.offsetParent !== null; }); }
  function userFor(pw) {
    // the username is usually the text/email/tel field just before the password in the same form
    var form = pw.form || document, cands = [].slice.call(form.querySelectorAll('input[type=text],input[type=email],input[type=tel],input:not([type])'));
    var best = '';
    cands.forEach(function (c) { if (c.offsetParent !== null && c.value) { if (!best) best = c.value; } });
    return best;
  }
  // 1) capture on submit / login-button click
  function grab() {
    var ps = pfields(); if (!ps.length) return;
    var pw = ps[0].value; if (!pw) return;
    try { VPApp.pwOffer(userFor(ps[0]) || '', pw); } catch (e) {}
  }
  document.addEventListener('submit', grab, true);
  document.addEventListener('click', function (e) {
    var b = e.target && e.target.closest && e.target.closest('button,[type=submit],[role=button],a');
    if (!b) return;
    var t = (b.innerText || b.value || '').toLowerCase();
    if (/log ?in|sign ?in|continue|next|submit|enter|login/.test(t)) setTimeout(grab, 30);
  }, true);

  // 2) offer to fill saved credentials when a password field exists
  function offerFill() {
    var ps = pfields(); if (!ps.length) return;
    var raw = '[]'; try { raw = VPApp.pwGet(); } catch (e) {}
    var creds; try { creds = JSON.parse(raw); } catch (e) { creds = []; }
    if (!creds.length) return;
    if (document.getElementById('__vpPwBar')) return;
    var c = creds[0];
    var bar = document.createElement('div');
    bar.id = '__vpPwBar';
    bar.style.cssText = 'position:fixed;left:10px;right:10px;bottom:14px;z-index:2147483646;background:#1c1c24;color:#fff;border:1px solid #E2B33C;border-radius:12px;padding:10px 12px;display:flex;align-items:center;gap:10px;font:14px sans-serif;box-shadow:0 6px 24px rgba(0,0,0,.5)';
    var tx = document.createElement('div'); tx.style.cssText = 'flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';
    tx.textContent = 'Fill saved login' + (c.u ? ' (' + c.u + ')' : '') + '?';
    var fill = document.createElement('button'); fill.textContent = 'Fill'; fill.style.cssText = 'background:#E2B33C;color:#1a1405;border:0;border-radius:8px;padding:8px 14px;font-weight:700';
    var no = document.createElement('button'); no.textContent = '✕'; no.style.cssText = 'background:none;color:#aaa;border:0;font-size:16px;padding:4px 8px';
    fill.onclick = function () {
      var ps2 = pfields(); if (ps2.length) { setVal(ps2[0], c.p); var u = userFor(ps2[0]); var uf = findUser(ps2[0]); if (uf) setVal(uf, c.u); }
      bar.remove();
    };
    no.onclick = function () { bar.remove(); };
    bar.appendChild(tx); bar.appendChild(fill); bar.appendChild(no);
    document.body.appendChild(bar);
    setTimeout(function () { if (bar.parentNode) bar.remove(); }, 12000);
  }
  function findUser(pw) {
    var form = pw.form || document, cands = [].slice.call(form.querySelectorAll('input[type=text],input[type=email],input[type=tel],input:not([type])'));
    return cands.filter(function (c) { return c.offsetParent !== null; })[0];
  }
  function setVal(el, v) {
    try {
      var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
      d.set.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (e) { el.value = v; }
  }
  var tries = 0;
  (function wait() { if (pfields().length) { offerFill(); return; } if (++tries < 20) setTimeout(wait, 400); })();
})();
