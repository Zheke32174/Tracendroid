package dev.pleiades.masamune.operator

import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.Connection
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.BlockRegistry
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import dev.pleiades.masamune.operator.a11y.ScreenActuator
import kotlinx.coroutines.CoroutineScope

/**
 * The observe→decide→act loop, expressed as a flow the operator runs — the realisation of the one
 * decision in docs/AI-OPERATOR.md: **the operator is a fiber.** It does not reach into the
 * accessibility service; it walks a three-block [FlowGraph] on the very same [Scheduler] the
 * manual palette uses, so halt, trace, and persist-resume are inherited rather than reimplemented,
 * and every action the model takes is a visible block on a canvas a human can watch and stop.
 *
 * The graph is deliberately tiny — three nodes and a back-edge:
 *
 * ```
 *   observe (inspect_layout) ──YES/NO──▶ decide (operator_decide) ──YES──▶ act (operator_act)
 *        ▲                                        │                              │
 *        └────────────────────────────────────── OK ◀──────────────────────────┘
 *                                                 │
 *                                                 NO (goal reached / step limit) ──▶ (fiber stops)
 * ```
 *
 * - **observe** is the *real* `Inspect layout` Interface block — the same one the palette places.
 *   It binds the compact screen tree to a fiber variable. Using the genuine block here is what
 *   makes "a flow the user built by hand can be handed to the operator, and vice versa" true
 *   rather than aspirational.
 * - **decide** hands the goal + the latest observation to an [OperatorDecider] (the LLM, via
 *   `AiService`) and gets back the next action as a choice of Interface block plus arguments. The
 *   model's reasoning is therefore a node in the graph, not a hidden step.
 * - **act** places and runs the chosen Interface block. It looks the impl up in the operator
 *   registry and invokes it — so the action the operator takes is literally one of the
 *   Interface-category blocks, gated and executed by the same code a manual `Interact` would use.
 *
 * Because a fiber is serializable at every block boundary, an operator task survives process death
 * mid-run and resumes at its last block; because [Scheduler] consults `isHalted` between blocks,
 * the user's stop lands between any two actions. Neither property is written here — both fall out
 * of running on the shared runtime.
 */
object OperatorLoop {

    /** The flow id the operator's fibers persist under. */
    const val FLOW_ID = "masamune-operator"

    // Loop state lives in `$op.`-prefixed variables: the leading `$` marks them as runtime control
    // state (the monitor hides them from the user's own variable frame, exactly as it does the
    // call stack and loop cursors). The operator's human-readable progress is surfaced separately
    // through the [OperatorTrace] transcript, not by exposing these.
    const val VAR_GOAL = "\$op.goal"
    const val VAR_OBSERVATION = "\$op.observation"
    const val VAR_CALL = "\$op.call"
    const val VAR_REASON = "\$op.reason"
    const val VAR_STEP = "\$op.step"

    /** A hard ceiling on iterations so a model that never declares "done" still terminates the fiber. */
    const val DEFAULT_MAX_STEPS = 40

    /** The two operator-only specs, resolved alongside the frozen catalog (never added to it). */
    private val operatorSpecs: Map<String, BlockSpec> = mapOf(
        "operator_decide" to BlockSpec(
            id = "operator_decide",
            name = "Operator decide",
            category = BlockCategory.FLOW,
            shape = BlockShape.DECISION,
            summary = "Hands the goal and the latest screen observation to the LLM and routes YES " +
                "to the chosen action, NO when the goal is reached or the step limit is hit.",
        ),
        "operator_act" to BlockSpec(
            id = "operator_act",
            name = "Operator act",
            category = BlockCategory.FLOW,
            shape = BlockShape.ACTION,
            summary = "Places and runs the Interface block the decide step chose, then loops back " +
                "to observe.",
        ),
    )

    /**
     * The scheduler's spec resolver for an operator run: the two operator specs, falling back to
     * the frozen [BlockCatalog] so the observe node's real `inspect_layout` spec resolves. The
     * catalog itself is never mutated — this is a lookup that composes over it.
     */
    val specLookup: (String) -> BlockSpec? = { operatorSpecs[it] ?: BlockCatalog[it] }

    /** Build the operator's flow graph. The observe node binds its layout dump to [VAR_OBSERVATION]. */
    fun buildGraph(): FlowGraph = FlowGraph(
        id = FLOW_ID,
        name = "AI operator",
        nodes = listOf(
            FlowNode(
                id = NODE_OBSERVE,
                specId = "inspect_layout",
                x = 96f,
                y = 96f,
                outputs = mapOf("varResult" to VAR_OBSERVATION),
            ),
            FlowNode(id = NODE_DECIDE, specId = "operator_decide", x = 96f, y = 232f),
            FlowNode(id = NODE_ACT, specId = "operator_act", x = 96f, y = 368f),
        ),
        connections = listOf(
            // A layout read that is empty still goes to decide — the model can choose to press
            // Back or wait — so both of inspect_layout's ports feed the decide step.
            Connection(NODE_OBSERVE, Port.YES, NODE_DECIDE),
            Connection(NODE_OBSERVE, Port.NO, NODE_DECIDE),
            Connection(NODE_DECIDE, Port.YES, NODE_ACT),
            // decide's NO is intentionally unconnected: reaching it stops the top-level fiber
            // normally, which is how the loop ends when the goal is met.
            Connection(NODE_ACT, Port.OK, NODE_OBSERVE),
        ),
    )

