package dev.pleiades.masamune.flow.expr

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Literals and operator spellings — the layer where a reflex costs the most. */
class LexerTest {

    private fun types(source: String): List<TokenType> = Lexer(source).scan().map { it.type }

    private fun literal(source: String): Value? = Lexer(source).scan().first().literal

    private fun failure(source: String): ExprFailure = try {
        Lexer(source).scan()
        throw AssertionError("`$source` was expected to fail lexing")
    } catch (e: ExprException) {
        e.failure
    }

    // ------------------------------------------------------------- literals

    @Test
    fun `decimal literals`() {
        assertEquals(Value.Num(0.0), literal("0"))
        assertEquals(Value.Num(42.0), literal("42"))
        assertEquals(Value.Num(1.5), literal("1.5"))
        assertEquals(Value.Num(150.0), literal("1.5e2"))
        assertEquals(Value.Num(0.015), literal("1.5e-2"))
        assertEquals(Value.Num(1500.0), literal("15E+2"))
    }

    @Test
    fun `hex literals`() {
        assertEquals(Value.Num(255.0), literal("0xFF"))
        assertEquals(Value.Num(255.0), literal("0xff"))
        assertEquals(Value.Num(31.0), literal("0X1f"))
        // Exceeds Int; the lexer must not be parsing these with toInt(16).
        assertEquals(Value.Num(4294967295.0), literal("0xFFFFFFFF"))
    }

    @Test
    fun `binary literals`() {
        assertEquals(Value.Num(1.0), literal("0b1"))
        assertEquals(Value.Num(10.0), literal("0b1010"))
        assertEquals(Value.Num(0.0), literal("0B0"))
    }

    @Test
    fun `the n suffix makes a bigint in every base`() {
        assertEquals(Value.BigInt(BigInteger.TEN), literal("10n"))
        assertEquals(Value.BigInt(BigInteger.valueOf(255)), literal("0xFFn"))
        assertEquals(Value.BigInt(BigInteger.ONE), literal("0b1n"))
    }

    @Test
    fun `bigint literals keep digits a double would round away`() {
        assertEquals(Value.BigInt(BigInteger("9007199254740993")), literal("9007199254740993n"))
    }

    @Test
    fun `text literals and escapes`() {
        assertEquals(Value.Text("hi"), literal("\"hi\""))
        assertEquals(Value.Text(""), literal("\"\""))
        assertEquals(Value.Text("a\nb"), literal("\"a\\nb\""))
        assertEquals(Value.Text("say \"hi\""), literal("\"say \\\"hi\\\"\""))
        assertEquals(Value.Text("back\\slash"), literal("\"back\\\\slash\""))
        assertEquals(Value.Text("A"), literal("\"\\u0041\""))
        assertEquals(Value.Text("\t"), literal("\"\\t\""))
    }

    @Test
    fun `null is a literal, not a variable`() {
        assertEquals(Value.Null, literal("null"))
        assertEquals(listOf(TokenType.NULL, TokenType.END), types("null"))
    }

    @Test
    fun `identifiers are variable names`() {
        val tokens = Lexer("battery_level2").scan()
        assertEquals(TokenType.IDENTIFIER, tokens[0].type)
        assertEquals("battery_level2", tokens[0].text)
    }

    // ------------------------------------------------------------ operators

    /** The single most likely reflex error: treating `//` as a line comment. */
    @Test
    fun `double slash is integer division and not a comment`() {
        assertEquals(
            listOf(TokenType.NUMBER, TokenType.SLASH_SLASH, TokenType.NUMBER, TokenType.END),
            types("10 // 3"),
        )
        assertEquals(
            listOf(TokenType.NUMBER, TokenType.SLASH_SLASH, TokenType.NUMBER, TokenType.PLUS, TokenType.NUMBER, TokenType.END),
            types("10 // 3 + 1"),
        )
    }

    @Test
    fun `plus plus is concatenation, distinct from plus`() {
        assertEquals(listOf(TokenType.CONCAT, TokenType.END), types("++"))
        assertEquals(listOf(TokenType.PLUS, TokenType.END), types("+"))
    }

