package dev.pleiades.masamune.flow.ui

import androidx.lifecycle.ViewModel
import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.model.Requirement
import dev.pleiades.masamune.flow.runtime.Fiber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * [fibers] is empty for the same reason: the runtime ([dev.pleiades.masamune.flow.runtime.Scheduler]
 * / [dev.pleiades.masamune.flow.runtime.FiberStore]) exists, but no [dev.pleiades.masamune.flow.runtime.BlockImpl]
 * registry is wired in this build, so there is nothing to execute and no fiber to invent. The
 * monitor renders whatever list the runtime hands it; today that list is empty.
 */
class FlowPlaneViewModel : ViewModel() {

    private val _graph = MutableStateFlow(FlowGraph(id = UUID.randomUUID().toString(), name = "Untitled flow"))
    val graph: StateFlow<FlowGraph> = _graph.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    /** Real device grants. Empty until a capability detector is wired — fail-closed by design. */
    val satisfied: Set<Requirement> = emptySet()

    /** Live fibers from the runtime. No scheduler is attached in this build, so there are none. */
    val fibers: List<Fiber> = emptyList()

    /** Whether a scheduler is attached and a flow can actually be run from here. It is not. */
    val executionWired: Boolean = false

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
}
