package dev.pleiades.masamune.flow.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape of the tree, not the answers — grouping is asserted structurally here so that a
 * precedence regression is reported as a precedence regression rather than as a surprising
 * number in [EvaluatorTest].
 */
class ParserTest {

    private fun parse(source: String): Expr = Parser(source).parse()

    private fun failure(source: String): ExprFailure = try {
        parse(source)
        throw AssertionError("`$source` was expected to fail parsing")
    } catch (e: ExprException) {
        e.failure
    }

    /** Renders the tree as fully-parenthesised prefix form: cheap, and unambiguous about shape. */
    private fun shape(expr: Expr): String = when (expr) {
        is Expr.Literal -> expr.value.asText().ifEmpty { "null" }
        is Expr.Variable -> expr.name
        is Expr.ArrayLiteral -> expr.items.joinToString(" ", "(array ", ")") { shape(it) }
        is Expr.DictLiteral ->
            expr.entries.joinToString(" ", "(dict ", ")") { "${it.key}=${shape(it.value)}" }
        is Expr.Unary -> "(${expr.op.symbol} ${shape(expr.operand)})"
        is Expr.Binary -> "(${expr.op.symbol} ${shape(expr.left)} ${shape(expr.right)})"
        is Expr.Logical -> "(${expr.op.symbol} ${shape(expr.left)} ${shape(expr.right)})"
        is Expr.Index -> "(index ${shape(expr.target)} ${shape(expr.index)})"
        is Expr.Member -> "(member ${shape(expr.target)} ${expr.name})"
    }

    // ----------------------------------------------------------- precedence

    @Test
    fun `multiplication binds tighter than addition`() {
        assertEquals("(+ 1 (* 2 3))", shape(parse("1 + 2 * 3")))
        assertEquals("(* (+ 1 2) 3)", shape(parse("(1 + 2) * 3")))
    }

    @Test
    fun `integer division sits with multiplication`() {
        assertEquals("(+ 1 (// 6 4))", shape(parse("1 + 6 // 4")))
        assertEquals("(// (* 2 3) 4)", shape(parse("2 * 3 // 4")))
    }

    /**
     * `++` shares the additive level, so `"a" ++ b + c` groups left. Asserted because it is the
     * one precedence decision in the port a reader is likely to want to "fix".
     */
    @Test
    fun `concatenation shares the additive level and groups left`() {
        assertEquals("(+ (++ a b) c)", shape(parse("a ++ b + c")))
        assertEquals("(++ (++ a b) c)", shape(parse("a ++ b ++ c")))
    }

    @Test
    fun `shifts are looser than arithmetic and tighter than comparison`() {
        assertEquals("(<< (+ 1 2) 3)", shape(parse("1 + 2 << 3")))
        assertEquals("(< (<< 1 2) 3)", shape(parse("1 << 2 < 3")))
    }

    @Test
    fun `comparison is tighter than equality, which is tighter than bitwise and`() {
        assertEquals("(= (< 1 2) 0)", shape(parse("1 < 2 = 0")))
        assertEquals("(& (= 1 1) 1)", shape(parse("1 = 1 & 1")))
    }

    @Test
    fun `bitwise and, xor, or nest in that order`() {
        assertEquals("(| (^ (& 1 2) 3) 4)", shape(parse("1 & 2 ^ 3 | 4")))
    }

    @Test
    fun `logical or is the loosest operator`() {
        assertEquals("(|| (&& a b) c)", shape(parse("a && b || c")))
        assertEquals("(&& (| a b) c)", shape(parse("a | b && c")))
    }

    @Test
    fun `arithmetic is left associative`() {
        assertEquals("(- (- 10 3) 2)", shape(parse("10 - 3 - 2")))
        assertEquals("(/ (/ 100 5) 2)", shape(parse("100 / 5 / 2")))
    }

    // ---------------------------------------------------------------- unary

    @Test
    fun `unary operators bind tighter than any binary operator`() {
        assertEquals("(* (- 2) 3)", shape(parse("-2 * 3")))
        assertEquals("(& (~ 1) 3)", shape(parse("~1 & 3")))
        assertEquals("(&& (! a) b)", shape(parse("!a && b")))
    }

    @Test
    fun `unary operators stack`() {
        assertEquals("(- (- 2))", shape(parse("- -2")))
        assertEquals("(! (! a))", shape(parse("!!a")))
    }

    @Test
    fun `unary plus is the coercion operator`() {
        assertEquals("(+ n)", shape(parse("+n")))
    }

    // -------------------------------------------------------------- postfix

    @Test
    fun `index and member access chain and bind tightest`() {
        assertEquals("(index a 0)", shape(parse("a[0]")))
        assertEquals("(member a b)", shape(parse("a.b")))
        assertEquals("(member (index a 0) b)", shape(parse("a[0].b")))
        assertEquals("(+ (member a b) 1)", shape(parse("a.b + 1")))
    }

    @Test
    fun `member chains do not swallow a decimal point`() {
        assertEquals("(member (member a b) c)", shape(parse("a.b.c")))
        assertEquals("1.5", shape(parse("1.5")))
    }

    // ------------------------------------------------------------- literals

    @Test
    fun `array literals`() {
        assertEquals("(array )", shape(parse("[]")))
        assertEquals("(array 1 2 3)", shape(parse("[1, 2, 3]")))
        assertEquals("(array (+ 1 2))", shape(parse("[1 + 2]")))
    }

    @Test
    fun `dictionary literals take quoted or bare keys`() {
        assertEquals("(dict )", shape(parse("{}")))
        assertEquals("(dict a=1)", shape(parse("{a: 1}")))
        assertEquals("(dict a=1 b=2)", shape(parse("{\"a\": 1, b: 2}")))
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `an unclosed group is reported where it should have closed`() {
        val failure = failure("(1 + 2")
        assertTrue(failure.detail, failure.detail.contains("`)`"))
        assertEquals(ExprFailure.Stage.PARSE, failure.stage)
    }

    @Test
    fun `trailing tokens are never silently dropped`() {
        val failure = failure("1 2")
        assertTrue(failure.detail, failure.detail.contains("after a complete expression"))
        assertEquals(2, failure.position)
    }

    @Test
    fun `a missing operand is reported at the gap`() {
        val failure = failure("1 +")
        assertTrue(failure.detail, failure.detail.contains("Expected a value"))
        assertEquals(3, failure.position)
    }

    @Test
    fun `an empty expression is a failure, not an empty value`() {
        assertTrue(failure("").detail.contains("Expected a value"))
        assertTrue(failure("   ").detail.contains("Expected a value"))
    }

    @Test
    fun `a dot must be followed by a key name`() {
        assertTrue(failure("a.1").detail.contains("key name after `.`"))
    }

    @Test
    fun `a dictionary entry needs a colon`() {
        assertTrue(failure("{a 1}").detail.contains("Expected `:`"))
    }

    @Test
    fun `an unclosed array or dictionary is reported`() {
        assertTrue(failure("[1, 2").detail.contains("`]`"))
        assertTrue(failure("{a: 1").detail.contains("`}`"))
    }

    // ------------------------------------------------------------ positions

    @Test
    fun `a node remembers the operator that produced it`() {
        val expr = parse("10 + 20") as Expr.Binary
        assertEquals(3, expr.position)
        assertEquals(0, expr.left.position)
        assertEquals(5, expr.right.position)
    }
}
