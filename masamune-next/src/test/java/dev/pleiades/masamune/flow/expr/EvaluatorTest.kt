package dev.pleiades.masamune.flow.expr

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The donor contract, row by row.
 *
 * `docs/donors/RE-automate.md` lists the semantics an implementer gets wrong by reflex, and every
 * row of both its tables has a test here under a name that quotes it. These are regression tests
 * against *our own instincts*: each one guards a place where the obvious Kotlin, C or JavaScript
 * behaviour is the wrong answer.
 */
class EvaluatorTest {

    private fun eval(source: String, variables: Map<String, Value> = emptyMap()): Value =
        Evaluator.evaluate(source, VariableScope.of(variables))

    private fun num(source: String): Double = (eval(source) as Value.Num).value

    private fun failure(source: String): ExprFailure =
        when (val outcome = Evaluator.tryEvaluate(source)) {
            is ExprOutcome.Ok -> throw AssertionError("`$source` unexpectedly produced ${outcome.value}")
            is ExprOutcome.Failed -> outcome.failure
        }

    private fun big(digits: String) = Value.BigInt(BigInteger(digits))

    // =============================================== the notable-choices table

    /** Row: "Equality | `=` | the reflex: `==`". */
    @Test
    fun `equality is a single equals sign`() {
        assertEquals(Value.Num(1.0), eval("1 = 1"))
        assertEquals(Value.Num(0.0), eval("1 = 2"))
        assertEquals(Value.Num(1.0), eval("\"a\" = \"a\""))
        assertTrue(failure("1 == 1").detail.contains("equality is written `=`"))
    }

    /** Row: "Text concatenation | `++` | the reflex: `+`". */
    @Test
    fun `text is joined with plus plus and never with plus`() {
        assertEquals(Value.Text("ab"), eval("\"a\" ++ \"b\""))
        assertEquals(Value.Text("n=1"), eval("\"n=\" ++ 1"))
        assertEquals(Value.Text("123"), eval("1 ++ 2 ++ 3"))
        val failure = failure("\"a\" + \"b\"")
        assertTrue(failure.detail, failure.detail.contains("Text is joined with `++`"))
    }

    /** `++` renders null as nothing, so an unset optional argument drops out of the sentence. */
    @Test
    fun `concatenating null contributes nothing`() {
        assertEquals(Value.Text("ab"), eval("\"a\" ++ missing ++ \"b\""))
    }

    /** Row: "Comparison result | number, `1` or `0` | the reflex: boolean". */
    @Test
    fun `comparison yields a number, never a Boolean`() {
        val result = eval("1 < 2")
        assertTrue("expected Value.Num, got ${result.typeName}", result is Value.Num)
        assertEquals(Value.Num(1.0), result)
        assertEquals(Value.Num(0.0), eval("2 < 1"))
        assertEquals(Value.Num(1.0), eval("2 >= 2"))
        assertEquals(Value.Num(1.0), eval("1 != 2"))
        // And the number it yields is an ordinary number: it can be added to.
        assertEquals(Value.Num(2.0), eval("(1 < 2) + (3 > 2)"))
    }

    /** Row: "Integer division | `//` distinct from `/` | the reflex: one operator". */
    @Test
    fun `integer division is a distinct operator`() {
        assertEquals(Value.Num(3.0), eval("7 // 2"))
        assertEquals(Value.Num(3.5), eval("7 / 2"))
        assertEquals(Value.Num(3.0), eval("10 // 3"))
    }

    /** `//` truncates toward zero so that adding an `n` cannot change the answer's sign. */
    @Test
    fun `integer division truncates toward zero, matching bigint division`() {
        assertEquals(Value.Num(-3.0), eval("-7 // 2"))
        assertEquals(big("-3"), eval("-7n / 2n"))
    }

    /** Row: "`10 / 0` | Infinity | the reflex: error". */
    @Test
    fun `dividing a number by zero is Infinity`() {
        assertEquals(Value.Num(Double.POSITIVE_INFINITY), eval("10 / 0"))
        assertEquals(Value.Num(Double.NEGATIVE_INFINITY), eval("-10 / 0"))
        assertEquals(Value.Num(Double.POSITIVE_INFINITY), eval("10 // 0"))
    }

