package dev.pleiades.masamune.operator

import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.model.Requirement
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import dev.pleiades.masamune.operator.a11y.GlobalKey
import dev.pleiades.masamune.operator.a11y.ScreenActuator

/**
 * The operator's action vocabulary: runnable implementations of the Automate `Interface`-category
 * blocks the AI operator emits (`Inspect layout`, `Interact`, `Interact touch`, `Inspect text
 * edit`, `Key send`, `Key send characters`). See docs/AI-OPERATOR.md — "One action vocabulary,
 * shared with the manual flow plane".
 *
 * These are the *same* block ids the manual palette places, so a flow the operator walks can be
 * opened and edited by a user and vice versa. They carry `Requirement.Accessibility` in the
 * catalog, and honest gating happens on two independent layers so neither can be forgotten:
 *
 *  1. **Gate by omission** ([OperatorLoop] registers these only when a live actuator exists), so
 *     with the service off the scheduler finds no impl and reports the missing requirement by
 *     name — exactly the "honest gate by omission" the flow `BlockRegistry` documents.
 *  2. **Gate at run** (every impl re-checks [actuatorProvider] and fails with [A11Y_ABSENT] if the
 *     service dropped mid-run), so a service that disconnects between two blocks cannot produce a
 *     silent no-op.
 *
 * Every impl also routes its effect through [OperatorGate] as [dev.pleiades.masamune.core.
 * capability.Caller.AiAgent], which is where the `HaltController` stop and the per-caller grant
 * are enforced — the operator never touches the screen without passing that gate.
 *
 * The impls depend on the [ScreenActuator] seam, never on `AccessibilityService`, so this whole
 * file is unit-testable on the JVM against a fake actuator.
 */

/** The sentence shown whenever an operator action cannot reach a connected accessibility service. */
internal val A11Y_ABSENT: String =
    "The AI operator cannot act: the ${Requirement.Accessibility.label} is not enabled. " +
        "Turn on Masamune in Settings → Accessibility so the operator can see and touch the screen."

/** Base for the six action blocks: resolves the actuator and the gate once, in one place. */
internal abstract class OperatorActionBlock(
    private val actuatorProvider: () -> ScreenActuator?,
    private val gate: OperatorGate,
) : BlockImpl {

    /**
     * Run [body] with a live actuator and a passed capability gate, or return an honest failure.
     *
     * The order matters: the gate is checked *before* the actuator is used, so a halted system or
     * an ungranted AI caller is refused with the gate's own message even when the service is on.
     * Only once the gate allows do we require the actuator, failing with [A11Y_ABSENT] if the
     * service has gone. Nothing past this returns a fabricated success.
     */
    protected suspend fun gated(
        capability: Capability,
        what: String,
        body: suspend (ScreenActuator) -> Outcome,
    ): Outcome {
        val decision = gate.check(capability, what)
        if (decision !is dev.pleiades.masamune.core.capability.GateDecision.Allowed) {
            return Outcome.Fail((decision as dev.pleiades.masamune.core.capability.GateDecision.Denied).message)
        }
        val actuator = actuatorProvider() ?: return Outcome.Fail(A11Y_ABSENT)
        return body(actuator)
    }
}

/** A numeric argument as an `Int`, or null when absent/non-numeric — the coordinate readers. */
private fun Value?.asIntOrNull(): Int? = when (this) {
    is Value.Num -> value.takeIf { it.isFinite() }?.toInt()
    is Value.BigInt -> value.toInt()
    is Value.Text -> value.trim().toIntOrNull()
    else -> null
}

private fun Value?.asStringOrNull(): String? = when (this) {
    null, Value.Null -> null
    else -> asText().takeIf { it.isNotBlank() }
}

