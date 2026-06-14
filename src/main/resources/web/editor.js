// ================================================================
//  Loom Web Editor  ·  editor.js
// ================================================================

// ── API client ───────────────────────────────────────────────────
const API = {
  sessionId: null,
  _h() {
    return { 'Content-Type': 'application/json', 'X-Session-Id': this.sessionId || '' };
  },
  async _req(url, opts) {
    const r = await fetch(url, { headers: this._h(), ...opts });
    if (!r.ok) { const t = await r.text(); throw new Error(t || r.statusText); }
    return r.json();
  },
  get(p)    { return this._req(p); },
  post(p,b) { return this._req(p, { method:'POST',  body: JSON.stringify(b) }); },
  put(p,b)  { return this._req(p, { method:'PUT',   body: JSON.stringify(b) }); },
  del(p)    { return this._req(p, { method:'DELETE' }); },
};

// ── State ────────────────────────────────────────────────────────
const S = {
  player: '',
  tabs: [],          // [{ name, dirty }]
  activeTab: null,
  activity: 'files',
  sidebarOpen: true,
  scripts: [],       // [{ name, state }]
  scriptStates: {},  // { name: state }
  filter: '',
  bottomTab: 'problems',
  diagCounts: { errors: 0, warnings: 0 },
};

// Monaco handles
let editor         = null;
let ws             = null;
let validateTimer  = null;
let unreachDecs    = null;
const editorModels = {};   // { name: ITextModel }
const pendingOpens = new Set(); // guard against concurrent openTab calls for same name

// ── Settings defaults ──────────────────────────────────────────────
const DEFAULTS = {
  fontSize: 14, fontFamily: "'JetBrains Mono', monospace",
  tabSize: 2, wordWrap: 'off', minimap: false, lineNumbers: 'on',
  renderWhitespace: 'selection', smoothScrolling: true, scrollBeyondLastLine: false,
  cursorStyle: 'line', cursorBlinking: 'smooth', bracketPairColorization: true,
  accentColor: '#5b8af0', uiFontSize: 13, showStatusBar: true,
  autoValidate: true, validateDelay: 600, confirmClose: true,
  commitMsgTemplate: 'Update {name}.loom',
};
let CFG = Object.assign({}, DEFAULTS);

// ── Context menu ──────────────────────────────────────────────────
const ctxMenu = (function() {
  var el = null, _dismiss = null;
  function _el() {
    if (!el) { el = document.createElement('div'); el.id = 'ctx-menu'; document.body.appendChild(el); }
    return el;
  }
  function show(items, x, y) {
    var m = _el();
    m.innerHTML = items.map(function(item, i) {
      if (item === null) return '<div class="ctx-sep"></div>';
      var cls = 'ctx-item' + (item.danger ? ' ctx-danger' : '') + (item.disabled ? ' ctx-disabled' : '');
      return '<div class="' + cls + '" data-idx="' + i + '">'
        + '<span class="ctx-icon">' + (item.icon || '') + '</span>'
        + '<span class="ctx-label">' + esc(item.label) + '</span>'
        + (item.key ? '<span class="ctx-key">' + esc(item.key) + '</span>' : '')
        + '</div>';
    }).join('');
    m.style.display = 'block';
    m.style.left = x + 'px';
    m.style.top  = y + 'px';
    requestAnimationFrame(function() {
      var r = m.getBoundingClientRect();
      if (r.right  > window.innerWidth  - 6) m.style.left = (x - r.width)  + 'px';
      if (r.bottom > window.innerHeight - 6) m.style.top  = (y - r.height) + 'px';
    });
    m.onclick = function(ev) {
      var it = ev.target.closest('.ctx-item');
      if (!it || it.classList.contains('ctx-disabled')) return;
      var idx = parseInt(it.dataset.idx, 10);
      hide();
      if (items[idx] && items[idx].fn) items[idx].fn();
    };
    if (_dismiss) document.removeEventListener('mousedown', _dismiss, true);
    _dismiss = function(ev) { if (!m.contains(ev.target)) hide(); };
    setTimeout(function() { document.addEventListener('mousedown', _dismiss, true); }, 0);
  }
  function hide() {
    if (el) el.style.display = 'none';
    if (_dismiss) { document.removeEventListener('mousedown', _dismiss, true); _dismiss = null; }
  }
  return { show: show, hide: hide };
}());

// ── SVG icons ─────────────────────────────────────────────────────
const SVG = {
  filePlus: '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M7.5 1.5H3a1 1 0 00-1 1v8a1 1 0 001 1h7a1 1 0 001-1V5.5L7.5 1.5z"/><polyline points="7.5,1.5 7.5,6 11,6"/><line x1="5" y1="9" x2="9" y2="9"/><line x1="7" y1="7" x2="7" y2="11"/></svg>',
  save:    '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6.5 1.5v7.5M4 6.5l2.5 2.5L9 6.5"/><line x1="2" y1="11.5" x2="11" y2="11.5"/></svg>',
  play:    '<svg width="11" height="12" viewBox="0 0 11 12" fill="currentColor"><polygon points="1,0.5 10.5,6 1,11.5"/></svg>',
  stop:    '<svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor"><rect width="10" height="10" rx="1.5"/></svg>',
  refresh: '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M11 6.5A4.5 4.5 0 116.5 2"/><polyline points="11,2 11,6.5 6.5,6.5"/></svg>',
  check:   '<svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><polyline points="1.5,6 4.5,9.5 10.5,2.5"/></svg>',
  pencil:  '<svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8.5 1.5l2 2-7 7H1.5v-2l7-7z"/></svg>',
  trash:   '<svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="1,3 11,3"/><path d="M4 3V2a.75.75 0 01.75-.75h2.5A.75.75 0 018 2v1"/><path d="M2 3l.75 7.5h6.5L10 3"/></svg>',
  sidebar: '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><rect x="1" y="1" width="11" height="11" rx="2"/><line x1="4.5" y1="1" x2="4.5" y2="12"/></svg>',
  warning: '<svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 1L11.5 11H0.5L6 1z"/><line x1="6" y1="5" x2="6" y2="7.5"/><circle cx="6" cy="9.5" r="0.5" fill="currentColor" stroke="none"/></svg>',
  git:     '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><circle cx="4" cy="3" r="1.5"/><circle cx="4" cy="10" r="1.5"/><circle cx="9.5" cy="5" r="1.5"/><line x1="4" y1="4.5" x2="4" y2="8.5"/><path d="M4 4.5C4 7 8 7 8 5"/></svg>',
  terminal:'<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="1" y="1" width="11" height="11" rx="2"/><polyline points="3.5,4.5 5.5,6.5 3.5,8.5"/><line x1="6.5" y1="8.5" x2="9.5" y2="8.5"/></svg>',
  folder:  '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M1 4A1.5 1.5 0 012.5 2.5h2.59a1.5 1.5 0 011.06.44L7 3.5H10.5A1.5 1.5 0 0112 5v5a1.5 1.5 0 01-1.5 1.5h-9A1.5 1.5 0 011 10V4z"/></svg>',
  copy:    '<svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="7" height="7" rx="1.5"/><path d="M8 4V2.5A1.5 1.5 0 006.5 1h-5A1.5 1.5 0 000 2.5v5A1.5 1.5 0 001.5 9H3"/></svg>',
  close:   '<svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="1" y1="1" x2="9" y2="9"/><line x1="9" y1="1" x2="1" y2="9"/></svg>',
  user:    '<svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="4" r="2.5"/><path d="M1.5 11c0-2.5 2-4 4.5-4s4.5 1.5 4.5 4"/></svg>',
  gear:    '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="6.5" cy="6.5" r="2"/><path d="M6.5 1v1.7M6.5 11.3V13M1 6.5h1.7M11.3 6.5H13M2.55 2.55l1.2 1.2M9.25 9.25l1.2 1.2M10.45 2.55l-1.2 1.2M3.75 9.25l-1.2 1.2"/></svg>',
  success: '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polyline points="2,6.5 5,9.5 11,3.5"/></svg>',
  error:   '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="2.5" y1="2.5" x2="10.5" y2="10.5"/><line x1="10.5" y1="2.5" x2="2.5" y2="10.5"/></svg>',
  info:    '<svg width="13" height="13" viewBox="0 0 13 13" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><line x1="6.5" y1="5.5" x2="6.5" y2="9.5"/><circle cx="6.5" cy="3.5" r="0.7" fill="currentColor" stroke="none"/></svg>',
  extLink: '<svg width="11" height="11" viewBox="0 0 11 11" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4.5 1H1.5A.5.5 0 001 1.5v8a.5.5 0 00.5.5h8a.5.5 0 00.5-.5V7"/><polyline points="7,1 10,1 10,4"/><line x1="5.5" y1="5.5" x2="10" y2="1"/></svg>',
};

