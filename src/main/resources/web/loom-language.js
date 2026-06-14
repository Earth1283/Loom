// Loom language definition for Monaco Editor
// Registered before Monaco loads so it's available at init time.

window.LOOM_KEYWORDS = [
  'script','on','command','every','after','ticks','seconds','minutes',
  'var','fun','return','if','else','while','for','in','break','continue',
  'and','or','not','true','false','null','import'
];

window.LOOM_BUILTINS = [
  'broadcast','log','print',
  'players','player','playerCount',
  'setBlock','getBlock','fill','summon',
  'serverVersion','maxPlayers','worlds',
  'floor','ceil','round','abs','min','max','random','sqrt','pow',
  'str','num','len','range','keys','values','contains','join','split',
  'upper','lower','trim','replace','startsWith','endsWith','substr',
  'push','pop','remove','sort','Item'
];

window.LOOM_EVENTS = [
  'PlayerJoin','PlayerQuit','PlayerChat','PlayerMove','PlayerDeath','PlayerRespawn',
  'PlayerInteract','PlayerLevelChange',
  'BlockBreak','BlockPlace',
  'EntityDamage','EntityDamageByEntity','EntitySpawn',
  'WeatherChange','ServerLoad'
];

window.LOOM_PLAYER_PROPS = [
  'name','uuid','health','maxHealth','foodLevel','level','exp',
  'gameMode','world','x','y','z','yaw','pitch',
  'isOp','isFlying','isSneaking','isSprinting','ping','ip',
  'message','kick','teleport','give','heal','feed',
  'setGameMode','setFlying','effect','title','actionBar','playSound'
];

window.registerLoomLanguage = function(monaco) {
  monaco.languages.register({ id: 'loom' });

  monaco.languages.setMonarchTokensProvider('loom', {
    keywords: window.LOOM_KEYWORDS,
    builtins: window.LOOM_BUILTINS,
    events: window.LOOM_EVENTS,
    tokenizer: {
      root: [
        // Comments
        [/\/\/.*$/, 'comment'],
        [/\/\*/, 'comment', '@blockComment'],
        // String interpolation
        [/"/, 'string', '@string'],
        // Numbers
        [/\d+\.?\d*/, 'number'],
        // Keywords, builtins, events, identifiers
        [/[a-zA-Z_]\w*/, {
          cases: {
            '@keywords': 'keyword',
            '@builtins': 'type.identifier',
            '@events': 'tag',
            '@default': 'identifier'
          }
        }],
        // Operators
        [/[+\-*/%=!<>&|]/, 'operator'],
        // Brackets
        [/[{}()\[\]]/, '@brackets'],
        // Punctuation
        [/[.,;:]/, 'delimiter'],
        // Whitespace
        [/\s+/, 'white'],
      ],
      string: [
        [/\$\{/, { token: 'string.escape', next: '@interpolation' }],
        [/\\[ntr"\\]/, 'string.escape'],
        [/"/, 'string', '@pop'],
        [/[^"\\$]+/, 'string'],
      ],
      interpolation: [
        [/\}/, { token: 'string.escape', next: '@pop' }],
        { include: 'root' },
      ],
      blockComment: [
        [/\*\//, 'comment', '@pop'],
        [/./, 'comment'],
      ],
    }
  });

  monaco.languages.setLanguageConfiguration('loom', {
    comments: { lineComment: '//', blockComment: ['/*', '*/'] },
    brackets: [['{', '}'], ['[', ']'], ['(', ')']],
    autoClosingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '(', close: ')' },
      { open: '"', close: '"' },
    ],
    surroundingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '(', close: ')' },
      { open: '"', close: '"' },
    ],
    indentationRules: {
      increaseIndentPattern: /^.*\{[^}"]*$/,
      decreaseIndentPattern: /^\s*\}/,
    },
    folding: {
      markers: { start: /\{/, end: /\}/ }
    }
  });

  // Theme
  monaco.editor.defineTheme('loom-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword',        foreground: '7b8ff7', fontStyle: 'bold' },
      { token: 'type.identifier',foreground: '3dd6c0' },
      { token: 'tag',            foreground: 'e6c97a' },
      { token: 'number',         foreground: 'b9c4f0' },
      { token: 'string',         foreground: 'e09b79' },
      { token: 'string.escape',  foreground: 'd7ba7d' },
      { token: 'comment',        foreground: '404660', fontStyle: 'italic' },
      { token: 'operator',       foreground: '6e7498' },
      { token: 'identifier',     foreground: '9db8e8' },
    ],
    colors: {
      'editor.background':                   '#0b0c0f',
      'editor.foreground':                   '#e6e8f2',
      'editorLineNumber.foreground':         '#2e3250',
      'editorLineNumber.activeForeground':   '#6e7498',
      'editor.lineHighlightBackground':      '#13141c',
      'editor.selectionBackground':          '#3d5a9048',
      'editor.inactiveSelectionBackground':  '#3d5a9028',
      'editorIndentGuide.background1':       '#1e2030',
      'editorIndentGuide.activeBackground1': '#32364f',
      'editorCursor.foreground':             '#5b8af0',
      'editorWhitespace.foreground':         '#22253a',
      'editorBracketMatch.background':       '#3d5a9030',
      'editorBracketMatch.border':           '#5b8af060',
    }
  });
};
