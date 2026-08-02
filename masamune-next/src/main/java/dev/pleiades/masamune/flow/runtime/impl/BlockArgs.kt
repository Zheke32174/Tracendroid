package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode

/**
 * Small readers shared by the block impls, kept in one place so "how a block reads a numeric
 * argument" or "where a mutating block finds its target variable" has a single definition rather
 * than a dozen subtly different ones.
 */

/**
 * A numeric argument as a [Double], or null when it is absent or not numeric.
 *
 * A bigint is read through here too, at the precision cost that implies, because these arguments —
 * an array index, a retry limit, a delay in milliseconds — are all small counts where a double is
 * exact and the alternative (rejecting a bigint the user reasonably typed) helps no one.
 */
internal fun Value?.asNumOrNull(): Double? = when (this) {
    is Value.Num -> value
    is Value.BigInt -> value.toDouble()
    else -> null
}

/**
 * The variable a mutating block reads-and-writes (`Variable set`, the `Array …`/`Dictionary …`
 * family, the atomics), bound under the output key `variable`.
 *
 * The name lives in [FlowNode.outputs] because it is a bare variable name, which is the output
 * channel's whole job — inputs are expressions, outputs are names (donor organ 3). A block whose
 * target is unbound does not silently no-op onto some default: it returns null here and the caller
 * fails visibly, because a mutation with no destination is a mistake the user must see, not one
 * the runtime should paper over.
 */
internal const val TARGET_VAR = "variable"

internal fun FlowNode.targetVariable(): String? = outputs[TARGET_VAR]?.takeIf { it.isNotBlank() }

/**
 * A `flag(...)` argument as a real boolean.
 *
 * The trap this closes: a flag in *constant* mode arrives as `Value.Text("true")` or
 * `Value.Text("false")`, and `Value.isTrue` on a non-empty string is `true` — so the naïve
 * `args["append"].isTrue` reads a checkbox the user cleared as *set*. So a constant flag is parsed
 * by its text: the words the editor writes ("true"/"false", plus the obvious synonyms) map to the
 * boolean they name; only in *expression* mode (an arbitrary computed value) do we fall back to the
 * language's own truthiness. Absent means the block's documented default, never a silent `false`
 * that could hide a mis-bound input.
 */
internal fun Value?.asFlag(default: Boolean = false): Boolean = when (this) {
    null, Value.Null -> default
    is Value.Text -> when (value.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off", "" -> false
        else -> value.isNotBlank()
    }
    is Value.Num -> !value.isNaN() && value != 0.0
    else -> isTrue
}

/** A text argument, or null when it is absent — distinct from an empty string a user typed. */
internal fun Value?.asTextOrNull(): String? = when (this) {
    null, Value.Null -> null
    else -> asText()
}
