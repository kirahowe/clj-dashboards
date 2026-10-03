// clj-dashboards browser client.
//
// Datastar (https://data-star.dev) does the real work: inputs are
// signals bound with data-bind, the page posts its signals back when
// they change, and the server's SSE stream morphs rendered outputs
// into place. This module adds what a dashboard needs on top:
//
// - the measured size of each plot, so plots are drawn to fit;
// - brushing and clicking points on plots, as inputs;
// - busy, recalculating and connection states, notifications, and
//   replacing an input's choices (the `datastar-dashboards` event);
// - tooltips, sortable tables, collapsible sidebars, full-screen cards.

const DATASTAR = './datastar-1.0.4.js';

// ---------------------------------------------------------------------
// Plot sizes. Measured before Datastar starts, so that the signals the
// stream opens with already carry them and plots render once, at the
// right size.

function measure(el) {
  const r = el.getBoundingClientRect();
  const w = Math.round(r.width), h = Math.round(r.height);
  return w > 0 && h > 0 ? [w, h] : null;
}

function plots(root) {
  const found = [...root.querySelectorAll('.dsh-output-plot[id]')];
  if (root.matches && root.matches('.dsh-output-plot[id]')) found.unshift(root);
  return found;
}

const sizes = {};
for (const el of plots(document)) {
  const s = measure(el);
  if (s) sizes[el.id] = s;
}
{
  const body = document.body;
  const signals = JSON.parse(body.getAttribute('data-signals') || '{}');
  signals.dsh = Object.assign({}, signals.dsh, { sizes });
  body.setAttribute('data-signals', JSON.stringify(signals));
}

const timers = {};
function later(key, ms, f) {
  clearTimeout(timers[key]);
  timers[key] = setTimeout(f, ms);
}

// ---------------------------------------------------------------------
// The connection. The stream's first event on every (re)connect is
// `connected`; until it arrives the page says why, but only once a
// wait is long enough to matter.

// A server closes its streams when it restarts for a deploy, and the
// browser is usually back well within this. Datastar retries the
// stream about 0.5s, 1.5s, 3.5s and 7.5s after it drops (an interval
// of 500ms, doubling), and a notice due just as a retry succeeds would
// flash up for a moment; 2.5s sits midway between two retries, so a
// drop the second retry recovers from shows nothing.
const RECONNECTING_AFTER_MS = 2500;
// A request for the stream that has neither failed nor brought
// `connected` by now is being held up on the way: a proxy, VPN,
// antivirus or firewall that buffers responses until they end.
const BUFFERING_AFTER_MS = 5000;

// Matched by path, as the page's own URL may change (history.pushState).
function isStreamUrl(url) {
  try {
    return new URL(url, document.baseURI).pathname.endsWith('/_dashboards/stream');
  } catch {
    return false;
  }
}

// state: null (hidden), 'reconnecting', 'buffering' or 'failed'.
function showConnection(state) {
  const overlay = document.querySelector('.dsh-disconnected');
  if (!overlay) return;
  overlay.hidden = !state;
  if (!state) return;
  overlay.dataset.state = state;
  for (const m of overlay.querySelectorAll('[data-dsh-connection]')) {
    m.hidden = m.dataset.dshConnection !== state;
  }
}

function cancel(key) {
  clearTimeout(timers[key]);
  delete timers[key];
}

function setConnected(connected) {
  cancel('buffering');
  if (connected) {
    cancel('reconnecting');
    showConnection(null);
    return;
  }
  document.documentElement.classList.remove('dsh-busy');
  const overlay = document.querySelector('.dsh-disconnected');
  // A notice already up means the wait is long enough to matter.
  if (overlay && !overlay.hidden) showConnection('reconnecting');
  // Counted from the first failure: each failed retry after it
  // mustn't push it back.
  else if (!timers.reconnecting) later('reconnecting', RECONNECTING_AFTER_MS, () => showConnection('reconnecting'));
}

