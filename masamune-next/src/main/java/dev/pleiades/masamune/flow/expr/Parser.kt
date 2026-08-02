package dev.pleiades.masamune.flow.expr

/**
 * Tokens to an [Expr] tree. Recursive descent, one function per precedence level.
 *
 * Precedence, tightest first — C's table with the donor's two additions slotted in:
 *
 * ```
 *   postfix     [ ]  .
 *   unary       -  +  !  ~
 *   multiply    *  /  //  %
 *   add         +  -  ++
 *   shift       <<  >>  >>>
 *   relational  <  <=  >  >=
 *   equality    =  !=
 *   bit and     &
 *   bit xor     ^
 *   bit or      |
 *   logical and &&
 *   logical or  ||
 * ```
 *
 * `++` sits with `+` and `-` rather than at a level of its own. That is the faithful reading of
 * a donor whose table is otherwise C's, and it has a consequence worth stating plainly:
 * `"total: " ++ a + b` groups as `("total: " ++ a) + b` and fails at run time, because `+` never
 * joins text. The alternative — giving `++` its own looser level so the arithmetic binds first —
 * would make that one expression work and would silently regroup every mixed expression a user
 * copies out of Automate. Parentheses are cheap; a syntax that agrees with the donor everywhere
 * except where we improved it is not.
 *
 * There is no ternary, no assignment, no function call. The donor's function library is a
 * separate organ and is not in this port; adding call syntax with nothing to call would be a
 * surface that reports a capability we do not have.
 */
class Parser(private val source: String) {

    private val tokens: List<Token> = Lexer(source).scan()
    private var index = 0

    /** Parse the whole source. Trailing tokens are an error, never silently dropped. */
    fun parse(): Expr {
        val expr = expression()
        val token = peek()
        if (token.type != TokenType.END) {
            fail(token.position, "Unexpected `${token.text}` after a complete expression.")
        }
        return expr
    }

    private fun expression(): Expr = logicalOr()

    private fun logicalOr(): Expr {
        var left = logicalAnd()
        while (peek().type == TokenType.OR) {
            val op = advance()
            left = Expr.Logical(LogicalOp.OR, left, logicalAnd(), op.position)
        }
        return left
    }

    private fun logicalAnd(): Expr {
        var left = bitOr()
        while (peek().type == TokenType.AND) {
            val op = advance()
            left = Expr.Logical(LogicalOp.AND, left, bitOr(), op.position)
        }
        return left
    }

    private fun bitOr(): Expr = leftAssociative(::bitXor, mapOf(TokenType.PIPE to BinaryOp.BIT_OR))

    private fun bitXor(): Expr = leftAssociative(::bitAnd, mapOf(TokenType.CARET to BinaryOp.BIT_XOR))

    private fun bitAnd(): Expr =
        leftAssociative(::equality, mapOf(TokenType.AMPERSAND to BinaryOp.BIT_AND))

    private fun equality(): Expr = leftAssociative(
        ::relational,
        mapOf(
            TokenType.EQUAL to BinaryOp.EQUAL,
            TokenType.NOT_EQUAL to BinaryOp.NOT_EQUAL,
        ),
    )

    private fun relational(): Expr = leftAssociative(
        ::shift,
        mapOf(
            TokenType.LESS to BinaryOp.LESS,
            TokenType.LESS_EQUAL to BinaryOp.LESS_EQUAL,
            TokenType.GREATER to BinaryOp.GREATER,
            TokenType.GREATER_EQUAL to BinaryOp.GREATER_EQUAL,
        ),
    )

    private fun shift(): Expr = leftAssociative(
        ::additive,
        mapOf(
            TokenType.SHIFT_LEFT to BinaryOp.SHIFT_LEFT,
            TokenType.SHIFT_RIGHT to BinaryOp.SHIFT_RIGHT,
            TokenType.SHIFT_RIGHT_UNSIGNED to BinaryOp.SHIFT_RIGHT_UNSIGNED,
        ),
    )

    private fun additive(): Expr = leftAssociative(
        ::multiplicative,
        mapOf(
            TokenType.PLUS to BinaryOp.ADD,
            TokenType.MINUS to BinaryOp.SUBTRACT,
            TokenType.CONCAT to BinaryOp.CONCAT,
        ),
    )

    private fun multiplicative(): Expr = leftAssociative(
        ::unary,
        mapOf(
            TokenType.STAR to BinaryOp.MULTIPLY,
            TokenType.SLASH to BinaryOp.DIVIDE,
            TokenType.SLASH_SLASH to BinaryOp.INT_DIVIDE,
            TokenType.PERCENT to BinaryOp.REMAINDER,
        ),
    )