// ── Utils ─────────────────────────────────────────────────────────
function esc(s)  { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function escJ(s) { return String(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'"); }
function el(id)  { return document.getElementById(id); }

// ── Tabs ──────────────────────────────────────────────────────────
async function openTab(name) {
  if (S.tabs.find(t => t.name === name)) { activateTab(name); return; }
  if (pendingOpens.has(name)) return;
  pendingOpens.add(name);
  try {
    const data = await API.get('/scripts/' + name);
    if (!S.tabs.find(t => t.name === name)) {
      S.tabs.push({ name, dirty: false });
      if (window.monaco) _createModel(name, data.source);
    }
    activateTab(name);
    await loadGitLog(name);
  } catch (err) {
    toast('Cannot open ' + name + ': ' + err.message, 'e');
  } finally {
    pendingOpens.delete(name);
  }
}

function _createModel(name, source) {
  if (editorModels[name] && !editorModels[name].isDisposed()) return editorModels[name];
  const uri = monaco.Uri.parse('loom://scripts/' + name + '.loom');
  const stale = monaco.editor.getModel(uri);
  if (stale) stale.dispose();
  const model = monaco.editor.createModel(source, 'loom', uri);
  model.onDidChangeContent(() => {
    _markDirty(name, true);
    if (CFG.autoValidate) {
      clearTimeout(validateTimer);
      validateTimer = setTimeout(validateCurrent, CFG.validateDelay);
    }
  });
  editorModels[name] = model;
  return model;
}

function activateTab(name) {
  S.activeTab = name;
  const model = editorModels[name];
  if (editor && model) editor.setModel(model);
  renderTabs();
  updateBreadcrumb();
  updateStatusBar();
  document.querySelectorAll('.script-item').forEach(
    e => e.classList.toggle('active', e.dataset.name === name)
  );
  if (name) validateCurrent();
}

function closeTab(name, ev) {
  ev && ev.stopPropagation();
  const tab = S.tabs.find(t => t.name === name);
  if (tab && tab.dirty && CFG.confirmClose) {
    if (!confirm(name + '.loom has unsaved changes. Close anyway?')) return;
  }
  const model = editorModels[name];
  if (model) { model.dispose(); delete editorModels[name]; }
  S.tabs = S.tabs.filter(t => t.name !== name);
  if (S.activeTab === name) {
    const next = S.tabs[S.tabs.length - 1];
    S.activeTab = null;
    if (next) activateTab(next.name);
    else {
      if (editor) editor.setModel(null);
      updateBreadcrumb();
      updateStatusBar();
    }
  }
  renderTabs();
}

function _markDirty(name, dirty) {
  const tab = S.tabs.find(t => t.name === name);
  if (tab && tab.dirty !== dirty) { tab.dirty = dirty; renderTabs(); }
}

function renderTabs() {
  const container = el('tabs');
  if (!container) return;
  container.innerHTML = S.tabs.map(t => {
    const state  = S.scriptStates[t.name] || 'UNLOADED';
    const active = t.name === S.activeTab;
    return `<div class="editor-tab${active ? ' active' : ''}" data-name="${esc(t.name)}"
              onclick="activateTab('${escJ(t.name)}')" title="${esc(t.name)}.loom">
      <span class="script-status s-${state}"></span>
      <span class="tab-name-text">${esc(t.name)}.loom</span>
      ${t.dirty ? '<span class="tab-dirty" title="Unsaved changes">●</span>' : ''}
      <button class="tab-close-btn" onclick="closeTab('${escJ(t.name)}',event)" title="Close">&#215;</button>
    </div>`;
  }).join('');
}

// ── Toast ─────────────────────────────────────────────────────────
function toast(msg, type, duration) {
  type     = type     || 'i';
  duration = duration || 3500;
  const icons = { s: SVG.success, e: SVG.error, w: SVG.warning, i: SVG.info };
  const container = el('toast-container');
  const div = document.createElement('div');
  div.className = 'toast toast-' + type;
  div.innerHTML = '<span class="toast-icon">' + (icons[type] || '·') + '</span>'
                + '<span class="toast-text">' + esc(msg) + '</span>';
  container.appendChild(div);
  requestAnimationFrame(function() { div.classList.add('toast-in'); });
  setTimeout(function() {
    div.classList.remove('toast-in');
    setTimeout(function() { div.remove(); }, 280);
  }, duration);
}

// ── Command palette ────────────────────────────────────────────────
var COMMANDS = [
  { label:'New Script',       key:'Ctrl+N',        icon: SVG.filePlus, fn: function() { newScript(); } },
  { label:'Save Script',      key:'Ctrl+S',        icon: SVG.save,     fn: function() { saveScript(); } },
  { label:'Load Script',      key:'Ctrl+Enter',    icon: SVG.play,     fn: function() { runScript(); } },
  { label:'Unload Script',    key:'',              icon: SVG.stop,     fn: function() { stopScript(); } },
  { label:'Reload Script',    key:'Ctrl+Shift+R',  icon: SVG.refresh,  fn: function() { reloadScript(); } },
  { label:'Validate',         key:'',              icon: SVG.check,    fn: function() { validateScript(); } },
  { label:'Rename Script',    key:'',              icon: SVG.pencil,   fn: function() { renameScript(); } },
  { label:'Delete Script',    key:'',              icon: SVG.trash,    fn: function() { deleteScript(); } },
  { label:'Toggle Sidebar',   key:'Ctrl+B',        icon: SVG.sidebar,  fn: function() { toggleSidebar(); } },
  { label:'Refresh Scripts',  key:'',              icon: SVG.refresh,  fn: function() { refreshScripts(); } },
  { label:'Show Problems',    key:'',              icon: SVG.warning,  fn: function() { showBottomTab('problems'); } },
  { label:'Show Git Log',     key:'',              icon: SVG.git,      fn: function() { showBottomTab('git'); } },
  { label:'Show Output',      key:'',              icon: SVG.terminal, fn: function() { showBottomTab('output'); } },
  { label:'Files View',       key:'',              icon: SVG.folder,   fn: function() { setActivity('files'); } },
  { label:'Git View',         key:'',              icon: SVG.git,      fn: function() { setActivity('git'); } },
  { label:'Settings',         key:'Ctrl+,',        icon: SVG.gear,     fn: function() { openSettings(); } },
];
var filteredCmds = COMMANDS;
var cmdIdx = 0;

function openCommandPalette() {
  el('cmd-overlay').classList.add('open');
  el('cmd-input').value = '';
  _filterCmds('');
  setTimeout(function() { el('cmd-input').focus(); }, 10);
}
function closeCommandPalette() { el('cmd-overlay').classList.remove('open'); }
function _filterCmds(q) {
  var lo = q.toLowerCase();
  filteredCmds = q ? COMMANDS.filter(function(c) { return c.label.toLowerCase().indexOf(lo) !== -1; }) : COMMANDS;
  cmdIdx = 0;
  _renderCmds();
}
function _renderCmds() {
  el('cmd-list').innerHTML = filteredCmds.map(function(c, i) {
    return '<div class="cmd-item' + (i === cmdIdx ? ' cmd-selected' : '') + '" onclick="runCommand(' + i + ')">'
      + '<span class="cmd-item-icon">' + c.icon + '</span>'
      + '<span class="cmd-label">' + esc(c.label) + '</span>'
      + (c.key ? '<span class="cmd-key">' + esc(c.key) + '</span>' : '')
      + '</div>';
  }).join('');
}
function runCommand(i) {
  closeCommandPalette();
  if (filteredCmds[i]) filteredCmds[i].fn();
}

// ── Modal ──────────────────────────────────────────────────────────
function _modal(opts) {
  var title      = opts.title      || '';
  var message    = opts.message    || '';
  var hasInput   = opts.hasInput   || false;
  var defaultVal = opts.defaultVal || '';
  var okLabel    = opts.okLabel    || 'OK';
  var danger     = opts.danger     || false;

  return new Promise(function(resolve) {
    var overlay = el('modal-overlay');
    var titleEl = el('modal-title');
    var msgEl   = el('modal-message');
    var inputEl = el('modal-input');
    var okBtn   = el('modal-ok');
    var cancelBtn = el('modal-cancel');

    titleEl.textContent   = title;
    titleEl.style.display = title ? '' : 'none';
    msgEl.textContent     = message;
    inputEl.style.display = hasInput ? '' : 'none';
    if (hasInput) inputEl.value = defaultVal;
    okBtn.textContent = okLabel;
    okBtn.className   = 'btn ' + (danger ? 'btn-danger' : 'btn-primary');
    overlay.classList.add('open');
    if (hasInput) setTimeout(function() { inputEl.focus(); inputEl.select(); }, 30);

    function finish(val) {
      overlay.classList.remove('open');
      okBtn.removeEventListener('click', onOk);
      cancelBtn.removeEventListener('click', onCancel);
      document.removeEventListener('keydown', onKey);
      overlay.removeEventListener('mousedown', onBd);
      resolve(val);
    }
    function onOk()     { finish(hasInput ? inputEl.value : true); }
    function onCancel() { finish(hasInput ? null : false); }
    function onKey(e)   { if (e.key === 'Enter') onOk(); else if (e.key === 'Escape') onCancel(); }
    function onBd(e)    { if (e.target === overlay) onCancel(); }

    okBtn.addEventListener('click', onOk);
    cancelBtn.addEventListener('click', onCancel);
    document.addEventListener('keydown', onKey);
    overlay.addEventListener('mousedown', onBd);
  });
}

function showPrompt(message, defaultVal, title) {
  return _modal({ title: title || '', message: message, hasInput: true, defaultVal: defaultVal || '' });
}
function showConfirm(message, danger, title) {
  return _modal({ title: title || '', message: message, okLabel: danger ? 'Delete' : 'Confirm', danger: !!danger });
}

// ── Auth ───────────────────────────────────────────────────────────
async function initAuth() {
  _setAuthStatus('Connecting…');
  var stored = sessionStorage.getItem('loom_session');
  if (stored) {
    try {
      var check = await fetch('/auth/status/' + stored).then(function(r) { return r.json(); });
      if (check.authenticated) {
        API.sessionId = stored;
        S.player = check.player;
        _hideAuth();
        return;
      }
    } catch (_) {}
  }

  var data;
  try {
    data = await fetch('/auth/init').then(function(r) { return r.json(); });
  } catch (_) { _setAuthStatus('Could not reach server.'); return; }

  API.sessionId = data.sessionId;
  sessionStorage.setItem('loom_session', data.sessionId);
  var codeMatch = (data.message || '').match(/confirm (\S+)/);
  var code = codeMatch ? codeMatch[1] : '???';
  el('auth-cmd').textContent = '/loom confirm ' + code;
  _setAuthStatus('Waiting for in-game confirmation…');

  var poll = setInterval(async function() {
    try {
      var s = await fetch('/auth/status/' + data.sessionId).then(function(r) { return r.json(); });
      if (s.authenticated) {
        clearInterval(poll);
        S.player = s.player;
        _hideAuth();
      }
    } catch (_) {}
  }, 2000);
}

function _setAuthStatus(msg) { var e = el('auth-status'); if (e) e.textContent = msg; }

function _hideAuth() {
  el('auth-overlay').style.display = 'none';
  updateStatusBar();
  initEditor();
  initWebSocket();
  refreshScripts();
}

// ── Monaco ─────────────────────────────────────────────────────────
function initEditor() {
  require.config({ paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs' } });
  require(['vs/editor/editor.main'], function() {
    if (window.registerLoomLanguage) window.registerLoomLanguage(monaco);

    editor = monaco.editor.create(el('editor-container'), {
      language: 'loom', theme: 'loom-dark', value: '',
      automaticLayout: true,
      fontSize: CFG.fontSize, fontFamily: CFG.fontFamily,
      lineNumbers: CFG.lineNumbers, minimap: { enabled: CFG.minimap },
      wordWrap: CFG.wordWrap, tabSize: CFG.tabSize, insertSpaces: true,
      scrollBeyondLastLine: CFG.scrollBeyondLastLine,
      renderWhitespace: CFG.renderWhitespace,
      bracketPairColorization: { enabled: CFG.bracketPairColorization },
      cursorStyle: CFG.cursorStyle, cursorBlinking: CFG.cursorBlinking,
      cursorSmoothCaretAnimation: 'on',
      padding: { top: 8, bottom: 8 },
      smoothScrolling: CFG.smoothScrolling,
      suggest: { showKeywords: true },
    });

    unreachDecs = editor.createDecorationsCollection([]);

    editor.onDidChangeCursorPosition(function(ev) {
      var pos = ev.position;
      var posEl = el('sb-pos');
      if (posEl) posEl.textContent = 'Ln ' + pos.lineNumber + ', Col ' + pos.column;
    });

    // Completion provider
    monaco.languages.registerCompletionItemProvider('loom', {
      triggerCharacters: ['.', ' '],
      provideCompletionItems: function(model, pos) {
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type:'complete',
            payload:{ source: model.getValue(), line: pos.lineNumber, col: pos.column } }));
        }
        var word  = model.getWordUntilPosition(pos);
        var range = { startLineNumber: pos.lineNumber, endLineNumber: pos.lineNumber,
          startColumn: word.startColumn, endColumn: word.endColumn };
        var kw = (window.LOOM_KEYWORDS || []).map(function(k) {
          return { label:k, kind: monaco.languages.CompletionItemKind.Keyword, insertText:k, range:range }; });
        var bi = (window.LOOM_BUILTINS || []).map(function(b) {
          return { label:b, kind: monaco.languages.CompletionItemKind.Function,
            insertText: b + '($0)',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range:range, detail:'built-in' }; });
        var evs = (window.LOOM_EVENTS || []).map(function(e) {
          return { label:e, kind: monaco.languages.CompletionItemKind.Event, insertText:e, range:range, detail:'event' }; });
        return { suggestions: kw.concat(bi).concat(evs) };
      }
    });

    // Hover provider
    monaco.languages.registerHoverProvider('loom', {
      provideHover: function(model, pos) {
        var word = model.getWordAtPosition(pos);
        if (!word) return null;
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type:'hover', payload:{ word: word.word } }));
        }
        var docs = window.LOOM_BUILTIN_DOCS || {};
        var doc  = docs[word.word];
        if (!doc) return null;
        return {
          range: new monaco.Range(pos.lineNumber, word.startColumn, pos.lineNumber, word.endColumn),
          contents: [{ value: '```\n' + doc + '\n```' }]
        };
      }
    });

    // Quick-fix code action provider
    monaco.languages.registerCodeActionProvider('loom', {
      provideCodeActions: function(model, range, ctx) {
        var markers = monaco.editor.getModelMarkers({ resource: model.uri, owner: 'loom' })
          .filter(function(m) {
            return m.startLineNumber <= range.endLineNumber && m.endLineNumber >= range.startLineNumber;
          });
        var actions = [];

        function lineEdit(title, marker, ln, sc, ec, text) {
          actions.push({ title:title, kind:'quickfix', diagnostics:[marker], isPreferred:true,
            edit:{ edits:[{ resource:model.uri, versionId:model.getVersionId(),
              textEdit:{ range: new monaco.Range(ln,sc,ln,ec), text:text } }] } });
        }
        function delLine(title, marker, ln) {
          actions.push({ title:title, kind:'quickfix', diagnostics:[marker], isPreferred:true,
            edit:{ edits:[{ resource:model.uri, versionId:model.getVersionId(),
              textEdit:{ range: new monaco.Range(ln,1,ln+1,1), text:'' } }] } });
        }

        for (var mi = 0; mi < markers.length; mi++) {
          var marker = markers[mi];
          var msg    = marker.message;
          var ln     = marker.startLineNumber;
          var line   = model.getLineContent(ln);

          var typo = msg.match(/Unknown event '(\w+)'.*did you mean '(\w+)'/);
          if (typo) {
            var wrong = typo[1], right = typo[2];
            var tidx = line.indexOf(wrong);
            if (tidx !== -1) lineEdit("Rename to '" + right + "'", marker, ln, tidx+1, tidx+1+wrong.length, right);
            continue;
          }
          if (msg.indexOf('Double negation') !== -1) {
            var dm = line.match(/^(\s*)(not\s+not\s+|!!)(.*)/);
            if (dm) lineEdit('Remove double negation', marker, ln, 1, line.length+1, dm[1]+dm[3]);
          }
          if (msg.indexOf("can be written as 'a != b'") !== -1) {
            var m1 = line.match(/not\s*\(([^)]+)==([^)]+)\)/);
            if (m1) { var i1=line.indexOf(m1[0]); var l1=m1[1].trim(),r1=m1[2].trim();
              lineEdit('Simplify to '+l1+' != '+r1, marker, ln, i1+1, i1+1+m1[0].length, l1+' != '+r1); }
          }
          if (msg.indexOf("can be written as 'a == b'") !== -1) {
            var m2 = line.match(/not\s*\(([^)]+)!=([^)]+)\)/);
            if (m2) { var i2=line.indexOf(m2[0]); var l2=m2[1].trim(),r2=m2[2].trim();
              lineEdit('Simplify to '+l2+' == '+r2, marker, ln, i2+1, i2+1+m2[0].length, l2+' == '+r2); }
          }
          if (msg.indexOf('has no effect') !== -1 &&
              (msg.indexOf('+= 0') !== -1 || msg.indexOf('-= 0') !== -1 || msg.indexOf('*= 1') !== -1))
            delLine('Remove no-op statement', marker, ln);
          if (msg.indexOf('Self-assignment') !== -1 && msg.indexOf('has no effect') !== -1)
            delLine('Remove self-assignment', marker, ln);
        }
        return { actions:actions, dispose:function(){} };
      }
    });

    // Loom actions in the Monaco right-click context menu
    editor.addAction({ id:'loom.save',     label:'Loom: Save & Commit',  contextMenuGroupId:'loom', contextMenuOrder:1, run: function() { saveScript(); } });
    editor.addAction({ id:'loom.load',     label:'Loom: Load Script',    contextMenuGroupId:'loom', contextMenuOrder:2, run: function() { runScript(); } });
    editor.addAction({ id:'loom.unload',   label:'Loom: Unload Script',  contextMenuGroupId:'loom', contextMenuOrder:3, run: function() { stopScript(); } });
    editor.addAction({ id:'loom.reload',   label:'Loom: Reload Script',  contextMenuGroupId:'loom', contextMenuOrder:4, run: function() { reloadScript(); } });
    editor.addAction({ id:'loom.validate', label:'Loom: Validate',       contextMenuGroupId:'loom', contextMenuOrder:5, run: function() { validateScript(); } });
    editor.addAction({ id:'loom.rename',   label:'Loom: Rename Script…', contextMenuGroupId:'loom', contextMenuOrder:6, run: function() { renameScript(); } });
  });
}