    /** Row: "`12 % 0` | NaN | the reflex: error". */
    @Test
    fun `taking a number remainder by zero is NaN`() {
        assertTrue(num("12 % 0").isNaN())
        assertTrue(num("0 / 0").isNaN())
    }

    /** Row: "`10n / 0n` (bigint) | fails". */
    @Test
    fun `dividing a bigint by zero fails`() {
        val failure = failure("10n / 0n")
        assertTrue(failure.detail, failure.detail.contains("divide a bigint by zero"))
        assertEquals(ExprFailure.Stage.EVAL, failure.stage)
    }

    @Test
    fun `taking a bigint remainder by zero fails`() {
        assertTrue(failure("10n % 0n").detail.contains("remainder by zero"))
    }

    /** Row: "`10n / 3n` | `BigInt(3)`". */
    @Test
    fun `bigint division is integer division`() {
        assertEquals(big("3"), eval("10n / 3n"))
        assertEquals(big("3"), eval("10n // 3n"))
        assertEquals(big("1"), eval("10n % 3n"))
    }

    /** Row: "Mixing bigint and number in arithmetic | fails — must coerce explicitly". */
    @Test
    fun `mixing a number and a bigint in arithmetic fails in both orders`() {
        for (source in listOf("10n + 3", "3 + 10n", "10n - 3", "10n * 3", "10n / 3", "10n // 3", "10n % 3")) {
            val failure = failure(source)
            assertTrue(
                "$source: ${failure.detail}",
                failure.detail.contains("cannot mix a number and a bigint"),
            )
        }
    }

    /** The same rule for bitwise operators — the contract names both classes. */
    @Test
    fun `mixing a number and a bigint in bitwise operators fails`() {
        for (source in listOf("10n & 3", "3 | 10n", "10n ^ 3", "10n << 2", "2 >> 1n")) {
            assertTrue(source, failure(source).detail.contains("cannot mix a number and a bigint"))
        }
    }

    /** Unary `+` is the sanctioned crossing, and it costs what it costs. */
    @Test
    fun `unary plus coerces a bigint into a number`() {
        assertEquals(Value.Num(13.0), eval("+10n + 3"))
        // 2^53 + 1 has no double form; the coercion says so by rounding, not by failing.
        assertEquals(Value.Num(9.007199254740992E15), eval("+9007199254740993n"))
    }

    /** Row: "Mixing them in comparison | allowed". */
    @Test
    fun `a number and a bigint may be compared`() {
        assertEquals(Value.Num(1.0), eval("10n = 10"))
        assertEquals(Value.Num(1.0), eval("10 = 10n"))
        assertEquals(Value.Num(1.0), eval("10n < 11"))
        assertEquals(Value.Num(1.0), eval("11 > 10n"))
        assertEquals(Value.Num(0.0), eval("10n != 10"))
    }

    /** Compared exactly, not by widening — widening is what the arithmetic rule forbids. */
    @Test
    fun `bigint comparison keeps digits past two to the fifty-third`() {
        assertEquals(Value.Num(1.0), eval("9007199254740993n > 9007199254740992"))
        assertEquals(Value.Num(0.0), eval("9007199254740993n = 9007199254740992"))
    }

    /** Row: "Comparing number with text | returns `0`, never an error | the reflex: type error". */
    @Test
    fun `comparing a number with text is zero, not an error`() {
        assertEquals(Value.Num(0.0), eval("1 = \"1\""))
        assertEquals(Value.Num(0.0), eval("1 < \"1\""))
        assertEquals(Value.Num(0.0), eval("1 > \"1\""))
        assertEquals(Value.Num(0.0), eval("1 <= \"1\""))
        assertEquals(Value.Num(0.0), eval("1 >= \"1\""))
    }

    /** The one disambiguation we had to make: `!=` stays the exact negation of `=`. */
    @Test
    fun `unequal types are not equal`() {
        assertEquals(Value.Num(1.0), eval("1 != \"1\""))
        assertEquals(Value.Num(1.0), eval("\"1\" != 1"))
    }

