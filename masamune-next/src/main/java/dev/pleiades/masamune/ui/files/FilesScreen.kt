package dev.pleiades.masamune.ui.files

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import dev.pleiades.masamune.R
import dev.pleiades.masamune.fs.FileSystemRegistry
import dev.pleiades.masamune.fs.FsEntry
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.theme.MasamuneTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file explorer. Start destination — this is the first thing the app shows.
 *
 * Everything on a pane goes through the [dev.pleiades.masamune.fs.FileSystem] interface, so the
 * same UI drives the java.io mounts and the SAF mounts with no branching. A future SFTP / SMB /
 * privileged backend appears as another entry in the storage picker.
 *
 * This screen is the Total Commander two-pane host (DONOR-SURFACES §6 line 105): a dual-pane toggle
 * gives a second, fully independent pane (its own path, sort, filter, selection) laid out side by
 * side on a wide screen or one-at-a-time behind a switcher on a phone, with Copy / Move across the
 * two panes. Each pane is a [FilesViewModel] keyed into the store, so the panes never share state.
 */
@Composable
fun FilesScreen() {
    var dual by remember { mutableStateOf(false) }
    var activePane by remember { mutableStateOf(0) }

    val leftVm = keyedFilesViewModel("files-pane-left")
    val rightVm = keyedFilesViewModel("files-pane-right")
    val leftState by leftVm.state.collectAsState()
    val rightState by rightVm.state.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Column(modifier = Modifier.fillMaxSize()) {
            PaneToolbar(
                dual = dual,
                onToggleDual = { dual = !dual; if (!dual) activePane = 0 },
                activePane = activePane,
                onSelectPane = { activePane = it },
                showSwitcher = dual && !wide,
                leftState = leftState,
                rightState = rightState,
                onCopyToOther = { moveIt ->
                    val (src, dst) = if (activePane == 0) leftState to rightVm else rightState to leftVm
                    dst.receiveTransfer(src.fsId, src.selection.toList(), moveIt)
                    (if (activePane == 0) leftVm else rightVm).clearSelection()
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (!dual) {
                FilesPane(leftVm, modifier = Modifier.weight(1f).fillMaxWidth())
            } else if (wide) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    FilesPane(
                        leftVm,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        focused = activePane == 0,
                        onFocus = { activePane = 0 },
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FilesPane(
                        rightVm,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        focused = activePane == 1,
                        onFocus = { activePane = 1 },
                    )
                }
            } else {
                val vm = if (activePane == 0) leftVm else rightVm
                FilesPane(vm, modifier = Modifier.weight(1f).fillMaxWidth(), onFocus = {})
            }
        }
    }
}

/** A [FilesViewModel] pinned to [key] in the store, so two panes stay independent. */
@Composable
private fun keyedFilesViewModel(key: String): FilesViewModel {
    val appContext = LocalContext.current.applicationContext
    return viewModel<FilesViewModel>(
        key = key,
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FilesViewModel(appContext) as T
        },
    )
}