    private fun leftAssociative(next: () -> Expr, operators: Map<TokenType, BinaryOp>): Expr {
        var left = next()
        while (true) {
            val op = operators[peek().type] ?: return left
            val token = advance()
            left = Expr.Binary(op, left, next(), token.position)
        }
    }

    private fun unary(): Expr {
        val op = when (peek().type) {
            TokenType.MINUS -> UnaryOp.NEGATE
            TokenType.PLUS -> UnaryOp.TO_NUMBER
            TokenType.BANG -> UnaryOp.NOT
            TokenType.TILDE -> UnaryOp.BIT_NOT
            else -> return postfix()
        }
        val token = advance()
        return Expr.Unary(op, unary(), token.position)
    }

    private fun postfix(): Expr {
        var target = primary()
        while (true) {
            when (peek().type) {
                TokenType.LEFT_BRACKET -> {
                    val bracket = advance()
                    val index = expression()
                    expect(TokenType.RIGHT_BRACKET, "Expected `]` to close this index.")
                    target = Expr.Index(target, index, bracket.position)
                }
                TokenType.DOT -> {
                    val dot = advance()
                    val name = peek()
                    if (name.type != TokenType.IDENTIFIER) {
                        fail(name.position, "Expected a key name after `.`, found ${name.type.symbol}.")
                    }
                    advance()
                    target = Expr.Member(target, name.text, dot.position)
                }
                else -> return target
            }
        }
    }

    private fun primary(): Expr {
        val token = peek()
        return when (token.type) {
            TokenType.NUMBER, TokenType.BIGINT, TokenType.TEXT, TokenType.NULL -> {
                advance()
                // The lexer already built the Value; a literal token without one is a lexer bug,
                // and turning it into a null-safe fallback here would hide that bug at run time.
                val value = token.literal
                    ?: fail(token.position, "`${token.text}` did not produce a value.")
                Expr.Literal(value, token.position)
            }
            TokenType.IDENTIFIER -> {
                advance()
                Expr.Variable(token.text, token.position)
            }
            TokenType.LEFT_PAREN -> {
                advance()
                val inner = expression()
                expect(TokenType.RIGHT_PAREN, "Expected `)` to close this group.")
                inner
            }
            TokenType.LEFT_BRACKET -> arrayLiteral()
            TokenType.LEFT_BRACE -> dictLiteral()
            else -> fail(token.position, "Expected a value, found ${token.type.symbol}.")
        }
    }

    private fun arrayLiteral(): Expr {
        val open = advance()
        val items = ArrayList<Expr>()
        if (peek().type != TokenType.RIGHT_BRACKET) {
            do {
                items += expression()
            } while (match(TokenType.COMMA))
        }
        expect(TokenType.RIGHT_BRACKET, "Expected `]` to close this array.")
        return Expr.ArrayLiteral(items, open.position)
    }

    /**
     * `{ "a": 1, b: 2 }`.
     *
     * Keys may be written as text or as a bare name. The bare form exists because the editor's
     * `fx` toggle means a user is often typing an expression into a one-line field on a phone,
     * where every pair of quotes is two more taps.
     */
    private fun dictLiteral(): Expr {
        val open = advance()
        val entries = ArrayList<Expr.DictLiteral.Entry>()
        if (peek().type != TokenType.RIGHT_BRACE) {
            do {
                val keyToken = peek()
                val key = when (keyToken.type) {
                    TokenType.IDENTIFIER -> keyToken.text
                    TokenType.TEXT -> (keyToken.literal as? Value.Text)?.value
                        ?: fail(keyToken.position, "This key is not text.")
                    else -> fail(
                        keyToken.position,
                        "Expected a key name or quoted key, found ${keyToken.type.symbol}.",
                    )
                }
                advance()
                expect(TokenType.COLON, "Expected `:` after the key `$key`.")
                entries += Expr.DictLiteral.Entry(key, expression())
            } while (match(TokenType.COMMA))
        }
        expect(TokenType.RIGHT_BRACE, "Expected `}` to close this dictionary.")
        return Expr.DictLiteral(entries, open.position)
    }

    private fun peek(): Token = tokens[index]

    private fun advance(): Token = tokens[index].also { if (index < tokens.lastIndex) index++ }

    private fun match(type: TokenType): Boolean {
        if (peek().type != type) return false
        advance()
        return true
    }

    private fun expect(type: TokenType, detail: String) {
        val token = peek()
        if (token.type != type) fail(token.position, "$detail Found ${token.type.symbol}.")
        advance()
    }

    private fun fail(position: Int, detail: String): Nothing =
        throw ExprException(ExprFailure(ExprFailure.Stage.PARSE, detail, position, source))
}
