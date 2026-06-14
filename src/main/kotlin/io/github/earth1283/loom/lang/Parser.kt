package io.github.earth1283.loom.lang

class Parser(private val tokens: List<Token>) {
    private var current = 0

    fun parse(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (!isAtEnd()) stmts.add(declaration())
        return stmts
    }

    // --- Declarations ---

    private fun declaration(): Stmt = when {
        check(TokenType.SCRIPT) -> scriptDecl()
        check(TokenType.VAR) -> varDecl()
        check(TokenType.FUN) -> funDecl()
        check(TokenType.IMPORT) -> importDecl()
        else -> statement()
    }

    private fun scriptDecl(): Stmt.ScriptDecl {
        val tok = consume(TokenType.SCRIPT, "Expected 'script'")
        val name = consume(TokenType.STRING_PART, "Expected script name string").lexeme
        consume(TokenType.LBRACE, "Expected '{' after script name")
        val body = block()
        return Stmt.ScriptDecl(name, body, tok.line, tok.col)
    }

    private fun varDecl(): Stmt.VarDecl {
        val tok = consume(TokenType.VAR, "Expected 'var'")
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
        val init = if (match(TokenType.ASSIGN)) expression() else null
        matchSemicolon()
        return Stmt.VarDecl(name, init, tok.line, tok.col)
    }

    private fun funDecl(): Stmt.FunDecl {
        val tok = consume(TokenType.FUN, "Expected 'fun'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name").lexeme
        consume(TokenType.LPAREN, "Expected '('")
        val params = paramList()
        consume(TokenType.LBRACE, "Expected '{'")
        val body = block()
        return Stmt.FunDecl(name, params, body, tok.line, tok.col)
    }

    private fun importDecl(): Stmt.Import {
        val tok = consume(TokenType.IMPORT, "Expected 'import'")
        val path = consume(TokenType.STRING_PART, "Expected import path string").lexeme
        matchSemicolon()
        return Stmt.Import(path, tok.line, tok.col)
    }

    // --- Statements ---

    private fun statement(): Stmt = when {
        check(TokenType.ON) -> onEvent()
        check(TokenType.COMMAND) -> commandDecl()
        check(TokenType.EVERY) -> everySchedule()
        check(TokenType.AFTER) -> afterSchedule()
        check(TokenType.IF) -> ifStmt()
        check(TokenType.WHILE) -> whileStmt()
        check(TokenType.FOR) -> forStmt()
        check(TokenType.RETURN) -> returnStmt()
        check(TokenType.BREAK) -> { val t = advance(); matchSemicolon(); Stmt.Break(t.line, t.col) }
        check(TokenType.CONTINUE) -> { val t = advance(); matchSemicolon(); Stmt.Continue(t.line, t.col) }
        else -> exprStmt()
    }

    private fun onEvent(): Stmt.OnEvent {
        val tok = consume(TokenType.ON, "Expected 'on'")
        val event = consume(TokenType.IDENTIFIER, "Expected event name").lexeme
        consume(TokenType.LPAREN, "Expected '('")
        val params = paramList()
        consume(TokenType.LBRACE, "Expected '{'")
        val body = block()
        return Stmt.OnEvent(event, params, body, tok.line, tok.col)
    }

    private fun commandDecl(): Stmt.CommandDecl {
        val tok = consume(TokenType.COMMAND, "Expected 'command'")
        val name = consume(TokenType.STRING_PART, "Expected command name string").lexeme
        consume(TokenType.LPAREN, "Expected '('")
        val params = paramList()
        consume(TokenType.LBRACE, "Expected '{'")
        val body = block()
        return Stmt.CommandDecl(name, params, body, tok.line, tok.col)
    }

    private fun everySchedule(): Stmt.EverySchedule {
        val tok = consume(TokenType.EVERY, "Expected 'every'")
        val amount = expression()
        val unit = scheduleUnit()
        consume(TokenType.LBRACE, "Expected '{'")
        val body = block()
        return Stmt.EverySchedule(amount, unit, body, tok.line, tok.col)
    }

    private fun afterSchedule(): Stmt.AfterSchedule {
        val tok = consume(TokenType.AFTER, "Expected 'after'")
        val amount = expression()
        val unit = scheduleUnit()
        consume(TokenType.LBRACE, "Expected '{'")
        val body = block()
        return Stmt.AfterSchedule(amount, unit, body, tok.line, tok.col)
    }

    private fun scheduleUnit(): ScheduleUnit = when {
        match(TokenType.TICKS) -> ScheduleUnit.TICKS
        match(TokenType.SECONDS) -> ScheduleUnit.SECONDS
        match(TokenType.MINUTES) -> ScheduleUnit.MINUTES
        else -> throw LoomError.Parser(peek().line, peek().col, "Expected 'ticks', 'seconds', or 'minutes'")
    }

