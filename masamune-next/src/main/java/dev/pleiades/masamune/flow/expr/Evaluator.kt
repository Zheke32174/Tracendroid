package dev.pleiades.masamune.flow.expr

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.sign
import kotlin.math.truncate

/**
 * Where an expression's variables come from.
 *
 * A fiber holds its own variable frame, and two fibers of the same flow hold different ones, so
 * the scope is passed per evaluation rather than owned by the evaluator. Returning `null` means
 * "no such variable", which the evaluator renders as [Value.Null] — see [Expr.Variable].
 */
fun interface VariableScope {
    fun lookup(name: String): Value?

    companion object {
        val EMPTY = VariableScope { null }

        fun of(variables: Map<String, Value>): VariableScope = VariableScope { variables[it] }
    }
}

/**
 * Evaluates an [Expr] tree, exactly as Automate does. The rules below are ported, not designed;
 * `docs/donors/RE-automate.md` is the contract and every row of its table has a test.
 *
 * The four that an implementer gets wrong from muscle memory, and why each is right:
 *
 * - **Comparison yields a number**, `Num(1.0)` or `Num(0.0)`, never a Kotlin `Boolean`. The
 *   language has no boolean type at all, so a comparison that produced one would produce a value
 *   no other operator accepts and no variable can hold.
 * - **`10 / 0` is `Infinity` and `12 % 0` is `NaN`**, because numbers are IEEE-754 doubles and
 *   those are simply what IEEE says. Neither is a special case; the special case would be
 *   raising an error, which costs code and stops a fiber that had a usable answer.
 * - **`10n / 0n` fails.** A bigint has no Infinity to be, so there is nothing to return, and
 *   returning `0n` or `null` would let a divide-by-zero bug propagate as a plausible number.
 * - **A number and a bigint never mix in arithmetic or bitwise operators.** Promotion would
 *   silently drop digits past 2^53 — the exact thing the user reached for a bigint to avoid.
 *   They *do* mix in comparison, where no value is produced and so nothing can be lost.
 *
 * One row of the donor table could not be implemented as written: it gives `~0b1n` as
 * `BigInt(-1)`, while the neighbouring row gives `~0b1` as `0xFFFFFFFE`, which is `-2` read as
 * an unsigned 32-bit pattern. Both cannot be true — bitwise NOT is `-x - 1`, so `~1` is `-2` and
 * `-1` is `~0`. The two's-complement answer is implemented and tested; the doc row is a
 * transcription slip.
 */
class Evaluator(private val source: String, private val scope: VariableScope) {