    /**
     * Assemble the scheduler's impl lookup for one run, composing three layers:
     *  1. the operator's own decide/act blocks;
     *  2. the six Interface action blocks — **registered only when a live actuator exists**, so
     *     that with the service off the scheduler finds no `inspect_layout` impl and reports the
     *     missing accessibility requirement by name (honest gate by omission);
     *  3. the existing flow [BlockRegistry], so any ordinary flow block the graph might use still
     *     resolves.
     */
    fun buildImplLookup(
        graph: FlowGraph,
        scope: CoroutineScope,
        actuatorProvider: () -> ScreenActuator?,
        gate: OperatorGate,
        decider: OperatorDecider,
        trace: OperatorTrace = OperatorTrace {},
        maxSteps: Int = DEFAULT_MAX_STEPS,
    ): (String) -> BlockImpl? {
        val interfaceImpls: Map<String, BlockImpl> =
            if (actuatorProvider() != null) interfaceBlocks(actuatorProvider, gate) else emptyMap()

        val operatorImpls: Map<String, BlockImpl> = mapOf(
            "operator_decide" to OperatorDecideBlock(decider, trace, maxSteps),
            "operator_act" to OperatorActBlock({ interfaceImpls[it] }, trace),
        )

        val flowRegistry = BlockRegistry(graph, scope)
        return { id -> operatorImpls[id] ?: interfaceImpls[id] ?: flowRegistry.lookup(id) }
    }

    /** The six Interface action blocks, sharing one actuator provider and one capability gate. */
    fun interfaceBlocks(
        actuatorProvider: () -> ScreenActuator?,
        gate: OperatorGate,
    ): Map<String, BlockImpl> = listOf(
        InspectLayoutBlock(actuatorProvider, gate),
        InteractBlock(actuatorProvider, gate),
        InteractTouchBlock(actuatorProvider, gate),
        InspectTextEditBlock(actuatorProvider, gate),
        KeySendBlock(actuatorProvider, gate),
        KeySendCharactersBlock(actuatorProvider, gate),
    ).associateBy { it.specId }

    /** Friendly names for the monitor: the two operator nodes, else the catalog's own block name. */
    fun blockName(specId: String): String? = operatorSpecs[specId]?.name ?: BlockCatalog[specId]?.name

    const val NODE_OBSERVE = "observe"
    const val NODE_DECIDE = "decide"
    const val NODE_ACT = "act"
}

/**
 * The decide seam — "hand the goal and what is on screen to the model, get the next move".
 *
 * A `fun interface` so the production path (an LLM over `AiService`, [AiOperatorDecider]) and the
 * test path (a scripted decider) are the same shape, and so the decide *block* holds no Android
 * or network types and stays unit-testable. An implementation that cannot produce a usable action
 * throws — it never fabricates one — and the decide block turns that throw into a visible fiber
 * failure rather than a phantom tap.
 */
fun interface OperatorDecider {
    suspend fun decide(goal: String, observation: String, step: Int): OperatorDecision
}

/** The model's move: run one more Interface action, or stop because the goal is met. */
sealed interface OperatorDecision {
    /** Run [call]; [reason] is the model's one-line justification, shown in the transcript. */
    data class Act(val call: InterfaceCall, val reason: String) : OperatorDecision

    /** The goal is reached (or cannot proceed); stop the loop. [reason] explains why. */
    data class Finish(val reason: String) : OperatorDecision
}

/**
 * A chosen Interface block and its arguments — the operator's "tool call" projected onto the flow
 * plane's own vocabulary. [blockId] is a catalog Interface id (`interact_touch`, `key_send`, …),
 * [args] the resolved [Value] inputs that block reads, [outputs] any result bindings, and [label]
 * a human phrase for the transcript.
 */
data class InterfaceCall(
    val blockId: String,
    val args: Map<String, Value>,
    val label: String,
    val outputs: Map<String, String> = emptyMap(),
) {
    /** Encode into a [Value.DictV] so a decided-but-not-yet-run action survives a persist/resume. */
    fun encode(): Value = Value.DictV(
        mapOf(
            "block" to Value.Text(blockId),
            "label" to Value.Text(label),
            "args" to Value.DictV(args),
            "outputs" to Value.DictV(outputs.mapValues { Value.Text(it.value) }),
        ),
    )

    companion object {
        fun decode(value: Value?): InterfaceCall? {
            val entries = (value as? Value.DictV)?.entries ?: return null
            val block = (entries["block"] as? Value.Text)?.value ?: return null
            val label = (entries["label"] as? Value.Text)?.value ?: block
            val args = (entries["args"] as? Value.DictV)?.entries ?: emptyMap()
            val outputs = (entries["outputs"] as? Value.DictV)?.entries
                ?.mapValues { (it.value as? Value.Text)?.value ?: "" }
                ?: emptyMap()
            return InterfaceCall(block, args, label, outputs)
        }
    }
}

