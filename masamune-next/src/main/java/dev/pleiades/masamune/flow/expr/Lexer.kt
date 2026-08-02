package dev.pleiades.masamune.flow.expr

import java.math.BigInteger

/**
 * A lexical unit. [symbol] is how the token is written, and is what failure messages quote back
 * at the user, so it doubles as the operator spelling everywhere else in the package.
 */
enum class TokenType(val symbol: String) {
    NUMBER("a number"),
    BIGINT("a bigint"),
    TEXT("text"),
    IDENTIFIER("a variable name"),
    NULL("null"),

    PLUS("+"),
    MINUS("-"),
    STAR("*"),
    SLASH("/"),
    SLASH_SLASH("//"),
    PERCENT("%"),
    CONCAT("++"),

    AMPERSAND("&"),
    PIPE("|"),
    CARET("^"),
    TILDE("~"),
    SHIFT_LEFT("<<"),
    SHIFT_RIGHT(">>"),
    SHIFT_RIGHT_UNSIGNED(">>>"),

    EQUAL("="),
    NOT_EQUAL("!="),
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),

    AND("&&"),
    OR("||"),
    BANG("!"),

    DOT("."),
    COMMA(","),
    COLON(":"),
    LEFT_PAREN("("),
    RIGHT_PAREN(")"),
    LEFT_BRACKET("["),
    RIGHT_BRACKET("]"),
    LEFT_BRACE("{"),
    RIGHT_BRACE("}"),

    END("the end of the expression"),
}

/**
 * One token. [literal] is filled for [TokenType.NUMBER], [TokenType.BIGINT], [TokenType.TEXT]
 * and [TokenType.NULL] so the parser never re-reads source text to recover a value; [text]
 * carries the spelling for [TokenType.IDENTIFIER].
 */
data class Token(
    val type: TokenType,
    val position: Int,
    val text: String = type.symbol,
    val literal: Value? = null,
)

/**
 * Source text to tokens.
 *
 * Two properties of this grammar bite an implementer who works from muscle memory:
 *
 * - **`//` is integer division, not a comment.** An expression language has no statements and
 *   therefore nowhere to put a comment, so the sequence is free for an operator, and the donor
 *   spends it on the one arithmetic operation a phone user reaches for most (`bytes // 1024`).
 *   A lexer that skips to end-of-line on `//` silently truncates half of every such expression.
 * - **`=` is equality.** There is no assignment anywhere in the language — a block's output
 *   section binds names, expressions only read them — so `=` is not ambiguous and `==` does not
 *   exist. Accepting `==` "to be helpful" would fork the donor's syntax on day one.
 *
 * Longest-match order is explicit below rather than emergent, because `>` / `>=` / `>>` / `>>>`
 * all share a first character and getting that ordering wrong produces expressions that parse
 * successfully and mean something else.
 */
class Lexer(private val source: String) {

    private var index = 0

