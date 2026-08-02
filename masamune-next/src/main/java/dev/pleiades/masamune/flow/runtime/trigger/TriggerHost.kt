package dev.pleiades.masamune.flow.runtime.trigger

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.runtime.impl.FlowStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The engine that turns events into running flows — the n8n trigger plane.
 *
 * It holds [FlowTrigger] → flow bindings and, when a trigger fires, launches that flow through the
 * [FlowStarter] built for `Flow start`. That reuse is the point: a manual `Flow start` and an
 * event-driven trigger take the *same* launch path, so a flow started by a schedule is
 * indistinguishable from one a user started — exactly what an n8n trigger is.
 *
 * The wiring is deliberately thin and honest. A trigger's `onFire` is not suspend, so each firing
 * launches the (suspend) [FlowStarter.start] on [scope]; if the starter cannot resolve the flow it
 * returns null and nothing runs — a trigger for a deleted flow fires into nothing rather than
 * crashing. [install] is idempotent per flow (re-installing disarms the previous binding first, so
 * a flow is never double-armed), and [disarmAll] tears every trigger down with the host's lifecycle.
 *
 * The host owns no Android surface and no scheduler; a [FlowTrigger] can be a [ManualTrigger], an
 * [IntervalTrigger], or an app-layer source (a broadcast receiver, a sensor watch) behind the same
 * interface. That is what keeps the whole trigger plane unit-testable against fakes.
 */
class TriggerHost(
    private val starter: FlowStarter,
    private val scope: CoroutineScope,
) {
    private data class Installed(val flowUri: String, val trigger: FlowTrigger, val stopWithParent: Boolean)

    private val lock = Any()
    private val installed = LinkedHashMap<String, Installed>()

    /** The flow URIs currently armed. */
    val armedFlows: Set<String> get() = synchronized(lock) { installed.keys.toSet() }

    /**
     * Arm [trigger] to start the flow named by [flowUri]; each firing launches the flow with the
     * event payload. Idempotent per flow: an existing binding for [flowUri] is disarmed first.
     */
    fun install(flowUri: String, trigger: FlowTrigger, stopWithParent: Boolean = false) {
        synchronized(lock) {
            installed.remove(flowUri)?.trigger?.disarm()
            installed[flowUri] = Installed(flowUri, trigger, stopWithParent)
        }
        trigger.arm { payload -> onFire(flowUri, payload, stopWithParent) }
    }

    /** Disarm and forget the trigger for [flowUri], if any. */
    fun uninstall(flowUri: String) {
        val removed = synchronized(lock) { installed.remove(flowUri) }
        removed?.trigger?.disarm()
    }

    /** Disarm and forget every trigger — called when the host's owner is torn down. */
    fun disarmAll() {
        val all = synchronized(lock) { installed.values.toList().also { installed.clear() } }
        all.forEach { it.trigger.disarm() }
    }

    private fun onFire(flowUri: String, payload: Value, stopWithParent: Boolean) {
        scope.launch { starter.start(flowUri, payload, stopWithParent, TRIGGER_PARENT) }
    }

    private companion object {
        /** The parent-flow id recorded for a flow that a trigger — not another flow — started. */
        const val TRIGGER_PARENT = "\$trigger"
    }
}
