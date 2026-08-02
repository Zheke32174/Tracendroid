package dev.pleiades.masamune.flow.expr

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.truncate

/**
 * The value domain of the flow plane's expression language, ported from Automate
 * (see `docs/donors/RE-automate.md`, "The expression language").
 *
 * Sealed, never `Any?`. Every rule the donor contract states — bigint and number never mix in
 * arithmetic, a number compared with text is `0` rather than an error, null orders below
 * everything — is a claim about a *closed* set of cases. With `Any?` those claims live only in
 * the evaluator's `if` ladder, where the compiler cannot tell anyone a case was forgotten; with
 * a sealed hierarchy an exhaustive `when` is checked at build time and a seventh value kind
 * cannot be added without every operator being revisited.
 *
 * There is deliberately no boolean case. Comparison yields [Num] `1.0` or `0.0`, so a boolean
 * value would be a second truth domain nothing in the language produces.
 */
sealed class Value {

    /**
     * A double, always.
     *
     * Automate has exactly one ordinary numeric type and it is floating point, which is why
     * `10 / 0` is `Infinity` and `12 % 0` is `NaN`: those are plain IEEE-754 outcomes, not
     * special cases the language bolts on. An implementation that reaches for `Long` here has
     * to *invent* both, and will invent them as exceptions instead.
     *
     * Kotlin `==` on this class is structural, so `Num(NaN) == Num(NaN)` is `true` and tests can
     * assert on NaN directly. The *language's* `=` operator is IEEE, so `(0/0) = (0/0)`
     * evaluates to `Num(0.0)`. The two deliberately disagree; [Evaluator] never routes the `=`
     * operator through `equals`.
     */
    data class Num(val value: Double) : Value()

    /**
     * Arbitrary-precision integer — the `n` literal suffix.
     *
     * A separate case rather than a widened [Num] because the contract forbids implicit
     * promotion in *either* direction. A bigint that quietly became a double would start losing
     * digits past 2^53 without saying so, and silently losing digits is the exact failure a
     * bigint exists to prevent. The escape hatch is explicit: unary `+` coerces a bigint to a
     * number, at the cost the user asked for.
     */
    data class BigInt(val value: BigInteger) : Value()

    data class Text(val value: String) : Value()

    /** Ordered, heterogeneous. Indexed with `[i]`; an out-of-range index reads as [Null]. */
    data class ArrayV(val items: List<Value>) : Value()

    /** Keyed by text. Read with `d["k"]` or `d.k`; an absent key reads as [Null]. */
    data class DictV(val entries: Map<String, Value>) : Value()

    /**
     * Absence — an unset variable, a blank optional argument, a missing dictionary key.
     *
     * Not zero and not empty text: it is a distinct case precisely so that "the sensor never
     * reported" is distinguishable from "the sensor reported 0". It orders below every non-null
     * value, which gives `null < anything` a defined answer instead of an error.
     */
    data object Null : Value()

    /** How this kind of value is named in a failure message: "cannot negate text". */
    val typeName: String
        get() = when (this) {
            is Num -> "number"
            is BigInt -> "bigint"
            is Text -> "text"
            is ArrayV -> "array"
            is DictV -> "dictionary"
            Null -> "null"
        }

    /**
     * Truthiness — what `!`, `&&`, `||` and a `Decision` block's YES/NO branch key on.
     *
     * `NaN` is false, and that is the load-bearing case rather than a rounding of JavaScript's
     * rules: `12 % 0` is `NaN`, meaning "there is no answer", and a fiber must not take the YES
     * branch on the strength of a non-answer. Empty text and empty containers are false for the
     * same reason a blank optional argument is: there is nothing there.
     */
    val isTrue: Boolean
        get() = when (this) {
            is Num -> !value.isNaN() && value != 0.0
            is BigInt -> value.signum() != 0
            is Text -> value.isNotEmpty()
            is ArrayV -> items.isNotEmpty()
            is DictV -> entries.isNotEmpty()
            Null -> false
        }

