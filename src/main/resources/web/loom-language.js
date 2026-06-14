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
      'editor.background':                   '#13141e',
      'editor.foreground':                   '#e8eaf4',
      'editorLineNumber.foreground':         '#383c60',
      'editorLineNumber.activeForeground':   '#7880a8',
      'editor.lineHighlightBackground':      '#1a1c2b',
      'editor.selectionBackground':          '#4060a855',
      'editor.inactiveSelectionBackground':  '#4060a830',
      'editorIndentGuide.background1':       '#252840',
      'editorIndentGuide.activeBackground1': '#3a3f62',
      'editorCursor.foreground':             '#5b8af0',
      'editorWhitespace.foreground':         '#252840',
      'editorBracketMatch.background':       '#4060a838',
      'editorBracketMatch.border':           '#5b8af075',
    }
  });
};
