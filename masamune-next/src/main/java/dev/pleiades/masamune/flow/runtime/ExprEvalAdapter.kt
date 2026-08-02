package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Evaluator
import dev.pleiades.masamune.flow.expr.ExprException
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.expr.VariableScope

/**
 * The one adapter that binds the runtime's [ExprEval] seam to the real expression [Evaluator].
 *
 * [ArgResolver] depends on "evaluate this text in this scope, or tell me why it failed" and
 * nothing wider; this class is the whole of that dependency. It exists as a named class rather
 * than an inline lambda for two reasons: the wiring (ViewModel, tests) references it by name, and
 * the failure-flattening below is a contract that deserves to be stated once and reused, not
 * re-derived at every call site.
 *
 * The evaluator throws [ExprException] on a bad expression; the seam promises a [Result.failure]
 * carrying a human-readable message. The bridge is [ExprFailure.detail] — the bare cause ("`+`
 * needs a number…"), not the multi-line caret rendering. [ArgResolver] already prefixes the node
 * id and argument key onto whatever message it gets, so the detail is exactly the part that is
 * still missing at that point; handing it the full caret diagram would double up the framing and
 * bury the reason.
 */
class ExprEvalAdapter : ExprEval {
    override fun eval(expression: String, scope: Map<String, Value>): Result<Value> =
        try {
            Result.success(Evaluator.evaluate(expression, VariableScope.of(scope)))
        } catch (failure: ExprException) {
            // Carry the bare cause. ArgResolver adds the "node X argument 'k':" frame itself, so a
            // caret diagram here would be framed twice and read as noise on a phone screen.
            Result.failure(ExprEvalFailure(failure.failure.detail))
        }
}

/**
 * The exception an [ExprEvalAdapter] failure carries. Its [message] is the expression layer's
 * [dev.pleiades.masamune.flow.expr.ExprFailure.detail]; it is never thrown past [ArgResolver],
 * which reads the message and folds it into an [ExprArgError]. A named type (rather than a bare
 * `Exception`) keeps a `Result.failure` from this adapter distinguishable from any other in a test.
 */
class ExprEvalFailure(message: String) : Exception(message)
