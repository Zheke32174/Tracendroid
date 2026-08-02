package dev.pleiades.masamune.flow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.ui.components.EmptyState
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.masamuneViewModel

/**
 * The flow plane: the pan/zoom canvas on top, and a tabbed tool panel below carrying the palette,
 * the selected block's editor, and the live fiber monitor. n8n's editor feel, Automate's
 * semantics, wired to one [FlowPlaneViewModel] that owns the graph.
 *
 * Selecting a block on the canvas jumps the panel to the editor; adding one from the palette keeps
 * the palette open so a run of blocks can be dropped in without paging back and forth.
 */
@Composable
fun FlowPlaneScreen() {
    val vm = masamuneViewModel { FlowPlaneViewModel() }
    val graph by vm.graph.collectAsState()
    val selectedId by vm.selectedNodeId.collectAsState()

    var tab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        FlowCanvas(
            graph = graph,
            selectedNodeId = selectedId,
            specOf = { BlockCatalog[it] },
            onSelectNode = {
                vm.selectNode(it)
                tab = 1
            },
            onMoveNode = vm::moveNode,
            onConnect = vm::connect,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Palette") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Block") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Fibers") })
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (tab) {
                0 -> BlockPalette(
                    satisfied = vm.satisfied,
                    onAddBlock = { vm.addBlock(it) },
                )

                1 -> {
                    val node = selectedId?.let { graph.node(it) }
                    val spec = node?.let { BlockCatalog[it.specId] }
                    if (node != null && spec != null) {
                        BlockEditor(
                            node = node,
                            spec = spec,
                            onChange = vm::updateNode,
                            onDelete = { vm.deleteNode(node.id) },
                        )
                    } else {
                        EmptyState(
                            title = "No block selected",
                            body = "Tap a block on the canvas to edit its options, input arguments " +
                                "and output variables.",
                        )
                    }
                }

                else -> FibersPanel(vm)
            }
        }
    }
}

@Composable
private fun FibersPanel(vm: FlowPlaneViewModel) {
    val fibers by vm.fibers.collectAsState()
    val running by vm.running.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Notice(
                title = "Execution is wired on this screen",
                body = "A real Scheduler runs the current graph through the BlockRegistry of " +
                    "payload-free blocks, over the actual expression evaluator and an in-memory " +
                    "FiberStore. Running fibers appear below with their live current block and " +
                    "variable frame. A block this build cannot run reports its gate rather than " +
                    "faking a result; the shared halt control parks a running flow.",
                tone = NoticeTone.INFO,
            )
            // Executes the current graph. Guarded against a second concurrent run while one is live.
            Button(onClick = { vm.runFlow() }, enabled = vm.executionWired && !running) {
                Text(if (running) "Running…" else "Run flow")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FiberMonitor(
            fibers = fibers,
            modifier = Modifier.fillMaxSize(),
            resolveBlockName = { vm.blockNameOf(it) },
        )
    }
}
