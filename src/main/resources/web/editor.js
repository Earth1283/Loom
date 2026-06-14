// Loom Web Editor — Monaco integration + API client

const API = {
  sessionId: null,

  headers() {
    return { 'Content-Type': 'application/json', 'X-Session-Id': this.sessionId || '' };
  },

  async get(path) {
    const r = await fetch(path, { headers: this.headers() });
    if (!r.ok) throw new Error(await r.text());
    return r.json();
  },

  async post(path, body) {
    const r = await fetch(path, { method: 'POST', headers: this.headers(), body: JSON.stringify(body) });
    if (!r.ok) throw new Error(await r.text());
    return r.json();
  },

  async put(path, body) {
    const r = await fetch(path, { method: 'PUT', headers: this.headers(), body: JSON.stringify(body) });
    if (!r.ok) throw new Error(await r.text());
    return r.json();
  },

  async delete(path) {
    const r = await fetch(path, { method: 'DELETE', headers: this.headers() });
    if (!r.ok) throw new Error(await r.text());
    return r.json();
  }
};

// State
let editor = null;
let ws = null;
let currentScript = null;
let diagnosticDecorations = [];
let validateTimeout = null;

// Modal dialogs
function showDialog({ message, input = false, defaultValue = '', okLabel = 'OK', dangerOk = false }) {
  return new Promise(resolve => {
    const overlay  = document.getElementById('modal-overlay');
    const msgEl    = document.getElementById('modal-message');
    const inputEl  = document.getElementById('modal-input');
    const okBtn    = document.getElementById('modal-ok');
    const cancelBtn = document.getElementById('modal-cancel');

    msgEl.textContent  = message;
    okBtn.textContent  = okLabel;
    okBtn.className    = 'btn ' + (dangerOk ? 'btn-danger' : 'btn-primary');

    inputEl.style.display = input ? '' : 'none';
    if (input) { inputEl.value = defaultValue; }

    overlay.classList.add('open');
    if (input) setTimeout(() => { inputEl.focus(); inputEl.select(); }, 30);

    function cleanup(result) {
      overlay.classList.remove('open');
      okBtn.removeEventListener('click', onOk);
      cancelBtn.removeEventListener('click', onCancel);
      document.removeEventListener('keydown', onKey);
      overlay.removeEventListener('mousedown', onBdClick);
      resolve(result);
    }
    function onOk()    { cleanup(input ? inputEl.value : true); }
    function onCancel(){ cleanup(input ? null : false); }
    function onKey(e)  { if (e.key === 'Enter') onOk(); else if (e.key === 'Escape') onCancel(); }
    function onBdClick(e) { if (e.target === overlay) onCancel(); }

    okBtn.addEventListener('click', onOk);
    cancelBtn.addEventListener('click', onCancel);
    document.addEventListener('keydown', onKey);
    overlay.addEventListener('mousedown', onBdClick);
  });
}

function showPrompt(message, defaultValue = '') {
  return showDialog({ message, input: true, defaultValue });
}

function showConfirm(message, dangerOk = false) {
  return showDialog({ message, okLabel: dangerOk ? 'Delete' : 'Confirm', dangerOk });
}

// Auth
async function initAuth() {
  const stored = sessionStorage.getItem('loom_session');
  if (stored) {
    const check = await fetch('/auth/status/' + stored).then(r => r.json()).catch(() => ({ authenticated: false }));
    if (check.authenticated) {
      API.sessionId = stored;
      hideAuthOverlay();
      return;
    }
  }

  const data = await fetch('/auth/init').then(r => r.json());
  API.sessionId = data.sessionId;
  sessionStorage.setItem('loom_session', data.sessionId);

  document.getElementById('auth-code').textContent = '';
  document.getElementById('auth-instruction').innerHTML =
    `Open Minecraft and run:<br><code style="color:#4ec9b0; font-size:16px; letter-spacing:2px">/loom confirm ${extractCode(data.message)}</code>`;
  document.getElementById('auth-waiting').textContent = 'Waiting for confirmation…';

  pollAuth(data.sessionId);
}

function extractCode(msg) {
  const m = msg.match(/confirm (\S+)/);
  return m ? m[1] : '???';
}

async function pollAuth(sessionId) {
  const interval = setInterval(async () => {
    try {
      const status = await fetch('/auth/status/' + sessionId).then(r => r.json());
      if (status.authenticated) {
        clearInterval(interval);
        API.sessionId = sessionId;
        hideAuthOverlay();
        document.getElementById('auth-waiting').textContent = `Authenticated as ${status.player}`;
      }
    } catch (_) {}
  }, 2000);
}

