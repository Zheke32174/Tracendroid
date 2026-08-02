package dev.pleiades.masamune.flow.model

/**
 * A placed block: one instance of a [BlockSpec] on the canvas, with its configuration.
 *
 * [options] and [args] are stored as raw strings rather than parsed values because the
 * editor must round-trip whatever the user typed, including an expression that does not
 * yet parse. Parsing happens at run time, per fiber; a half-written flow still saves.
 *
 * [argIsExpression] records the `fx` toggle per argument. Automate makes the constant/
 * expression distinction explicit in the field itself so the user never has to quote a
 * literal to get one, and that only works if the mode is stored, not inferred.
 *
 * Encoded through `org.json` in the flow codec, not a serialization plugin — this module
 * carries no reflective-serialization surface by design (see the module `build.gradle.kts`).
 */
data class FlowNode(
    val id: String,
    val specId: String,
    val x: Float,
    val y: Float,
    val options: Map<String, String> = emptyMap(),
    val args: Map<String, String> = emptyMap(),
    val argIsExpression: Map<String, Boolean> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    /** User's own note on this node. Automate calls it a "comment"; it renders on canvas. */
    val note: String? = null,
)

/**
 * A directed edge from one node's outgoing [Port] to another node's `IN`.
 *
 * There is no `toPort` because a block has a single logical `IN` accepting any number of
 * edges — the fan-in is unnamed, so naming it would invent structure the donor does not
 * have and the runtime cannot use.
 */
data class Connection(
    val fromNode: String,
    val fromPort: Port,
    val toNode: String,
)

/**
 * The flow — Automate's "source code". Nodes plus edges, and nothing about execution:
 * a running instance is a `Fiber`, and one flow may have many at once.
 *
 * A flow is a user document — exported, imported, shared and diffed — so its encoded form
 * is stable: the codec's field names are the on-disk format, and renaming one is a
 * file-format change, not a refactor.
 */
data class FlowGraph(
    val id: String,
    val name: String,
    val nodes: List<FlowNode> = emptyList(),
    val connections: List<Connection> = emptyList(),
    val description: String? = null,
) {
    fun node(id: String): FlowNode? = nodes.firstOrNull { it.id == id }

    /**
     * Where a fiber goes after leaving [nodeId] by [port], or null if that port is
     * unconnected.
     *
     * Null is not an error condition — reaching an unconnected port is one of the four
     * documented ways a fiber terminates normally. Callers must treat it as termination,
     * never as a failure to route.
     */
    fun next(nodeId: String, port: Port): String? =
        connections.firstOrNull { it.fromNode == nodeId && it.fromPort == port }?.toNode

    /**
     * Connect [fromNode]`.`[fromPort] to [toNode], replacing any edge already leaving that
     * port.
     *
     * An output port holds at most one edge: a fiber is a single point of execution, so a
     * second edge from one port would mean silently forking, and forking is [Fork]'s job
     * and must be visible in the graph. Fan-*in* is unrestricted.
     */
    fun connect(fromNode: String, fromPort: Port, toNode: String): FlowGraph =
        copy(
            connections = connections.filterNot {
                it.fromNode == fromNode && it.fromPort == fromPort
            } + Connection(fromNode, fromPort, toNode),
        )

    fun disconnect(fromNode: String, fromPort: Port): FlowGraph =
        copy(connections = connections.filterNot { it.fromNode == fromNode && it.fromPort == fromPort })

    /** Removing a node removes every edge touching it, in either direction. */
    fun removeNode(nodeId: String): FlowGraph =
        copy(
            nodes = nodes.filterNot { it.id == nodeId },
            connections = connections.filterNot { it.fromNode == nodeId || it.toNode == nodeId },
        )

    /**
     * Nodes no fiber can ever reach: not the target of any edge, and not a block that can
     * begin a flow on its own.
     *
     * Surfaced in the editor rather than rejected. A half-connected graph is a normal
     * intermediate state while building, and refusing to save it would make the editor
     * fight the user. But an orphan that ships is almost always a mistake, so it is worth
     * naming before a run.
     */
    fun unreachableNodes(catalog: (String) -> BlockSpec?): List<FlowNode> {
        val targets = connections.map { it.toNode }.toSet()
        return nodes.filter { it.id !in targets && catalog(it.specId)?.category != BlockCategory.FLOW }
    }
}