    private fun ifStmt(): Stmt.If {
        val tok = consume(TokenType.IF, "Expected 'if'")
        consume(TokenType.LPAREN, "Expected '('")
        val cond = expression()
        consume(TokenType.RPAREN, "Expected ')'")
        consume(TokenType.LBRACE, "Expected '{'")
        val thenB = block()
        val elseB = if (match(TokenType.ELSE)) {
            consume(TokenType.LBRACE, "Expected '{'")
            block()
        } else null
        return Stmt.If(cond, thenB, elseB, tok.line, tok.col)
    }

    private fun whileStmt(): Stmt.While {
        val tok = consume(TokenType.WHILE, "Expected 'while'")
        consume(TokenType.LPAREN, "Expected '('")
        val cond = expression()
        consume(TokenType.RPAREN, "Expected ')'")
        consume(TokenType.LBRACE, "Expected '{'")
        val body = block()
        return Stmt.While(cond, body, tok.line, tok.col)
    }

    private fun forStmt(): Stmt.For {
        val tok = consume(TokenType.FOR, "Expected 'for'")
        consume(TokenType.LPAREN, "Expected '('")
        val varName = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
        consume(TokenType.IN, "Expected 'in'")
        val iterable = expression()
        consume(TokenType.RPAREN, "Expected ')'")
        consume(TokenType.LBRACE, "Expected '{'")
        val body = block()
        return Stmt.For(varName, iterable, body, tok.line, tok.col)
    }