    fun eval(expr: Expr): Value = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.Variable -> scope.lookup(expr.name) ?: Value.Null
        is Expr.ArrayLiteral -> Value.ArrayV(expr.items.map { eval(it) })
        is Expr.DictLiteral -> Value.DictV(expr.entries.associate { it.key to eval(it.value) })
        is Expr.Unary -> unary(expr.op, eval(expr.operand), expr.position)
        is Expr.Binary -> binary(expr.op, eval(expr.left), eval(expr.right), expr.position)
        is Expr.Logical -> logical(expr)
        is Expr.Index -> index(eval(expr.target), eval(expr.index), expr.position)
        is Expr.Member -> member(eval(expr.target), expr.name, expr.position)
    }

    // ---------------------------------------------------------------- unary

    private fun unary(op: UnaryOp, operand: Value, at: Int): Value = when (op) {
        UnaryOp.NEGATE -> when (operand) {
            is Value.Num -> Value.Num(-operand.value)
            is Value.BigInt -> Value.BigInt(operand.value.negate())
            else -> fail(at, "`-` needs a number or a bigint, got ${operand.typeName}.")
        }

        UnaryOp.TO_NUMBER -> toNumber(operand, at)

        UnaryOp.NOT -> Value.truth(!operand.isTrue)

        UnaryOp.BIT_NOT -> when (operand) {
            is Value.Num -> Value.Num(unsigned32(toInt32(operand.value).inv()))
            is Value.BigInt -> Value.BigInt(operand.value.not())
            else -> fail(at, "`~` needs a number or a bigint, got ${operand.typeName}.")
        }
    }

    /**
     * Unary `+`, the coercion operator — and the only sanctioned way across the bigint/number
     * wall.
     *
     * Text that does not read as a number becomes `NaN` rather than failing, matching how the
     * language already treats arithmetic it cannot answer (`12 % 0`). Null becomes `NaN` too,
     * deliberately not `0`: a blank optional argument that quietly counted as zero would be
     * indistinguishable from one the user actually set to zero, and `NaN` poisons the rest of
     * the arithmetic instead of producing a confident wrong number.
     */
    private fun toNumber(operand: Value, at: Int): Value = when (operand) {
        is Value.Num -> operand
        is Value.BigInt -> Value.Num(operand.value.toDouble())
        is Value.Text -> Value.Num(operand.value.trim().toDoubleOrNull() ?: Double.NaN)
        Value.Null -> Value.Num(Double.NaN)
        is Value.ArrayV, is Value.DictV ->
            fail(at, "`+` cannot turn ${operand.typeName} into a number.")
    }

    // --------------------------------------------------------------- binary

    private fun binary(op: BinaryOp, left: Value, right: Value, at: Int): Value = when (op) {
        BinaryOp.ADD, BinaryOp.SUBTRACT, BinaryOp.MULTIPLY,
        BinaryOp.DIVIDE, BinaryOp.INT_DIVIDE, BinaryOp.REMAINDER,
        -> arithmetic(op, left, right, at)

        BinaryOp.CONCAT -> Value.Text(left.asText() + right.asText())

        BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR,
        BinaryOp.SHIFT_LEFT, BinaryOp.SHIFT_RIGHT, BinaryOp.SHIFT_RIGHT_UNSIGNED,
        -> bitwise(op, left, right, at)

        BinaryOp.EQUAL -> Value.truth(valuesEqual(left, right))
        BinaryOp.NOT_EQUAL -> Value.truth(!valuesEqual(left, right))
        BinaryOp.LESS, BinaryOp.LESS_EQUAL, BinaryOp.GREATER, BinaryOp.GREATER_EQUAL ->
            ordering(op, left, right)
    }

    private fun arithmetic(op: BinaryOp, left: Value, right: Value, at: Int): Value = when {
        left is Value.Num && right is Value.Num ->
            Value.Num(numberArithmetic(op, left.value, right.value, at))

        left is Value.BigInt && right is Value.BigInt ->
            Value.BigInt(bigIntArithmetic(op, left.value, right.value, at))

        left is Value.Num && right is Value.BigInt || left is Value.BigInt && right is Value.Num ->
            fail(
                at,
                "`${op.symbol}` cannot mix a number and a bigint. Coerce first — unary `+` " +
                    "turns a bigint into a number, at the precision cost that implies.",
            )

        else -> fail(
            at,
            "`${op.symbol}` needs two numbers or two bigints, got ${left.typeName} and " +
                "${right.typeName}." + if (op == BinaryOp.ADD) " Text is joined with `++`." else "",
        )
    }

    private fun numberArithmetic(op: BinaryOp, a: Double, b: Double, at: Int): Double = when (op) {
        BinaryOp.ADD -> a + b
        BinaryOp.SUBTRACT -> a - b
        BinaryOp.MULTIPLY -> a * b
        // Division by zero is Infinity and 0/0 is NaN by IEEE-754. Nothing here special-cases
        // them, which is the point: they are results, not errors.
        BinaryOp.DIVIDE -> a / b
        // Truncation toward zero, not floor, so `-7 // 2` and `-7n / 2n` agree. If they
        // disagreed, adding an `n` to a working expression would change its answer.
        BinaryOp.INT_DIVIDE -> truncate(a / b)
        BinaryOp.REMAINDER -> a % b
        else -> fail(at, "`${op.symbol}` is not arithmetic.")
    }

    private fun bigIntArithmetic(op: BinaryOp, a: BigInteger, b: BigInteger, at: Int): BigInteger =
        when (op) {
            BinaryOp.ADD -> a.add(b)
            BinaryOp.SUBTRACT -> a.subtract(b)
            BinaryOp.MULTIPLY -> a.multiply(b)
            BinaryOp.DIVIDE, BinaryOp.INT_DIVIDE -> {
                if (b.signum() == 0) {
                    fail(
                        at,
                        "Cannot divide a bigint by zero. `10 / 0` is Infinity because numbers " +
                            "are floating point; a bigint has no Infinity to be.",
                    )
                }
                a.divide(b)
            }
            BinaryOp.REMAINDER -> {
                if (b.signum() == 0) {
                    fail(
                        at,
                        "Cannot take a bigint remainder by zero. `12 % 0` is NaN because " +
                            "numbers are floating point; a bigint has no NaN to be.",
                    )
                }
                a.rem(b)
            }
            else -> fail(at, "`${op.symbol}` is not arithmetic.")
        }

    // -------------------------------------------------------------- bitwise

    /**
     * Bitwise operators read their operands as 32-bit two's complement and yield the result as
     * an unsigned 32-bit magnitude, so `~0b1` is `0xFFFFFFFE` (4294967294) rather than `-2`.
     *
     * The donor's table fixes that one case, and the rest follow it for consistency: a bitwise
     * result is a bit pattern, and every pattern in this language should print in the same range
     * a mask literal is written in. The visible consequence is that `-2 >> 1` is `0xFFFFFFFF`,
     * not `-1` — the arithmetic shift still propagates the sign *bit*, and the value shown is
     * that pattern read unsigned.
     *
     * Bigints shift by exact bit counts with no width to wrap against, which is why `>>>` has no
     * bigint meaning: there is no leading region to shift zeros into.
     */
    private fun bitwise(op: BinaryOp, left: Value, right: Value, at: Int): Value = when {
        left is Value.Num && right is Value.Num -> {
            val a = toInt32(left.value)
            val b = toInt32(right.value)
            // Shift counts wrap at the operand width, as they do on every 32-bit machine.
            val places = b and 31
            val result = when (op) {
                BinaryOp.BIT_AND -> a and b
                BinaryOp.BIT_OR -> a or b
                BinaryOp.BIT_XOR -> a xor b
                BinaryOp.SHIFT_LEFT -> a shl places
                BinaryOp.SHIFT_RIGHT -> a shr places
                BinaryOp.SHIFT_RIGHT_UNSIGNED -> a ushr places
                else -> fail(at, "`${op.symbol}` is not bitwise.")
            }
            Value.Num(unsigned32(result))
        }

        left is Value.BigInt && right is Value.BigInt -> Value.BigInt(
            when (op) {
                BinaryOp.BIT_AND -> left.value.and(right.value)
                BinaryOp.BIT_OR -> left.value.or(right.value)
                BinaryOp.BIT_XOR -> left.value.xor(right.value)
                BinaryOp.SHIFT_LEFT -> left.value.shiftLeft(shiftCount(right.value, op, at))
                BinaryOp.SHIFT_RIGHT -> left.value.shiftRight(shiftCount(right.value, op, at))
                BinaryOp.SHIFT_RIGHT_UNSIGNED -> fail(
                    at,
                    "`>>>` has no meaning for a bigint: an unsigned shift needs a fixed width " +
                        "to shift zeros into, and a bigint has none. Use `>>`.",
                )
                else -> fail(at, "`${op.symbol}` is not bitwise.")
            },
        )

        left is Value.Num && right is Value.BigInt || left is Value.BigInt && right is Value.Num ->
            fail(
                at,
                "`${op.symbol}` cannot mix a number and a bigint. Coerce first — unary `+` " +
                    "turns a bigint into a number, at the precision cost that implies.",
            )

        else -> fail(
            at,
            "`${op.symbol}` needs two numbers or two bigints, got ${left.typeName} and " +
                "${right.typeName}.",
        )
    }

    private fun shiftCount(places: BigInteger, op: BinaryOp, at: Int): Int = when {
        places.signum() < 0 -> fail(
            at,
            "`${op.symbol}` needs a shift count of zero or more; shifting by $places would " +
                "silently reverse the direction the expression reads.",
        )
        // 2^30 bits is 128 MiB of number. Refusing here beats an OutOfMemoryError with no cause.
        places.bitLength() > 30 -> fail(at, "Shift count $places is impossibly large.")
        else -> places.toInt()
    }

    // ----------------------------------------------------------- comparison

    /**
     * Equality, and the one place the language's `=` deliberately parts company with Kotlin's
     * `==`: `NaN` equals nothing, including itself, because [ordering] has no answer for it.
     *
     * Arrays and dictionaries compare structurally, element by element, using these same rules
     * rather than `List.equals` — otherwise `[0/0] = [0/0]` would be true while `0/0 = 0/0` is
     * false, and a container would be a place where the language's own semantics stop applying.
     */
    private fun valuesEqual(left: Value, right: Value): Boolean = when {
        left is Value.ArrayV && right is Value.ArrayV ->
            left.items.size == right.items.size &&
                left.items.indices.all { valuesEqual(left.items[it], right.items[it]) }

        left is Value.DictV && right is Value.DictV ->
            left.entries.keys == right.entries.keys &&
                left.entries.all { (key, value) -> valuesEqual(value, right.entries.getValue(key)) }

        else -> compareOrNull(left, right) == 0
    }

    /**
     * `< <= > >=`. An unordered pair is `Num(0.0)`, never a failure — comparing a number with
     * text is the donor's stated behaviour and it is the right one for a flow: a `Decision`
     * block whose two operands turned out to be different types should take the NO branch, not
     * kill the fiber.
     *
     * `!=` is the negation of `=` rather than a third answer, so exactly one of `a = b` and
     * `a != b` holds for every pair. A language where both are false for a number and a text has
     * no way left to ask whether two things differ.
     */
    private fun ordering(op: BinaryOp, left: Value, right: Value): Value {
        val order = compareOrNull(left, right) ?: return Value.FALSE
        return Value.truth(
            when (op) {
                BinaryOp.LESS -> order < 0
                BinaryOp.LESS_EQUAL -> order <= 0
                BinaryOp.GREATER -> order > 0
                else -> order >= 0
            },
        )
    }

    /**
     * Total order where one exists, `null` where none does.
     *
     * Null orders below every non-null value — an absent reading sorts first rather than
     * throwing, which is what makes `last_seen < now` safe on a variable no block has set yet.
     * Number against bigint is compared exactly through [BigDecimal] rather than by widening the
     * bigint to a double, because widening is what this language refuses to do everywhere else
     * and doing it here would make `9007199254740993n > 9007199254740992` answer wrongly.
     * Text is compared by UTF-16 code unit — case-sensitive, locale-independent, so a flow
     * cannot change its behaviour when the phone's language changes.
     */
    private fun compareOrNull(left: Value, right: Value): Int? = when {
        left is Value.Null && right is Value.Null -> 0
        left is Value.Null -> -1
        right is Value.Null -> 1

        left is Value.Num && right is Value.Num -> compareDoubles(left.value, right.value)
        left is Value.BigInt && right is Value.BigInt -> left.value.compareTo(right.value).sign
        left is Value.Num && right is Value.BigInt -> compareDoubleToBigInt(left.value, right.value)
        left is Value.BigInt && right is Value.Num ->
            compareDoubleToBigInt(right.value, left.value)?.let { -it }

        left is Value.Text && right is Value.Text -> left.value.compareTo(right.value).sign

        else -> null
    }

    /** IEEE ordering: `NaN` is unordered against everything, and `-0.0` equals `0.0`. */
    private fun compareDoubles(a: Double, b: Double): Int? = when {
        a.isNaN() || b.isNaN() -> null
        a < b -> -1
        a > b -> 1
        else -> 0
    }

    private fun compareDoubleToBigInt(a: Double, b: BigInteger): Int? = when {
        a.isNaN() -> null
        a == Double.POSITIVE_INFINITY -> 1
        a == Double.NEGATIVE_INFINITY -> -1
        else -> BigDecimal(a).compareTo(BigDecimal(b)).sign
    }

    // -------------------------------------------------------------- logical

    private fun logical(expr: Expr.Logical): Value = when (expr.op) {
        LogicalOp.AND ->
            if (!eval(expr.left).isTrue) Value.FALSE else Value.truth(eval(expr.right).isTrue)

        LogicalOp.OR ->
            if (eval(expr.left).isTrue) Value.TRUE else Value.truth(eval(expr.right).isTrue)
    }

    // --------------------------------------------------------------- access

    /**
     * Reading past the end of an array, or a key a dictionary does not have, is [Value.Null].
     *
     * Indexing [Value.Null] is [Value.Null] too, which is what lets `event.location.name` be
     * written once and still evaluate when no event has arrived yet. The failure cases are the
     * ones that indicate a real mistake: indexing a number, or subscripting a dictionary with
     * something that is not text.
     */
    private fun index(target: Value, key: Value, at: Int): Value = when (target) {
        Value.Null -> Value.Null

        is Value.ArrayV -> {
            val position = arrayIndex(key, at)
            if (position in target.items.indices) target.items[position] else Value.Null
        }

        is Value.DictV -> when (key) {
            is Value.Text -> target.entries[key.value] ?: Value.Null
            else -> fail(at, "A dictionary key must be text, got ${key.typeName}.")
        }

        else -> fail(at, "Cannot index ${target.typeName} with `[ ]`.")
    }

    private fun arrayIndex(key: Value, at: Int): Int = when (key) {
        is Value.Num ->
            if (key.value.isFinite() && truncate(key.value) == key.value) {
                key.value.toInt()
            } else {
                fail(at, "An array index must be a whole number, got ${key.asText()}.")
            }

        is Value.BigInt ->
            if (key.value.bitLength() < 31) {
                key.value.toInt()
            } else {
                fail(at, "Array index ${key.value} is outside any array.")
            }

        else -> fail(at, "An array index must be a number, got ${key.typeName}.")
    }

    private fun member(target: Value, name: String, at: Int): Value = when (target) {
        Value.Null -> Value.Null
        is Value.DictV -> target.entries[name] ?: Value.Null
        else -> fail(at, "Cannot read `.$name` from ${target.typeName}.")
    }

    private fun fail(position: Int, detail: String): Nothing =
        throw ExprException(ExprFailure(ExprFailure.Stage.EVAL, detail, position, source))

    companion object {
        /**
         * Parse without evaluating.
         *
         * A block re-reads its arguments on every visit and a fiber may loop thousands of times,
         * so the runtime compiles each argument once and keeps the tree. Parsing per visit would
         * put the lexer in the hot path of every flow.
         */
        fun compile(source: String): Expr = Parser(source).parse()

        /** Throws [ExprException]. Prefer [tryEvaluate] anywhere a fiber's outcome depends on it. */
        fun evaluate(source: String, scope: VariableScope = VariableScope.EMPTY): Value =
            Evaluator(source, scope).eval(compile(source))

        /**
         * The form the flow runtime uses: a failed argument is an ordinary fiber outcome with a
         * user-visible cause, not an exception the dispatch loop has to guard against.
         */
        fun tryEvaluate(
            source: String,
            scope: VariableScope = VariableScope.EMPTY,
        ): ExprOutcome = try {
            ExprOutcome.Ok(evaluate(source, scope))
        } catch (failure: ExprException) {
            ExprOutcome.Failed(failure.failure)
        }

        /** As [tryEvaluate], for an [Expr] already produced by [compile]. */
        fun tryEvaluate(
            expr: Expr,
            source: String,
            scope: VariableScope = VariableScope.EMPTY,
        ): ExprOutcome = try {
            ExprOutcome.Ok(Evaluator(source, scope).eval(expr))
        } catch (failure: ExprException) {
            ExprOutcome.Failed(failure.failure)
        }

        private const val TWO_TO_32 = 4294967296.0

        /** JavaScript's ToInt32: truncate, wrap modulo 2^32, read as two's complement. */
        private fun toInt32(value: Double): Int {
            if (!value.isFinite()) return 0
            val wrapped = truncate(value) % TWO_TO_32
            val unsigned = if (wrapped < 0.0) wrapped + TWO_TO_32 else wrapped
            return unsigned.toLong().toInt()
        }

        private fun unsigned32(value: Int): Double = (value.toLong() and 0xFFFFFFFFL).toDouble()
    }
}
