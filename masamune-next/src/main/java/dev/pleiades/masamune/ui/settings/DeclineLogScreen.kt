package dev.pleiades.masamune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.core.decline.DeclineRegistry
import dev.pleiades.masamune.core.halt.HaltController
import dev.pleiades.masamune.ui.components.EmptyState
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.theme.MasamuneTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Refusal log. About → Refusal log.
 *
 * Every "no" this app says lands here with a classification and the exact text the user saw.
 * A refusal that is not in this list did not happen.
 */
@Composable
fun DeclineLogScreen() {
    val declines by DeclineRegistry.recent.collectAsState()
    val halts by HaltController.audit.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Notice(
                title = "In-memory, last 64",
                body = "This log is process-scoped and is not persisted; it exists to make a " +
                    "refusal inspectable while it matters, not to build an audit trail.",
                tone = NoticeTone.INFO,
            )
            if (halts.isNotEmpty()) {
                Text(
                    "${halts.size} halt event(s) this session — most recent: " +
                        "${halts.last().by} / ${halts.last().reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.warning,
                )
            }
            if (declines.isNotEmpty()) {
                TextButton(onClick = { DeclineRegistry.clear() }) { Text("Clear log") }
            }
        }

        if (declines.isEmpty()) {
            EmptyState(
                title = "Nothing refused yet",
                body = "Revoke a capability at About → Capabilities and try the matching action; " +
                    "the refusal shows up here.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(declines) { d ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                d.reason.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "${timeFormat.format(Date(d.at))}  ·  caller ${d.callerTag}" +
                                    (d.capability?.let { "  ·  ${it.name}" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MasamuneTheme.semantic.dim,
                            )
                            Text(
                                "operation: ${d.operation}",
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(d.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