/**
 * `Inspect layout` — the observe primitive. Dumps the current window as the compact
 * [dev.pleiades.masamune.operator.a11y.SimplifiedNode] tree and binds its rendered text to the
 * block's `varResult` output. Reading the screen is [Capability.SYSTEM_READ]. YES when a non-empty
 * layout was read (Automate: "YES … a non-empty Node-set"), NO when the screen read empty.
 */
internal class InspectLayoutBlock(
    actuatorProvider: () -> ScreenActuator?,
    gate: OperatorGate,
) : OperatorActionBlock(actuatorProvider, gate) {
    override val specId = "inspect_layout"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome =
        gated(Capability.SYSTEM_READ, "inspect_layout: read the on-screen UI") { actuator ->
            val tree = actuator.dumpLayout()?.render().orEmpty()
            val writes = LinkedHashMap<String, Value>()
            node.outputs["varResult"]?.takeIf { it.isNotBlank() }?.let { writes[it] = Value.Text(tree) }
            Outcome.Proceed(if (tree.isNotBlank()) Port.YES else Port.NO, writes)
        }
}

/**
 * `Interact` — the node-oriented touch. With an `xpathExpression` it clicks the node whose text,
 * description or id matches (the framework's click action, robust to layout shift); with only
 * coordinates it taps or long-presses them. `Inspect` reads the tree into `varContent`. Touching
 * the screen is [Capability.SYSTEM_WRITE]; YES on success, NO when nothing matched or the gesture
 * was refused.
 */
internal class InteractBlock(
    actuatorProvider: () -> ScreenActuator?,
    gate: OperatorGate,
) : OperatorActionBlock(actuatorProvider, gate) {
    override val specId = "interact"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val action = args["action"].asStringOrNull()?.lowercase() ?: "click"
        val query = args["xpathExpression"].asStringOrNull()
        val x = args["argX"].asIntOrNull()
        val y = args["argY"].asIntOrNull()
        val capability = if (action == "inspect") Capability.SYSTEM_READ else Capability.SYSTEM_WRITE
        return gated(capability, "interact: $action") { actuator ->
            val ok = when {
                action == "inspect" -> {
                    val tree = actuator.dumpLayout()?.render().orEmpty()
                    val writes = LinkedHashMap<String, Value>()
                    node.outputs["varContent"]?.takeIf { it.isNotBlank() }?.let { writes[it] = Value.Text(tree) }
                    return@gated Outcome.Proceed(if (tree.isNotBlank()) Port.YES else Port.NO, writes)
                }
                query != null -> actuator.clickNodeMatching(query)
                x != null && y != null && action == "long click" -> actuator.longPress(x, y)
                x != null && y != null -> actuator.tap(x, y)
                else -> return@gated Outcome.Fail(
                    "interact needs either an xpathExpression to match a node or argX/argY coordinates.",
                )
            }
            Outcome.Proceed(if (ok) Port.YES else Port.NO)
        }
    }
}

/**
 * `Interact touch` — the coordinate gesture. `Click`/`Long click` at (x0,y0), `Swipe` from
 * (x0,y0) to (x1,y1). NO when the gesture failed to dispatch or was cancelled, which is exactly
 * the donor's contract for this block.
 */
internal class InteractTouchBlock(
    actuatorProvider: () -> ScreenActuator?,
    gate: OperatorGate,
) : OperatorActionBlock(actuatorProvider, gate) {
    override val specId = "interact_touch"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val gesture = args["gesture"].asStringOrNull()?.lowercase() ?: "click"
        val x0 = args["x0"].asIntOrNull()
        val y0 = args["y0"].asIntOrNull()
        val x1 = args["x1"].asIntOrNull()
        val y1 = args["y1"].asIntOrNull()
        val duration = args["speed"].asIntOrNull()?.toLong() ?: DEFAULT_SWIPE_MS
        return gated(Capability.SYSTEM_WRITE, "interact_touch: $gesture") { actuator ->
            val ok = when (gesture) {
                "swipe" -> {
                    if (x0 == null || y0 == null || x1 == null || y1 == null) {
                        return@gated Outcome.Fail("interact_touch swipe needs x0,y0,x1,y1 coordinates.")
                    }
                    actuator.swipe(x0, y0, x1, y1, duration)
                }
                "long click", "long press" -> {
                    if (x0 == null || y0 == null) return@gated Outcome.Fail("interact_touch needs x0,y0 coordinates.")
                    actuator.longPress(x0, y0)
                }
                else -> {
                    if (x0 == null || y0 == null) return@gated Outcome.Fail("interact_touch needs x0,y0 coordinates.")
                    actuator.tap(x0, y0)
                }
            }
            Outcome.Proceed(if (ok) Port.YES else Port.NO)
        }
    }

    private companion object {
        const val DEFAULT_SWIPE_MS = 300L
    }
}

