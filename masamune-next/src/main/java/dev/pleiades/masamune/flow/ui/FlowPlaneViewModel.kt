package dev.pleiades.masamune.flow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.core.halt.HaltController
import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.model.Requirement
import dev.pleiades.masamune.flow.runtime.ArgResolver
import dev.pleiades.masamune.flow.runtime.BlockRegistry
import dev.pleiades.masamune.flow.runtime.ExprEvalAdapter
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.InMemoryFiberStore
import dev.pleiades.masamune.flow.runtime.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds the one editable [FlowGraph] and the view's selection, and applies every mutation through
 * the graph's own methods so the model's invariants (an output port holds at most one edge; a
 * removed node drops every edge touching it) are the graph's to keep, not the UI's to reinvent.
 *
 * ### Honest state, not simulated state
 * [satisfied] is the set of [Requirement]s the device actually grants. This build has no detector
 * for the flow-block grants (an enabled AccessibilityService, the Yojimbo uid-2000 server, a
 * NotificationListener, a device-admin receiver), so the honest, fail-closed answer is the empty
 * set: nothing is assumed granted. That is what makes the palette's gating visible rather than
 * decorative — a block whose grant cannot be confirmed reports unavailable, it does not pretend.
 *
 * [fibers] is the live list the running [Scheduler] holds — empty until [runFlow] starts one, then
 * the fibers actually executing this graph, current block and variable frame and all. The monitor
 * renders exactly what the runtime hands it; there is no invented state.
 */
class FlowPlaneViewModel : ViewModel() {

    private val _graph = MutableStateFlow(FlowGraph(id = UUID.randomUUID().toString(), name = "Untitled flow"))
    val graph: StateFlow<FlowGraph> = _graph.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    /** Real device grants. Empty until a capability detector is wired — fail-closed by design. */
    val satisfied: Set<Requirement> = emptySet()

    /** Live fibers from the running scheduler. Empty when nothing is running; never fabricated. */
    private val _fibers = MutableStateFlow<List<Fiber>>(emptyList())
    val fibers: StateFlow<List<Fiber>> = _fibers.asStateFlow()

    /** Execution is wired on this screen: a real scheduler runs the current graph. */
    val executionWired: Boolean = true

    /** True while a run is in flight, so the Run control can guard against launching a second. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var runJob: Job? = null

    private var placed = 0

    fun addBlock(spec: BlockSpec) {
        // Cascade new blocks so they do not stack exactly on top of one another.
        val step = placed % 6
        val row = placed / 6
        val node = FlowNode(
            id = UUID.randomUUID().toString(),
            specId = spec.id,
            x = 96f + step * 56f,
            y = 96f + step * 40f + row * 24f,
        )
        placed++
        _graph.update { it.copy(nodes = it.nodes + node) }
        _selectedNodeId.value = node.id
    }

    fun selectNode(id: String) {
        _selectedNodeId.value = id
    }

    fun moveNode(id: String, x: Float, y: Float) {
        _graph.update { g ->
            g.copy(nodes = g.nodes.map { if (it.id == id) it.copy(x = x, y = y) else it })
        }
    }

    fun connect(fromNode: String, fromPort: Port, toNode: String) {
        if (fromNode == toNode) return
        _graph.update { it.connect(fromNode, fromPort, toNode) }
    }

    fun updateNode(node: FlowNode) {
        _graph.update { g ->
            g.copy(nodes = g.nodes.map { if (it.id == node.id) node else it })
        }
    }

    fun deleteNode(id: String) {
        _graph.update { it.removeNode(id) }
        if (_selectedNodeId.value == id) _selectedNodeId.value = null
    }

    /** A fiber's `currentNode` (a node id) resolved to its block's display name, for the monitor. */
    fun blockNameOf(nodeId: String): String? =
        _graph.value.node(nodeId)?.let { BlockCatalog[it.specId]?.name }

    /**
     * Run the current graph to completion on a background coroutine, streaming the live fiber list
     * into [fibers] as it goes.
     *
     * A [Scheduler] is built fresh per run from the graph snapshot, the real [BlockRegistry]
     * (whose gate reports any block this build cannot run), the [ExprEvalAdapter] over the actual
     * expression evaluator, and an [InMemoryFiberStore]. The scheduler's `isHalted` seam is bound
     * to [HaltController], so the shared stop control parks this flow exactly as it stops every
     * other privileged surface — halt is a property of the run, not a flag this screen invents.
     *
     * The registry closes over [viewModelScope], so a `Delay`'s waker and the run itself share the
     * ViewModel's lifecycle: clearing the screen cancels an in-flight run and its parked timers.
     * A lightweight poll mirrors the scheduler's snapshot into [fibers] while it runs, then a final
     * snapshot captures the terminal state.
     */
    fun runFlow() {
        if (runJob?.isActive == true) return
        val snapshot = _graph.value
        _running.value = true
        runJob = viewModelScope.launch {
            val registry = BlockRegistry(snapshot, viewModelScope)
            val scheduler = Scheduler(
                graph = snapshot,
                specs = { BlockCatalog[it] },
                impls = registry.lookup,
                resolver = ArgResolver(ExprEvalAdapter()),
                store = InMemoryFiberStore(),
                scope = viewModelScope,
                isHalted = { HaltController.isHalted },
            )
            val mirror = launch {
                while (isActive) {
                    _fibers.value = scheduler.snapshot()
                    delay(FIBER_POLL_MS)
                }
            }
            try {
                scheduler.start(UUID.randomUUID().toString())
                scheduler.run()
            } finally {
                mirror.cancel()
                _fibers.value = scheduler.snapshot()
                _running.value = false
            }
        }
    }

    private companion object {
        /** How often the monitor mirror samples the scheduler while a flow runs. */
        const val FIBER_POLL_MS = 120L
    }
}
