package dev.pleiades.masamune.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.fs.FileSystemRegistry
import dev.pleiades.masamune.fs.FsEntry
import dev.pleiades.masamune.fs.ZipArchiver
import dev.pleiades.masamune.shell.TermuxShellBackend
import dev.pleiades.masamune.ui.components.KeyValueRow
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.theme.MasamuneTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================================
// External hand-off intents (§6 line 114: open with / share).
//
// Both need a content:// URI another app can read; a per-launch read grant rides on the intent.
// The Explorer only calls these with a URI a backend actually handed out (SAF documents today),
// so a java.io FileUriExposedException is impossible — the null-URI case is gated in the UI first.
// =====================================================================================

private fun openWith(context: Context, uri: Uri, mime: String?, chooserTitle: String) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(view, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

private fun shareUris(context: Context, uris: List<Uri>, mime: String?, chooserTitle: String) {
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
            type = mime ?: "*/*"
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "*/*"
        }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

/** Launches Open-with for [entry] if the backend can expose a content URI, else explains the gate. */
fun launchOpenWith(context: Context, vm: FilesViewModel, entry: FsEntry, onGated: (String) -> Unit) {
    val uri = vm.externalUriOf(entry)
    if (uri == null) onGated(context.getString(R.string.explorer_open_with_gated))
    else openWith(context, uri, entry.mimeType, context.getString(R.string.explorer_open_with_chooser))
}

/** Shares [entries] if every one can expose a content URI, else explains the gate. */
fun launchShare(context: Context, vm: FilesViewModel, entries: List<FsEntry>, onGated: (String) -> Unit) {
    val files = entries.filter { !it.isDirectory }
    val uris = files.mapNotNull { vm.externalUriOf(it) }
    if (files.isEmpty() || uris.size != files.size) {
        onGated(context.getString(R.string.explorer_share_gated))
        return
    }
    val mime = files.map { it.mimeType }.distinct().singleOrNull()
    shareUris(context, uris, mime, context.getString(R.string.explorer_share_chooser))
}

// =====================================================================================
// Per-row action menu  (§6 line 114; Amaze menu/item_extras).
//
// Backable actions are live; every capability this build cannot back is a dim, Block-marked row
// that states which channel is missing on tap — never a live control that silently does nothing.
// =====================================================================================

@Composable
fun FileRowActionsMenu(
    expanded: Boolean,
    entry: FsEntry,
    hasExternalUri: Boolean,
    canOpenInTerminal: Boolean,
    localMount: Boolean,
    canWrite: Boolean,
    onDismiss: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onOpenInTerminal: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
    onProperties: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExplainGated: (String) -> Unit,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        LiveOrGatedItem(
            label = stringResource(R.string.explorer_action_open_with),
            capable = hasExternalUri,
            gateMessage = stringResource(R.string.explorer_open_with_gated),
            onAction = { onDismiss(); onOpenWith() },
            onGated = { onDismiss(); onExplainGated(it) },
        )
        LiveOrGatedItem(
            label = stringResource(R.string.explorer_action_share),
            capable = hasExternalUri && !entry.isDirectory,
            gateMessage = stringResource(R.string.explorer_share_gated),
            onAction = { onDismiss(); onShare() },
            onGated = { onDismiss(); onExplainGated(it) },
        )
        LiveOrGatedItem(
            label = stringResource(R.string.explorer_action_open_in_terminal),
            capable = canOpenInTerminal,
            gateMessage = stringResource(R.string.explorer_open_in_terminal_gated),
            onAction = { onDismiss(); onOpenInTerminal() },
            onGated = { onDismiss(); onExplainGated(it) },
        )
        // Open as project — cross-surface hand-off to the Editor, which has no wired hook here.
        GatedItem(
            label = stringResource(R.string.explorer_action_open_as_project),
            onClick = { onDismiss(); onExplainGated(context.getString(R.string.explorer_open_as_project_gated)) },
        )

        HorizontalDivider()

        if (canWrite) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explorer_action_rename)) },
                onClick = { onDismiss(); onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explorer_action_delete)) },
                onClick = { onDismiss(); onDelete() },
            )
            if (!entry.isDirectory && ZipArchiver.isZip(entry.name)) {
                LiveOrGatedItem(
                    label = stringResource(R.string.explorer_action_extract),
                    capable = localMount,
                    gateMessage = stringResource(R.string.explorer_extract_gated),
                    onAction = { onDismiss(); onExtract() },
                    onGated = { onDismiss(); onExplainGated(it) },
                )
            }
            LiveOrGatedItem(
                label = stringResource(R.string.explorer_action_compress),
                capable = localMount,
                gateMessage = stringResource(R.string.explorer_compress_gated),
                onAction = { onDismiss(); onCompress() },
                onGated = { onDismiss(); onExplainGated(it) },
            )
        }

        DropdownMenuItem(
            text = { Text(stringResource(R.string.explorer_action_properties)) },
            onClick = { onDismiss(); onProperties() },
        )
    }
}