/** Optional live transcript sink so the UI can show the operator's reasoning step by step. */
fun interface OperatorTrace {
    fun onStep(entry: String)
}

/**
 * `operator_decide` — the decide block. Reads the goal and latest observation from the fiber
 * frame, enforces the step ceiling, calls the [decider], and routes: YES with the encoded action
 * to `act`, or NO (which stops the top-level fiber) when the goal is met or the limit is reached.
 * A decider that throws becomes an [Outcome.Fail] — the fiber errors with the model's own message,
 * never a silent stop.
 */
internal class OperatorDecideBlock(
    private val decider: OperatorDecider,
    private val trace: OperatorTrace,
    private val maxSteps: Int,
) : BlockImpl {
    override val specId = "operator_decide"

    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val step = (fiber.readVariable(OperatorLoop.VAR_STEP) as? Value.Num)?.value?.toInt() ?: 0
        if (step >= maxSteps) {
            val reason = "Reached the operator step limit ($maxSteps) without the goal being declared done."
            trace.onStep("stopped: $reason")
            return Outcome.Proceed(Port.NO, mapOf(OperatorLoop.VAR_REASON to Value.Text(reason)))
        }
        val goal = fiber.readVariable(OperatorLoop.VAR_GOAL).asText()
        val observation = fiber.readVariable(OperatorLoop.VAR_OBSERVATION).asText()

        val decision = try {
            decider.decide(goal, observation, step)
        } catch (e: Exception) {
            val message = e.message ?: "${e.javaClass.simpleName} while deciding the next action."
            trace.onStep("decide failed: $message")
            return Outcome.Fail(message)
        }

        val nextStep = Value.Num((step + 1).toDouble())
        return when (decision) {
            is OperatorDecision.Finish -> {
                trace.onStep("done: ${decision.reason}")
                Outcome.Proceed(
                    Port.NO,
                    mapOf(OperatorLoop.VAR_REASON to Value.Text(decision.reason), OperatorLoop.VAR_STEP to nextStep),
                )
            }

            is OperatorDecision.Act -> {
                trace.onStep("step ${step + 1}: ${decision.call.label} — ${decision.reason}")
                Outcome.Proceed(
                    Port.YES,
                    mapOf(
                        OperatorLoop.VAR_CALL to decision.call.encode(),
                        OperatorLoop.VAR_REASON to Value.Text(decision.reason),
                        OperatorLoop.VAR_STEP to nextStep,
                    ),
                )
            }
        }
    }
}

/**
 * `operator_act` — places and runs the Interface block the decide step chose.
 *
 * It decodes the [InterfaceCall], looks the impl up in [interfaceLookup] (the operator registry's
 * Interface layer), synthesises a [FlowNode] carrying the chosen args and output bindings, and
 * invokes the block. This is the "the runtime places and runs it" half of the design: the action
 * the operator takes is a genuine Interface block impl, gated and executed by the same code path a
 * manual `Interact` uses. A failure propagates (the fiber errors visibly, and a `Failure catch`
 * upstream could route on it); any other outcome loops back to observe with the block's writes
 * preserved, so the next iteration sees the screen the action produced.
 */
internal class OperatorActBlock(
    private val interfaceLookup: (String) -> BlockImpl?,
    private val trace: OperatorTrace,
) : BlockImpl {
    override val specId = "operator_act"

    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val call = InterfaceCall.decode(fiber.readVariable(OperatorLoop.VAR_CALL))
            ?: return Outcome.Fail("operator_act reached with no decided action to run.")
        val impl = interfaceLookup(call.blockId)
            ?: return Outcome.Fail(
                "operator_act: no runnable '${call.blockId}' block — the accessibility service is not enabled.",
            )
        val syntheticNode = node.copy(specId = call.blockId, outputs = call.outputs)
        return when (val outcome = impl.run(fiber, syntheticNode, call.args)) {
            is Outcome.Fail -> {
                trace.onStep("act failed: ${outcome.message}")
                outcome
            }
            is Outcome.Proceed -> {
                trace.onStep("act done: ${call.label}")
                // The Interface block's own YES/NO is not the loop's branch — the loop always
                // returns to observe. Its writes are carried so a captured result survives.
                Outcome.Proceed(Port.OK, outcome.writes)
            }
            // Await/Stop/Jump/Fork/StopFlow/StopFiber pass through unchanged: an Interface block
            // that parks or stops means exactly that, and the operator must not paper over it.
            else -> outcome
        }
    }
}