// ── WebSocket ──────────────────────────────────────────────────────
function initWebSocket() {
  var proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(proto + '://' + location.host + '/ws/' + API.sessionId);
  ws.onopen  = function() { _sbConn(true);  appendOutput('Connected to language server.', 'ok'); };
  ws.onclose = function() { _sbConn(false); setTimeout(initWebSocket, 3500); };
  ws.onmessage = function(ev) {
    try {
      var msg = JSON.parse(ev.data);
      if (msg.type === 'diagnostics') showDiagnostics(msg.diagnostics || []);
    } catch (_) {}
  };
}

function _sbConn(connected) {
  var e = el('sb-conn');
  if (!e) return;
  e.innerHTML = '<span class="sb-conn-dot" style="color:'
    + (connected ? 'var(--success)' : 'var(--error)') + '">●</span>'
    + (connected ? ' Connected' : ' Reconnecting…');
}

// ── Scripts ────────────────────────────────────────────────────────
async function refreshScripts() {
  try {
    var list = await API.get('/scripts');
    S.scripts = list;
    list.forEach(function(s) { S.scriptStates[s.name] = s.state; });
    renderScriptList();
    renderTabs();
    updateStatusBar();
  } catch (err) { appendOutput('Error loading scripts: ' + err.message, 'err'); }
}