    /** Row: "`null` | compares as less than any non-null". */
    @Test
    fun `null orders below every non-null value`() {
        assertEquals(Value.Num(1.0), eval("null < 0"))
        assertEquals(Value.Num(1.0), eval("null < -1000"))
        assertEquals(Value.Num(1.0), eval("null < \"\""))
        assertEquals(Value.Num(1.0), eval("null < 0n"))
        assertEquals(Value.Num(0.0), eval("0 < null"))
        assertEquals(Value.Num(1.0), eval("0 > null"))
        assertEquals(Value.Num(1.0), eval("null = null"))
        assertEquals(Value.Num(0.0), eval("null = 0"))
    }

    /** Row: "Text comparison | case-sensitive lexicographical". */
    @Test
    fun `text compares case-sensitively and lexicographically`() {
        assertEquals(Value.Num(1.0), eval("\"apple\" < \"banana\""))
        assertEquals(Value.Num(1.0), eval("\"Z\" < \"a\""))
        assertEquals(Value.Num(0.0), eval("\"a\" = \"A\""))
        assertEquals(Value.Num(1.0), eval("\"abc\" < \"abd\""))
        assertEquals(Value.Num(1.0), eval("\"ab\" < \"abc\""))
    }

    // ============================================ arithmetic edge-case table

    /** Row: "`~0b1` | `0xFFFFFFFE` (32-bit)". */
    @Test
    fun `bitwise not on a number is 32-bit and reads unsigned`() {
        assertEquals(Value.Num(4294967294.0), eval("~0b1"))
        assertEquals(Value.Num(4294967294.0), eval("0xFFFFFFFE"))
        assertEquals(Value.Num(4294967295.0), eval("~0"))
    }

    /**
     * Row: "`~0b1n` | `BigInt(-1)`" — implemented as `BigInt(-2)`.
     *
     * The contract's two NOT rows disagree: `~0b1` is given as `0xFFFFFFFE`, which is `-2` read
     * unsigned, and bitwise NOT is `-x - 1` in every width, so `~1n` is `-2n` and `-1n` is `~0n`.
     * The self-consistent answer is implemented; the doc row is a transcription slip.
     */
    @Test
    fun `bitwise not on a bigint is twos complement at unbounded width`() {
        assertEquals(big("-2"), eval("~0b1n"))
        assertEquals(big("-1"), eval("~0n"))
        assertEquals(big("0"), eval("~-1n"))
    }

    /** Row: "`>>>` on `BigInt` | failure". */
    @Test
    fun `unsigned right shift has no bigint meaning`() {
        val failure = failure("8n >>> 1n")
        assertTrue(failure.detail, failure.detail.contains("no meaning for a bigint"))
        // The signed shift is fine, and is what the message points at.
        assertEquals(big("4"), eval("8n >> 1n"))
    }

    @Test
    fun `bitwise operators on numbers work in 32 bits`() {
        assertEquals(Value.Num(240.0), eval("0xFFFFFFFF & 0xF0"))
        assertEquals(Value.Num(15.0), eval("0b1010 | 0b0101"))
        assertEquals(Value.Num(5.0), eval("6 ^ 3"))
        assertEquals(Value.Num(8.0), eval("1 << 3"))
        assertEquals(Value.Num(2147483648.0), eval("1 << 31"))
        assertEquals(Value.Num(2.0), eval("8 >> 2"))
        assertEquals(Value.Num(2147483647.0), eval("-2 >>> 1"))
        // An arithmetic shift keeps the sign bit; the value shown is that pattern read unsigned.
        assertEquals(Value.Num(4294967295.0), eval("-2 >> 1"))
    }

    @Test
    fun `bigint shifts are exact and unbounded`() {
        assertEquals(big("18446744073709551616"), eval("1n << 64n"))
        assertEquals(big("1"), eval("18446744073709551616n >> 64n"))
        assertTrue(failure("1n << -1n").detail.contains("shift count of zero or more"))
    }

    // ================================================== the rest of the table

    @Test
    fun `arithmetic operators`() {
        assertEquals(Value.Num(5.0), eval("2 + 3"))
        assertEquals(Value.Num(-1.0), eval("2 - 3"))
        assertEquals(Value.Num(6.0), eval("2 * 3"))
        assertEquals(Value.Num(0.5), eval("1 / 2"))
        assertEquals(Value.Num(1.0), eval("7 % 3"))
        assertEquals(Value.Num(-1.0), eval("-7 % 3"))
        assertEquals(Value.Num(-3.0), eval("-3"))
        assertEquals(Value.Num(3.0), eval("- -3"))
    }

