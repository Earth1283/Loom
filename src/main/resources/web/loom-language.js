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

window.LOOM_BUILTIN_DOCS = {
  'broadcast': 'broadcast(msg) — Send a message to all online players',
  'log':       'log(msg) — Print to server console',
  'print':     'print(msg) — Alias for log()',
  'players':   'players() → List<Player> — All online players',
  'player':    'player(name) → Player? — Get player by name',
  'playerCount': 'playerCount() → Number — Number of online players',
  'setBlock':  'setBlock(world, x, y, z, material) — Place a block',
  'getBlock':  'getBlock(world, x, y, z) → String — Get block material name',
  'fill':      'fill(world, x1,y1,z1, x2,y2,z2, material) — Fill a region',
  'summon':    'summon(world, x, y, z, entityType) — Spawn an entity',
  'serverVersion': 'serverVersion() → String',
  'maxPlayers':    'maxPlayers() → Number',
  'worlds':        'worlds() → List<String> — All world names',
  'floor':  'floor(n) → Number',
  'ceil':   'ceil(n) → Number',
  'round':  'round(n) → Number',
  'abs':    'abs(n) → Number',
  'min':    'min(a, b) → Number',
  'max':    'max(a, b) → Number',
  'random': 'random() → Number — Random in [0.0, 1.0)',
  'sqrt':   'sqrt(n) → Number',
  'pow':    'pow(base, exp) → Number',
  'str':    'str(value) → String — Convert to string',
  'num':    'num(value) → Number — Parse to number',
  'len':    'len(value) → Number — Length of string / list / map',
  'range':  'range(start, end) → List — Integer range [start, end)',
  'keys':   'keys(map) → List',
  'values': 'values(map) → List',
  'contains':   'contains(collection, item) → Bool',
  'join':       'join(list, sep?) → String',
  'split':      'split(str, sep) → List',
  'upper':      'upper(str) → String',
  'lower':      'lower(str) → String',
  'trim':       'trim(str) → String',
  'replace':    'replace(str, from, to) → String',
  'startsWith': 'startsWith(str, prefix) → Bool',
  'endsWith':   'endsWith(str, suffix) → Bool',
  'substr':     'substr(str, start, end?) → String',
  'push':   'push(list, item) — Append item to list',
  'pop':    'pop(list) — Remove and return last item',
  'remove': 'remove(list, index) — Remove item at index',
  'sort':   'sort(list) — Sort list in-place',
  'Item':   'Item.MATERIAL_NAME — Material constant',
};

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