@Composable
private fun LiveOrGatedItem(
    label: String,
    capable: Boolean,
    gateMessage: String,
    onAction: () -> Unit,
    onGated: (String) -> Unit,
) {
    if (capable) {
        DropdownMenuItem(text = { Text(label) }, onClick = onAction)
    } else {
        GatedItem(label = label, onClick = { onGated(gateMessage) })
    }
}

@Composable
private fun GatedItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = MasamuneTheme.semantic.dim) },
        leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null, tint = MasamuneTheme.semantic.dim) },
        onClick = onClick,
    )
}

/** The pop-up that explains why a gated action is inert. Mirrors the terminal context menu's. */
@Composable
fun GatedExplainDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explorer_close)) } },
        icon = { Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.explorer_action_more)) },
        text = { Text(message) },
    )
}

// =====================================================================================
// View settings sheet  (§6 line 117).
// =====================================================================================

@Composable
fun ViewSettingsDialog(
    view: ViewSettings,
    onChange: ((ViewSettings) -> ViewSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explorer_close)) } },
        title = { Text(stringResource(R.string.explorer_view_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
            ) {
                SwitchRow(
                    title = stringResource(R.string.explorer_view_hidden),
                    subtitle = stringResource(R.string.explorer_view_hidden_sub),
                    checked = view.showHidden,
                    enabled = true,
                    onCheckedChange = { on -> onChange { it.copy(showHidden = on) } },
                )
                HorizontalDivider()

                Text(stringResource(R.string.explorer_view_sort_by), style = MaterialTheme.typography.labelLarge)
                SortMode.values().forEach { mode ->
                    RadioRow(
                        label = when (mode) {
                            SortMode.NAME -> stringResource(R.string.explorer_view_sort_name)
                            SortMode.SIZE -> stringResource(R.string.explorer_view_sort_size)
                            SortMode.DATE -> stringResource(R.string.explorer_view_sort_date)
                        },
                        selected = view.sortMode == mode,
                        onSelect = { onChange { it.copy(sortMode = mode) } },
                    )
                }
                RadioRow(
                    label = stringResource(R.string.explorer_view_ascending),
                    selected = view.sortAscending,
                    onSelect = { onChange { it.copy(sortAscending = true) } },
                )
                RadioRow(
                    label = stringResource(R.string.explorer_view_descending),
                    selected = !view.sortAscending,
                    onSelect = { onChange { it.copy(sortAscending = false) } },
                )
                SwitchRow(
                    title = stringResource(R.string.explorer_view_folders_first),
                    subtitle = null,
                    checked = view.foldersFirst,
                    enabled = true,
                    onCheckedChange = { on -> onChange { it.copy(foldersFirst = on) } },
                )
                HorizontalDivider()

                Text(stringResource(R.string.explorer_view_filter), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = view.nameMask,
                    onValueChange = { v -> onChange { it.copy(nameMask = v) } },
                    label = { Text(stringResource(R.string.explorer_view_filter_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.explorer_view_filter_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
                HorizontalDivider()

                // Compact folders + Indexing: no engine — disabled, with the reason named.
                SwitchRow(
                    title = stringResource(R.string.explorer_view_compact),
                    subtitle = stringResource(R.string.explorer_view_compact_gated),
                    checked = false,
                    enabled = false,
                    onCheckedChange = {},
                )
                SwitchRow(
                    title = stringResource(R.string.explorer_view_indexing),
                    subtitle = stringResource(R.string.explorer_view_indexing_gated),
                    checked = false,
                    enabled = false,
                    onCheckedChange = {},
                )
                Text(
                    stringResource(R.string.explorer_view_indexing_stat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MasamuneTheme.semantic.dim,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MasamuneTheme.semantic.dim)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

// =====================================================================================
// Properties sheet  (§6 line 116).
// =====================================================================================

@Composable
fun PropertiesDialog(state: PropertiesState, onDismiss: () -> Unit) {
    val entry = state.entry
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explorer_close)) } },
        title = { Text(stringResource(R.string.explorer_props_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
            ) {
                KeyValueRow(stringResource(R.string.explorer_props_name), entry.name)
                KeyValueRow(stringResource(R.string.explorer_props_path), state.displayPath, mono = true)
                KeyValueRow(
                    stringResource(R.string.explorer_props_type),
                    if (entry.isDirectory) stringResource(R.string.explorer_props_type_folder)
                    else stringResource(R.string.explorer_props_type_file),
                )
                if (entry.isDirectory) {
                    if (state.computing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    } else {
                        KeyValueRow(
                            stringResource(R.string.explorer_props_contents),
                            stringResource(
                                R.string.explorer_props_contents_value,
                                state.childFolders ?: 0,
                                state.childFiles ?: 0,
                            ),
                        )
                    }
                } else {
                    KeyValueRow(
                        stringResource(R.string.explorer_props_size),
                        FileSystemRegistry.humanSize(entry.sizeBytes),
                    )
                    entry.mimeType?.let { KeyValueRow(stringResource(R.string.explorer_props_mime), it) }
                }
                if (entry.lastModified > 0) {
                    KeyValueRow(
                        stringResource(R.string.explorer_props_modified),
                        propsDate.format(Date(entry.lastModified)),
                    )
                }
                HorizontalDivider()
                Notice(
                    title = stringResource(R.string.explorer_props_archive_title),
                    body = stringResource(R.string.explorer_props_archive_absent),
                    tone = NoticeTone.INFO,
                )
            }
        },
    )
}

// =====================================================================================
// Compress dialog  (Amaze "Enter Zip Name").
// =====================================================================================

@Composable
fun CompressDialog(defaultName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_compress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.explorer_compress_name_label)) },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.explorer_compress_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.explorer_compress_title)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explorer_cancel)) } },
    )
}