function hideAuthOverlay() {
  document.getElementById('auth-overlay').style.display = 'none';
  initEditor();
  initWebSocket();
  refreshScripts();
}

// Monaco
function initEditor() {
  require.config({ paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs' } });
  require(['vs/editor/editor.main'], function(monaco) {
    window.monaco = monaco;

    if (window.registerLoomLanguage) window.registerLoomLanguage(monaco);

    editor = monaco.editor.create(document.getElementById('editor-container'), {
      language: 'loom',
      theme: 'loom-dark',
      value: '// Select a script from the sidebar or create a new one.',
      automaticLayout: true,
      fontSize: 14,
      lineNumbers: 'on',
      minimap: { enabled: true },
      wordWrap: 'on',
      tabSize: 2,
      insertSpaces: true,
      scrollBeyondLastLine: false,
      renderWhitespace: 'selection',
      bracketPairColorization: { enabled: true },
      suggest: { showKeywords: true },
      cursorStyle: 'line',
      cursorBlinking: 'smooth',
      cursorSmoothCaretAnimation: 'on',
    });

    // Register completion provider
    monaco.languages.registerCompletionItemProvider('loom', {
      triggerCharacters: ['.', ' '],
      provideCompletionItems(model, position) {
        const source = model.getValue();
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({
            type: 'complete',
            payload: { source, line: position.lineNumber, col: position.column }
          }));
        }
        // Return built-in static completions immediately
        const word = model.getWordUntilPosition(position);
        const range = { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber,
          startColumn: word.startColumn, endColumn: word.endColumn };
        const items = [
          ...window.LOOM_KEYWORDS.map(k => ({ label: k, kind: monaco.languages.CompletionItemKind.Keyword,
            insertText: k, range })),
          ...window.LOOM_BUILTINS.map(b => ({ label: b, kind: monaco.languages.CompletionItemKind.Function,
            insertText: b + '($0)', insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet, range })),
          ...window.LOOM_EVENTS.map(e => ({ label: e, kind: monaco.languages.CompletionItemKind.Event,
            insertText: e, range, detail: 'Loom event' })),
        ];
        return { suggestions: items };
      }
    });

    // Register hover provider
    monaco.languages.registerHoverProvider('loom', {
      provideHover(model, position) {
        const word = model.getWordAtPosition(position);
        if (!word) return null;
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'hover', payload: { word: word.word } }));
        }
        return null;
      }
    });

    // Register quick-fix provider
    monaco.languages.registerCodeActionProvider('loom', {
      provideCodeActions(model, range, context) {
        // Use getModelMarkers so fixes appear even when 1-char markers don't overlap the cursor column exactly.
        const markers = monaco.editor.getModelMarkers({ resource: model.uri, owner: 'loom' })
          .filter(m => m.startLineNumber <= range.endLineNumber && m.endLineNumber >= range.startLineNumber);

        const actions = [];

        function singleLineEdit(title, marker, ln, startCol, endCol, text, preferred = true) {
          actions.push({
            title,
            kind: 'quickfix',
            diagnostics: [marker],
            isPreferred: preferred,
            edit: {
              edits: [{
                resource: model.uri,
                versionId: model.getVersionId(),
                textEdit: { range: new monaco.Range(ln, startCol, ln, endCol), text }
              }]
            }
          });
        }

        function deleteLines(title, marker, startLn, endLn) {
          actions.push({
            title,
            kind: 'quickfix',
            diagnostics: [marker],
            isPreferred: true,
            edit: {
              edits: [{
                resource: model.uri,
                versionId: model.getVersionId(),
                textEdit: { range: new monaco.Range(startLn, 1, endLn + 1, 1), text: '' }
              }]
            }
          });
        }

        for (const marker of markers) {
          const msg = marker.message;
          const ln  = marker.startLineNumber;
          const line = model.getLineContent(ln);

          // Event name typo: "Unknown event 'X' — did you mean 'Y'?"
          const typoMatch = msg.match(/Unknown event '(\w+)'.*did you mean '(\w+)'/);
          if (typoMatch) {
            const [, wrong, right] = typoMatch;
            const idx = line.indexOf(wrong);
            if (idx !== -1)
              singleLineEdit(`Rename to '${right}'`, marker, ln, idx + 1, idx + 1 + wrong.length, right);
            continue;
          }

          // Double negation: "Double negation 'not not x'"
          if (msg.includes('Double negation')) {
            const m = line.match(/^(\s*)(not\s+not\s+|!!)(.*)/);
            if (m)
              singleLineEdit('Remove double negation', marker, ln, 1, line.length + 1, m[1] + m[3]);
          }

          // not (a == b) → a != b
          if (msg.includes("can be written as 'a != b'")) {
            const m = line.match(/not\s*\(([^)]+)==([^)]+)\)/);
            if (m) {
              const idx = line.indexOf(m[0]);
              const l = m[1].trim(), r = m[2].trim();
              singleLineEdit(`Simplify to '${l} != ${r}'`, marker, ln, idx + 1, idx + 1 + m[0].length, `${l} != ${r}`);
            }
          }

          // not (a != b) → a == b
          if (msg.includes("can be written as 'a == b'")) {
            const m = line.match(/not\s*\(([^)]+)!=([^)]+)\)/);
            if (m) {
              const idx = line.indexOf(m[0]);
              const l = m[1].trim(), r = m[2].trim();
              singleLineEdit(`Simplify to '${l} == ${r}'`, marker, ln, idx + 1, idx + 1 + m[0].length, `${l} == ${r}`);
            }
          }

          // No-op compound assignments: x += 0, x -= 0, x *= 1
          if (msg.includes('has no effect') &&
              (msg.includes('+= 0') || msg.includes('-= 0') || msg.includes('*= 1'))) {
            deleteLines('Remove no-op statement', marker, ln, ln);
          }

          // Self-assignment: x = x has no effect
          if (msg.includes('Self-assignment') && msg.includes('has no effect')) {
            deleteLines('Remove self-assignment', marker, ln, ln);
          }
        }

        return { actions, dispose: () => {} };
      }
    });

    // Validate on change (debounced)
    editor.onDidChangeModelContent(() => {
      clearTimeout(validateTimeout);
      validateTimeout = setTimeout(validateCurrent, 600);
    });
  });
}