function renderScriptList() {
  var container = el('script-list');
  if (!container) return;
  var q = S.filter.toLowerCase();
  var items = q ? S.scripts.filter(function(s) { return s.name.toLowerCase().indexOf(q) !== -1; }) : S.scripts;
  if (!items.length) {
    container.innerHTML = '<span class="empty-msg">' + (q ? 'No matches.' : 'No scripts. Create one!') + '</span>';
    return;
  }
  container.innerHTML = items.map(function(s) {
    return '<div class="script-item' + (s.name === S.activeTab ? ' active' : '')
      + '" data-name="' + esc(s.name) + '" onclick="openTab(\'' + escJ(s.name) + '\')" title="' + esc(s.name) + '.loom — ' + s.state + '">'
      + '<span class="script-status s-' + s.state + '"></span>'
      + '<span class="script-name">' + esc(s.name) + '.loom</span>'
      + '<span class="script-row-btns">'
      + '<button onclick="quickLoad(\'' + escJ(s.name) + '\',event)" title="Load">' + SVG.play + '</button>'
      + '<button onclick="quickStop(\'' + escJ(s.name) + '\',event)" title="Unload">' + SVG.stop + '</button>'
      + '</span>'
      + '</div>';
  }).join('');
}

function filterScripts(q) {
  S.filter = q || '';
  renderScriptList();
}