    @Test
    fun `bigint arithmetic is exact`() {
        assertEquals(big("9007199254740994"), eval("9007199254740993n + 1n"))
        assertEquals(
            big("123456789012345678901234567890"),
            eval("12345678901234567890123456789n * 10n"),
        )
        assertEquals(big("-5"), eval("-5n"))
    }

    @Test
    fun `arithmetic on text or containers fails and names both operand types`() {
        val failure = failure("\"a\" - 1")
        assertTrue(failure.detail, failure.detail.contains("text"))
        assertTrue(failure.detail, failure.detail.contains("number"))
        assertTrue(failure("[1] * 2").detail.contains("array"))
        assertTrue(failure("-\"a\"").detail.contains("`-` needs a number or a bigint"))
        assertTrue(failure("~\"a\"").detail.contains("`~` needs a number or a bigint"))
    }

    @Test
    fun `logical operators yield numbers and short-circuit`() {
        assertEquals(Value.Num(1.0), eval("1 && 2"))
        assertEquals(Value.Num(0.0), eval("1 && 0"))
        assertEquals(Value.Num(1.0), eval("0 || 3"))
        assertEquals(Value.Num(0.0), eval("0 || 0"))
        assertEquals(Value.Num(0.0), eval("!1"))
        assertEquals(Value.Num(1.0), eval("!0"))
        assertEquals(Value.Num(1.0), eval("!null"))
        // The right operand would fail if it were evaluated, so reaching a value proves the cut.
        assertEquals(Value.Num(0.0), eval("0 && (\"a\" - 1)"))
        assertEquals(Value.Num(1.0), eval("1 || (\"a\" - 1)"))
    }

    @Test
    fun `NaN is unordered and equals nothing, including itself`() {
        assertEquals(Value.Num(0.0), eval("(0 / 0) = (0 / 0)"))
        assertEquals(Value.Num(0.0), eval("(0 / 0) < 1"))
        assertEquals(Value.Num(0.0), eval("(0 / 0) > 1"))
        assertEquals(Value.Num(1.0), eval("(0 / 0) != (0 / 0)"))
    }

    /** Kotlin's `==` and the language's `=` deliberately disagree about NaN. */
    @Test
    fun `structural equality and the equality operator part company on NaN`() {
        assertEquals(Value.Num(Double.NaN), eval("0 / 0"))
        assertNotEquals(Value.Num(1.0), eval("(0 / 0) = (0 / 0)"))
    }

    @Test
    fun `containers compare structurally using the same rules`() {
        assertEquals(Value.Num(1.0), eval("[1, 2] = [1, 2]"))
        assertEquals(Value.Num(0.0), eval("[1, 2] = [1, 3]"))
        assertEquals(Value.Num(0.0), eval("[1, 2] = [1]"))
        assertEquals(Value.Num(1.0), eval("{a: 1} = {a: 1}"))
        assertEquals(Value.Num(0.0), eval("{a: 1} = {b: 1}"))
        // A container is not a place where the language's own NaN rule stops applying.
        assertEquals(Value.Num(0.0), eval("[0 / 0] = [0 / 0]"))
        // Ordering is undefined for containers, so it is 0 rather than a failure.
        assertEquals(Value.Num(0.0), eval("[1] < [2]"))
    }

    // ================================================== literals and access

    @Test
    fun `every literal form evaluates`() {
        assertEquals(Value.Num(42.0), eval("42"))
        assertEquals(Value.Num(255.0), eval("0xFF"))
        assertEquals(Value.Num(10.0), eval("0b1010"))
        assertEquals(big("10"), eval("10n"))
        assertEquals(Value.Text("hi"), eval("\"hi\""))
        assertEquals(Value.Null, eval("null"))
        assertEquals(
            Value.ArrayV(listOf(Value.Num(1.0), Value.Text("a"), Value.Null)),
            eval("[1, \"a\", null]"),
        )
        assertEquals(
            Value.DictV(mapOf("n" to Value.Num(1.0), "s" to Value.Text("a"))),
            eval("{n: 1, \"s\": \"a\"}"),
        )
    }