// Datastar says when it schedules a retry of the stream, but not when
// the retry starts, so watch for the request itself. Installed before
// Datastar loads, as it opens the stream as soon as it starts.
const nativeFetch = window.fetch;
let streamRequest = null; // the latest request for the stream
window.fetch = function (input, ...rest) {
  const response = nativeFetch.call(this, input, ...rest);
  if (isStreamUrl(input instanceof Request ? input.url : String(input))) {
    const request = (streamRequest = {});
    later('buffering', BUFFERING_AFTER_MS, () => {
      cancel('reconnecting');
      showConnection('buffering');
    });
    // An aborted request isn't being held up. (A failed one is
    // retried, which Datastar reports.)
    response.catch(() => {
      if (streamRequest === request) cancel('buffering');
    });
  }
  return response;
};

document.addEventListener('datastar-fetch', (e) => {
  const { type, el } = e.detail;
  if (!el || el.id !== 'dsh-stream') return;
  if (type === 'finished') {
    // Datastar has given up on the stream, one way or another.
    cancel('buffering');
  } else if (type === 'retrying' || type === 'error') {
    setConnected(false);
  } else if (type === 'retries-failed') {
    setConnected(false);
    cancel('reconnecting');
    showConnection('failed');
  }
});

const { mergePatch, getPath, watcher } = await import(DATASTAR);

const resizeObserver = new ResizeObserver((entries) => {
  for (const { target } of entries) {
    const s = measure(target);
    if (!s) continue; // hidden, e.g. in an inactive tab
    const old = sizes[target.id];
    if (old && Math.abs(old[0] - s[0]) < 2 && Math.abs(old[1] - s[1]) < 2) continue;
    sizes[target.id] = s;
    later('size:' + target.id, 250, () => mergePatch({ dsh: { sizes: { [target.id]: s } } }));
  }
});

function observePlots(root) {
  for (const el of plots(root)) resizeObserver.observe(el);
}
observePlots(document);
new MutationObserver((records) => {
  for (const r of records) {
    for (const n of r.addedNodes) if (n.nodeType === 1) observePlots(n);
  }
}).observe(document.body, { childList: true, subtree: true });

// ---------------------------------------------------------------------
// Brushing and clicking points. plotje marks each point with the index
// of its row in the plotted data.

function points(el) {
  return [...el.querySelectorAll('svg [data-row-idx]')];
}

function clearBrush(el) {
  const b = el.__dshBrush;
  if (!b) return;
  b.rect.remove();
  for (const p of points(el)) p.removeAttribute('data-dsh-dim');
  el.classList.remove('dsh-brushing');
  el.__dshBrush = null;
  // An empty selection rather than null: Datastar doesn't report a
  // removed signal as a change.
  mergePatch({ [el.dataset.brush]: [] });
}

document.addEventListener('mousedown', (e) => {
  const el = e.target.closest && e.target.closest('.dsh-output-plot[data-brush]');
  if (!el || e.button !== 0 || !el.querySelector('svg')) return;
  e.preventDefault();
  const box = el.getBoundingClientRect();
  const x0 = e.clientX, y0 = e.clientY;
  const rect = document.createElement('div');
  rect.className = 'dsh-brush-rect';
  el.appendChild(rect);
  let moved = false;
  const move = (ev) => {
    if (Math.abs(ev.clientX - x0) > 3 || Math.abs(ev.clientY - y0) > 3) moved = true;
    rect.style.left = (Math.min(x0, ev.clientX) - box.left) + 'px';
    rect.style.top = (Math.min(y0, ev.clientY) - box.top) + 'px';
    rect.style.width = Math.abs(ev.clientX - x0) + 'px';
    rect.style.height = Math.abs(ev.clientY - y0) + 'px';
  };
  const up = (ev) => {
    document.removeEventListener('mousemove', move);
    document.removeEventListener('mouseup', up);
    if (!moved) { rect.remove(); clearBrush(el); return; }
    const l = Math.min(x0, ev.clientX), r = Math.max(x0, ev.clientX);
    const t = Math.min(y0, ev.clientY), b = Math.max(y0, ev.clientY);
    const selected = new Set();
    for (const p of points(el)) {
      const pr = p.getBoundingClientRect();
      const cx = pr.left + pr.width / 2, cy = pr.top + pr.height / 2;
      if (cx >= l && cx <= r && cy >= t && cy <= b) selected.add(p.getAttribute('data-row-idx'));
    }
    if (el.__dshBrush) el.__dshBrush.rect.remove();
    el.__dshBrush = { rect };
    el.classList.add('dsh-brushing');
    for (const p of points(el)) {
      if (selected.has(p.getAttribute('data-row-idx'))) p.removeAttribute('data-dsh-dim');
      else p.setAttribute('data-dsh-dim', '');
    }
    const rows = [...selected].map(Number).sort((a, b) => a - b);
    mergePatch({ [el.dataset.brush]: rows });
  };
  document.addEventListener('mousemove', move);
  document.addEventListener('mouseup', up);
});