function defaultSource(name) {
  return 'script "' + name + '" {\n  on PlayerJoin(player) {\n    player.message("Hello!")\n  }\n}';
}

async function newScript() {
  var name = await showPrompt('Script name (letters, digits, hyphens, underscores):', '', 'New Script');
  if (!name || !name.trim()) return;
  var clean = name.trim();
  try {
    await API.put('/scripts/' + clean, { source: defaultSource(clean) });
    toast('Created ' + clean + '.loom', 's');
    await refreshScripts();
    await openTab(clean);
  } catch (err) { toast('Create failed: ' + err.message, 'e'); }
}

async function saveScript() {
  if (!S.activeTab || !editor) { toast('No script open.', 'w'); return; }
  var name  = S.activeTab;
  var model = editorModels[name];
  if (!model) return;
  var source = model.getValue();
  var msg = await showPrompt('Commit message (leave blank for auto):', '', 'Save ' + name + '.loom');
  if (msg === null) return;
  try {
    var autoMsg = CFG.commitMsgTemplate.replace(/\{name\}/g, name);
    await API.post('/scripts/' + name, { source: source, commitMessage: msg || autoMsg });
    _markDirty(name, false);
    toast('Saved ' + name + '.loom', 's');
    appendOutput('Saved ' + name + '.loom');
    await loadGitLog(name);
    await refreshScripts();
  } catch (err) { toast('Save failed: ' + err.message, 'e'); }
}

async function runScript(nameArg) {
  var name = nameArg || S.activeTab;
  if (!name) { toast('No script open.', 'w'); return; }
  try {
    var res = await API.post('/scripts/' + name + '/load', {});
    S.scriptStates[name] = res.state;
    if (res.diagnostics && res.diagnostics.length) showDiagnostics(res.diagnostics);
    var ok = res.state === 'RUNNING';
    toast('Loaded ' + name + ' → ' + res.state, ok ? 's' : 'e');
    appendOutput('Load: ' + name + ' → ' + res.state, ok ? 'ok' : 'err');
    renderTabs(); renderScriptList(); updateStatusBar();
  } catch (err) { toast('Load failed: ' + err.message, 'e'); }
}

async function stopScript(nameArg) {
  var name = nameArg || S.activeTab;
  if (!name) { toast('No script open.', 'w'); return; }
  try {
    await API.post('/scripts/' + name + '/unload', {});
    S.scriptStates[name] = 'UNLOADED';
    toast('Unloaded ' + name, 'i');
    appendOutput('Unloaded ' + name);
    renderTabs(); renderScriptList(); updateStatusBar();
  } catch (err) { toast('Unload failed: ' + err.message, 'e'); }
}

async function reloadScript(nameArg) {
  var name = nameArg || S.activeTab;
  if (!name) { toast('No script open.', 'w'); return; }
  try {
    var res = await API.post('/scripts/' + name + '/reload', {});
    S.scriptStates[name] = res.state;
    if (res.diagnostics && res.diagnostics.length) showDiagnostics(res.diagnostics);
    var ok = res.state === 'RUNNING';
    toast('Reloaded ' + name + ' → ' + res.state, ok ? 's' : 'e');
    appendOutput('Reload: ' + name + ' → ' + res.state, ok ? 'ok' : 'err');
    renderTabs(); renderScriptList(); updateStatusBar();
  } catch (err) { toast('Reload failed: ' + err.message, 'e'); }
}

async function validateScript() {
  await validateCurrent();
  var c = S.diagCounts;
  toast('Validate: ' + c.errors + ' error(s), ' + c.warnings + ' warning(s)',
    c.errors ? 'e' : c.warnings ? 'w' : 's');
  showBottomTab('problems');
}

async function deleteScript(nameArg) {
  var name = nameArg || S.activeTab;
  if (!name) { toast('No script selected.', 'w'); return; }
  if (!await showConfirm('Delete ' + name + '.loom permanently? This cannot be undone.', true, 'Delete Script')) return;
  try {
    await API.del('/scripts/' + name);
    closeTab(name);
    delete S.scriptStates[name];
    toast('Deleted ' + name + '.loom', 'i');
    await refreshScripts();
  } catch (err) { toast('Delete failed: ' + err.message, 'e'); }
}

async function renameScript(nameArg) {
  var oldName = nameArg || S.activeTab;
  if (!oldName) { toast('No script selected.', 'w'); return; }
  var newName = await showPrompt('New name for ' + oldName + ':', oldName, 'Rename Script');
  if (!newName || newName === oldName) return;
  try {
    await API.post('/scripts/' + oldName + '/rename', { newName: newName });
    var model = editorModels[oldName];
    if (model) { model.dispose(); delete editorModels[oldName]; }
    S.tabs = S.tabs.filter(function(t) { return t.name !== oldName; });
    delete S.scriptStates[oldName];
    if (S.activeTab === oldName) S.activeTab = null;
    toast('Renamed to ' + newName + '.loom', 's');
    await refreshScripts();
    await openTab(newName);
  } catch (err) { toast('Rename failed: ' + err.message, 'e'); }
}

async function quickLoad(name, ev) {
  ev && ev.stopPropagation();
  try {
    var res = await API.post('/scripts/' + name + '/load', {});
    S.scriptStates[name] = res.state;
    toast(name + ' → ' + res.state, res.state === 'RUNNING' ? 's' : 'e');
    renderScriptList(); renderTabs(); updateStatusBar();
  } catch (err) { toast(err.message, 'e'); }
}

async function quickStop(name, ev) {
  ev && ev.stopPropagation();
  try {
    await API.post('/scripts/' + name + '/unload', {});
    S.scriptStates[name] = 'UNLOADED';
    toast(name + ' unloaded', 'i');
    renderScriptList(); renderTabs(); updateStatusBar();
  } catch (err) { toast(err.message, 'e'); }
}

// ── Context menu builders ──────────────────────────────────────────
function showScriptCtxMenu(name, x, y) {
  var state  = S.scriptStates[name] || 'UNLOADED';
  var loaded = state !== 'UNLOADED';
  ctxMenu.show([
    { icon: SVG.extLink, label: 'Open',      fn: function() { openTab(name); } },
    null,
    { icon: SVG.play,    label: 'Load',      fn: function() { runScript(name); } },
    { icon: SVG.stop,    label: 'Unload',    disabled: !loaded, fn: function() { stopScript(name); } },
    { icon: SVG.refresh, label: 'Reload',    disabled: !loaded, fn: function() { reloadScript(name); } },
    null,
    { icon: SVG.pencil,  label: 'Rename…',   fn: function() { renameScript(name); } },
    { icon: SVG.copy,    label: 'Copy Name', fn: function() { navigator.clipboard.writeText(name).catch(function(){}); toast('Copied', 's', 1500); } },
    null,
    { icon: SVG.trash,   label: 'Delete',    danger: true, fn: function() { deleteScript(name); } },
  ], x, y);
}

function showTabCtxMenu(name, x, y) {
  var others = S.tabs.filter(function(t) { return t.name !== name; });
  ctxMenu.show([
    { icon: SVG.close,  label: 'Close Tab',    key: 'Alt+W', fn: function() { closeTab(name); } },
    { icon: '',         label: 'Close Others', disabled: others.length === 0, fn: function() { others.forEach(function(t) { closeTab(t.name); }); } },
    { icon: '',         label: 'Close All',    fn: function() { S.tabs.slice().forEach(function(t) { closeTab(t.name); }); } },
    null,
    { icon: SVG.pencil, label: 'Rename…',      fn: function() { activateTab(name); renameScript(name); } },
    { icon: SVG.trash,  label: 'Delete',        danger: true, fn: function() { activateTab(name); deleteScript(name); } },
  ], x, y);
}