/**
 * `Inspect text edit` — read the currently input-focused editable field. Reading is
 * [Capability.SYSTEM_READ]. Binds `varNewText` and `varPackageName`; fails visibly when no
 * editable field holds focus rather than proceeding with an empty read a downstream block would
 * misread as "the field is blank".
 */
internal class InspectTextEditBlock(
    actuatorProvider: () -> ScreenActuator?,
    gate: OperatorGate,
) : OperatorActionBlock(actuatorProvider, gate) {
    override val specId = "inspect_text_edit"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome =
        gated(Capability.SYSTEM_READ, "inspect_text_edit: read the focused text field") { actuator ->
            val field = actuator.readFocusedField()
                ?: return@gated Outcome.Fail("No editable text field currently holds input focus.")
            val writes = LinkedHashMap<String, Value>()
            node.outputs["varNewText"]?.takeIf { it.isNotBlank() }?.let { writes[it] = Value.Text(field.text) }
            node.outputs["varPackageName"]?.takeIf { it.isNotBlank() }
                ?.let { writes[it] = Value.Text(field.packageName.orEmpty()) }
            Outcome.Proceed(Port.OK, writes)
        }
}

/**
 * `Key send` — a system global action (Back, Home, Recents, …). Sending a system key is
 * [Capability.SYSTEM_WRITE]. An unmapped keycode fails by name rather than silently taking NO,
 * because a mistyped key is an authoring error the operator should see, not swallow.
 */
internal class KeySendBlock(
    actuatorProvider: () -> ScreenActuator?,
    gate: OperatorGate,
) : OperatorActionBlock(actuatorProvider, gate) {
    override val specId = "key_send"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val raw = args["keyCode"].asStringOrNull()
            ?: return Outcome.Fail("key_send needs a keyCode (e.g. BACK, HOME, RECENTS).")
        val key = raw.trim().uppercase().removePrefix("KEYCODE_").let { name ->
            GlobalKey.entries.firstOrNull { it.name == name }
        } ?: return Outcome.Fail(
            "key_send: '$raw' is not a supported global key. Supported: " +
                GlobalKey.entries.joinToString(", ") { it.name } + ".",
        )
        return gated(Capability.SYSTEM_WRITE, "key_send: $key") { actuator ->
            Outcome.Proceed(if (actuator.globalKey(key)) Port.YES else Port.NO)
        }
    }
}

/**
 * `Key send characters` — type text into the focused field via the accessibility set-text route.
 * Typing is [Capability.SYSTEM_WRITE]. NO when no editable field holds focus, matching the donor's
 * "mechanism disconnected → NO" reading for the key family.
 */
internal class KeySendCharactersBlock(
    actuatorProvider: () -> ScreenActuator?,
    gate: OperatorGate,
) : OperatorActionBlock(actuatorProvider, gate) {
    override val specId = "key_send_characters"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val characters = args["characters"].asStringOrNull().orEmpty()
        return gated(Capability.SYSTEM_WRITE, "key_send_characters: type text") { actuator ->
            Outcome.Proceed(if (actuator.setFocusedText(characters)) Port.YES else Port.NO)
        }
    }
}
