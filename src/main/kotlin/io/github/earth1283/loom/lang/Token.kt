package io.github.earth1283.loom.lang

data class Token(val type: TokenType, val lexeme: String, val line: Int, val col: Int)

enum class TokenType {
    // Literals
    NUMBER, STRING, TRUE, FALSE, NULL,

    // Identifiers / keywords
    IDENTIFIER,
    SCRIPT, ON, COMMAND, EVERY, AFTER, TICKS, SECONDS, MINUTES,
    VAR, FUN, RETURN, IF, ELSE, WHILE, FOR, IN, BREAK, CONTINUE,
    AND, OR, NOT,
    IMPORT,

    // Punctuation
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, DOT, COLON, SEMICOLON, ARROW,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NEQ, LT, LTE, GT, GTE,
    ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
    BANG,

    // String interpolation
    STRING_PART, INTERP_START, INTERP_END,

    EOF
}