    @Test
    fun `array and dictionary elements are expressions`() {
        assertEquals(Value.ArrayV(listOf(Value.Num(3.0))), eval("[1 + 2]"))
        assertEquals(Value.DictV(mapOf("n" to Value.Num(3.0))), eval("{n: 1 + 2}"))
    }

    @Test
    fun `an unset variable reads as null rather than failing`() {
        assertEquals(Value.Null, eval("never_set"))
        assertEquals(Value.Num(1.0), eval("never_set = null"))
    }

    @Test
    fun `variables come from the scope`() {
        val scope = mapOf("level" to Value.Num(80.0), "name" to Value.Text("phone"))
        assertEquals(Value.Num(160.0), eval("level * 2", scope))
        assertEquals(Value.Text("phone: 80"), eval("name ++ \": \" ++ level", scope))
        assertEquals(Value.Num(1.0), eval("level > 50 && name = \"phone\"", scope))
    }

    @Test
    fun `reading past an array or a missing key is null`() {
        assertEquals(Value.Num(20.0), eval("[10, 20, 30][1]"))
        assertEquals(Value.Null, eval("[10][5]"))
        assertEquals(Value.Null, eval("[10][-1]"))
        assertEquals(Value.Num(1.0), eval("{a: 1}.a"))
        assertEquals(Value.Num(1.0), eval("{a: 1}[\"a\"]"))
        assertEquals(Value.Null, eval("{a: 1}.b"))
        // Absent all the way down, so a path written once still evaluates before the event lands.
        assertEquals(Value.Null, eval("event.location.name"))
    }

    @Test
    fun `indexing something that is not a container fails`() {
        assertTrue(failure("1[0]").detail.contains("Cannot index number"))
        assertTrue(failure("{a: 1}[0]").detail.contains("dictionary key must be text"))
        assertTrue(failure("[1][\"a\"]").detail.contains("index must be a number"))
        assertTrue(failure("[1][0.5]").detail.contains("whole number"))
        assertTrue(failure("1 . a").detail.contains("Cannot read `.a` from number"))
    }

    // ============================================================== failures

    @Test
    fun `a failure names what failed and where`() {
        val failure = failure("1 + 2 + 10n")
        assertEquals(ExprFailure.Stage.EVAL, failure.stage)
        assertEquals(6, failure.position)
        assertTrue(failure.message, failure.message.contains("1 + 2 + 10n"))
        assertTrue(failure.message, failure.message.contains("^"))
    }

    @Test
    fun `a failure is catchable as an exception carrying the same data`() {
        val caught = try {
            Evaluator.evaluate("10n / 0n")
            throw AssertionError("expected an ExprException")
        } catch (e: ExprException) {
            e
        }
        assertEquals(ExprFailure.Stage.EVAL, caught.failure.stage)
        assertEquals(caught.failure.message, caught.message)
    }

    @Test
    fun `tryEvaluate never throws`() {
        assertTrue(Evaluator.tryEvaluate("10n / 0n") is ExprOutcome.Failed)
        assertTrue(Evaluator.tryEvaluate("(") is ExprOutcome.Failed)
        assertTrue(Evaluator.tryEvaluate("1 @ 2") is ExprOutcome.Failed)
        assertEquals(Value.Num(3.0), Evaluator.tryEvaluate("1 + 2").valueOrNull)
    }

    /** A compiled tree is reused across visits, so it must be evaluable against many scopes. */
    @Test
    fun `a compiled expression can be evaluated repeatedly against different scopes`() {
        val expr = Evaluator.compile("count + 1")
        val first = Evaluator.tryEvaluate(expr, "count + 1", VariableScope.of(mapOf("count" to Value.Num(1.0))))
        val second = Evaluator.tryEvaluate(expr, "count + 1", VariableScope.of(mapOf("count" to Value.Num(41.0))))
        assertEquals(Value.Num(2.0), first.valueOrNull)
        assertEquals(Value.Num(42.0), second.valueOrNull)
    }
}
