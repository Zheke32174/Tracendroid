package dev.pleiades.masamune.flow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.ui.theme.MasamuneTheme
import kotlin.math.roundToInt

/**
 * The pan/zoom canvas — n8n's editor feel over Automate's two-shape grammar.
 *
 * A [FlowNode] renders as a card; a [dev.pleiades.masamune.flow.model.Connection] renders as a
 * curved edge from an output port ([Port.OK], or [Port.YES]/[Port.NO]) to a node's single `IN`.
 * Everything the canvas manipulates it does through [FlowGraph]'s own methods, called on the
 * hoisted graph via the callbacks: the canvas owns no graph state, only the view transform.
 *
 * ### Coordinate model
 * Node positions ([FlowNode.x]/[FlowNode.y]) are pixels in the *content* space. The content layer
 * carries a [graphicsLayer] scale+translation for pan/zoom, and because Compose delivers pointer
 * deltas to a child in that child's own (pre-transform) space, a drag reported inside the content
 * layer is already in content pixels — it is never re-divided by the zoom. Edges, port anchors and
 * hit-testing all live in that one space, which is what keeps drag-to-connect landing where the
 * cursor is at any zoom.
 */

private val NODE_WIDTH = 176.dp
private val CONTENT_EXTENT = 3600.dp
private const val MIN_SCALE = 0.35f
private const val MAX_SCALE = 2.5f

/** Where along a node's bottom edge each output port's edge attaches. */
private fun portFraction(port: Port): Float = when (port) {
    Port.OK -> 0.5f
    Port.YES -> 0.24f
    Port.NO -> 0.76f
}

@Composable
fun FlowCanvas(
    graph: FlowGraph,
    selectedNodeId: String?,
    specOf: (String) -> BlockSpec?,
    onSelectNode: (String) -> Unit,
    onMoveNode: (id: String, x: Float, y: Float) -> Unit,
    onConnect: (fromNode: String, fromPort: Port, toNode: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fallbackSize = remember(density) {
        with(density) { Size(NODE_WIDTH.toPx(), 132.dp.toPx()) }
    }

    // View transform. Held here because it is a property of *looking* at the graph, not of the
    // graph itself — panning must not dirty the document.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Measured node sizes in content px, so an edge lands on a card's real bottom edge rather
    // than a guessed one. Position comes from the live node; only size is measured here.
    val nodeSizes = remember { androidx.compose.runtime.mutableStateMapOf<String, Size>() }
    // The in-progress drag-to-connect, if any. Rendered as a dashed lead line.
    var pending by remember { mutableStateOf<PendingEdge?>(null) }

    fun sizeOf(id: String): Size = nodeSizes[id] ?: fallbackSize
    fun outAnchor(node: FlowNode, port: Port): Offset {
        val s = sizeOf(node.id)
        return Offset(node.x + s.width * portFraction(port), node.y + s.height)
    }
    fun inAnchor(node: FlowNode): Offset {
        val s = sizeOf(node.id)
        return Offset(node.x + s.width / 2f, node.y)
    }
    fun nodeAt(point: Offset, exclude: String): String? =
        graph.nodes.lastOrNull { it.id != exclude && Rect(Offset(it.x, it.y), sizeOf(it.id)).contains(point) }?.id

    val edgeColor = MaterialTheme.colorScheme.primary
    val yesColor = MasamuneTheme.semantic.success
    val noColor = MaterialTheme.colorScheme.error
    val pendingColor = MaterialTheme.colorScheme.onSurfaceVariant
    fun colorOf(port: Port): Color = when (port) {
        Port.OK -> edgeColor
        Port.YES -> yesColor
        Port.NO -> noColor
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    // Keep the gesture centroid fixed while zooming, then apply the pan.
                    offset = Offset(
                        x = centroid.x - (centroid.x - offset.x) / scale * newScale + pan.x,
                        y = centroid.y - (centroid.y - offset.y) / scale * newScale + pan.y,
                    )
                    scale = newScale
                }
            },
    ) {
        Box(
            modifier = Modifier
                .size(CONTENT_EXTENT)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            // Edges, drawn behind the cards.
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (conn in graph.connections) {
                    val from = graph.node(conn.fromNode) ?: continue
                    val to = graph.node(conn.toNode) ?: continue
                    if (from.id !in nodeSizes || to.id !in nodeSizes) continue
                    drawEdge(outAnchor(from, conn.fromPort), inAnchor(to), colorOf(conn.fromPort), dashed = false)
                }
                pending?.let { drawEdge(it.start, it.end, pendingColor, dashed = true) }
            }

            for (node in graph.nodes) {
                val spec = specOf(node.specId)
                NodeCard(
                    node = node,
                    spec = spec,
                    selected = node.id == selectedNodeId,
                    onTap = { onSelectNode(node.id) },
                    onMove = onMoveNode,
                    onMeasured = { s -> nodeSizes[node.id] = s },
                    onPortDragStart = { port ->
                        val a = outAnchor(node, port)
                        pending = PendingEdge(node.id, port, a, a)
                    },
                    onPortDrag = { delta ->
                        pending = pending?.let { it.copy(end = it.end + delta) }
                    },
                    onPortDragEnd = {
                        pending?.let { p ->
                            nodeAt(p.end, exclude = p.fromNode)?.let { target ->
                                onConnect(p.fromNode, p.fromPort, target)
                            }
                        }
                        pending = null
                    },
                    onPortDragCancel = { pending = null },
                )
            }
        }

        // Fixed (un-scaled) view controls.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(scale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Reset view") }
            }
        }

        if (graph.nodes.isEmpty()) {
            Text(
                "Empty flow. Add a block from the palette below; drag it to move, drag from its " +
                    "OK / YES / NO port onto another block to connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            )
        }
    }
}