// WebSocket
function initWebSocket() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws/${API.sessionId}`);

  ws.onopen = () => appendOutput('Connected to Loom language server.');
  ws.onclose = () => setTimeout(initWebSocket, 3000);

  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'diagnostics') showDiagnostics(msg.diagnostics);
    if (msg.type === 'hover' && msg.doc) {
      // Monaco hover handled via provider; log here for debug
    }
  };
}

// Script list
async function refreshScripts() {
  try {
    const list = await API.get('/scripts');
    const el = document.getElementById('script-list');
    el.innerHTML = '';
    list.forEach(s => {
      const item = document.createElement('div');
      item.className = 'script-item' + (s.name === currentScript ? ' active' : '');
      item.innerHTML = `<span class="script-status status-${s.state}"></span>${s.name}`;
      item.onclick = () => openScript(s.name);
      el.appendChild(item);
    });
  } catch (err) { appendOutput('Error loading scripts: ' + err.message); }
}

async function openScript(name) {
  try {
    const data = await API.get('/scripts/' + name);
    currentScript = name;
    document.getElementById('filename-display').textContent = name + '.loom';
    if (editor) editor.setValue(data.source);
    await refreshScripts();
    await loadGitLog(name);
    validateCurrent();
  } catch (err) { appendOutput('Error opening script: ' + err.message); }
}

// Toolbar actions
async function saveScript() {
  if (!currentScript || !editor) return;
  const source = editor.getValue();
  const msg = await showPrompt('Commit message (leave blank for auto):') ?? '';
  try {
    await API.post('/scripts/' + currentScript, {
      source,
      commitMessage: msg || `Save ${currentScript}.loom`
    });
    appendOutput(`Saved ${currentScript}.loom`);
    await loadGitLog(currentScript);
    await refreshScripts();
  } catch (err) { appendOutput('Save error: ' + err.message); }
}

async function runScript() {
  if (!currentScript) return;
  try {
    const res = await API.post('/scripts/' + currentScript + '/load', {});
    appendOutput(`Load result: ${res.state}`);
    if (res.diagnostics?.length) showDiagnostics(res.diagnostics);
    await refreshScripts();
  } catch (err) { appendOutput('Load error: ' + err.message); }
}

async function stopScript() {
  if (!currentScript) return;
  try {
    await API.post('/scripts/' + currentScript + '/unload', {});
    appendOutput(`Unloaded ${currentScript}`);
    await refreshScripts();
  } catch (err) { appendOutput('Unload error: ' + err.message); }
}

async function validateScript() {
  if (!currentScript || !editor) return;
  await validateCurrent();
}

async function deleteScript() {
  if (!currentScript) return;
  if (!await showConfirm(`Delete ${currentScript}.loom? This cannot be undone.`, true)) return;
  try {
    await API.delete('/scripts/' + currentScript);
    currentScript = null;
    document.getElementById('filename-display').textContent = 'No script open';
    if (editor) editor.setValue('');
    await refreshScripts();
  } catch (err) { appendOutput('Delete error: ' + err.message); }
}

async function newScript() {
  const name = await showPrompt('Script name (alphanumeric, underscores, hyphens):');
  if (!name) return;
  try {
    await API.put('/scripts/' + name, { source: defaultSource(name) });
    await refreshScripts();
    await openScript(name);
  } catch (err) { appendOutput('Create error: ' + err.message); }
}

function defaultSource(name) {
  return `script "${name}" {\n  on PlayerJoin(player) {\n    player.message("Hello, \${player.name}!")\n  }\n}`;
}

// Validation
async function validateCurrent() {
  if (!currentScript || !editor || !window.monaco) return;
  const source = editor.getValue();
  try {
    const res = await API.post('/scripts/' + currentScript + '/validate', { source });
    showDiagnostics(res.diagnostics || []);
  } catch (_) {}
}

function showDiagnostics(diags) {
  if (!window.monaco || !editor) return;
  const model = editor.getModel();
  if (!model) return;

  const markers = diags.map(d => ({
    severity: d.severity === 'ERROR' ? monaco.MarkerSeverity.Error
      : d.severity === 'WARNING' ? monaco.MarkerSeverity.Warning
      : monaco.MarkerSeverity.Info,
    message: d.message,
    startLineNumber: d.line,
    startColumn: d.col,
    endLineNumber: d.endLine || d.line,
    endColumn: d.endCol || d.col + 1,
  }));

  monaco.editor.setModelMarkers(model, 'loom', markers);

  const diagEl = document.getElementById('tab-diagnostics');
  if (diags.length === 0) {
    diagEl.innerHTML = '<span style="color:#888">No problems detected.</span>';
  } else {
    diagEl.innerHTML = diags.map(d =>
      `<div class="diag-${d.severity.toLowerCase()}">` +
      `[${d.line}:${d.col}] ${escHtml(d.message)}</div>`
    ).join('');
    showTab('diagnostics');
  }
}

// Git log panel
async function loadGitLog(name) {
  try {
    const commits = await API.get('/git/' + name + '/log');
    const el = document.getElementById('tab-git');
    if (commits.length === 0) {
      el.innerHTML = '<span style="color:#888">No commits yet.</span>';
      return;
    }
    el.innerHTML = commits.map(c => `
      <div class="commit-item">
        <span class="commit-hash" style="cursor:pointer" onclick="showCommit('${c.hash}','${name}')" title="Restore this version">${c.shortHash}</span>
        <span class="commit-msg"> ${escHtml(c.message)}</span>
        <span class="commit-meta"> — ${c.author} at ${new Date(c.timestamp).toLocaleString()}</span>
      </div>`
    ).join('');
  } catch (_) {}
}

async function showCommit(hash, name) {
  try {
    const data = await API.get(`/git/${name}/show/${hash}`);
    if (await showConfirm(`Restore to commit ${hash.slice(0,7)}? Current unsaved changes will be overwritten in the editor.`)) {
      if (editor) editor.setValue(data.source);
      appendOutput(`Loaded version ${hash.slice(0,7)} into editor. Save to persist.`);
    }
  } catch (err) { appendOutput('Error loading commit: ' + err.message); }
}

function showGit() {
  showTab('git');
  if (currentScript) loadGitLog(currentScript);
}

// UI helpers
function showTab(name) {
  ['diagnostics', 'git', 'output'].forEach(t => {
    document.getElementById('tab-' + t).style.display = t === name ? '' : 'none';
    document.querySelectorAll('.tab-btn').forEach((btn, i) => {
      btn.classList.toggle('active', btn.textContent.toLowerCase().includes(t === name ? name.slice(0,3) : '___'));
    });
  });
  document.querySelectorAll('.tab-btn').forEach(btn => {
    const tabName = btn.getAttribute('onclick')?.match(/'(\w+)'/)?.[1];
    btn.classList.toggle('active', tabName === name);
  });
}

function appendOutput(msg) {
  const el = document.getElementById('tab-output');
  const line = document.createElement('div');
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  el.appendChild(line);
  el.scrollTop = el.scrollHeight;
}

function escHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// Boot
window.addEventListener('DOMContentLoaded', () => {
  initAuth();
  // default tab
  showTab('diagnostics');
});