// ── Diagnostics ────────────────────────────────────────────────────
async function validateCurrent() {
  if (!S.activeTab || !editor || !window.monaco) return;
  var name  = S.activeTab;
  var model = editorModels[name];
  if (!model || model.isDisposed()) return;
  try {
    var res = await API.post('/scripts/' + name + '/validate', { source: model.getValue() });
    showDiagnostics(res.diagnostics || []);
  } catch (_) {}
}

function showDiagnostics(diags) {
  if (!window.monaco || !editor) return;
  var model = editor.getModel();
  if (!model) return;

  var unreachable = diags.filter(function(d) { return d.severity === 'UNREACHABLE'; });
  var regular     = diags.filter(function(d) { return d.severity !== 'UNREACHABLE'; });

  if (unreachDecs) {
    unreachDecs.set(unreachable.map(function(d) {
      return {
        range: new monaco.Range(d.line, 1, d.endLine || d.line, 9999),
        options: { inlineClassName: 'loom-unreachable', isWholeLine: true }
      };
    }));
  }

  var markers = regular.map(function(d) {
    return {
      severity: d.severity === 'ERROR'   ? monaco.MarkerSeverity.Error
              : d.severity === 'WARNING' ? monaco.MarkerSeverity.Warning
              : monaco.MarkerSeverity.Info,
      message: d.message,
      startLineNumber: d.line,    startColumn: d.col,
      endLineNumber: d.endLine || d.line, endColumn: (d.endCol || d.col + 1),
      owner: 'loom',
    };
  });
  monaco.editor.setModelMarkers(model, 'loom', markers);

  var errors   = regular.filter(function(d) { return d.severity === 'ERROR'; }).length;
  var warnings = regular.filter(function(d) { return d.severity === 'WARNING'; }).length;
  S.diagCounts = { errors: errors, warnings: warnings };

  // Badge on Problems tab
  var badge = el('prob-badge');
  if (badge) {
    if (errors > 0) {
      badge.textContent = errors; badge.className = 'count-badge'; badge.style.display = '';
    } else if (warnings > 0) {
      badge.textContent = warnings; badge.className = 'count-badge badge-warn'; badge.style.display = '';
    } else { badge.style.display = 'none'; }
  }

  // Status bar diag count
  var sd = el('sb-diags');
  if (sd) {
    if (errors > 0 || warnings > 0) {
      sd.innerHTML = (errors > 0 ? SVG.error + ' ' + errors : '')
        + (warnings > 0 ? (errors > 0 ? '  ' + SVG.warning + ' ' : SVG.warning + ' ') + warnings : '');
      sd.style.display = '';
    } else { sd.style.display = 'none'; }
  }

  // Problems panel
  var probEl = el('tab-problems');
  var all = regular.concat(unreachable);
  if (!all.length) {
    probEl.innerHTML = '<span class="empty-msg">No problems detected.</span>';
    return;
  }
  var sevIcon = { ERROR: SVG.error, WARNING: SVG.warning, INFO:'·', UNREACHABLE:'~' };
  var sevCls  = { ERROR:'E', WARNING:'W', INFO:'I', UNREACHABLE:'U' };
  probEl.innerHTML = all.map(function(d) {
    return '<div class="diag-item" onclick="jumpTo(' + d.line + ',' + d.col + ')">'
      + '<span class="dsev dsev-' + (sevCls[d.severity] || 'I') + '">' + (sevIcon[d.severity] || '·') + '</span>'
      + '<span class="dloc">' + d.line + ':' + d.col + '</span>'
      + '<span class="dmsg">' + esc(d.message) + '</span>'
      + '</div>';
  }).join('');

  if (regular.some(function(d) { return d.severity !== 'INFO'; })) showBottomTab('problems');
  updateStatusBar();
}

function jumpTo(line, col) {
  if (!editor) return;
  editor.revealLineInCenter(line);
  editor.setPosition({ lineNumber: line, column: col });
  editor.focus();
}

// ── Git ────────────────────────────────────────────────────────────
async function loadGitLog(name) {
  try {
    var commits = await API.get('/git/' + name + '/log');
    var gitEl  = el('tab-git');
    var sideEl = el('git-sidebar');
    if (!commits.length) {
      var empty = '<span class="empty-msg">No commits yet.</span>';
      if (gitEl)  gitEl.innerHTML  = empty;
      if (sideEl) sideEl.innerHTML = empty;
      return;
    }
    var html = commits.map(function(c) {
      return '<div class="commit-item">'
        + '<span class="commit-hash" onclick="showCommit(\'' + escJ(c.hash) + '\',\'' + escJ(name) + '\')" title="' + esc(c.hash) + '">' + esc(c.shortHash) + '</span>'
        + '<span class="commit-msg">' + esc(c.message) + '</span>'
        + '<span class="commit-meta">— ' + esc(c.author) + ' · ' + new Date(c.timestamp).toLocaleString() + '</span>'
        + '</div>';
    }).join('');
    if (gitEl)  gitEl.innerHTML  = html;
    if (sideEl) sideEl.innerHTML = html;
  } catch (_) {}
}

async function showCommit(hash, name) {
  try {
    var data = await API.get('/git/' + name + '/show/' + hash);
    if (await showConfirm(
      'Restore ' + name + '.loom to version ' + hash.slice(0,7) + '? Unsaved editor changes will be replaced.',
      false, 'Restore Version'
    )) {
      var model = editorModels[name];
      if (model) model.setValue(data.source);
      else if (editor) editor.setValue(data.source);
      _markDirty(name, true);
      toast('Restored version ' + hash.slice(0,7), 'i');
    }
  } catch (err) { toast('Cannot load commit: ' + err.message, 'e'); }
}

// ── UI ─────────────────────────────────────────────────────────────
function setActivity(name) {
  S.activity = name;
  document.querySelectorAll('.act-btn[data-act]').forEach(function(b) {
    b.classList.toggle('active', b.dataset.act === name);
  });
  document.querySelectorAll('.side-panel').forEach(function(p) { p.style.display = 'none'; });
  var panel = el('panel-' + name);
  if (panel) panel.style.display = '';
}

function toggleSidebar() {
  S.sidebarOpen = !S.sidebarOpen;
  el('sidebar').classList.toggle('collapsed', !S.sidebarOpen);
  if (editor) editor.layout();
}

function showBottomTab(name) {
  S.bottomTab = name;
  document.querySelectorAll('.btab').forEach(function(b) {
    b.classList.toggle('active', b.dataset.tab === name);
  });
  document.querySelectorAll('#bottom-content > div').forEach(function(d) { d.style.display = 'none'; });
  var target = el('tab-' + name);
  if (target) target.style.display = '';
}

function updateBreadcrumb() {
  var bc = el('breadcrumb');
  if (!bc) return;
  bc.innerHTML = S.activeTab
    ? 'scripts / <span class="bc-file">' + esc(S.activeTab) + '.loom</span>'
    : '<span style="color:var(--text-3)">No script open</span>';
}

function updateStatusBar() {
  var playerEl = el('sb-player');
  var scriptEl = el('sb-script');
  var stateEl  = el('sb-state');
  var bar      = el('status-bar');

  if (playerEl) {
    if (S.player) { playerEl.innerHTML = SVG.user + ' ' + esc(S.player); playerEl.style.display = ''; }
    else playerEl.style.display = 'none';
  }
  if (scriptEl) scriptEl.textContent = S.activeTab ? S.activeTab + '.loom' : 'Loom Editor';

  var state = S.activeTab ? (S.scriptStates[S.activeTab] || 'UNLOADED') : '';
  if (stateEl) stateEl.textContent = state ? '[' + state + ']' : '';
  if (bar) {
    bar.className = state === 'ERROR'   ? 'sb-error'
      : (state === 'IDLE' || state === 'UNLOADED') ? 'sb-idle'
      : '';
  }

  // Update player initial in activity bar
  var actPlayer = el('act-player');
  if (actPlayer) {
    actPlayer.title = S.player ? S.player : 'Not connected';
    actPlayer.textContent = S.player ? S.player[0].toUpperCase() : '?';
  }
}