document.addEventListener('click', (e) => {
  const p = e.target.closest && e.target.closest('[data-row-idx]');
  const el = p && p.closest('.dsh-output-plot[data-click]');
  if (el) mergePatch({ [el.dataset.click]: Number(p.getAttribute('data-row-idx')) });
});

// ---------------------------------------------------------------------
// The datastar-dashboards event: what Datastar has no event for.

function byId(id) { return document.getElementById(id); }

function notify({ message, level = 'info', duration = '0' }) {
  const box = byId('dsh-notifications');
  if (!box) return;
  const n = document.createElement('div');
  n.className = 'dsh-notification dsh-level-' + level;
  n.setAttribute('role', level === 'error' ? 'alert' : 'status');
  const text = document.createElement('div');
  text.textContent = message;
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'dsh-notification-close';
  close.setAttribute('aria-label', 'Dismiss');
  close.textContent = '×';
  close.onclick = () => n.remove();
  n.append(text, close);
  box.appendChild(n);
  if (Number(duration) > 0) setTimeout(() => n.remove(), Number(duration));
}

function setChoices({ id, signal, choices, selected }) {
  const el = byId(id);
  if (!el) return;
  choices = JSON.parse(choices);
  selected = JSON.parse(selected);
  const values = choices.map((c) => c.value);
  const chosen = (v) => (Array.isArray(selected) ? selected.includes(v) : selected === v);
  if (el instanceof HTMLSelectElement) {
    const value = el.multiple
      ? values.filter(chosen)
      : (selected != null && values.includes(selected) ? selected : values[0] ?? '');
    mergePatch({ [signal]: value });
    el.replaceChildren(...choices.map((c) => {
      const o = document.createElement('option');
      o.value = c.value;
      o.textContent = c.label;
      o.selected = el.multiple ? chosen(c.value) : c.value === value;
      return o;
    }));
    el.dispatchEvent(new Event('change'));
    return;
  }
  const box = byId(id + '-choices');
  if (!box) return;
  const radio = el.classList.contains('dsh-input-radio');
  const value = radio
    ? (selected != null && values.includes(selected) ? selected : values[0] ?? '')
    : values.map((v) => (chosen(v) ? v : ''));
  mergePatch({ [signal]: value });
  box.replaceChildren(...choices.map((c) => {
    const label = document.createElement('label');
    label.className = 'dsh-check';
    const input = document.createElement('input');
    input.type = radio ? 'radio' : 'checkbox';
    input.name = id;
    input.value = c.value;
    input.checked = radio ? c.value === value : chosen(c.value);
    input.setAttribute('data-bind', signal);
    const span = document.createElement('span');
    span.textContent = c.label;
    label.append(input, span);
    return label;
  }));
}