// =====================================================================================
// Shell action sheet  (§6 line 119: [Run here] | [Run in Termux (RUN_COMMAND)]).
// =====================================================================================

@Composable
fun ShellActionDialog(
    workdir: String?,
    availability: TermuxShellBackend.Availability,
    granted: Boolean,
    run: ShellRunState?,
    onGrant: () -> Unit,
    onRecheck: () -> Unit,
    onRequestPermission: () -> Unit,
    onRun: (String) -> Unit,
    onClose: () -> Unit,
) {
    var command by remember { mutableStateOf("") }
    val ready = availability == TermuxShellBackend.Availability.Ready && granted && workdir != null
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.explorer_close)) } },
        title = { Text(stringResource(R.string.explorer_shell_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
            ) {
                if (workdir == null) {
                    Notice(
                        title = stringResource(R.string.explorer_shell_title),
                        body = stringResource(R.string.explorer_shell_workdir_gated),
                        tone = NoticeTone.BLOCKED,
                    )
                } else {
                    KeyValueRow(stringResource(R.string.explorer_shell_workdir_label), workdir, mono = true)
                }

                when (availability) {
                    TermuxShellBackend.Availability.NotInstalled -> Notice(
                        title = stringResource(R.string.explorer_shell_not_installed_title),
                        body = stringResource(R.string.explorer_shell_not_installed_body),
                        tone = NoticeTone.BLOCKED,
                        actionLabel = stringResource(R.string.explorer_shell_recheck),
                        onAction = onRecheck,
                    )
                    TermuxShellBackend.Availability.PermissionNotGranted -> Notice(
                        title = stringResource(R.string.explorer_shell_perm_title),
                        body = stringResource(R.string.explorer_shell_perm_body),
                        tone = NoticeTone.WARNING,
                        actionLabel = stringResource(R.string.explorer_shell_request_perm),
                        onAction = onRequestPermission,
                    )
                    TermuxShellBackend.Availability.Ready -> Notice(
                        title = stringResource(R.string.explorer_shell_ready_title),
                        body = stringResource(R.string.explorer_shell_ready_body),
                        tone = NoticeTone.SUCCESS,
                    )
                }

                if (!granted) {
                    Notice(
                        title = stringResource(R.string.explorer_shell_cap_title),
                        body = stringResource(R.string.explorer_shell_cap_body),
                        tone = NoticeTone.BLOCKED,
                        actionLabel = stringResource(R.string.explorer_shell_grant),
                        onAction = onGrant,
                    )
                }

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.explorer_shell_command_label)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
                    // Run here — no in-app PTY exists, so this stays disabled and says why.
                    Button(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.explorer_shell_run_here))
                    }
                    Button(
                        onClick = { onRun(command) },
                        enabled = ready && command.isNotBlank() && (run == null || !run.running),
                    ) {
                        Text(stringResource(R.string.explorer_shell_run_termux))
                    }
                }
                Text(
                    stringResource(R.string.explorer_shell_run_here_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )

                run?.let { ShellRunResult(it) }
            }
        },
    )
}

@Composable
private fun ShellRunResult(run: ShellRunState) {
    HorizontalDivider()
    Text(
        "$ ${run.command}",
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.primary,
    )
    when {
        run.running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.explorer_shell_running),
                style = MaterialTheme.typography.labelSmall,
                color = MasamuneTheme.semantic.dim,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        run.failure != null -> Text(
            run.failure,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
        )
        else -> {
            if (run.stdout.isNotBlank()) {
                SelectionContainer {
                    Text(
                        run.stdout.trimEnd(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            if (run.stderr.isNotBlank()) {
                SelectionContainer {
                    Text(
                        run.stderr.trimEnd(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MasamuneTheme.semantic.warning,
                    )
                }
            }
            if (run.stdout.isBlank() && run.stderr.isBlank()) {
                Text(
                    stringResource(R.string.explorer_shell_no_output),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
            Text(
                stringResource(R.string.explorer_shell_exit, run.exitCode ?: 0),
                style = MaterialTheme.typography.labelSmall,
                color = if ((run.exitCode ?: 0) == 0) MasamuneTheme.semantic.success else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val propsDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
