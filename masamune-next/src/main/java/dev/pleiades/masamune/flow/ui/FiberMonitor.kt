package dev.pleiades.masamune.flow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.FiberStatus
import dev.pleiades.masamune.ui.components.KeyValueRow
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The live view of a flow's running fibers — the surface that makes the AI operator auditable
 * (see docs/AI-OPERATOR.md). Every action the operator takes is a block on a fiber, so this
 * monitor is where a human watches, and could stop, what the operator is doing.
 *
 * It renders each [Fiber]'s **live current block** (not a summary), its [FiberStatus], the
 * `awaitReason` or `errorMessage` behind a parked or failed fiber, and the fiber's private
 * variable frame. It is a pure function of the [fibers] list handed in by the runtime — it holds
 * no scheduler and starts nothing, so what it shows is exactly what the runtime holds.
 *
 * @param resolveBlockName maps a node id (a fiber's `currentNode`) to a human block name when the
 *   caller has the graph to hand; falls back to the raw node id otherwise.
 */
@Composable
fun FiberMonitor(
    fibers: List<Fiber>,
    modifier: Modifier = Modifier,
    resolveBlockName: (String) -> String? = { null },
) {
    if (fibers.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No fibers", style = MaterialTheme.typography.titleMedium)
            Text(
                "A fiber is a running instance of this flow. None are running.",
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(fibers, key = { it.id }) { fiber ->
            FiberCard(fiber, resolveBlockName)
        }
    }
}

@Composable
private fun FiberCard(fiber: Fiber, resolveBlockName: (String) -> String?) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "fiber ${fiber.id.take(8)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(fiber.status)
            }

            // The live current block — the point of this surface. Never a rolled-up summary.
            val blockLabel = when (val nodeId = fiber.currentNode) {
                null -> "not started"
                else -> resolveBlockName(nodeId)?.let { "$it  (node ${nodeId.take(8)})" } ?: "node ${nodeId.take(8)}"
            }
            KeyValueRow(
                key = "Current block" + (fiber.enteredBy?.let { " · entered by ${it.name}" } ?: ""),
                value = blockLabel,
            )

            if (fiber.status == FiberStatus.AWAITING) {
                Notice(
                    title = "Awaiting",
                    body = fiber.awaitReason ?: "Parked on a condition; no reason recorded.",
                    tone = NoticeTone.INFO,
                )
            }
            if (fiber.status == FiberStatus.ERROR) {
                Notice(
                    title = "Errored",
                    body = fiber.errorMessage ?: "No cause recorded.",
                    tone = NoticeTone.ERROR,
                )
            }

            // Hide the runtime's private control state (call stack, catch frames, loop cursors),
            // which is namespaced under a leading `$` a user variable can never carry. The frame
            // shown is the user's own variables — what a flow author reasons about.
            val userVars = fiber.variables.filterKeys { !it.startsWith("$") }
            Text(
                "Variable frame (${userVars.size})",
                style = MaterialTheme.typography.labelLarge,
            )
            if (userVars.isEmpty()) {
                Text(
                    "— empty —",
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            } else {
                userVars.forEach { (name, value) ->
                    KeyValueRow(
                        key = "$name : ${value.typeName}",
                        value = value.asText().ifEmpty { "(empty)" },
                        mono = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: FiberStatus) {
    val color: Color = when (status) {
        FiberStatus.READY -> MaterialTheme.colorScheme.primary
        FiberStatus.RUNNING -> MasamuneTheme.semantic.success
        FiberStatus.AWAITING -> MasamuneTheme.semantic.warning
        FiberStatus.STOPPED -> MasamuneTheme.semantic.dim
        FiberStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small) {
        Text(
            status.name,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