watcher({
  name: 'datastar-dashboards',
  apply(_ctx, args) {
    switch (args.type) {
      case 'connected':
      // The stream is up, though the app failed to start on it; the
      // notification sent with this says why.
      case 'failed':
        setConnected(true);
        break;
      case 'busy':
        document.documentElement.classList.add('dsh-busy');
        break;
      case 'idle':
        document.documentElement.classList.remove('dsh-busy');
        break;
      case 'recalculating': {
        const el = byId(args.id);
        if (el) el.classList.add('dsh-recalculating');
        break;
      }
      case 'rendered': {
        const el = byId(args.id);
        if (!el) break;
        el.classList.remove('dsh-recalculating');
        el.classList.toggle('dsh-has-error', args.status === 'error');
        el.classList.toggle('dsh-has-validation', args.status === 'validation');
        // A redrawn plot's row indices may mean something new.
        if (el.__dshBrush) clearBrush(el);
        break;
      }
      case 'choices':
        setChoices(args);
        break;
      case 'notify':
        notify(args);
        break;
    }
  },
});

// The session lives on the server as long as the page keeps saying it
// is there, and ends as soon as the page goes away.
function sessionBody() {
  return JSON.stringify({ session: getPath('dsh.session') || '' });
}
setInterval(() => {
  if (getPath('dsh.session')) {
    fetch('_dashboards/alive', { method: 'POST', body: sessionBody(), keepalive: true }).catch(() => {});
  }
}, 20000);
window.addEventListener('pagehide', (e) => {
  // A page kept in the back/forward cache may come back; keep its session.
  if (!e.persisted && getPath('dsh.session')) navigator.sendBeacon('_dashboards/close', sessionBody());
});

// ---------------------------------------------------------------------
// Purely client-side behaviour.

let tip = null;
document.addEventListener('mousemove', (e) => {
  const t = e.target.closest && e.target.closest('[data-tooltip],[data-tooltip-html]');
  if (!t) { if (tip) tip.hidden = true; return; }
  if (!tip) {
    tip = document.createElement('div');
    tip.className = 'dsh-tooltip';
    document.body.appendChild(tip);
  }
  const html = t.getAttribute('data-tooltip-html');
  if (html) tip.innerHTML = html; else tip.textContent = t.getAttribute('data-tooltip');
  tip.hidden = false;
  tip.style.left = (e.clientX + 12) + 'px';
  tip.style.top = (e.clientY + 12) + 'px';
});

function sortTable(th) {
  const table = th.closest('table');
  if (!table || table.getAttribute('data-sortable') !== 'true') return;
  const idx = [...th.parentNode.children].indexOf(th);
  const asc = th.getAttribute('data-sort') !== 'asc';
  for (const h of table.querySelectorAll('th')) h.removeAttribute('data-sort');
  th.setAttribute('data-sort', asc ? 'asc' : 'desc');
  const tbody = table.tBodies[0];
  const key = (r) => {
    const t = r.cells[idx] ? r.cells[idx].textContent : '';
    const n = Number(t.replace(/,/g, ''));
    return t !== '' && !isNaN(n) ? n : t.toLowerCase();
  };
  const rows = [...tbody.rows].sort((a, b) => {
    const x = key(a), y = key(b);
    if (x === '' && y !== '') return 1;
    if (y === '' && x !== '') return -1;
    const c = typeof x === typeof y ? (x < y ? -1 : x > y ? 1 : 0) : (typeof x === 'number' ? -1 : 1);
    return asc ? c : -c;
  });
  tbody.append(...rows);
}

document.addEventListener('click', (e) => {
  const t = e.target;
  const toggle = t.closest('[data-dsh-toggle-sidebar]');
  if (toggle) {
    toggle.closest('.dsh-layout-sidebar').classList.toggle('dsh-sidebar-collapsed');
    return;
  }
  const fs = t.closest('[data-dsh-fullscreen]');
  if (fs) {
    const on = fs.closest('.dsh-card').classList.toggle('dsh-fullscreen');
    document.documentElement.classList.toggle('dsh-has-fullscreen', on);
    return;
  }
  const th = t.closest('.dsh-table th');
  if (th) { sortTable(th); return; }
  if (t.closest('.dsh-download.dsh-disabled')) e.preventDefault();
});

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  const card = document.querySelector('.dsh-card.dsh-fullscreen');
  if (card) {
    card.classList.remove('dsh-fullscreen');
    document.documentElement.classList.remove('dsh-has-fullscreen');
  }
});

window.Dashboards = { mergePatch, getPath };
