package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value

/**
 * The multi-flow host behind `Flow start` — the seam that launches *another* flow.
 *
 * A single [dev.pleiades.masamune.flow.runtime.Scheduler] runs one flow's fibers; starting a
 * different flow needs something that owns the set of flows and can spin a second scheduler. That
 * host lives at the app layer (it resolves a `flowUri` against the stored flow graphs and runs it),
 * so the runtime depends only on this narrow, `android.*`-free contract: "start the flow named by
 * this URI with this payload, and give me back the started fiber's URI, or null if there is no such
 * flow." That keeps `Flow start` unit-testable against a fake host on the JVM.
 *
 * This is the same shape [dev.pleiades.masamune.flow.model.FlowGraph]-external capability the earlier
 * "no multi-flow registry exists in this build" note referred to: `Flow start` and cross-flow
 * `Flow stop` both want it. When no host is wired the block fails by name — the honest report that
 * this build cannot reach another flow — rather than silently starting nothing.
 */
interface FlowStarter {
    /**
     * Start the flow named by [flowUri], handing it [payload] as its starting input, and return the
     * launched fiber's URI. Returns null when no flow resolves to [flowUri] — a visible failure at
     * the call site, never a fabricated URI. [stopWithParent] records the donor option that the
     * child should stop when the starting flow stops; [parentFlowId] is who started it.
     */
    suspend fun start(
        flowUri: String,
        payload: Value,
        stopWithParent: Boolean,
        parentFlowId: String,
    ): String?
}
