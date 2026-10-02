// clj-dashboards browser client.
//
// Finds inputs ([data-input-id]) and outputs ([data-output-id]) on the
// page, opens a websocket to the session, sends input values as they
// change, and swaps in the HTML the server renders for outputs.
// No dependencies, no build step.
(function () {
  'use strict';

  var ws = null;
  var sessionId = null;
  var ready = false;
  var outbox = [];
  var timers = {};
  var sizes = {};
  var resizeObserver = null;

  // ---------------------------------------------------------------------
  // Connection

  function baseUrl() {
    var path = window.location.pathname;
    if (!path.endsWith('/')) path = path.substring(0, path.lastIndexOf('/') + 1);
    return path;
  }

  function wsUrl() {
    var proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return proto + '//' + window.location.host + baseUrl() + '_dashboards/ws';
  }

  function send(msg) {
    if (ws && ws.readyState === WebSocket.OPEN && ready) {
      ws.send(JSON.stringify(msg));
    } else {
      outbox.push(msg);
    }
  }

  function connect() {
    ws = new WebSocket(wsUrl());
    ws.onopen = function () {
      var init = collectInputs(document);
      ws.send(JSON.stringify({ type: 'init', inputs: init.values, kinds: init.kinds }));
      ready = true;
      while (outbox.length) ws.send(JSON.stringify(outbox.shift()));
    };
    ws.onmessage = function (e) {
      var msg;
      try { msg = JSON.parse(e.data); } catch (err) { return; }
      var handler = handlers[msg.type];
      if (handler) handler(msg);
    };
    ws.onclose = function () {
      ready = false;
      document.documentElement.classList.remove('dsh-busy');
      var overlay = document.querySelector('.dsh-disconnected');
      if (overlay) overlay.hidden = false;
    };
    setInterval(function () {
      if (ws && ws.readyState === WebSocket.OPEN) ws.send('{"type":"ping"}');
    }, 25000);
  }

  // ---------------------------------------------------------------------
  // Inputs

  function inputValue(el) {
    var type = el.getAttribute('data-input-type');
    switch (type) {
      case 'number':
        return { value: el.value === '' ? null : Number(el.value) };
      case 'slider':
        return { value: Number(el.value) };
      case 'checkbox':
        return { value: !!el.checked };
      case 'select':
        if (el.multiple) {
          return {
            value: Array.prototype.filter.call(el.options, function (o) { return o.selected; })
              .map(function (o) { return o.value; }),
            kind: 'edn'
          };
        }
        return { value: el.value === '' ? null : el.value, kind: 'edn' };
      case 'checkbox-group':
        return {
          value: Array.prototype.map.call(el.querySelectorAll('input:checked'), function (i) { return i.value; }),
          kind: 'edn'
        };
      case 'radio':
        var checked = el.querySelector('input:checked');
        return { value: checked ? checked.value : null, kind: 'edn' };
      case 'date':
        return { value: el.value || null, kind: 'date' };
      case 'action':
        var n = Number(el.getAttribute('data-count') || 0);
        return { value: n === 0 ? null : n };
      case 'tabs':
        var active = el.querySelector(':scope > .dsh-tabs .dsh-tab.dsh-active, :scope > .dsh-header .dsh-tab.dsh-active');
        return { value: active ? active.getAttribute('data-dsh-tab') : null, kind: 'edn' };
      default:
        return { value: el.value };
    }
  }

  function sendInput(el) {
    var id = el.getAttribute('data-input-id');
    var v = inputValue(el);
    send({ type: 'input', id: id, value: v.value, kind: v.kind || null });
  }

  function sendInputLater(el, ms) {
    var id = el.getAttribute('data-input-id');
    clearTimeout(timers[id]);
    timers[id] = setTimeout(function () { sendInput(el); }, ms);
  }

  function updateSliderLabel(el) {
    var wrap = el.closest('.dsh-input-slider');
    var out = wrap && wrap.querySelector('.dsh-slider-value');
    if (out) {
      out.textContent = (out.getAttribute('data-prefix') || '') + el.value + (out.getAttribute('data-suffix') || '');
    }
    var min = Number(el.min || 0), max = Number(el.max || 100);
    var pct = max > min ? ((Number(el.value) - min) / (max - min)) * 100 : 0;
    el.style.setProperty('--dsh-fill', pct + '%');
  }

  function bindInput(el) {
    if (el.__dshBound) return false;
    el.__dshBound = true;
    var type = el.getAttribute('data-input-type');
    switch (type) {
      case 'text':
      case 'number':
        el.addEventListener('input', function () { sendInputLater(el, 300); });
        el.addEventListener('change', function () { sendInputLater(el, 0); });
        break;
      case 'slider':
        updateSliderLabel(el);
        el.addEventListener('input', function () { updateSliderLabel(el); sendInputLater(el, 120); });
        break;
      case 'action':
        el.addEventListener('click', function () {
          el.setAttribute('data-count', Number(el.getAttribute('data-count') || 0) + 1);
          sendInput(el);
        });
        break;
      case 'tabs':
        break; // handled by the tab click handler
      default:
        el.addEventListener('change', function () { sendInput(el); });
    }
    return true;
  }

  function findAll(root, selector) {
    var found = Array.prototype.slice.call(root.querySelectorAll(selector));
    if (root.matches && root.matches(selector)) found.unshift(root);
    return found;
  }

  // Binds every not-yet-bound input under root and returns their values.
  function collectInputs(root) {
    var values = {}, kinds = {};
    findAll(root, '[data-input-id]').forEach(function (el) {
      if (!bindInput(el)) return;
      var id = el.getAttribute('data-input-id');
      var v = inputValue(el);
      values[id] = v.value;
      if (v.kind) kinds[id] = v.kind;
    });
    findAll(root, '[data-output-id]').forEach(function (el) {
      bindOutput(el);
      var s = measure(el);
      if (s) {
        var id = el.getAttribute('data-output-id');
        values['clientdata/output-' + id + '-width'] = s[0];
        values['clientdata/output-' + id + '-height'] = s[1];
        sizes[id] = s;
      }
    });
    return { values: values, kinds: kinds };
  }

  // ---------------------------------------------------------------------
  // Outputs

  function measure(el) {
    if (!el.classList.contains('dsh-output-plot')) return null;
    var r = el.getBoundingClientRect();
    var w = Math.round(r.width), h = Math.round(r.height);
    return w > 0 && h > 0 ? [w, h] : null;
  }

  function onResize(entries) {
    entries.forEach(function (entry) {
      var el = entry.target;
      var id = el.getAttribute('data-output-id');
      var s = measure(el);
      if (!s) return;
      var old = sizes[id];
      if (old && Math.abs(old[0] - s[0]) < 2 && Math.abs(old[1] - s[1]) < 2) return;
      sizes[id] = s;
      clearTimeout(timers['size:' + id]);
      timers['size:' + id] = setTimeout(function () {
        var values = {};
        values['clientdata/output-' + id + '-width'] = s[0];
        values['clientdata/output-' + id + '-height'] = s[1];
        send({ type: 'inputs', inputs: values });
      }, 250);
    });
  }

  function bindOutput(el) {
    if (el.__dshBound) return;
    el.__dshBound = true;
    if (el.classList.contains('dsh-output-plot')) {
      if (resizeObserver) resizeObserver.observe(el);
      if (el.getAttribute('data-brush')) enableBrush(el);
      if (el.getAttribute('data-click')) enableClick(el);
    }
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function setOutput(msg) {
    var el = document.querySelector('[data-output-id="' + CSS.escape(msg.id) + '"]');
    if (!el) return;
    el.classList.remove('dsh-recalculating', 'dsh-has-error', 'dsh-has-validation');
    // A redrawn plot's row indices may mean something new, so a brush
    // does not survive a redraw.
    if (el.__dshBrush) clearBrush(el);
    if (msg.status === 'error') {
      el.classList.add('dsh-has-error');
      el.innerHTML = '<div class="dsh-output-error"><strong>Error</strong> ' + escapeHtml(msg.message) + '</div>';
    } else if (msg.status === 'validation') {
      el.classList.add('dsh-has-validation');
      el.innerHTML = '<div class="dsh-output-validation">' + escapeHtml(msg.message) + '</div>';
    } else {
      el.innerHTML = msg.html;
    }
    // Rendered HTML may hold new inputs and outputs (render/ui).
    var found = collectInputs(el);
    var newInputs = Object.keys(found.values).filter(function (k) { return k.indexOf('clientdata/') !== 0; });
    if (newInputs.length) send({ type: 'inputs', inputs: found.values, kinds: found.kinds });
    var newOutputs = findAll(el, '[data-output-id]').filter(function (o) { return o !== el; })
      .map(function (o) { return o.getAttribute('data-output-id'); });
    if (newOutputs.length) send({ type: 'bind', outputs: newOutputs });
    refreshDownloads(el);
  }

  // ---------------------------------------------------------------------
  // Plot interaction: brushing and clicking points

  function points(el) {
    return Array.prototype.slice.call(el.querySelectorAll('svg [data-row-idx]'));
  }

  function clearBrush(el) {
    var b = el.__dshBrush;
    if (b && b.rect) b.rect.remove();
    points(el).forEach(function (p) { p.removeAttribute('data-dsh-dim'); });
    el.classList.remove('dsh-brushing');
    var had = b && b.selected;
    el.__dshBrush = null;
    if (had) send({ type: 'input', id: el.getAttribute('data-brush'), value: null });
  }

  function enableBrush(el) {
    el.classList.add('dsh-brushable');
    el.addEventListener('mousedown', function (e) {
      if (e.button !== 0 || !el.querySelector('svg')) return;
      e.preventDefault();
      var box = el.getBoundingClientRect();
      var x0 = e.clientX, y0 = e.clientY;
      var rect = document.createElement('div');
      rect.className = 'dsh-brush-rect';
      el.appendChild(rect);
      var moved = false;
      function place(x1, y1) {
        rect.style.left = (Math.min(x0, x1) - box.left) + 'px';
        rect.style.top = (Math.min(y0, y1) - box.top) + 'px';
        rect.style.width = Math.abs(x1 - x0) + 'px';
        rect.style.height = Math.abs(y1 - y0) + 'px';
      }
      function move(ev) {
        if (Math.abs(ev.clientX - x0) > 3 || Math.abs(ev.clientY - y0) > 3) moved = true;
        place(ev.clientX, ev.clientY);
      }
      function up(ev) {
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
        if (!moved) { rect.remove(); clearBrush(el); return; }
        var x1 = ev.clientX, y1 = ev.clientY;
        var l = Math.min(x0, x1), r = Math.max(x0, x1), t = Math.min(y0, y1), b = Math.max(y0, y1);
        var selected = {};
        points(el).forEach(function (p) {
          var pr = p.getBoundingClientRect();
          var cx = pr.left + pr.width / 2, cy = pr.top + pr.height / 2;
          var inside = cx >= l && cx <= r && cy >= t && cy <= b;
          if (inside) selected[p.getAttribute('data-row-idx')] = true;
        });
        var rows = Object.keys(selected).map(Number).sort(function (a, b) { return a - b; });
        if (el.__dshBrush && el.__dshBrush.rect) el.__dshBrush.rect.remove();
        el.__dshBrush = { rect: rect, selected: true };
        el.classList.add('dsh-brushing');
        points(el).forEach(function (p) {
          if (selected[p.getAttribute('data-row-idx')]) p.removeAttribute('data-dsh-dim');
          else p.setAttribute('data-dsh-dim', '');
        });
        send({ type: 'input', id: el.getAttribute('data-brush'), value: rows });
      }
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    });
  }

  function enableClick(el) {
    el.addEventListener('click', function (e) {
      var p = e.target.closest && e.target.closest('[data-row-idx]');
      if (!p || !el.contains(p)) return;
      send({ type: 'input', id: el.getAttribute('data-click'), value: Number(p.getAttribute('data-row-idx')) });
    });
  }

  // Tooltips: plotje marks carry data-tooltip (text) or data-tooltip-html.
  var tip = null;
  function showTip(e) {
    var t = e.target.closest && e.target.closest('[data-tooltip],[data-tooltip-html]');
    if (!t) { if (tip) tip.hidden = true; return; }
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'dsh-tooltip';
      document.body.appendChild(tip);
    }
    var html = t.getAttribute('data-tooltip-html');
    if (html) tip.innerHTML = html; else tip.textContent = t.getAttribute('data-tooltip');
    tip.hidden = false;
    tip.style.left = (e.clientX + 12) + 'px';
    tip.style.top = (e.clientY + 12) + 'px';
  }

  // ---------------------------------------------------------------------
  // Server messages

  function setChoices(el, props) {
    var type = el.getAttribute('data-input-type');
    var selected = props.selected;
    var isSelected = function (v) {
      if (selected === undefined || selected === null) return false;
      return Array.isArray(selected) ? selected.indexOf(v) >= 0 : selected === v;
    };
    if (type === 'select') {
      el.innerHTML = '';
      props.choices.forEach(function (c, i) {
        var o = document.createElement('option');
        o.value = c.value; o.textContent = c.label;
        o.selected = isSelected(c.value) || (!el.multiple && selected == null && i === 0);
        el.appendChild(o);
      });
    } else if (type === 'checkbox-group' || type === 'radio') {
      el.querySelectorAll('label.dsh-check').forEach(function (l) { l.remove(); });
      var name = el.getAttribute('data-input-id');
      props.choices.forEach(function (c, i) {
        var label = document.createElement('label');
        label.className = 'dsh-check';
        var input = document.createElement('input');
        input.type = type === 'radio' ? 'radio' : 'checkbox';
        input.name = name; input.value = c.value;
        input.checked = isSelected(c.value) || (type === 'radio' && selected == null && i === 0);
        var span = document.createElement('span');
        span.textContent = c.label;
        label.appendChild(input); label.appendChild(span);
        el.appendChild(label);
      });
    }
  }

  function setSelected(el, selected) {
    var type = el.getAttribute('data-input-type');
    var sel = Array.isArray(selected) ? selected : [selected];
    if (type === 'select') {
      Array.prototype.forEach.call(el.options, function (o) { o.selected = sel.indexOf(o.value) >= 0; });
    } else {
      el.querySelectorAll('input').forEach(function (i) { i.checked = sel.indexOf(i.value) >= 0; });
    }
  }

  function updateInput(msg) {
    var el = document.querySelector('[data-input-id="' + CSS.escape(msg.id) + '"]');
    if (!el) return;
    var p = msg.props || {};
    var type = el.getAttribute('data-input-type');
    if (p.label !== undefined) {
      var wrap = el.closest('.dsh-input');
      var label = wrap && wrap.querySelector('.dsh-label > span, .dsh-label, .dsh-check > span');
      if (label) label.textContent = p.label;
    }
    ['min', 'max', 'step'].forEach(function (k) { if (p[k] !== undefined) el.setAttribute(k, p[k]); });
    if (p.choices) setChoices(el, p);
    else if (p.selected !== undefined) setSelected(el, p.selected);
    if (p.value !== undefined) {
      if (type === 'checkbox') el.checked = !!p.value;
      else el.value = p.value == null ? '' : p.value;
    }
    if (type === 'slider') updateSliderLabel(el);
    sendInput(el);
  }

  function notify(msg) {
    var box = document.querySelector('.dsh-notifications');
    if (!box) return;
    var n = document.createElement('div');
    n.className = 'dsh-notification dsh-level-' + (msg.level || 'info');
    n.setAttribute('role', msg.level === 'error' ? 'alert' : 'status');
    var text = document.createElement('div');
    text.textContent = msg.message;
    var close = document.createElement('button');
    close.type = 'button'; close.className = 'dsh-notification-close';
    close.setAttribute('aria-label', 'Dismiss'); close.textContent = '×';
    close.onclick = function () { n.remove(); };
    n.appendChild(text); n.appendChild(close);
    box.appendChild(n);
    if (msg.duration) setTimeout(function () { n.remove(); }, msg.duration);
  }

  function refreshDownloads(root) {
    if (!sessionId) return;
    findAll(root || document, '[data-download-id]').forEach(function (a) {
      a.href = '_dashboards/download/' + encodeURIComponent(sessionId) + '/' +
        encodeURIComponent(a.getAttribute('data-download-id'));
      a.classList.remove('dsh-disabled');
    });
  }

  var handlers = {
    hello: function (msg) { sessionId = msg.session; refreshDownloads(document); },
    output: setOutput,
    recalculating: function (msg) {
      var el = document.querySelector('[data-output-id="' + CSS.escape(msg.id) + '"]');
      if (el) el.classList.add('dsh-recalculating');
    },
    busy: function () { document.documentElement.classList.add('dsh-busy'); },
    idle: function () { document.documentElement.classList.remove('dsh-busy'); },
    'update-input': updateInput,
    notification: notify,
    reload: function () { window.location.reload(); }
  };

  // ---------------------------------------------------------------------
  // Purely client-side behaviour: tabs, sidebars, full screen, sorting

  function activateTab(tab) {
    var navset = tab.closest('.dsh-navset');
    if (!navset) return;
    var value = tab.getAttribute('data-dsh-tab');
    var own = function (el) { return el.closest('.dsh-navset') === navset; };
    navset.querySelectorAll('[data-dsh-tab]').forEach(function (t) {
      if (!own(t)) return;
      var on = t.getAttribute('data-dsh-tab') === value;
      t.classList.toggle('dsh-active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    navset.querySelectorAll('[data-dsh-pane]').forEach(function (p) {
      if (own(p)) p.classList.toggle('dsh-active', p.getAttribute('data-dsh-pane') === value);
    });
    if (navset.getAttribute('data-input-id')) sendInput(navset);
  }

  function sortTable(th) {
    var table = th.closest('table');
    if (!table || table.getAttribute('data-sortable') !== 'true') return;
    var idx = Array.prototype.indexOf.call(th.parentNode.children, th);
    var asc = th.getAttribute('data-sort') !== 'asc';
    table.querySelectorAll('th').forEach(function (h) { h.removeAttribute('data-sort'); });
    th.setAttribute('data-sort', asc ? 'asc' : 'desc');
    var tbody = table.tBodies[0];
    var rows = Array.prototype.slice.call(tbody.rows);
    var key = function (r) {
      var t = r.cells[idx] ? r.cells[idx].textContent : '';
      var n = Number(t.replace(/,/g, ''));
      return t !== '' && !isNaN(n) ? n : t.toLowerCase();
    };
    rows.sort(function (a, b) {
      var x = key(a), y = key(b);
      if (x === '' && y !== '') return 1;
      if (y === '' && x !== '') return -1;
      var c = (typeof x === typeof y) ? (x < y ? -1 : x > y ? 1 : 0) : (typeof x === 'number' ? -1 : 1);
      return asc ? c : -c;
    });
    rows.forEach(function (r) { tbody.appendChild(r); });
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    var tab = t.closest('[data-dsh-tab]');
    if (tab) { activateTab(tab); return; }
    var toggle = t.closest('[data-dsh-toggle-sidebar]');
    if (toggle) {
      toggle.closest('.dsh-layout-sidebar').classList.toggle('dsh-sidebar-collapsed');
      return;
    }
    var fs = t.closest('[data-dsh-fullscreen]');
    if (fs) {
      var card = fs.closest('.dsh-card');
      var on = card.classList.toggle('dsh-fullscreen');
      document.documentElement.classList.toggle('dsh-has-fullscreen', on);
      return;
    }
    var th = t.closest('.dsh-table th');
    if (th) { sortTable(th); return; }
    var dl = t.closest('.dsh-download.dsh-disabled');
    if (dl) e.preventDefault();
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      var card = document.querySelector('.dsh-card.dsh-fullscreen');
      if (card) {
        card.classList.remove('dsh-fullscreen');
        document.documentElement.classList.remove('dsh-has-fullscreen');
      }
    }
  });

  document.addEventListener('mousemove', showTip);

  function start() {
    if (window.ResizeObserver) resizeObserver = new ResizeObserver(onResize);
    connect();
  }

  window.Dashboards = {
    send: send,
    sessionId: function () { return sessionId; }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
