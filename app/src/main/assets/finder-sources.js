// Look up watch pages using the clues from the current clip.
const FinderSources = (() => {
  const field = document.getElementById('sourceQuery');
  const status = document.getElementById('sourceStatus');
  const results = document.getElementById('sourceResults');
  const credits = document.getElementById('sourceLinks');
  const more = document.getElementById('sourceMore');
  const panel = document.getElementById('sources');
  const noise = new Set(('the and was are with for from this that you your how all reel reels trending viral shorts clip video full movie film original scene instagram youtube tiktok facebook watch part official fyp fypシ explore edit').split(' '));
  let hints, links, handles, inputLink, duration, edited, last, revision, timer, controller, hits;
  function words(value) {
    return (String(value).normalize('NFKC').toLowerCase().match(/[\p{L}\p{N}][\p{L}\p{M}\p{N}]*/gu) || [])
      .filter(w => w.length > 1 && !noise.has(w));
  }
  function clean(value) {
    return String(value || '').replace(/https?:\/\/\S+/gi, ' ')
      .replace(/#(?:reels?|viral|fyp|trending|explore|shorts)\b/gi, ' ')
      .replace(/[@#]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 140);
  }
  function usable(text) {
    return words(text).some(w => /\p{L}/u.test(w)) && !/^(?:check out|look at|watch this|log in|sign up|make your day|enjoy the videos|see instagram|share this)/i.test(text);
  }
  function watchLink(raw) {
    try {
      const u = new URL(raw);
      if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password || u.port) return '';
      const h = u.hostname.toLowerCase().replace(/^(?:www\.|m\.)/, '');
      let m;
      if (h === 'youtu.be' || h === 'youtube.com') {
        const id = h === 'youtu.be' ? u.pathname.slice(1) : u.pathname === '/watch' ? u.searchParams.get('v') : '';
        return /^[\w-]{11}$/.test(id || '') ? 'https://www.youtube.com/watch?v=' + id : '';
      }
      if (h === 'vimeo.com' && (m = u.pathname.match(/^\/(\d+)\/?$/))) return 'https://vimeo.com/' + m[1];
      if (h === 'dailymotion.com' && (m = u.pathname.match(/^\/video\/([a-z0-9]+)(?:_|\/|$)/i))) return 'https://www.dailymotion.com/video/' + m[1];
      if (h === 'archive.org' && (m = u.pathname.match(/^\/details\/([\w.-]+)\/?$/))) return 'https://archive.org/details/' + m[1];
    } catch (_) {}
    return '';
  }
  function anchor(label, url) {
    const a = document.createElement('a'); a.textContent = label; a.href = url; return a;
  }
  function addLinks(text) {
    for (const raw of String(text).match(/https?:\/\/[^\s<>"'“”]+/gi) || []) {
      const url = watchLink(raw.replace(/[).,;!]+$/, ''));
      if (!url || url === watchLink(inputLink) || links.has(url)) continue;
      links.add(url);
      if (links.size === 1) {
        const title = document.createElement('strong'); title.textContent = 'Video links mentioned in the post'; credits.append(title);
      }
      credits.append(anchor(url.replace(/^https:\/\/(?:www\.)?/, ''), url));
    }
  }
  function hint(value, label, priority) {
    const text = clean(value);
    if (!usable(text)) return;
    if (!hints.some(h => h.text.toLowerCase() === text.toLowerCase() && h.label === label)) hints.push({text, label, priority});
    hints.sort((a, b) => b.priority - a.priority);
    if (!edited && hints[0].text !== field.value) {
      field.value = hints[0].text;
      clearTimeout(timer); timer = setTimeout(search, 450);
    }
  }
  function post(text) {
    addLinks(text);
    for (const tag of text.match(/@[a-zA-Z0-9_.]{3,30}/g) || []) handles.add(tag.slice(1).toLowerCase().replace(/[^a-z0-9]/g, ''));
    const named = text.match(/(?:\b(?:movie|film|series|episode|video|title)(?:\s+name)?|फिल्म|फ़िल्म|मूवी)\s*[:=–-]\s*([^\n|#@•“”"]{2,90})/i);
    if (named) hint(named[1].split(/https?:\/\/|[.!?]\s/)[0], 'title in the post', 90);
    const caption = text.replace(/^.*?\bcomments?\s*[-–]\s*[^:]{0,110}:\s*/i, '')
      .split(/\n|[|•]|https?:\/\//).map(clean).find(t => usable(t) && !/ on (?:instagram|tiktok|facebook)\b/i.test(t));
    if (caption) hint(caption, 'caption', 50);
  }
  function reset(link = '') {
    if (controller) controller.abort();
    clearTimeout(timer); revision = (revision || 0) + 1;
    hints = []; hits = []; links = new Set(); handles = new Set(); inputLink = link; duration = 0; edited = false; last = '';
    field.value = ''; credits.replaceChildren(); results.replaceChildren(); more.replaceChildren();
    status.textContent = 'Add a title or a few words if the clip has no readable clues.';
    panel.hidden = false;
  }
  function rank(row, query) {
    const tokens = [...new Set(words(query))], title = new Set(words(row.title));
    if (!tokens.length) return null;
    const common = tokens.filter(t => title.has(t));
    const coverage = common.length / tokens.length;
    const creator = String(row.creator || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    const credited = creator && handles.has(creator);
    const creatorQuery = creator && creator === query.toLowerCase().replace(/[^a-z0-9]/g, '');
    const description = String(row.description || '').slice(0, 12000).toLowerCase().replace(/\s+/g, ' ');
    const phrase = tokens.length >= 3 && description.includes(query.toLowerCase());
    if (coverage < 0.4 && !phrase && !creatorQuery) return null;
    if (row.duration && duration && row.duration <= duration + 2) return null;
    if (/\b(trailer|teaser|shorts|reels?|promo|reaction|recap|highlight[s]?)\b/i.test(row.title) &&
        !/\b(trailer|teaser|shorts|reels?|promo|reaction|recap|highlight[s]?)\b/i.test(query)) return null;
    const corroboration = hints.filter(h => h.text !== query && words(h.text).some(t => title.has(t)));
    const exact = row.title.toLowerCase().includes(query.toLowerCase());
    const evidence = [];
    if (common.length) evidence.push('Title matched “' + common.slice(0, 4).join(' ') + '”');
    if (credited || creatorQuery) evidence.push('Creator name matches');
    if (phrase) evidence.push('Search phrase in description');
    return {row, evidence, score: coverage * 10 + (exact ? 3 : 0) + (credited ? 5 : 0) + (phrase ? 4 : 0) +
      Math.min(2, corroboration.length) + (row.duration > Math.max(duration, 180) ? 1 : 0)};
  }
  function render() {
    const rows = hits.map(r => rank(r, last)).filter(Boolean).sort((a, b) => b.score - a.score);
    results.replaceChildren();
    const used = new Set();
    for (const {row, evidence} of rows) {
      if (used.has(row.url) || row.url === watchLink(inputLink)) continue;
      used.add(row.url);
      const item = document.createElement('li'), meta = document.createElement('small');
      const clock = row.duration ? Math.floor(row.duration / 60) + ':' + String(row.duration % 60).padStart(2, '0') : '';
      meta.textContent = [row.provider, row.creator, clock, ...evidence].filter(Boolean).join(' · ');
      item.append(anchor(row.title, row.url), meta); results.append(item);
      if (used.size >= 8) break;
    }
    return used.size;
  }
  const catalogs = [
    { name: 'Dailymotion', url(q) {
      const p = new URLSearchParams({search:q, fields:'id,title,owner.screenname,duration,description', limit:'20'});
      if (duration > 0) p.set('longer_than', String(Math.floor(duration)));
      return 'https://api.dailymotion.com/videos?' + p;
    }, read(data) {
      return data.list.filter(i => /^[a-z0-9]+$/i.test(i.id)).map(i => ({
        title:i.title, description:i.description, url:'https://www.dailymotion.com/video/' + i.id, creator:i['owner.screenname'], duration:Number(i.duration) || 0
      }));
    }},
    { name: 'Internet Archive', url(q) {
      const terms = [...new Set(words(q))].slice(0, 10).map(w => '"' + w + '"');
      const phrase = words(q).slice(0, 12).join(' ');
      const scope = 'title:(' + terms.join(' AND ') + ')' + (terms.length >= 3 ? ' OR description:"' + phrase + '"' : '') + ' OR creator:"' + phrase + '"';
      const p = new URLSearchParams({q:'mediatype:movies AND (' + scope + ')', rows:'10', output:'json'});
      for (const f of ['identifier', 'title', 'creator', 'description']) p.append('fl[]', f);
      return 'https://archive.org/advancedsearch.php?' + p;
    }, read(data) {
      return data.response.docs.filter(i => /^[\w.-]+$/.test(i.identifier)).map(i => ({
        title:i.title, description:i.description, url:'https://archive.org/details/' + i.identifier, creator:[].concat(i.creator || []).slice(0, 2).join(', '), duration:0
      }));
    }}
  ];
  async function search() {
    clearTimeout(timer);
    const q = clean(field.value);
    if (controller) controller.abort();
    const run = ++revision;
    hits = []; results.replaceChildren(); more.replaceChildren(); last = q;
    if (!usable(q)) { status.textContent = 'Enter a title, a line of dialogue, or a creator name.'; return; }
    controller = new AbortController();
    const signal = controller.signal;
    const from = hints.find(h => h.text === q);
    const explanation = 'Searching for “' + q + '”' + (from ? ' from the ' + from.label : '') + '.';
    status.textContent = explanation;
    more.append(anchor('Search YouTube', 'https://www.youtube.com/results?search_query=' + encodeURIComponent(q + ' full video')),
      anchor('Search web videos', 'https://www.google.com/search?tbm=vid&q=' + encodeURIComponent(q)));
    const failed = [];
    await Promise.all(catalogs.map(async catalog => {
      const deadline = new AbortController(), cancel = () => deadline.abort();
      const timeout = setTimeout(cancel, 12000);
      signal.addEventListener('abort', cancel, {once:true});
      try {
        const response = await fetch(catalog.url(q), {signal:deadline.signal, credentials:'omit', referrerPolicy:'no-referrer'});
        if (!response.ok) throw new Error('lookup failed');
        const rows = catalog.read(await response.json());
        if (run !== revision) return;
        for (const row of rows) if (typeof row.title === 'string') hits.push({...row, provider:catalog.name});
        render();
      } catch (_) { failed.push(catalog.name); }
      finally { clearTimeout(timeout); signal.removeEventListener('abort', cancel); }
    }));
    if (run !== revision) return;
    const count = render();
    status.textContent = (count ? 'Compare these possible matches for “' + q + '”.' : failed.length === catalogs.length
      ? 'The video catalogs could not be reached.' : 'No close matches for “' + q + '”. Try a shorter title or a different clue.') +
      (failed.length && failed.length < catalogs.length ? ' Unavailable: ' + failed.join(', ') + '.' : '');
  }
  field.addEventListener('input', () => { edited = true; clearTimeout(timer); });
  field.addEventListener('keydown', e => { if (e.key === 'Enter') { edited = true; search(); } });
  document.getElementById('sourceGo').onclick = () => { edited = true; search(); };
  return {reset, post, hint, duration(seconds) { duration = Number.isFinite(seconds) ? seconds : 0; if (last) render(); }};
})();