/** A drag-to-connect in flight: which port it left, and where the lead line currently ends. */
private data class PendingEdge(
    val fromNode: String,
    val fromPort: Port,
    val start: Offset,
    val end: Offset,
)

@Composable
private fun NodeCard(
    node: FlowNode,
    spec: BlockSpec?,
    selected: Boolean,
    onTap: () -> Unit,
    onMove: (id: String, x: Float, y: Float) -> Unit,
    onMeasured: (Size) -> Unit,
    onPortDragStart: (Port) -> Unit,
    onPortDrag: (Offset) -> Unit,
    onPortDragEnd: () -> Unit,
    onPortDragCancel: () -> Unit,
) {
    val current by rememberUpdatedState(node)
    val ports = spec?.ports ?: Port.of(BlockShape.ACTION)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (selected) 6.dp else 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .offset { IntOffset(current.x.roundToInt(), current.y.roundToInt()) }
            .width(NODE_WIDTH)
            .onSizeChanged { onMeasured(Size(it.width.toFloat(), it.height.toFloat())) }
            .pointerInput(node.id) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(node.id) {
                var px = 0f
                var py = 0f
                detectDragGestures(
                    onDragStart = {
                        px = current.x
                        py = current.y
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        px += drag.x
                        py += drag.y
                        onMove(current.id, px, py)
                    },
                )
            },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec?.name ?: node.specId,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
            }
            if (spec == null) {
                Text(
                    "Unknown block id \"${node.specId}\" — not in the catalog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    spec.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                    maxLines = 3,
                )
            }
            node.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = if (ports.size == 1) Arrangement.Center else Arrangement.SpaceBetween,
            ) {
                ports.forEach { port ->
                    PortHandle(
                        port = port,
                        nodeId = node.id,
                        onDragStart = { onPortDragStart(port) },
                        onDrag = onPortDrag,
                        onDragEnd = onPortDragEnd,
                        onDragCancel = onPortDragCancel,
                    )
                }
            }
        }
    }
}

/**
 * The draggable output connector. Dragging from it draws a lead line; releasing over another node
 * connects. A block has no `IN` handle because its fan-in is unnamed — an edge simply targets the
 * whole card, mirroring the model where a node has one logical `IN` accepting any number of edges.
 */
@Composable
private fun PortHandle(
    port: Port,
    nodeId: String,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val color = when (port) {
        Port.OK -> MaterialTheme.colorScheme.primary
        Port.YES -> MasamuneTheme.semantic.success
        Port.NO -> MaterialTheme.colorScheme.error
    }
    val label = when (port) {
        Port.OK -> "OK"
        Port.YES -> "YES"
        Port.NO -> "NO"
    }
    Surface(
        color = color,
        shape = CircleShape,
        modifier = Modifier
            .size(34.dp)
            .pointerInput(nodeId, port) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** A downward cubic between two anchors, with a dot at each end. Direction reads top-to-bottom. */
private fun DrawScope.drawEdge(from: Offset, to: Offset, color: Color, dashed: Boolean) {
    val dy = ((to.y - from.y) * 0.5f).coerceAtLeast(48f)
    val path = Path().apply {
        moveTo(from.x, from.y)
        cubicTo(from.x, from.y + dy, to.x, to.y - dy, to.x, to.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 3f,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(18f, 14f)) else null,
        ),
    )
    drawCircle(color = color, radius = 5f, center = from)
    drawCircle(color = color, radius = 5f, center = to)
}
