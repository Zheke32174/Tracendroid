package dev.pleiades.masamune.flow.expr

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value domain on its own: truthiness and text rendering.
 *
 * These are tested apart from the evaluator because both are consumed outside it — the flow
 * runtime asks [Value.isTrue] to pick a `Decision` block's branch, and the fiber monitor renders
 * [Value.asText] into the UI.
 */
class ValueTest {

    @Test
    fun `zero is false and any other number is true`() {
        assertFalse(Value.Num(0.0).isTrue)
        assertFalse(Value.Num(-0.0).isTrue)
        assertTrue(Value.Num(1.0).isTrue)
        assertTrue(Value.Num(-1.0).isTrue)
        assertTrue(Value.Num(0.5).isTrue)
    }

    /** `12 % 0` is NaN; a fiber must not take the YES branch on the strength of a non-answer. */
    @Test
    fun `NaN is false`() {
        assertFalse(Value.Num(Double.NaN).isTrue)
    }

    @Test
    fun `infinity is true`() {
        assertTrue(Value.Num(Double.POSITIVE_INFINITY).isTrue)
        assertTrue(Value.Num(Double.NEGATIVE_INFINITY).isTrue)
    }

    @Test
    fun `bigint zero is false`() {
        assertFalse(Value.BigInt(BigInteger.ZERO).isTrue)
        assertTrue(Value.BigInt(BigInteger.ONE).isTrue)
        assertTrue(Value.BigInt(BigInteger.valueOf(-1)).isTrue)
    }

    @Test
    fun `empty text and empty containers are false`() {
        assertFalse(Value.Text("").isTrue)
        assertTrue(Value.Text("0").isTrue)
        assertFalse(Value.ArrayV(emptyList()).isTrue)
        assertTrue(Value.ArrayV(listOf(Value.Null)).isTrue)
        assertFalse(Value.DictV(emptyMap()).isTrue)
        assertTrue(Value.DictV(mapOf("a" to Value.Null)).isTrue)
    }

    @Test
    fun `null is false`() {
        assertFalse(Value.Null.isTrue)
    }

    @Test
    fun `whole numbers render without a fractional part`() {
        assertEquals("1", Value.Num(1.0).asText())
        assertEquals("0", Value.Num(0.0).asText())
        assertEquals("0", Value.Num(-0.0).asText())
        assertEquals("-42", Value.Num(-42.0).asText())
        assertEquals("1.5", Value.Num(1.5).asText())
    }

    @Test
    fun `non-finite numbers render by name`() {
        assertEquals("Infinity", Value.Num(Double.POSITIVE_INFINITY).asText())
        assertEquals("-Infinity", Value.Num(Double.NEGATIVE_INFINITY).asText())
        assertEquals("NaN", Value.Num(Double.NaN).asText())
    }

    @Test
    fun `bigint renders its exact digits`() {
        assertEquals(
            "9007199254740993",
            Value.BigInt(BigInteger("9007199254740993")).asText(),
        )
    }

    @Test
    fun `null renders as nothing so it drops out of concatenation`() {
        assertEquals("", Value.Null.asText())
    }

    @Test
    fun `containers render bracketed with nested text quoted`() {
        assertEquals(
            "[1, \"a\", null]",
            Value.ArrayV(listOf(Value.Num(1.0), Value.Text("a"), Value.Null)).asText(),
        )
        assertEquals(
            "{n: 1, s: \"a\"}",
            Value.DictV(linkedMapOf("n" to Value.Num(1.0), "s" to Value.Text("a"))).asText(),
        )
    }

    @Test
    fun `type names read naturally in a failure message`() {
        assertEquals("number", Value.Num(1.0).typeName)
        assertEquals("bigint", Value.BigInt(BigInteger.ONE).typeName)
        assertEquals("text", Value.Text("").typeName)
        assertEquals("array", Value.ArrayV(emptyList()).typeName)
        assertEquals("dictionary", Value.DictV(emptyMap()).typeName)
        assertEquals("null", Value.Null.typeName)
    }

    @Test
    fun `truth is the only bridge from Boolean and it yields numbers`() {
        assertEquals(Value.Num(1.0), Value.truth(true))
        assertEquals(Value.Num(0.0), Value.truth(false))
    }
}
