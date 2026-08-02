package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.runtime.impl.AndroidVersionBlock
import dev.pleiades.masamune.flow.runtime.impl.ArrayAddBlock
import dev.pleiades.masamune.flow.runtime.impl.ArrayRemoveBlock
import dev.pleiades.masamune.flow.runtime.impl.ArraySetBlock
import dev.pleiades.masamune.flow.runtime.impl.AtomicAddBlock
import dev.pleiades.masamune.flow.runtime.impl.AtomicCasBlock
import dev.pleiades.masamune.flow.runtime.impl.AtomicClearAllBlock
import dev.pleiades.masamune.flow.runtime.impl.AtomicLoadBlock
import dev.pleiades.masamune.flow.runtime.impl.AtomicStore
import dev.pleiades.masamune.flow.runtime.impl.AtomicStoreBlock
import dev.pleiades.masamune.flow.runtime.impl.FlowLog
import dev.pleiades.masamune.flow.runtime.impl.FlowStartBlock
import dev.pleiades.masamune.flow.runtime.impl.FlowStarter
import dev.pleiades.masamune.flow.runtime.impl.HandoffStore
import dev.pleiades.masamune.flow.runtime.impl.InMemoryFlowLog
import dev.pleiades.masamune.flow.runtime.impl.LogAppendBlock
import dev.pleiades.masamune.flow.runtime.impl.VariablesGiveBlock
import dev.pleiades.masamune.flow.runtime.impl.VariablesTakeBlock
import dev.pleiades.masamune.flow.runtime.impl.DelayBlock
import dev.pleiades.masamune.flow.runtime.impl.DestructuringAssignBlock
import dev.pleiades.masamune.flow.runtime.impl.DictionaryPutBlock
import dev.pleiades.masamune.flow.runtime.impl.DictionaryRemoveBlock
import dev.pleiades.masamune.flow.runtime.impl.ExpressionTrueBlock
import dev.pleiades.masamune.flow.runtime.impl.FailureCatchBlock
import dev.pleiades.masamune.flow.runtime.impl.FiberStopBlock
import dev.pleiades.masamune.flow.runtime.impl.FlowBeginningBlock
import dev.pleiades.masamune.flow.runtime.impl.FlowStopBlock
import dev.pleiades.masamune.flow.runtime.impl.ForEachBlock
import dev.pleiades.masamune.flow.runtime.impl.FileCopyBlock
import dev.pleiades.masamune.flow.runtime.impl.FileDeleteBlock
import dev.pleiades.masamune.flow.runtime.impl.FileExistsBlock
import dev.pleiades.masamune.flow.runtime.impl.FileListBlock
import dev.pleiades.masamune.flow.runtime.impl.FileMakeDirectoryBlock
import dev.pleiades.masamune.flow.runtime.impl.FileMoveBlock
import dev.pleiades.masamune.flow.runtime.impl.FileReadBlock
import dev.pleiades.masamune.flow.runtime.impl.FileWriteBlock
import dev.pleiades.masamune.flow.runtime.impl.ForkBlock
import dev.pleiades.masamune.flow.runtime.impl.GotoBlock
import dev.pleiades.masamune.flow.runtime.impl.LabelBlock
import dev.pleiades.masamune.flow.runtime.impl.SubroutineBlock
import dev.pleiades.masamune.flow.runtime.impl.ZipCompressBlock
import dev.pleiades.masamune.flow.runtime.impl.ZipExtractBlock
import dev.pleiades.masamune.flow.runtime.impl.ZipListBlock
import dev.pleiades.masamune.flow.runtime.impl.TimeZoneGetBlock
import dev.pleiades.masamune.flow.runtime.impl.VariableSetBlock
import kotlinx.coroutines.CoroutineScope