    private fun returnStmt(): Stmt.Return {
        val tok = consume(TokenType.RETURN, "Expected 'return'")
        val value = if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd()) expression() else null
        matchSemicolon()
        return Stmt.Return(value, tok.line, tok.col)
    }

    private fun exprStmt(): Stmt.Expression {
        val expr = expression()
        matchSemicolon()
        return Stmt.Expression(expr, expr.line, expr.col)
    }

    private fun block(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) stmts.add(declaration())
        consume(TokenType.RBRACE, "Expected '}'")
        return stmts
    }

    private fun paramList(): List<String> {
        val params = mutableListOf<String>()
        if (!check(TokenType.RPAREN)) {
            params.add(consume(TokenType.IDENTIFIER, "Expected parameter name").lexeme)
            while (match(TokenType.COMMA))
                params.add(consume(TokenType.IDENTIFIER, "Expected parameter name").lexeme)
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return params
    }

    // --- Expressions ---

    private fun expression(): Expr = assignment()

    private fun assignment(): Expr {
        val expr = or()
        val assignOps = setOf(TokenType.ASSIGN, TokenType.PLUS_ASSIGN, TokenType.MINUS_ASSIGN,
            TokenType.STAR_ASSIGN, TokenType.SLASH_ASSIGN)
        if (peek().type in assignOps) {
            val op = advance()
            val value = assignment()
            return when (expr) {
                is Expr.Variable -> Expr.Assign(expr.name, op.type, value, expr.line, expr.col)
                is Expr.Get -> Expr.Set(expr.obj, expr.name, value, expr.line, expr.col)
                is Expr.Index -> Expr.IndexSet(expr.obj, expr.index, value, expr.line, expr.col)
                else -> throw LoomError.Parser(op.line, op.col, "Invalid assignment target")
            }
        }
        return expr
    }

    private fun or(): Expr {
        var left = and()
        while (match(TokenType.OR)) {
            val op = previous()
            left = Expr.Binary(left, op.type, and(), op.line, op.col)
        }
        return left
    }

    private fun and(): Expr {
        var left = equality()
        while (match(TokenType.AND)) {
            val op = previous()
            left = Expr.Binary(left, op.type, equality(), op.line, op.col)
        }
        return left
    }

    private fun equality(): Expr {
        var left = comparison()
        while (check(TokenType.EQ) || check(TokenType.NEQ)) {
            val op = advance()
            left = Expr.Binary(left, op.type, comparison(), op.line, op.col)
        }
        return left
    }

    private fun comparison(): Expr {
        var left = addition()
        while (check(TokenType.LT) || check(TokenType.LTE) || check(TokenType.GT) || check(TokenType.GTE)) {
            val op = advance()
            left = Expr.Binary(left, op.type, addition(), op.line, op.col)
        }
        return left
    }

    private fun addition(): Expr {
        var left = multiplication()
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val op = advance()
            left = Expr.Binary(left, op.type, multiplication(), op.line, op.col)
        }
        return left
    }

    private fun multiplication(): Expr {
        var left = unary()
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            val op = advance()
            left = Expr.Binary(left, op.type, unary(), op.line, op.col)
        }
        return left
    }

    private fun unary(): Expr {
        if (check(TokenType.BANG) || check(TokenType.NOT) || check(TokenType.MINUS)) {
            val op = advance()
            return Expr.Unary(op.type, unary(), op.line, op.col)
        }
        return call()
    }

    private fun call(): Expr {
        var expr = primary()
        while (true) {
            expr = when {
                match(TokenType.LPAREN) -> {
                    val args = argList()
                    Expr.Call(expr, args, expr.line, expr.col)
                }
                match(TokenType.DOT) -> {
                    val name = consume(TokenType.IDENTIFIER, "Expected property name after '.'")
                    Expr.Get(expr, name.lexeme, name.line, name.col)
                }
                match(TokenType.LBRACKET) -> {
                    val index = expression()
                    consume(TokenType.RBRACKET, "Expected ']'")
                    Expr.Index(expr, index, expr.line, expr.col)
                }
                else -> break
            }
        }
        return expr
    }

    private fun argList(): List<Expr> {
        val args = mutableListOf<Expr>()
        if (!check(TokenType.RPAREN)) {
            args.add(expression())
            while (match(TokenType.COMMA)) args.add(expression())
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return args
    }

    private fun primary(): Expr {
        val tok = peek()
        return when (tok.type) {
            TokenType.NUMBER -> { advance(); Expr.Literal(tok.lexeme.toDouble(), tok.line, tok.col) }
            TokenType.TRUE -> { advance(); Expr.Literal(true, tok.line, tok.col) }
            TokenType.FALSE -> { advance(); Expr.Literal(false, tok.line, tok.col) }
            TokenType.NULL -> { advance(); Expr.Literal(null, tok.line, tok.col) }
            TokenType.STRING_PART -> { advance(); Expr.Literal(tok.lexeme, tok.line, tok.col) }
            TokenType.INTERP_START -> stringTemplate()
            TokenType.IDENTIFIER -> { advance(); Expr.Variable(tok.lexeme, tok.line, tok.col) }
            TokenType.LPAREN -> {
                advance()
                val expr = expression()
                consume(TokenType.RPAREN, "Expected ')'")
                expr
            }
            TokenType.LBRACKET -> {
                advance()
                val elements = mutableListOf<Expr>()
                if (!check(TokenType.RBRACKET)) {
                    elements.add(expression())
                    while (match(TokenType.COMMA) && !check(TokenType.RBRACKET)) elements.add(expression())
                }
                consume(TokenType.RBRACKET, "Expected ']'")
                Expr.ListLiteral(elements, tok.line, tok.col)
            }
            TokenType.LBRACE -> {
                advance()
                val entries = mutableListOf<Pair<Expr, Expr>>()
                if (!check(TokenType.RBRACE)) {
                    do {
                        val key = expression()
                        consume(TokenType.COLON, "Expected ':'")
                        val value = expression()
                        entries.add(key to value)
                    } while (match(TokenType.COMMA) && !check(TokenType.RBRACE))
                }
                consume(TokenType.RBRACE, "Expected '}'")
                Expr.MapLiteral(entries, tok.line, tok.col)
            }
            TokenType.FUN -> {
                advance()
                consume(TokenType.LPAREN, "Expected '('")
                val params = paramList()
                consume(TokenType.LBRACE, "Expected '{'")
                val body = block()
                Expr.Lambda(params, body, tok.line, tok.col)
            }
            else -> throw LoomError.Parser(tok.line, tok.col, "Unexpected token '${tok.lexeme}'")
        }
    }

    private fun stringTemplate(): Expr {
        val start = peek()
        val parts = mutableListOf<Expr>()
        while (!isAtEnd()) {
            when (peek().type) {
                TokenType.STRING_PART -> {
                    val t = advance()
                    parts.add(Expr.Literal(t.lexeme, t.line, t.col))
                }
                TokenType.INTERP_START -> {
                    advance() // consume ${
                    parts.add(expression())
                    consume(TokenType.INTERP_END, "Expected '}'")
                }
                else -> break
            }
        }
        return if (parts.size == 1 && parts[0] is Expr.Literal) parts[0]
        else Expr.StringTemplate(parts, start.line, start.col)
    }

    // --- Helpers ---

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) if (check(type)) { advance(); return true }
        return false
    }

    private fun check(type: TokenType) = !isAtEnd() && peek().type == type

    private fun advance(): Token { if (!isAtEnd()) current++; return previous() }

    private fun consume(type: TokenType, msg: String): Token {
        if (check(type)) return advance()
        val t = peek()
        throw LoomError.Parser(t.line, t.col, "$msg (got '${t.lexeme}')")
    }

    private fun matchSemicolon() { match(TokenType.SEMICOLON) }

    private fun peek() = tokens[current]
    private fun previous() = tokens[current - 1]
    private fun isAtEnd() = peek().type == TokenType.EOF
}