    fun scan(): List<Token> {
        val tokens = ArrayList<Token>()
        while (true) {
            skipWhitespace()
            if (index >= source.length) {
                tokens += Token(TokenType.END, index)
                return tokens
            }
            tokens += scanToken()
        }
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun scanToken(): Token {
        val start = index
        val c = source[index]
        return when {
            c.isDigit() -> scanNumber()
            c == '"' -> scanText()
            c.isLetter() || c == '_' -> scanWord()
            else -> scanOperator(start, c)
        }
    }

    private fun scanOperator(start: Int, c: Char): Token {
        fun take(type: TokenType): Token {
            index += type.symbol.length
            return Token(type, start)
        }
        return when (c) {
            '+' -> if (peekIs(1, '+')) take(TokenType.CONCAT) else take(TokenType.PLUS)
            '-' -> take(TokenType.MINUS)
            '*' -> take(TokenType.STAR)
            '/' -> if (peekIs(1, '/')) take(TokenType.SLASH_SLASH) else take(TokenType.SLASH)
            '%' -> take(TokenType.PERCENT)
            '&' -> if (peekIs(1, '&')) take(TokenType.AND) else take(TokenType.AMPERSAND)
            '|' -> if (peekIs(1, '|')) take(TokenType.OR) else take(TokenType.PIPE)
            '^' -> take(TokenType.CARET)
            '~' -> take(TokenType.TILDE)
            '<' -> when {
                peekIs(1, '<') -> take(TokenType.SHIFT_LEFT)
                peekIs(1, '=') -> take(TokenType.LESS_EQUAL)
                else -> take(TokenType.LESS)
            }
            '>' -> when {
                peekIs(1, '>') && peekIs(2, '>') -> take(TokenType.SHIFT_RIGHT_UNSIGNED)
                peekIs(1, '>') -> take(TokenType.SHIFT_RIGHT)
                peekIs(1, '=') -> take(TokenType.GREATER_EQUAL)
                else -> take(TokenType.GREATER)
            }
            '=' -> if (peekIs(1, '=')) {
                fail(start, "`==` is not an operator here — equality is written `=`.")
            } else {
                take(TokenType.EQUAL)
            }
            '!' -> if (peekIs(1, '=')) take(TokenType.NOT_EQUAL) else take(TokenType.BANG)
            '.' -> take(TokenType.DOT)
            ',' -> take(TokenType.COMMA)
            ':' -> take(TokenType.COLON)
            '(' -> take(TokenType.LEFT_PAREN)
            ')' -> take(TokenType.RIGHT_PAREN)
            '[' -> take(TokenType.LEFT_BRACKET)
            ']' -> take(TokenType.RIGHT_BRACKET)
            '{' -> take(TokenType.LEFT_BRACE)
            '}' -> take(TokenType.RIGHT_BRACE)
            else -> fail(start, "`$c` is not part of any operator or value.")
        }
    }

    private fun scanWord(): Token {
        val start = index
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
        val word = source.substring(start, index)
        return if (word == "null") {
            Token(TokenType.NULL, start, word, Value.Null)
        } else {
            Token(TokenType.IDENTIFIER, start, word)
        }
    }

    /**
     * Decimal, `0x` hex or `0b` binary, each optionally suffixed `n` for a bigint.
     *
     * Radix literals go through [BigInteger] even on the number path: `0xFFFFFFFF` exceeds
     * `Int`, and parsing it with `toInt(16)` would throw on a literal the language considers
     * perfectly ordinary (it is the mask `~0` produces).
     */
    private fun scanNumber(): Token {
        val start = index
        if (source[index] == '0' && index + 1 < source.length && (source[index + 1] == 'x' || source[index + 1] == 'X')) {
            return scanRadix(start, radix = 16, prefixLength = 2) { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        }
        if (source[index] == '0' && index + 1 < source.length && (source[index + 1] == 'b' || source[index + 1] == 'B')) {
            return scanRadix(start, radix = 2, prefixLength = 2) { it == '0' || it == '1' }
        }

        while (index < source.length && source[index].isDigit()) index++
        var fractional = false
        // `1.5` is one number but `list.0` is a member access, so a dot only continues the
        // literal when a digit follows it.
        if (index + 1 < source.length && source[index] == '.' && source[index + 1].isDigit()) {
            fractional = true
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            val exponentStart = index
            var cursor = index + 1
            if (cursor < source.length && (source[cursor] == '+' || source[cursor] == '-')) cursor++
            if (cursor < source.length && source[cursor].isDigit()) {
                fractional = true
                index = cursor
                while (index < source.length && source[index].isDigit()) index++
            } else {
                index = exponentStart
            }
        }

        val digits = source.substring(start, index)
        if (index < source.length && source[index] == 'n') {
            index++
            if (fractional) {
                fail(start, "`${digits}n` is not a bigint — a bigint literal has no fractional part or exponent.")
            }
            rejectTrailingWord(start)
            return Token(TokenType.BIGINT, start, digits + "n", Value.BigInt(BigInteger(digits)))
        }
        rejectTrailingWord(start)
        return Token(TokenType.NUMBER, start, digits, Value.Num(digits.toDouble()))
    }

    private fun scanRadix(
        start: Int,
        radix: Int,
        prefixLength: Int,
        isDigit: (Char) -> Boolean,
    ): Token {
        index += prefixLength
        val digitsStart = index
        while (index < source.length && isDigit(source[index])) index++
        if (index == digitsStart) {
            fail(start, "`${source.substring(start, index)}` has no digits after its base prefix.")
        }
        val digits = source.substring(digitsStart, index)
        val magnitude = BigInteger(digits, radix)
        if (index < source.length && source[index] == 'n') {
            index++
            rejectTrailingWord(start)
            return Token(TokenType.BIGINT, start, source.substring(start, index), Value.BigInt(magnitude))
        }
        rejectTrailingWord(start)
        return Token(TokenType.NUMBER, start, source.substring(start, index), Value.Num(magnitude.toDouble()))
    }

    /** `10abc` is a typo, not a number followed by a variable. Say so where it happened. */
    private fun rejectTrailingWord(start: Int) {
        if (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) {
            val end = source.indexOfFirst(index) { !it.isLetterOrDigit() && it != '_' }
            fail(start, "`${source.substring(start, end)}` is not a valid number.")
        }
    }

    private fun scanText(): Token {
        val start = index
        index++
        val builder = StringBuilder()
        while (true) {
            if (index >= source.length) {
                fail(start, "This text is never closed — add the matching `\"`.")
            }
            when (val c = source[index]) {
                '"' -> {
                    index++
                    return Token(TokenType.TEXT, start, source.substring(start, index), Value.Text(builder.toString()))
                }
                '\\' -> {
                    index++
                    if (index >= source.length) fail(start, "This text is never closed — add the matching `\"`.")
                    builder.append(readEscape())
                }
                else -> {
                    builder.append(c)
                    index++
                }
            }
        }
    }

    private fun readEscape(): String {
        val escapeStart = index - 1
        val c = source[index]
        index++
        return when (c) {
            '"' -> "\""
            '\\' -> "\\"
            '/' -> "/"
            'b' -> "\b"
            'f' -> "\u000C"
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'u' -> {
                if (index + 4 > source.length) fail(escapeStart, "`\\u` needs four hex digits.")
                val hex = source.substring(index, index + 4)
                val code = hex.toIntOrNull(16) ?: fail(escapeStart, "`\\u$hex` is not four hex digits.")
                index += 4
                Char(code).toString()
            }
            else -> fail(escapeStart, "`\\$c` is not an escape sequence.")
        }
    }

    private fun peekIs(offset: Int, c: Char): Boolean =
        index + offset < source.length && source[index + offset] == c

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }

    private fun fail(position: Int, detail: String): Nothing =
        throw ExprException(ExprFailure(ExprFailure.Stage.LEX, detail, position, source))
}
