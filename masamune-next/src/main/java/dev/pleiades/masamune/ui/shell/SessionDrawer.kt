package dev.pleiades.masamune.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * Session drawer (DONOR-SURFACES §4 line 78): the session list, rename-on-long-press,
 * [New session], [Failsafe], and close/kill with the donor's "Really kill this session?" confirm.
 *
 * The header note states plainly that a session is a named run-group over one-shot RUN_COMMAND,
 * not a live PTY — so nobody reads the list as a set of live interactive shells.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawer(
    sessions: List<ShellSession>,
    activeSessionId: Long,
    onSelect: (Long) -> Unit,
    onNewSession: () -> Unit,
    onNewFailsafe: () -> Unit,
    onRename: (Long, String) -> Unit,
    onKill: (Long) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ShellSession?>(null) }
    var killTarget by remember { mutableStateOf<ShellSession?>(null) }

    ModalDrawerSheet {
        Column(
            modifier = Modifier.padding(MasamuneTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
        ) {
            Text(
                stringResource(R.string.terminal_sessions_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.terminal_sessions_model_note),
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
                TextButton(onClick = onNewSession) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.terminal_action_new_session))
                }
                TextButton(onClick = onNewFailsafe) {
                    Icon(Icons.Filled.HealthAndSafety, contentDescription = null)
                    Text(stringResource(R.string.terminal_action_new_session_failsafe))
                }
            }

            Text(
                stringResource(R.string.terminal_session_hint_longpress),
                style = MaterialTheme.typography.labelSmall,
                color = MasamuneTheme.semantic.dim,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = MasamuneTheme.spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    active = session.id == activeSessionId,
                    onSelect = { onSelect(session.id) },
                    onLongPress = { renameTarget = session },
                    onKill = { killTarget = session },
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            current = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name -> onRename(target.id, name); renameTarget = null },
        )
    }

    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text(stringResource(R.string.terminal_confirm_kill_title)) },
            text = { Text(stringResource(R.string.terminal_confirm_kill_body)) },
            confirmButton = {
                TextButton(onClick = { onKill(target.id); killTarget = null }) {
                    Text(stringResource(R.string.terminal_action_close_session))
                }
            },
            dismissButton = {
                TextButton(onClick = { killTarget = null }) { Text(stringResource(R.string.terminal_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ShellSession,
    active: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onKill: () -> Unit,
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MasamuneTheme.spacing.sm, vertical = MasamuneTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                val failsafeTag = stringResource(R.string.terminal_session_failsafe_tag)
                val subtitle = buildString {
                    if (session.failsafe) { append(failsafeTag); append(" · ") }
                    append("${session.transcript.size} runs")
                }
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
            }
            IconButton(onClick = onKill) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.terminal_action_close_session))
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_rename_session_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.terminal_rename_session_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) } },
    )
}
