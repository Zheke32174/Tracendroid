package dev.pleiades.masamune.flow.expr

/**
 * Unary operators.
 *
 * [TO_NUMBER] is the reason the language can forbid implicit bigint/number mixing without
 * becoming unusable: `+n` is the explicit, visible coercion the contract demands in place of a
 * silent promotion. Deleting it would leave a user holding a bigint and a number with no legal
 * way to add them.
 */
enum class UnaryOp(val symbol: String) {
    NEGATE("-"),
    TO_NUMBER("+"),
    NOT("!"),
    BIT_NOT("~"),
}

/** Binary operators. Logical `&&` / `||` are not here — they short-circuit, see [LogicalOp]. */
enum class BinaryOp(val symbol: String) {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),

    /** Integer division, a distinct operator from [DIVIDE] — `7 // 2` is `3`, `7 / 2` is `3.5`. */
    INT_DIVIDE("//"),
    REMAINDER("%"),

    /** Text concatenation. `+` never joins text; this is the only operator that does. */
    CONCAT("++"),

    BIT_AND("&"),
    BIT_OR("|"),
    BIT_XOR("^"),
    SHIFT_LEFT("<<"),
    SHIFT_RIGHT(">>"),
    SHIFT_RIGHT_UNSIGNED(">>>"),

    /** Equality. One `=`, not two — there is no assignment in the language to disambiguate from. */
    EQUAL("="),
    NOT_EQUAL("!="),
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),
}

enum class LogicalOp(val symbol: String) {
    AND("&&"),
    OR("||"),
}

/**
 * The parsed form of an expression: a tree, evaluated by [Evaluator] against a variable scope.
 *
 * Every node carries [position], the offset of the token that produced it, and that is the whole
 * reason the tree exists as data rather than as a fold performed during parsing. A flow's
 * arguments are typed by a person into a text field on a phone; when `duration * "5m"` fails,
 * the fiber error has to point at the `*`, and only a node that remembers where it came from can
 * do that.
 *
 * The tree is also reusable. A block re-evaluates its arguments on every visit, so the runtime
 * parses once at first execution and evaluates many times against different fibers' frames.
 */
sealed class Expr {
    abstract val position: Int

    /** A literal, already converted to its [Value] by the lexer. */
    data class Literal(val value: Value, override val position: Int) : Expr()

    /**
     * A variable read.
     *
     * An unbound name is [Value.Null], not a failure — the donor's blocks leave almost every
     * argument optional and unset variables are how "not specified" is spelled. The cost is that
     * a misspelled name reads as absent rather than as an error, which is a real cost and the
     * reason the block editor offers a variable picker instead of asking anyone to type names.
     */
    data class Variable(val name: String, override val position: Int) : Expr()

    data class ArrayLiteral(val items: List<Expr>, override val position: Int) : Expr()

    /** Keys are fixed at parse time; only values are expressions. */
    data class DictLiteral(val entries: List<Entry>, override val position: Int) : Expr() {
        data class Entry(val key: String, val value: Expr)
    }

    data class Unary(val op: UnaryOp, val operand: Expr, override val position: Int) : Expr()

    data class Binary(
        val op: BinaryOp,
        val left: Expr,
        val right: Expr,
        override val position: Int,
    ) : Expr()

    /**
     * `&&` / `||`. Separate from [Binary] because both operands of a [Binary] are evaluated
     * before the operator runs, and these two must not be: `count != 0 && total // count > 3`
     * relies on the right side never running when the left is false.
     */
    data class Logical(
        val op: LogicalOp,
        val left: Expr,
        val right: Expr,
        override val position: Int,
    ) : Expr()

    /** `target[index]` — array element or dictionary entry. */
    data class Index(val target: Expr, val index: Expr, override val position: Int) : Expr()

    /** `target.name` — the same dictionary read as `target["name"]`, spelled for the eye. */
    data class Member(val target: Expr, val name: String, override val position: Int) : Expr()
}