    /**
     * The text form produced by `++` and by anything showing a value to the user.
     *
     * Whole numbers print without a fractional part, because `++` exists to build user-facing
     * text and `"Retry " ++ 1` reading "Retry 1.0" is a defect report. [Null] renders empty:
     * concatenating an unset optional argument should drop out of the sentence, not spell the
     * word "null" into a notification.
     *
     * Arrays and dictionaries render bracketed and are debug-grade — readable in a log, not a
     * serialization format. Nothing parses this back.
     */
    fun asText(): String = when (this) {
        is Num -> formatNumber(value)
        is BigInt -> value.toString()
        is Text -> value
        is ArrayV -> items.joinToString(", ", "[", "]") { it.render() }
        is DictV -> entries.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value.render()}" }
        Null -> ""
    }

    /** [asText], except that nested text keeps its quotes so `["", ""]` is not `[, ]`. */
    private fun render(): String = when (this) {
        is Text -> "\"$value\""
        Null -> "null"
        else -> asText()
    }

    companion object {
        val TRUE = Num(1.0)
        val FALSE = Num(0.0)

        /**
         * The only bridge from a Kotlin `Boolean` into the language.
         *
         * Comparison and logic yield numbers here — `1 < 2` is `Num(1.0)` — so a Boolean must
         * never escape the evaluator's internals. Funnelling every such result through one
         * function is what keeps that true as operators are added.
         */
        fun truth(condition: Boolean): Num = if (condition) TRUE else FALSE

        private fun formatNumber(value: Double): String = when {
            value.isNaN() -> "NaN"
            value == Double.POSITIVE_INFINITY -> "Infinity"
            value == Double.NEGATIVE_INFINITY -> "-Infinity"
            value == 0.0 -> "0"
            // Past 2^53 a double no longer names a unique integer, so stop pretending it does.
            truncate(value) == value && abs(value) < 9.007199254740992E15 -> value.toLong().toString()
            else -> value.toString()
        }
    }
}

/**
 * Why an expression produced no value, and where.
 *
 * A failure is *data*, not a stack trace. The flow runtime turns one into a fiber error whose
 * cause is shown beside the block that failed, so [message] has to name the operand types and
 * point at the offset in the text the user actually typed — "type error" is useless on a phone
 * screen with no debugger attached. That is also why the source line and a caret are part of
 * the message rather than something a caller is trusted to assemble.
 */
class ExprFailure(
    val stage: Stage,
    val detail: String,
    val position: Int,
    val source: String,
) {
    /**
     * Which pass rejected the expression. [LEX] and [PARSE] are both syntax as far as the user
     * is concerned — the distinction is kept because it tells *us* which file to open.
     */
    enum class Stage(val label: String) {
        LEX("Syntax"),
        PARSE("Syntax"),
        EVAL("Evaluation"),
    }

    val message: String
        get() {
            val caret = " ".repeat(position.coerceIn(0, source.length))
            return "${stage.label} error at position $position: $detail\n  $source\n  $caret^"
        }

    override fun toString(): String = message
}

/**
 * The throwing form, used inside the lexer, parser and evaluator so a failure deep in a
 * recursive descent does not have to be threaded back by hand through every operand.
 *
 * It never escapes the API: [Evaluator.tryEvaluate] converts it to [ExprOutcome.Failed]. Callers
 * that would rather catch it may — that is why it carries [failure] rather than only a string.
 */
class ExprException(val failure: ExprFailure) : RuntimeException(failure.message)

/**
 * The result of evaluating an expression: a value, or a named failure.
 *
 * The flow runtime uses this rather than the exception because a failed argument is an ordinary
 * fiber outcome (it stops the fiber with a visible cause), not an exceptional condition of the
 * runtime itself, and modelling it as data keeps the dispatch loop free of `try`/`catch`.
 */
sealed class ExprOutcome {
    data class Ok(val value: Value) : ExprOutcome()
    data class Failed(val failure: ExprFailure) : ExprOutcome()

    val valueOrNull: Value? get() = (this as? Ok)?.value
    val failureOrNull: ExprFailure? get() = (this as? Failed)?.failure
}