// ── Resize handle ──────────────────────────────────────────────────
function initResize() {
  var handle = el('panel-resize');
  var panel  = el('bottom-panel');
  if (!handle || !panel) return;
  var dragging = false, startY = 0, startH = 0;
  handle.addEventListener('mousedown', function(ev) {
    dragging = true; startY = ev.clientY; startH = panel.offsetHeight;
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'row-resize';
  });
  document.addEventListener('mousemove', function(ev) {
    if (!dragging) return;
    var h = Math.max(60, Math.min(600, startH - (ev.clientY - startY)));
    panel.style.height = h + 'px';
  });
  document.addEventListener('mouseup', function() {
    if (!dragging) return;
    dragging = false;
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
    if (editor) editor.layout();
  });
}

// ── Keyboard shortcuts ─────────────────────────────────────────────
function initKeyboard() {
  document.addEventListener('keydown', function(ev) {
    var ctrl = ev.ctrlKey || ev.metaKey;
    if (ctrl && ev.key === 's')     { ev.preventDefault(); saveScript(); }
    if (ctrl && ev.key === 'k')     { ev.preventDefault(); openCommandPalette(); }
    if (ctrl && ev.key === 'b')     { ev.preventDefault(); toggleSidebar(); }
    if (ctrl && ev.key === 'n')     { ev.preventDefault(); newScript(); }
    if (ctrl && ev.key === ',')     { ev.preventDefault(); openSettings(); }
    if (ctrl && ev.key === 'Enter') { ev.preventDefault(); runScript(); }
    if (ctrl && ev.shiftKey && ev.key === 'R') { ev.preventDefault(); reloadScript(); }
    if (ev.key === 'Escape') {
      ctxMenu.hide();
      closeSettings();
      var overlay = el('cmd-overlay');
      if (overlay && overlay.classList.contains('open')) { ev.preventDefault(); closeCommandPalette(); }
    }
  });

  var cmdInput = el('cmd-input');
  if (cmdInput) {
    cmdInput.addEventListener('input', function(ev) { _filterCmds(ev.target.value); });
    cmdInput.addEventListener('keydown', function(ev) {
      if      (ev.key === 'ArrowDown') { cmdIdx = Math.min(cmdIdx+1, filteredCmds.length-1); _renderCmds(); ev.preventDefault(); }
      else if (ev.key === 'ArrowUp')   { cmdIdx = Math.max(cmdIdx-1, 0); _renderCmds(); ev.preventDefault(); }
      else if (ev.key === 'Enter')     { runCommand(cmdIdx); ev.preventDefault(); }
      else if (ev.key === 'Escape')    { closeCommandPalette(); ev.preventDefault(); }
    });
  }

  var cmdOverlay = el('cmd-overlay');
  if (cmdOverlay) {
    cmdOverlay.addEventListener('mousedown', function(ev) {
      if (ev.target === cmdOverlay) closeCommandPalette();
    });
  }
}

// ── Output log ─────────────────────────────────────────────────────
function appendOutput(msg, type) {
  var container = el('tab-output');
  if (!container) return;
  var div = document.createElement('div');
  div.className = 'out-line' + (type ? ' out-' + type : '');
  div.textContent = '[' + new Date().toLocaleTimeString() + '] ' + msg;
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
}

// ── Context menu wiring ────────────────────────────────────────────
function initContextMenus() {
  var list = el('script-list');
  if (list) {
    list.addEventListener('contextmenu', function(ev) {
      var item = ev.target.closest('.script-item');
      if (!item) return;
      ev.preventDefault();
      showScriptCtxMenu(item.dataset.name, ev.clientX, ev.clientY);
    });
  }
  var tabs = el('tabs');
  if (tabs) {
    tabs.addEventListener('contextmenu', function(ev) {
      var tab = ev.target.closest('.editor-tab');
      if (!tab) return;
      ev.preventDefault();
      showTabCtxMenu(tab.dataset.name, ev.clientX, ev.clientY);
    });
  }
}

// ── Settings ──────────────────────────────────────────────────────
var SETTINGS_SCHEMA = {
  editor: { label: 'Editor', items: [
    { key:'fontSize',             label:'Font Size',            desc:'Editor font size in pixels',        type:'range',  min:10, max:26,   step:1 },
    { key:'fontFamily',           label:'Font Family',          desc:'Editor font family',                 type:'text' },
    { key:'tabSize',              label:'Tab Size',             desc:'Number of spaces per tab stop',      type:'range',  min:1,  max:8,    step:1 },
    { key:'wordWrap',             label:'Word Wrap',            desc:'Line wrap mode',                     type:'select', options:['off','on','wordWrapColumn','bounded'] },
    { key:'minimap',              label:'Minimap',              desc:'Show the minimap scrollbar',         type:'toggle' },
    { key:'lineNumbers',          label:'Line Numbers',         desc:'Gutter line numbers',                type:'select', options:['on','off','relative'] },
    { key:'renderWhitespace',     label:'Render Whitespace',    desc:'Show whitespace characters',         type:'select', options:['selection','none','boundary','all'] },
    { key:'smoothScrolling',      label:'Smooth Scrolling',     desc:'Animate editor scrolling',           type:'toggle' },
    { key:'scrollBeyondLastLine', label:'Scroll Past End',      desc:'Allow scrolling past the last line', type:'toggle' },
    { key:'cursorStyle',          label:'Cursor Style',         desc:'Cursor shape in the editor',         type:'select', options:['line','block','underline','line-thin','block-outline','underline-thin'] },
    { key:'cursorBlinking',       label:'Cursor Blinking',      desc:'Cursor blink animation style',       type:'select', options:['smooth','blink','phase','expand','solid'] },
    { key:'bracketPairColorization', label:'Bracket Colorization', desc:'Colorize matching bracket pairs', type:'toggle' },
  ]},
  interface: { label: 'Interface', items: [
    { key:'accentColor',   label:'Accent Color',  desc:'Theme highlight and active-item color', type:'color' },
    { key:'uiFontSize',    label:'UI Font Size',  desc:'Interface font size in pixels',         type:'range', min:10, max:18, step:1 },
    { key:'showStatusBar', label:'Status Bar',    desc:'Show the bottom status bar',            type:'toggle' },
  ]},
  behavior: { label: 'Behavior', items: [
    { key:'autoValidate',  label:'Auto Validate',    desc:'Validate script as you type',           type:'toggle' },
    { key:'validateDelay', label:'Validate Delay',   desc:'Milliseconds before auto-validate runs', type:'range', min:200, max:2000, step:100 },
    { key:'confirmClose',  label:'Confirm on Close', desc:'Ask before closing a tab with unsaved changes', type:'toggle' },
  ]},
  git: { label: 'Git', items: [
    { key:'commitMsgTemplate', label:'Commit Message', desc:'Default commit message — {name} is replaced with the script name', type:'text' },
  ]},
};

function loadSettings() {
  try {
    var stored = localStorage.getItem('loom_settings');
    CFG = stored ? Object.assign({}, DEFAULTS, JSON.parse(stored)) : Object.assign({}, DEFAULTS);
  } catch(_) { CFG = Object.assign({}, DEFAULTS); }
}