@Composable
private fun PaneToolbar(
    dual: Boolean,
    onToggleDual: () -> Unit,
    activePane: Int,
    onSelectPane: (Int) -> Unit,
    showSwitcher: Boolean,
    leftState: FilesUiState,
    rightState: FilesUiState,
    onCopyToOther: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = dual,
                    onClick = onToggleDual,
                    label = { Text(stringResource(R.string.explorer_dual_pane)) },
                    leadingIcon = { Icon(Icons.Filled.Layers, null, Modifier.size(16.dp)) },
                )
                if (showSwitcher) {
                    FilterChip(
                        selected = activePane == 0,
                        onClick = { onSelectPane(0) },
                        label = { Text(stringResource(R.string.explorer_dual_left)) },
                    )
                    FilterChip(
                        selected = activePane == 1,
                        onClick = { onSelectPane(1) },
                        label = { Text(stringResource(R.string.explorer_dual_right)) },
                    )
                }
            }
            if (dual) {
                val sourceSelection =
                    if (activePane == 0) leftState.selection else rightState.selection
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { onCopyToOther(false) },
                        enabled = sourceSelection.isNotEmpty(),
                    ) { Text(stringResource(R.string.explorer_dual_copy_to_other)) }
                    TextButton(
                        onClick = { onCopyToOther(true) },
                        enabled = sourceSelection.isNotEmpty(),
                    ) { Text(stringResource(R.string.explorer_dual_move_to_other)) }
                }
                Text(
                    stringResource(R.string.explorer_dual_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesPane(
    vm: FilesViewModel,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onFocus: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val mounts by vm.mounts.collectAsState()

    var showStoragePicker by remember { mutableStateOf(false) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var newFileOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var viewSettingsOpen by remember { mutableStateOf(false) }
    var shellOpen by remember { mutableStateOf(false) }
    var shellWorkdir by remember { mutableStateOf<String?>(null) }
    var compressOpen by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    var gatedMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.addSafTree(uri) }
    val shellPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshShell() }

    LaunchedEffect(Unit) { vm.refreshShell() }

    val paneBorder = if (focused) {
        Modifier.padding(2.dp)
    } else {
        Modifier
    }

    Column(modifier = modifier.then(paneBorder)) {

        // --- location bar ----------------------------------------------------------------
        Surface(
            color = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.combinedClickable(onClick = onFocus, onLongClick = onFocus),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onFocus(); vm.navigateUp() }, enabled = !state.atRoot) {
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
                    IconButton(onClick = { onFocus(); showStoragePicker = true }) {
                        Icon(Icons.Filled.SdStorage, contentDescription = "Storage")
                    }
                    IconButton(onClick = { onFocus(); vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    AssistChip(
                        onClick = { onFocus(); searchOpen = true },
                        enabled = state.canFind,
                        label = { Text("Search") },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp)) },
                    )
                    AssistChip(
                        onClick = { onFocus(); viewSettingsOpen = true },
                        label = { Text(stringResource(R.string.explorer_view_settings)) },
                    )
                    AssistChip(
                        onClick = { onFocus(); shellWorkdir = vm.currentLocalPath(); vm.refreshShell(); shellOpen = true },
                        enabled = state.isLocalMount,
                        label = { Text(stringResource(R.string.explorer_shell_chip)) },
                        leadingIcon = { Icon(Icons.Filled.Terminal, null, Modifier.size(16.dp)) },
                    )
                    AssistChip(
                        onClick = { onFocus(); newFolderOpen = true },
                        enabled = state.canWrite,
                        label = { Text("Folder") },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(16.dp)) },
                    )
                    AssistChip(
                        onClick = { onFocus(); newFileOpen = true },
                        enabled = state.canWrite,
                        label = { Text("File") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                    )
                    if (state.clipboard != null) {
                        AssistChip(
                            onClick = { onFocus(); vm.paste() },
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

        // --- selection bar ---------------------------------------------------------------
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
                        onClick = { renameTarget = state.entries.firstOrNull { it.path in state.selection } },
                        enabled = state.canWrite && state.selection.size == 1,
                    ) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename")
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = state.canWrite) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                    Box {
                        IconButton(onClick = { selectionMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.explorer_action_more))
                        }
                        DropdownMenu(expanded = selectionMenuOpen, onDismissRequest = { selectionMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.explorer_action_select_all)) },
                                onClick = { selectionMenuOpen = false; vm.selectAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.explorer_action_share)) },
                                onClick = {
                                    selectionMenuOpen = false
                                    val chosen = state.entries.filter { it.path in state.selection }
                                    launchShare(context, vm, chosen) { gatedMessage = it }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.explorer_action_compress)) },
                                onClick = {
                                    selectionMenuOpen = false
                                    if (state.isLocalMount) compressOpen = true
                                    else gatedMessage = context.getString(R.string.explorer_compress_gated)
                                },
                            )
                        }
                    }
                    TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                }
            }
        }

        // --- messages --------------------------------------------------------------------
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

        // --- listing ---------------------------------------------------------------------
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

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            hasExternalUri = vm.externalUriOf(entry) != null,
                            canOpenInTerminal = vm.shellWorkdirFor(entry) != null,
                            localMount = state.isLocalMount,
                            canWrite = state.canWrite,
                            onClick = {
                                onFocus()
                                if (state.selection.isNotEmpty()) vm.toggleSelection(entry)
                                else vm.navigateTo(entry)
                            },
                            onLongClick = { onFocus(); vm.toggleSelection(entry) },
                            onOpenWith = { launchOpenWith(context, vm, entry) { gatedMessage = it } },
                            onShare = { launchShare(context, vm, listOf(entry)) { gatedMessage = it } },
                            onOpenInTerminal = { shellWorkdir = vm.shellWorkdirFor(entry); vm.refreshShell(); shellOpen = true },
                            onCompress = {
                                if (entry.path !in state.selection) vm.toggleSelection(entry)
                                compressOpen = true
                            },
                            onExtract = { vm.extract(entry) },
                            onProperties = { vm.openProperties(entry) },
                            onRename = { renameTarget = entry },
                            onDelete = {
                                if (entry.path !in state.selection) vm.toggleSelection(entry)
                                confirmDelete = true
                            },
                            onExplainGated = { gatedMessage = it },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    // --- dialogs -----------------------------------------------------------------------

    if (showStoragePicker) {
        StoragePickerDialog(
            mounts = mounts,
            onOpen = { vm.openMount(it); showStoragePicker = false },
            onUnmount = { vm.removeMount(it) },
            onAddSaf = { showStoragePicker = false; treePicker.launch(null) },
            onDismiss = { showStoragePicker = false },
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
                TextButton(onClick = { confirmDelete = false; vm.deleteSelection() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (searchOpen) {
        SearchDialog(
            initialQuery = state.searchQuery,
            displayPath = state.displayPath,
            onSearch = { q -> searchOpen = false; vm.setSearchQuery(q); vm.runSearch() },
            onDismiss = { searchOpen = false },
        )
    }
    if (viewSettingsOpen) {
        ViewSettingsDialog(
            view = state.view,
            onChange = { transform -> vm.setView(transform) },
            onDismiss = { viewSettingsOpen = false },
        )
    }
    if (compressOpen) {
        CompressDialog(
            defaultName = defaultArchiveName(state),
            onConfirm = { name -> compressOpen = false; vm.compressSelection(name) },
            onDismiss = { compressOpen = false },
        )
    }
    if (shellOpen) {
        ShellActionDialog(
            workdir = shellWorkdir,
            availability = state.shellAvailability,
            granted = state.shellGranted,
            run = state.shellRun,
            onGrant = { vm.grantShell() },
            onRecheck = { vm.refreshShell() },
            onRequestPermission = {
                shellPermissionLauncher.launch(dev.pleiades.masamune.shell.TermuxContract.PERMISSION)
            },
            onRun = { cmd -> shellWorkdir?.let { vm.runHere(cmd, it) } },
            onClose = { shellOpen = false; vm.clearShellRun() },
        )
    }
    state.properties?.let { props ->
        PropertiesDialog(state = props, onDismiss = { vm.closeProperties() })
    }
    gatedMessage?.let { message ->
        GatedExplainDialog(message = message, onDismiss = { gatedMessage = null })
    }

    state.viewer?.let { viewer ->
        TextViewerDialog(
            viewer = viewer,
            onClose = { vm.closeViewer() },
            onSave = { vm.saveViewer(it) },
        )
    }
}

private fun defaultArchiveName(state: FilesUiState): String {
    val selected = state.entries.filter { it.path in state.selection }
    return if (selected.size == 1) "${selected.first().name}.zip" else "archive.zip"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FsEntry,
    selected: Boolean,
    selectionActive: Boolean,
    hasExternalUri: Boolean,
    canOpenInTerminal: Boolean,
    localMount: Boolean,
    canWrite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
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
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.explorer_action_more))
            }
            FileRowActionsMenu(
                expanded = menuOpen,
                entry = entry,
                hasExternalUri = hasExternalUri,
                canOpenInTerminal = canOpenInTerminal,
                localMount = localMount,
                canWrite = canWrite,
                onDismiss = { menuOpen = false },
                onOpenWith = onOpenWith,
                onShare = onShare,
                onOpenInTerminal = onOpenInTerminal,
                onCompress = onCompress,
                onExtract = onExtract,
                onProperties = onProperties,
                onRename = onRename,
                onDelete = onDelete,
                onExplainGated = onExplainGated,
            )
        }
    }
}

@Composable
private fun StoragePickerDialog(
    mounts: List<dev.pleiades.masamune.fs.FileSystem>,
    onOpen: (String) -> Unit,
    onUnmount: (String) -> Unit,
    onAddSaf: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                mounts.forEach { mount ->
                    SectionCard(title = mount.displayName, subtitle = mount.boundaryNote) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onOpen(mount.id) }) { Text("Open") }
                            FileSystemRegistry.freeSpaceOf(mount)?.let {
                                Text(
                                    "$it free",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MasamuneTheme.semantic.dim,
                                )
                            }
                            if (mount.id.startsWith("saf:")) {
                                TextButton(onClick = { onUnmount(mount.id) }) { Text("Unmount") }
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
        confirmButton = { TextButton(onClick = onAddSaf) { Text("Add storage (SAF)") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SearchDialog(
    initialQuery: String,
    displayPath: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search under $displayPath") },
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
        confirmButton = { TextButton(onClick = { onSearch(query) }) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