/**
 * Assembles the payload-free block implementations into the `(specId) -> BlockImpl?` the
 * [Scheduler] takes.
 *
 * The registry is built **per flow run**, not as a global singleton, and the reason is the two
 * dependencies threaded in here: the [FlowGraph] (which `Go to` and `Subroutine` need to resolve a
 * label node or a callee body) and the [scope] (on which `Delay`'s waker runs). Both belong to one
 * running flow, so the registry that closes over them does too. The per-flow [AtomicStore] is
 * created here so a flow's atomic cells are shared among its fibers and no further — a fresh run
 * starts with fresh shared state.
 *
 * ### Honest gate by omission
 * Only genuinely runnable blocks are registered. Everything else — a block whose payload or
 * permission is absent, or one (the pickers) that needs a subsystem this build does not have — is
 * deliberately **not** put in the map. The scheduler treats a spec with no impl as gated and reports
 * the reason. That silence is the honest signal; a registered no-op would be the exact silent
 * failure the whole plane is built to remove.
 *
 * `Flow start` is the one registered block that can still fail for a *missing host* rather than a
 * bad input: it needs the [FlowStarter] multi-flow host, injected here and defaulting to none. With
 * no host it fails by name at run — the honest report that this build cannot reach another flow —
 * which is why it is registered (the impl exists) yet still gates itself honestly.
 */
class BlockRegistry(
    graph: FlowGraph,
    scope: CoroutineScope,
    flowStarter: () -> FlowStarter? = { null },
) {
    /** This flow's shared atomic cells — created here so they are shared among the flow's fibers and no wider. */
    private val atomics = AtomicStore()

    /** This flow's inter-fiber hand-off mailboxes — shared among the flow's fibers and no wider. */
    private val handoffs = HandoffStore()

    /** This flow's message log — the sink `Log append` writes to. In-memory by default. */
    private val flowLog: FlowLog = InMemoryFlowLog()

    private val byId: Map<String, BlockImpl> = buildMap {
        fun register(impl: BlockImpl) {
            val clash = put(impl.specId, impl)
            require(clash == null) { "Duplicate block impl for '${impl.specId}'" }
        }

        // Flow — the graph's own control structure.
        register(FlowBeginningBlock())
        register(LabelBlock())
        register(GotoBlock(graph))
        register(ForkBlock())
        register(SubroutineBlock(graph))
        register(FailureCatchBlock())
        register(FlowStopBlock())
        register(FiberStopBlock())
        register(LogAppendBlock(flowLog))
        register(FlowStartBlock(flowStarter))

        // General — conditional, mutations, loop.
        register(ExpressionTrueBlock())
        register(VariableSetBlock())
        register(ArrayAddBlock())
        register(ArraySetBlock())
        register(ArrayRemoveBlock())
        register(DictionaryPutBlock())
        register(DictionaryRemoveBlock())
        register(DestructuringAssignBlock())
        register(AndroidVersionBlock())
        register(ForEachBlock())

        // Concurrency — the flow-wide atomics and the give/take hand-off mailboxes.
        register(AtomicStoreBlock(atomics))
        register(AtomicLoadBlock(atomics))
        register(AtomicAddBlock(atomics))
        register(AtomicCasBlock(atomics))
        register(AtomicClearAllBlock(atomics))
        register(VariablesGiveBlock(handoffs))
        register(VariablesTakeBlock(handoffs))

        // Date & time — the two that need only a clock.
        register(DelayBlock(scope))
        register(TimeZoneGetBlock())

        // Storage — the local-filesystem core (file + zip). The FTP/Drive/OneDrive/SAF and the
        // StatFs-backed storage_* blocks need a subsystem this build lacks and stay gated by
        // omission; these eleven need only a path the process can reach.
        register(FileReadBlock())
        register(FileWriteBlock())
        register(FileExistsBlock())
        register(FileMakeDirectoryBlock())
        register(FileDeleteBlock())
        register(FileCopyBlock())
        register(FileMoveBlock())
        register(FileListBlock())
        register(ZipCompressBlock())
        register(ZipExtractBlock())
        register(ZipListBlock())
    }

    /** The lookup the scheduler consults: a registered impl, or null (gated) for everything else. */
    val lookup: (String) -> BlockImpl? = { byId[it] }

    /** The spec ids this build can actually run — useful to the editor for marking runnable blocks. */
    val implementedIds: Set<String> get() = byId.keys
}
