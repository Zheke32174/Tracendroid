package dev.pleiades.masamune.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.fs.FileSystemRegistry
import dev.pleiades.masamune.fs.FsEntry
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file explorer. Start destination — this is the first thing the app shows.
 *
 * Everything on this screen goes through the [dev.pleiades.masamune.fs.FileSystem] interface,
 * so the same UI drives the java.io mounts and the SAF mounts with no branching. A future
 * SFTP / SMB / privileged backend appears here as another entry in the storage picker.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen() {
    val vm = masamuneViewModel { ctx -> FilesViewModel(ctx) }
    val state by vm.state.collectAsState()
    val mounts by vm.mounts.collectAsState()

    var showStoragePicker by remember { mutableStateOf(false) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var newFileOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.addSafTree(uri) }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- location bar --------------------------------------------------------------
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { vm.navigateUp() },
                        enabled = !state.atRoot,
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Up")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.fsName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            state.displayPath,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { showStoragePicker = true }) {
                        Icon(Icons.Filled.SdStorage, contentDescription = "Storage")
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { searchOpen = true },
                        enabled = state.canFind,
                        label = { Text("Search") },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp)) },
                    )
                    AssistChip(
                        onClick = { newFolderOpen = true },
                        enabled = state.canWrite,
                        label = { Text("Folder") },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(16.dp)) },
                    )
                    AssistChip(
                        onClick = { newFileOpen = true },
                        enabled = state.canWrite,
                        label = { Text("File") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                    )
                    if (state.clipboard != null) {
                        AssistChip(
                            onClick = { vm.paste() },
                            label = { Text("Paste ${state.clipboard!!.paths.size}") },
                            leadingIcon = { Icon(Icons.Filled.ContentPaste, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }

        if (state.busy != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                state.busy!!,
                style = MaterialTheme.typography.labelSmall,
                color = MasamuneTheme.semantic.dim,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        // --- selection bar -------------------------------------------------------------
        if (state.selection.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${state.selection.size} selected", modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.copySelection(move = false) }, enabled = state.canWrite) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = { vm.copySelection(move = true) }, enabled = state.canWrite) {
                        Icon(Icons.Filled.ContentCut, contentDescription = "Cut")
                    }
                    IconButton(
                        onClick = {
                            renameTarget = state.entries.firstOrNull { it.path in state.selection }
                        },
                        enabled = state.canWrite && state.selection.size == 1,
                    ) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename")
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = state.canWrite) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                    TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                }
            }
        }

        // --- messages ------------------------------------------------------------------
        state.error?.let { err ->
            Notice(
                title = "Operation refused or failed",
                body = err,
                tone = NoticeTone.ERROR,
                modifier = Modifier.padding(12.dp),
                actionLabel = "Dismiss",
                onAction = { vm.dismissError() },
            )
        }
        state.notice?.let { note ->
            Notice(
                title = "Done",
                body = note,
                tone = NoticeTone.SUCCESS,
                modifier = Modifier.padding(horizontal = 12.dp),
                actionLabel = "Dismiss",
                onAction = { vm.dismissNotice() },
            )
        }

        // --- listing -------------------------------------------------------------------
        val shown = if (state.searchResults.isNotEmpty()) state.searchResults else state.entries
        if (state.searchResults.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.searchResults.size} match(es) for \"${state.searchQuery}\" " +
                        "(${state.searchExamined} entries examined)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.clearSearch() }) { Text("Clear") }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (shown.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (state.error != null) "Nothing to show." else "Empty directory.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        vm.boundaryNoteFor(state.fsId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MasamuneTheme.semantic.dim,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.fsId + "|" + it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            selected = entry.path in state.selection,
                            selectionActive = state.selection.isNotEmpty(),
                            onClick = {
                                if (state.selection.isNotEmpty()) vm.toggleSelection(entry)
                                else vm.navigateTo(entry)
                            },
                            onLongClick = { vm.toggleSelection(entry) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    // --- dialogs -----------------------------------------------------------------------

    if (showStoragePicker) {
        AlertDialog(
            onDismissRequest = { showStoragePicker = false },
            title = { Text("Storage") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    mounts.forEach { mount ->
                        SectionCard(
                            title = mount.displayName,
                            subtitle = mount.boundaryNote,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    vm.openMount(mount.id)
                                    showStoragePicker = false
                                }) { Text("Open") }
                                FileSystemRegistry.freeSpaceOf(mount)?.let {
                                    Text(
                                        "$it free",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MasamuneTheme.semantic.dim,
                                    )
                                }
                                if (mount.id.startsWith("saf:")) {
                                    TextButton(onClick = { vm.removeMount(mount.id) }) {
                                        Text("Unmount")
                                    }
                                }
                            }
                        }
                    }
                    Notice(
                        title = "Backends implemented in this build",
                        body = "java.io and SAF document trees only. SFTP, WebDAV, SMB, " +
                            "installed-apps and a Yojimbo-brokered privileged backend are " +
                            "designed for — they are further implementations of the same " +
                            "FileSystem interface — but none of them are written yet.",
                        tone = NoticeTone.INFO,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showStoragePicker = false
                    treePicker.launch(null)
                }) { Text("Add storage (SAF)") }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePicker = false }) { Text("Close") }
            },
        )
    }

    if (newFolderOpen) {
        NameDialog("New folder", "Folder name") { name ->
            newFolderOpen = false
            if (name != null) vm.createDirectory(name)
        }
    }
    if (newFileOpen) {
        NameDialog("New file", "File name") { name ->
            newFileOpen = false
            if (name != null) vm.createFile(name)
        }
    }
    renameTarget?.let { target ->
        NameDialog("Rename", "New name for ${target.name}", initial = target.name) { name ->
            renameTarget = null
            if (name != null) vm.rename(target, name)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${state.selection.size} item(s)?") },
            text = { Text("Directories are removed recursively. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelection()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
    if (searchOpen) {
        var query by remember { mutableStateOf(state.searchQuery) }
        AlertDialog(
            onDismissRequest = { searchOpen = false },
            title = { Text("Search under ${state.displayPath}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Name contains") },
                        singleLine = true,
                    )
                    Text(
                        "Recursive name match, case-insensitive, capped at 300 results. " +
                            "Content grep is not implemented.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MasamuneTheme.semantic.dim,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    searchOpen = false
                    vm.setSearchQuery(query)
                    vm.runSearch()
                }) { Text("Search") }
            },
            dismissButton = {
                TextButton(onClick = { searchOpen = false }) { Text("Cancel") }
            },
        )
    }

    state.viewer?.let { viewer ->
        TextViewerDialog(
            viewer = viewer,
            onClose = { vm.closeViewer() },
            onSave = { vm.saveViewer(it) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FsEntry,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionActive) {
            Checkbox(checked = selected, onCheckedChange = { onLongClick() })
        }
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                buildString {
                    if (!entry.isDirectory) append(FileSystemRegistry.humanSize(entry.sizeBytes))
                    if (entry.lastModified > 0) {
                        if (isNotEmpty()) append("  ·  ")
                        append(dateFormat.format(Date(entry.lastModified)))
                    }
                    entry.mimeType?.let {
                        if (isNotEmpty()) append("  ·  ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String = "",
    onResult: (String?) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onResult(value.trim()) }, enabled = value.isNotBlank()) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun TextViewerDialog(
    viewer: ViewerState,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(viewer.entry.path) { mutableStateOf(viewer.text) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(viewer.entry.name, maxLines = 1) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (viewer.truncated) {
                    Notice(
                        title = "Truncated",
                        body = "Showing the first ${text.length} bytes of " +
                            "${viewer.totalBytes}. Editing is disabled for truncated reads so a " +
                            "save cannot destroy the tail.",
                        tone = NoticeTone.WARNING,
                    )
                }
                if (viewer.editable) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                            .copy(fontFamily = FontFamily.Monospace),
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (viewer.editable) {
                Button(onClick = { onSave(text) }) { Text("Save") }
            } else {
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
        dismissButton = {
            if (viewer.editable) TextButton(onClick = onClose) { Text("Close") }
        },
    )
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