function saveSettings() {
  localStorage.setItem('loom_settings', JSON.stringify(CFG));
  _flashSaved();
}

function applySettings() {
  if (editor) {
    editor.updateOptions({
      fontSize: CFG.fontSize, fontFamily: CFG.fontFamily,
      tabSize: CFG.tabSize, wordWrap: CFG.wordWrap,
      minimap: { enabled: CFG.minimap }, lineNumbers: CFG.lineNumbers,
      renderWhitespace: CFG.renderWhitespace, smoothScrolling: CFG.smoothScrolling,
      scrollBeyondLastLine: CFG.scrollBeyondLastLine,
      cursorStyle: CFG.cursorStyle, cursorBlinking: CFG.cursorBlinking,
      bracketPairColorization: { enabled: CFG.bracketPairColorization },
    });
  }
  var hex = CFG.accentColor || '#5b8af0';
  if (/^#[0-9a-fA-F]{6}$/.test(hex)) {
    var r = parseInt(hex.slice(1,3),16), g = parseInt(hex.slice(3,5),16), b = parseInt(hex.slice(5,7),16);
    document.documentElement.style.setProperty('--accent', hex);
    document.documentElement.style.setProperty('--accent-dim',  'rgba('+r+','+g+','+b+',0.13)');
    document.documentElement.style.setProperty('--accent-glow', 'rgba('+r+','+g+','+b+',0.35)');
  }
  document.body.style.fontSize = CFG.uiFontSize + 'px';
  var sb = el('status-bar');
  if (sb) sb.style.display = CFG.showStatusBar ? '' : 'none';
}

function openSettings() {
  loadSettings();
  var ov = el('settings-overlay');
  if (!ov) return;
  ov.classList.add('open');
  var activeCat = (document.querySelector('.snav-item.active') || {}).dataset;
  showSettingsCat((activeCat && activeCat.cat) || 'editor');
}

function closeSettings() {
  var ov = el('settings-overlay');
  if (ov) ov.classList.remove('open');
}

function showSettingsCat(cat) {
  document.querySelectorAll('.snav-item').forEach(function(b) {
    b.classList.toggle('active', b.dataset.cat === cat);
  });
  _renderSettingsCat(cat);
}

function _renderSettingsCat(cat) {
  var schema = SETTINGS_SCHEMA[cat];
  if (!schema) return;
  var content = el('settings-content');
  if (!content) return;
  content.innerHTML = schema.items.map(function(item) {
    return _renderSettingItem(item);
  }).join('');
  schema.items.forEach(function(item) { _bindSettingItem(item); });
}

function _renderSettingItem(item) {
  var val = CFG[item.key];
  var ctrl = '';
  if (item.type === 'toggle') {
    ctrl = '<label class="s-toggle"><input type="checkbox" data-key="' + item.key + '"' + (val ? ' checked' : '') + '>'
         + '<span class="s-track"><span class="s-thumb"></span></span></label>';
  } else if (item.type === 'range') {
    ctrl = '<div class="s-range">'
         + '<input type="range" data-key="' + item.key + '" min="' + item.min + '" max="' + item.max + '" step="' + item.step + '" value="' + val + '">'
         + '<span class="s-range-val" id="rv-' + item.key + '">' + val + '</span></div>';
  } else if (item.type === 'select') {
    ctrl = '<select class="s-select" data-key="' + item.key + '">'
         + item.options.map(function(o) {
             return '<option value="' + esc(o) + '"' + (o === val ? ' selected' : '') + '>' + esc(o) + '</option>';
           }).join('') + '</select>';
  } else if (item.type === 'color') {
    ctrl = '<div class="s-color">'
         + '<input type="color" data-key="' + item.key + '" value="' + esc(val) + '">'
         + '<input class="s-color-hex" data-key-hex="' + item.key + '" value="' + esc(val) + '" maxlength="7" spellcheck="false"></div>';
  } else if (item.type === 'text') {
    ctrl = '<input class="s-text-input" data-key="' + item.key + '" value="' + esc(String(val)) + '" spellcheck="false">';
  }
  return '<div class="s-item">'
    + '<div class="s-item-info"><div class="s-label">' + esc(item.label) + '</div>'
    + (item.desc ? '<div class="s-desc">' + esc(item.desc) + '</div>' : '')
    + '</div><div class="s-ctrl">' + ctrl + '</div></div>';
}

function _bindSettingItem(item) {
  var content = el('settings-content');
  if (!content) return;
  if (item.type === 'toggle') {
    var cb = content.querySelector('input[type=checkbox][data-key="' + item.key + '"]');
    if (cb) cb.addEventListener('change', function() {
      CFG[item.key] = this.checked; saveSettings(); applySettings();
    });
  } else if (item.type === 'range') {
    var range = content.querySelector('input[type=range][data-key="' + item.key + '"]');
    var valEl = el('rv-' + item.key);
    if (range) range.addEventListener('input', function() {
      CFG[item.key] = parseFloat(this.value);
      if (valEl) valEl.textContent = this.value;
      saveSettings(); applySettings();
    });
  } else if (item.type === 'select') {
    var sel = content.querySelector('select[data-key="' + item.key + '"]');
    if (sel) sel.addEventListener('change', function() {
      CFG[item.key] = this.value; saveSettings(); applySettings();
    });
  } else if (item.type === 'color') {
    var cp  = content.querySelector('input[type=color][data-key="' + item.key + '"]');
    var hex = content.querySelector('input[data-key-hex="' + item.key + '"]');
    if (cp) cp.addEventListener('input', function() {
      CFG[item.key] = this.value;
      if (hex) hex.value = this.value;
      saveSettings(); applySettings();
    });
    if (hex) hex.addEventListener('change', function() {
      var v = this.value.trim();
      if (/^#[0-9a-fA-F]{6}$/.test(v)) {
        CFG[item.key] = v;
        if (cp) cp.value = v;
        saveSettings(); applySettings();
      } else { this.value = CFG[item.key]; }
    });
  } else if (item.type === 'text') {
    var txt = content.querySelector('input.s-text-input[data-key="' + item.key + '"]');
    if (txt) txt.addEventListener('change', function() {
      CFG[item.key] = this.value; saveSettings(); applySettings();
    });
  }
}

function initSettingsEvents() {
  var overlay = el('settings-overlay');
  if (!overlay) return;
  overlay.addEventListener('mousedown', function(ev) {
    if (ev.target === overlay) closeSettings();
  });
  var closeBtn = el('settings-close');
  if (closeBtn) closeBtn.addEventListener('click', closeSettings);
  var resetBtn = el('settings-reset');
  if (resetBtn) resetBtn.addEventListener('click', resetSettings);
  document.querySelectorAll('.snav-item').forEach(function(b) {
    b.addEventListener('click', function() { showSettingsCat(this.dataset.cat); });
  });
}

function resetSettings() {
  CFG = Object.assign({}, DEFAULTS);
  saveSettings(); applySettings();
  var active = document.querySelector('.snav-item.active');
  _renderSettingsCat(active ? active.dataset.cat : 'editor');
}

function _flashSaved() {
  var badge = el('settings-saved-badge');
  if (!badge) return;
  badge.classList.add('visible');
  clearTimeout(_flashSaved._t);
  _flashSaved._t = setTimeout(function() { badge.classList.remove('visible'); }, 2000);
}

// ── Boot ───────────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', function() {
  loadSettings();
  applySettings();
  initKeyboard();
  initResize();
  initContextMenus();
  initSettingsEvents();
  showBottomTab('problems');
  initAuth();
});