    @Test
    fun `shifts and relations disambiguate longest-first`() {
        assertEquals(listOf(TokenType.SHIFT_RIGHT_UNSIGNED, TokenType.END), types(">>>"))
        assertEquals(listOf(TokenType.SHIFT_RIGHT, TokenType.END), types(">>"))
        assertEquals(listOf(TokenType.GREATER_EQUAL, TokenType.END), types(">="))
        assertEquals(listOf(TokenType.GREATER, TokenType.END), types(">"))
        assertEquals(listOf(TokenType.SHIFT_LEFT, TokenType.END), types("<<"))
        assertEquals(listOf(TokenType.LESS_EQUAL, TokenType.END), types("<="))
        assertEquals(listOf(TokenType.LESS, TokenType.END), types("<"))
    }

    @Test
    fun `logical and bitwise operators are separate spellings`() {
        assertEquals(listOf(TokenType.AND, TokenType.END), types("&&"))
        assertEquals(listOf(TokenType.AMPERSAND, TokenType.END), types("&"))
        assertEquals(listOf(TokenType.OR, TokenType.END), types("||"))
        assertEquals(listOf(TokenType.PIPE, TokenType.END), types("|"))
        assertEquals(listOf(TokenType.CARET, TokenType.END), types("^"))
        assertEquals(listOf(TokenType.TILDE, TokenType.END), types("~"))
    }

    @Test
    fun `equality is one equals sign`() {
        assertEquals(listOf(TokenType.EQUAL, TokenType.END), types("="))
        assertEquals(listOf(TokenType.NOT_EQUAL, TokenType.END), types("!="))
        assertEquals(listOf(TokenType.BANG, TokenType.END), types("!"))
    }

    @Test
    fun `a C-style double equals is rejected by name`() {
        val failure = failure("1 == 1")
        assertTrue(failure.detail, failure.detail.contains("equality is written `=`"))
        assertEquals(2, failure.position)
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `an unterminated text says so`() {
        val failure = failure("\"open")
        assertTrue(failure.detail, failure.detail.contains("never closed"))
        assertEquals(0, failure.position)
    }

    @Test
    fun `a fractional bigint is rejected`() {
        val failure = failure("1.5n")
        assertTrue(failure.detail, failure.detail.contains("no fractional part"))
    }

    @Test
    fun `a base prefix with no digits is rejected`() {
        assertTrue(failure("0x").detail.contains("no digits"))
        assertTrue(failure("0b2").detail.contains("no digits"))
    }

    @Test
    fun `a number running into letters is a typo, not two tokens`() {
        assertTrue(failure("10abc").detail.contains("not a valid number"))
    }

    @Test
    fun `an unknown character names itself`() {
        val failure = failure("1 @ 2")
        assertTrue(failure.detail, failure.detail.contains("`@`"))
        assertEquals(2, failure.position)
    }

    @Test
    fun `an unknown escape names itself`() {
        assertTrue(failure("\"\\q\"").detail.contains("`\\q`"))
    }

    @Test
    fun `a failure message carries the source and a caret`() {
        val failure = failure("1 @ 2")
        assertTrue(failure.message, failure.message.contains("1 @ 2"))
        assertTrue(failure.message, failure.message.contains("^"))
        assertEquals(ExprFailure.Stage.LEX, failure.stage)
    }

    // ------------------------------------------------------------ positions

    @Test
    fun `token positions are source offsets`() {
        val tokens = Lexer("12 ++ x").scan()
        assertEquals(0, tokens[0].position)
        assertEquals(3, tokens[1].position)
        assertEquals(6, tokens[2].position)
        assertEquals(7, tokens[3].position)
    }

    @Test
    fun `whitespace is not required between tokens`() {
        assertEquals(
            listOf(TokenType.NUMBER, TokenType.SLASH_SLASH, TokenType.NUMBER, TokenType.END),
            types("10//3"),
        )
    }
}
