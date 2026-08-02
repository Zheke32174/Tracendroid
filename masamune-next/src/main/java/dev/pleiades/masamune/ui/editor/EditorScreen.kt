package dev.pleiades.masamune.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.fs.FsEntry
import dev.pleiades.masamune.ui.components.KeyValueRow
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The EDITOR surface (DONOR-SURFACES section 5, Xed — canonical). Bottom nav → Editor.
 *
 * A first-class workspace surface, not the explorer's modal viewer: a tab row over multiple open
 * buffers, an in-buffer find/replace panel, a line-number gutter with jump-to-line, per-buffer
 * undo/redo, a save family, an editor-settings sheet, a command palette and an extra-keys panel.
 * It stands on the same [dev.pleiades.masamune.fs.FileSystem] backend and [CapabilityGate] as the
 * explorer, so reads and writes are gated identically.
 *
 * Every capability this build cannot back is shown and named rather than hidden or faked: syntax
 * highlighting gates on a tree-sitter grammar (absent → plain text, stated), format-on-save gates
 * on a formatter (absent → off, stated), the minimap / sticky scroll / suggestions toggles gate on
 * a code-editor engine (absent → disabled, stated), binary files open read-only with the donor's
 * notice, and the language-server panel is a blocked empty state naming the missing server.
 */
@Composable
fun EditorScreen(
    onOpenDisclaimer: () -> Unit,
    onOpenWelcome: () -> Unit,
    onOpenCapabilities: () -> Unit,
) {
    val vm = masamuneViewModel { ctx -> EditorViewModel(ctx) }
    val state by vm.state.collectAsState()
    val browser by vm.browser.collectAsState()

    var tabMenuFor by remember { mutableStateOf<Int?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var newFileOpen by remember { mutableStateOf(false) }
    var saveAsOpen by remember { mutableStateOf(false) }
    var jumpOpen by remember { mutableStateOf(false) }
    var lspOpen by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf<Int?>(null) }
    // "Close others" gets its own confirm because it can discard several unsaved siblings at
    // once — the keep-index, when there are dirty others to warn about.
    var discardOthersFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { vm.refreshCapabilities() }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- header ------------------------------------------------------------------
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.editor_backend_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        }

        // --- capability gates --------------------------------------------------------
        if (!state.canRead) {
            Notice(
                title = stringResource(R.string.editor_title),
                body = "FILE_READ is not granted to caller \"user\", so no file can be opened. " +
                    "Grant it in the capability matrix; nothing here reads storage until then.",
                tone = NoticeTone.BLOCKED,
                modifier = Modifier.padding(12.dp),
                actionLabel = "Open the capability matrix",
                onAction = onOpenCapabilities,
            )
        }

        // --- toolbar -----------------------------------------------------------------
        EditorToolbar(
            state = state,
            canUndo = vm.canUndo,
            canRedo = vm.canRedo,
            onOpen = { vm.beginOpen() },
            onNew = { newFileOpen = true },
            onSave = { vm.save() },
            onUndo = { vm.undo() },
            onRedo = { vm.redo() },
            onFind = { vm.openFind() },
            onPalette = { paletteOpen = true },
            onSettings = { settingsOpen = true },
        )

        // --- tab row -----------------------------------------------------------------
        if (state.tabs.isNotEmpty()) {
            TabRow(
                state = state,
                onSelect = { vm.activateTab(it) },
                onClose = { idx ->
                    if (state.tabs[idx].dirty) discardPrompt = idx else vm.closeTab(idx)
                },
                onMenu = { tabMenuFor = it },
                menuFor = tabMenuFor,
                onMenuDismiss = { tabMenuFor = null },
                onCloseOthers = {
                    if (vm.dirtyOthers(it).isNotEmpty()) discardOthersFor = it else vm.closeOthers(it)
                    tabMenuFor = null
                },
                onCloseAll = {
                    if (state.anyDirty) discardPrompt = -1 else vm.closeAll()
                    tabMenuFor = null
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

        // --- messages ----------------------------------------------------------------
        state.error?.let {
            Notice(
                title = "Refused or failed",
                body = it,
                tone = NoticeTone.ERROR,
                modifier = Modifier.padding(12.dp),
                actionLabel = stringResource(R.string.editor_dismiss),
                onAction = { vm.dismissError() },
            )
        }
        state.notice?.let {
            Notice(
                title = "Done",
                body = it,
                tone = NoticeTone.SUCCESS,
                modifier = Modifier.padding(horizontal = 12.dp),
                actionLabel = stringResource(R.string.editor_dismiss),
                onAction = { vm.dismissNotice() },
            )
        }

        // --- find / replace ----------------------------------------------------------
        if (state.find.open && state.active != null) {
            FindReplacePanel(vm = vm, state = state)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // --- editor surface OR empty state -------------------------------------------
        val active = state.active
        if (active == null) {
            EditorEmptyState(
                canRead = state.canRead,
                onOpen = { vm.beginOpen() },
                onNew = { newFileOpen = true },
                canWrite = state.canWrite,
            )
        } else {
            EditorSurface(
                vm = vm,
                state = state,
                onJump = { jumpOpen = true },
                onManageServers = { lspOpen = true },
            )
        }
    }

    // --- dialogs ---------------------------------------------------------------------

    if (browser.visible) {
        OpenBrowserDialog(vm = vm, browser = browser)
    }
    if (newFileOpen) {
        NameDialog(
            title = stringResource(R.string.editor_new_file),
            label = stringResource(R.string.editor_file_name),
        ) { name ->
            newFileOpen = false
            if (name != null) vm.newFile(name)
        }
    }
    if (saveAsOpen) {
        NameDialog(
            title = stringResource(R.string.editor_save_as),
            label = stringResource(R.string.editor_file_name),
            initial = state.active?.name.orEmpty(),
        ) { name ->
            saveAsOpen = false
            if (name != null) vm.saveAs(name)
        }
    }
    if (jumpOpen) {
        JumpToLineDialog(
            lineCount = vm.lineCount(),
            onDismiss = { jumpOpen = false },
            onJump = { vm.jumpToLine(it); jumpOpen = false },
        )
    }
    if (settingsOpen) {
        EditorSettingsDialog(vm = vm, state = state, onDismiss = { settingsOpen = false })
    }
    val runGate = stringResource(R.string.editor_run_gate)
    if (paletteOpen) {
        CommandPaletteDialog(
            commands = vm.paletteCommands(),
            onDismiss = { paletteOpen = false },
            onRun = { id ->
                paletteOpen = false
                when (id) {
                    EditorViewModel.CMD_OPEN -> vm.beginOpen()
                    EditorViewModel.CMD_NEW -> newFileOpen = true
                    EditorViewModel.CMD_SAVE -> vm.save()
                    EditorViewModel.CMD_SAVE_AS -> saveAsOpen = true
                    EditorViewModel.CMD_SAVE_ALL -> vm.saveAll()
                    EditorViewModel.CMD_UNDO -> vm.undo()
                    EditorViewModel.CMD_REDO -> vm.redo()
                    EditorViewModel.CMD_FIND -> vm.openFind()
                    EditorViewModel.CMD_JUMP -> jumpOpen = true
                    EditorViewModel.CMD_RUN -> vm.refuseUnavailable("run", runGate)
                    EditorViewModel.CMD_CLOSE -> state.active?.let {
                        val idx = state.activeIndex
                        if (state.tabs[idx].dirty) discardPrompt = idx else vm.closeTab(idx)
                    }
                    EditorViewModel.CMD_DISCLAIMER -> onOpenDisclaimer()
                    EditorViewModel.CMD_WELCOME -> onOpenWelcome()
                }
            },
        )
    }
    if (lspOpen) {
        LspBlockedDialog(onDismiss = { lspOpen = false })
    }
    discardPrompt?.let { idx ->
        val multiple = idx < 0
        AlertDialog(
            onDismissRequest = { discardPrompt = null },
            title = { Text(stringResource(R.string.editor_discard)) },
            text = {
                Text(
                    stringResource(
                        if (multiple) R.string.editor_ask_multiple_unsaved
                        else R.string.editor_ask_unsaved
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (multiple) vm.closeAll() else vm.closeTab(idx)
                    discardPrompt = null
                }) { Text(stringResource(R.string.editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardPrompt = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        )
    }

    discardOthersFor?.let { keep ->
        AlertDialog(
            onDismissRequest = { discardOthersFor = null },
            title = { Text(stringResource(R.string.editor_discard)) },
            text = { Text(stringResource(R.string.editor_ask_multiple_unsaved)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeOthers(keep)
                    discardOthersFor = null
                }) { Text(stringResource(R.string.editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardOthersFor = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        )
    }
}

// --- toolbar -----------------------------------------------------------------------------

@Composable
private fun EditorToolbar(
    state: EditorUiState,
    canUndo: Boolean,
    canRedo: Boolean,
    onOpen: () -> Unit,
    onNew: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onPalette: () -> Unit,
    onSettings: () -> Unit,
) {
    val hasTab = state.active != null
    val editable = state.active?.editable == true
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBtn(Icons.Filled.Folder, stringResource(R.string.editor_open_file), enabled = state.canRead, onOpen)
            ToolBtn(Icons.Filled.Add, stringResource(R.string.editor_new_file), enabled = state.canWrite, onNew)
            ToolBtn(Icons.Filled.Save, stringResource(R.string.editor_save), enabled = hasTab && editable, onSave)
            ToolBtn(Icons.Filled.Undo, stringResource(R.string.editor_undo), enabled = canUndo, onUndo)
            ToolBtn(Icons.Filled.Redo, stringResource(R.string.editor_redo), enabled = canRedo, onRedo)
            ToolBtn(Icons.Filled.Search, stringResource(R.string.editor_search), enabled = hasTab, onFind)
            ToolBtn(Icons.Filled.Code, stringResource(R.string.editor_command_palette), enabled = true, onPalette)
            ToolBtn(Icons.Filled.Settings, stringResource(R.string.editor_settings), enabled = true, onSettings)
        }
    }
}

@Composable
private fun ToolBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label)
    }
}

// --- tab row -----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabRow(
    state: EditorUiState,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMenu: (Int) -> Unit,
    menuFor: Int?,
    onMenuDismiss: () -> Unit,
    onCloseOthers: (Int) -> Unit,
    onCloseAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tabs.forEachIndexed { index, tab ->
            val selected = index == state.activeIndex
            Surface(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(2.dp),
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onSelect(index) },
                                onLongClick = { onMenu(index) },
                            )
                            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (state.settings.showTabIcons) {
                            Icon(
                                if (tab.language.name == EditorLanguage.PLAIN) Icons.Filled.InsertDriveFile
                                else Icons.Filled.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            (if (tab.dirty) "• " else "") + tab.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        IconButton(onClick = { onClose(index) }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.editor_close_this),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = { onMenu(index) }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.editor_actions),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = menuFor == index, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_close_this)) },
                            onClick = { onMenuDismiss(); onClose(index) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_close_others)) },
                            onClick = { onCloseOthers(index) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_close_all)) },
                            onClick = { onCloseAll() },
                        )
                    }
                }
            }
        }
    }
}

// --- editor surface ----------------------------------------------------------------------

@Composable
private fun EditorSurface(
    vm: EditorViewModel,
    state: EditorUiState,
    onJump: () -> Unit,
    onManageServers: () -> Unit,
) {
    val tab = state.active ?: return
    val settings = state.settings

    Column(modifier = Modifier.fillMaxSize()) {

        // read-only / gate notices
        tab.readOnlyReason?.let { reason ->
            val body = when (reason) {
                ReadOnlyReason.BINARY -> stringResource(R.string.editor_binary_file_notice)
                ReadOnlyReason.TRUNCATED ->
                    "Showing the first ${tab.value.text.length} bytes of ${tab.totalBytes}. " +
                        "Editing is disabled for a truncated read so a save cannot destroy the tail."
                ReadOnlyReason.BACKEND_READ_ONLY -> "This mount is read-only, so the file cannot be saved."
            }
            Notice(
                title = if (reason == ReadOnlyReason.BINARY) stringResource(R.string.editor_detect_bin_files)
                else stringResource(R.string.editor_lang),
                body = body,
                tone = if (reason == ReadOnlyReason.BINARY) NoticeTone.BLOCKED else NoticeTone.WARNING,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (!tab.language.grammarPresent) {
            Notice(
                title = stringResource(R.string.editor_highlighting),
                body = if (tab.language.name == EditorLanguage.PLAIN) {
                    "This file type has no known language, so it renders as plain text."
                } else {
                    stringResource(R.string.editor_grammar_absent, tab.language.name)
                },
                tone = NoticeTone.INFO,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        // the buffer + gutter
        BufferView(
            vm = vm,
            tab = tab,
            settings = settings,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // status + extra keys
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusBar(tab = tab, onJump = onJump, onManageServers = onManageServers)
        if (tab.editable) {
            ExtraKeysPanel(vm = vm, settings = settings)
        }
    }
}

@Composable
private fun BufferView(
    vm: EditorViewModel,
    tab: EditorTab,
    settings: EditorSettings,
    modifier: Modifier = Modifier,
) {
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val gutterStyle = textStyle.copy(
        color = MasamuneTheme.semantic.dim,
        textAlign = TextAlign.End,
    )
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val lineCount = tab.value.text.count { it == '\n' } + 1
    val transformation: VisualTransformation =
        if (settings.renderWhitespace) WhitespaceTransformation else VisualTransformation.None

    // Jump-to-line best-effort vertical scroll: when the cursor line changes, bring it into view.
    val cursorLine = tab.value.text.substring(0, tab.value.selection.start.coerceIn(0, tab.value.text.length))
        .count { it == '\n' }
    LaunchedEffect(cursorLine) {
        val targetPx = (cursorLine * 20 * 2.5f).toInt() // approximate line-height in px
        if (targetPx in 1..vScroll.maxValue) vScroll.animateScrollTo(targetPx)
    }

    @Composable
    fun gutter() {
        if (!settings.showLineNumbers) return
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            for (n in 1..lineCount) {
                Text(n.toString(), style = gutterStyle)
            }
        }
    }

    Row(modifier = modifier.verticalScroll(vScroll)) {
        if (settings.showLineNumbers && settings.pinLineNumbers) gutter()
        Box(
            modifier = if (!settings.wordWrap) {
                Modifier.horizontalScroll(hScroll).weight(1f, fill = false)
            } else {
                Modifier.weight(1f)
            }
        ) {
            Row {
                if (settings.showLineNumbers && !settings.pinLineNumbers) gutter()
                BasicTextField(
                    value = tab.value,
                    onValueChange = { vm.onValueChange(it) },
                    readOnly = !tab.editable,
                    textStyle = textStyle,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusBar(tab: EditorTab, onJump: () -> Unit, onManageServers: () -> Unit) {
    val lineCount = tab.value.text.count { it == '\n' } + 1
    val cursorLine = tab.value.text.substring(0, tab.value.selection.start.coerceIn(0, tab.value.text.length))
        .count { it == '\n' } + 1
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.editor_line_of, cursorLine, lineCount),
            style = MaterialTheme.typography.labelSmall,
            color = MasamuneTheme.semantic.dim,
        )
        Text(
            "  ·  " + if (tab.language.name == EditorLanguage.PLAIN) stringResource(R.string.editor_lang_plain)
            else tab.language.name +
                (if (!tab.language.grammarPresent) "  ·  " + stringResource(R.string.editor_grammar_absent_short) else ""),
            style = MaterialTheme.typography.labelSmall,
            color = MasamuneTheme.semantic.dim,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onJump) { Text(stringResource(R.string.editor_jump_to_line)) }
        TextButton(onClick = onManageServers) { Text(stringResource(R.string.editor_language_server)) }
    }
}

@Composable
private fun ExtraKeysPanel(vm: EditorViewModel, settings: EditorSettings) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            KeyChip("⇥") { vm.insertIndent() }
            KeyChip("(") { vm.insertText("(") }
            KeyChip(")") { vm.insertText(")") }
            KeyChip("{") { vm.insertText("{") }
            KeyChip("}") { vm.insertText("}") }
            KeyChip("[") { vm.insertText("[") }
            KeyChip("]") { vm.insertText("]") }
            KeyChip("<") { vm.insertText("<") }
            KeyChip(">") { vm.insertText(">") }
            KeyChip("/") { vm.insertText("/") }
            KeyChip("\\") { vm.insertText("\\") }
            KeyChip("|") { vm.insertText("|") }
            KeyChip(";") { vm.insertText(";") }
            KeyChip(":") { vm.insertText(":") }
            KeyChip("\"") { vm.insertText("\"") }
            KeyChip("'") { vm.insertText("'") }
            KeyChip("-") { vm.insertText("-") }
            KeyChip("=") { vm.insertText("=") }
            IconButton(onClick = { vm.moveCursor(-1) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Left")
            }
            IconButton(onClick = { vm.moveCursor(1) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Right")
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.size(width = 40.dp, height = 36.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

// --- find / replace ----------------------------------------------------------------------

@Composable
private fun FindReplacePanel(vm: EditorViewModel, state: EditorUiState) {
    val f = state.find
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = f.find,
                    onValueChange = { vm.setFindQuery(it) },
                    label = { Text(stringResource(R.string.editor_find)) },
                    singleLine = true,
                    isError = f.error == "invalid_regex",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.findPrevious() }, enabled = f.matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.editor_previous))
                }
                IconButton(onClick = { vm.findNext() }, enabled = f.matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.editor_next))
                }
                IconButton(onClick = { vm.closeFind() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_close))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = f.replace,
                    onValueChange = { vm.setReplaceText(it) },
                    label = { Text(stringResource(R.string.editor_replace)) },
                    singleLine = true,
                    enabled = state.active?.editable == true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.replaceCurrent() }, enabled = f.current >= 0 && state.active?.editable == true) {
                    Text(stringResource(R.string.editor_replace))
                }
                TextButton(onClick = { vm.replaceAll() }, enabled = f.matches.isNotEmpty() && state.active?.editable == true) {
                    Text(stringResource(R.string.editor_replace_all))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(selected = f.ignoreCase, onClick = { vm.toggleIgnoreCase() }, label = { Text(stringResource(R.string.editor_ignore_case)) })
                FilterChip(selected = f.regex, onClick = { vm.toggleRegex() }, label = { Text(stringResource(R.string.editor_regex)) })
                FilterChip(selected = f.wholeWord, onClick = { vm.toggleWholeWord() }, label = { Text(stringResource(R.string.editor_whole_word)) })
            }
            Text(
                when {
                    f.error == "invalid_regex" -> stringResource(R.string.editor_invalid_regex)
                    f.find.isEmpty() -> ""
                    f.matches.isEmpty() -> stringResource(R.string.editor_no_matches)
                    else -> stringResource(R.string.editor_match_count, f.current + 1, f.matches.size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (f.error == "invalid_regex") MaterialTheme.colorScheme.error else MasamuneTheme.semantic.dim,
            )
        }
    }
}

// --- empty state -------------------------------------------------------------------------

@Composable
private fun EditorEmptyState(canRead: Boolean, canWrite: Boolean, onOpen: () -> Unit, onNew: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.editor_no_tabs_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.editor_no_tabs_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MasamuneTheme.semantic.dim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onOpen, enabled = canRead, label = { Text(stringResource(R.string.editor_open_file)) })
                AssistChip(onClick = onNew, enabled = canWrite, label = { Text(stringResource(R.string.editor_new_file)) })
            }
        }
    }
}

// --- open browser ------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OpenBrowserDialog(vm: EditorViewModel, browser: OpenBrowserState) {
    AlertDialog(
        onDismissRequest = { vm.cancelOpen() },
        title = { Text(stringResource(R.string.editor_open_from, browser.fsName)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.browserUp() }, enabled = !browser.atRoot) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.editor_up))
                    }
                    Text(
                        browser.displayPath,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        maxLines = 1,
                    )
                }
                browser.error?.let {
                    Notice(title = "Cannot list", body = it, tone = NoticeTone.ERROR)
                }
                if (browser.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(browser.entries, key = { it.fsId + "|" + it.path }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(onClick = { vm.browserNavigate(entry) }, onLongClick = {})
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(entry.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Text(
                    stringResource(R.string.editor_pick_folder),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.cancelOpen() }) { Text(stringResource(R.string.editor_close)) }
        },
    )
}

// --- jump to line ------------------------------------------------------------------------

@Composable
private fun JumpToLineDialog(lineCount: Int, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_jump_to_line)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { s -> text = s.filter { it.isDigit() } },
                    label = { Text(stringResource(R.string.editor_line_number)) },
                    singleLine = true,
                )
                Text(
                    "1 – $lineCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onJump) },
                enabled = text.toIntOrNull()?.let { it in 1..lineCount } == true,
            ) { Text(stringResource(R.string.editor_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

// --- command palette ---------------------------------------------------------------------

@Composable
private fun CommandPaletteDialog(
    commands: List<PaletteCommand>,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_command_palette)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                commands.forEach { cmd ->
                    DropdownMenuItem(
                        text = { Text(cmd.label, color = if (cmd.enabled) MaterialTheme.colorScheme.onSurface else MasamuneTheme.semantic.dim) },
                        enabled = cmd.enabled,
                        onClick = { onRun(cmd.id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_close)) } },
    )
}

// --- LSP blocked -------------------------------------------------------------------------

@Composable
private fun LspBlockedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_manage_language_servers)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Notice(
                    title = stringResource(R.string.editor_lsp_connection_error),
                    body = stringResource(R.string.editor_lsp_blocked),
                    tone = NoticeTone.BLOCKED,
                )
                Text(
                    stringResource(R.string.editor_go_to_definition) + " · " +
                        stringResource(R.string.editor_go_to_references) + " · " +
                        stringResource(R.string.editor_lsp_diagnostics) + " · " +
                        stringResource(R.string.editor_document_highlight),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_close)) } },
    )
}

// --- settings sheet ----------------------------------------------------------------------

@Composable
private fun EditorSettingsDialog(vm: EditorViewModel, state: EditorUiState, onDismiss: () -> Unit) {
    val s = state.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_settings)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Editor / Editor
                SectionCard(title = stringResource(R.string.editor_section_editor)) {
                    ToggleRow(stringResource(R.string.editor_show_line_number), s.showLineNumbers) { v -> vm.updateSettings { it.copy(showLineNumbers = v) } }
                    ToggleRow(stringResource(R.string.editor_pin_line_number), s.pinLineNumbers, enabled = s.showLineNumbers) { v -> vm.updateSettings { it.copy(pinLineNumbers = v) } }
                    ToggleRow(stringResource(R.string.editor_render_whitespace), s.renderWhitespace) { v -> vm.updateSettings { it.copy(renderWhitespace = v) } }
                    Text(stringResource(R.string.editor_render_whitespace_scope), style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                    StepperRow(stringResource(R.string.editor_tab_size), s.tabSize, EditorSettings.MIN_TAB_SIZE, EditorSettings.MAX_TAB_SIZE) { v -> vm.updateSettings { it.copy(tabSize = v) } }
                    ToggleRow(stringResource(R.string.editor_use_tabs), s.useTabs) { v -> vm.updateSettings { it.copy(useTabs = v) } }
                    GatedToggleRow(stringResource(R.string.editor_show_minimap), stringResource(R.string.editor_minimap_gate))
                    GatedToggleRow(stringResource(R.string.editor_sticky_scroll), stringResource(R.string.editor_sticky_scroll_gate))
                    GatedToggleRow(stringResource(R.string.editor_show_suggestions), stringResource(R.string.editor_suggestions_gate))
                }
                // Editor / Content
                SectionCard(title = stringResource(R.string.editor_section_content)) {
                    ToggleRow(stringResource(R.string.editor_word_wrap), s.wordWrap) { v -> vm.updateSettings { it.copy(wordWrap = v) } }
                    KeyValueRow(stringResource(R.string.editor_default_encoding), EditorSettings.ENCODING)
                    Text(stringResource(R.string.editor_encoding_gate), style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                    GatedToggleRow(stringResource(R.string.editor_editorconfig), stringResource(R.string.editor_editorconfig_gate))
                }
                // Editor / Tabs
                SectionCard(title = stringResource(R.string.editor_section_tabs)) {
                    ToggleRow(stringResource(R.string.editor_smooth_tabs), s.smoothTabs) { v -> vm.updateSettings { it.copy(smoothTabs = v) } }
                    ToggleRow(stringResource(R.string.editor_show_tab_icons), s.showTabIcons) { v -> vm.updateSettings { it.copy(showTabIcons = v) } }
                }
                // Editor / Other
                SectionCard(title = stringResource(R.string.editor_section_other)) {
                    ToggleRow(stringResource(R.string.editor_detect_bin_files), s.detectBinary) { v -> vm.updateSettings { it.copy(detectBinary = v) } }
                    Text(stringResource(R.string.editor_detect_bin_files_desc), style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                    ToggleRow(stringResource(R.string.editor_auto_save), s.autoSave) { v -> vm.updateSettings { it.copy(autoSave = v) } }
                    if (s.autoSave) {
                        Text(stringResource(R.string.editor_auto_save_delay), style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            EditorSettings.AUTO_SAVE_DELAYS.forEach { d ->
                                FilterChip(selected = s.autoSaveDelayMs == d, onClick = { vm.updateSettings { it.copy(autoSaveDelayMs = d) } }, label = { Text("${d}ms") })
                            }
                        }
                    }
                    GatedToggleRow(stringResource(R.string.editor_format_on_save), stringResource(R.string.editor_format_on_save_gate))
                    ToggleRow(stringResource(R.string.editor_restore_sessions), s.restoreSessions) { v -> vm.updateSettings { it.copy(restoreSessions = v) } }
                    Text(stringResource(R.string.editor_restore_sessions_desc), style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                    ToggleRow(stringResource(R.string.editor_auto_open_new_files), s.autoOpenNewFiles) { v -> vm.updateSettings { it.copy(autoOpenNewFiles = v) } }
                    Text(stringResource(R.string.editor_auto_open_new_files_desc), style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                }
                // Actions / extra keys info
                SectionCard(title = stringResource(R.string.editor_actions)) {
                    Text(stringResource(R.string.editor_info_toolbar_actions), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.editor_info_extra_keys), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.editor_actions_reorder_gate), style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_close)) } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MasamuneTheme.semantic.dim)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A toggle whose engine is absent: forced off, non-interactive, with the naming sentence beneath. */
@Composable
private fun GatedToggleRow(label: String, gate: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), color = MasamuneTheme.semantic.dim)
            Switch(checked = false, onCheckedChange = null, enabled = false)
        }
        Text(gate, style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
    }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { if (value > min) onChange(value - 1) }, enabled = value > min) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Decrease")
        }
        Text("$value", style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { if (value < max) onChange(value + 1) }, enabled = value < max) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Increase")
        }
    }
}

// --- name dialog (shared) ----------------------------------------------------------------

@Composable
private fun NameDialog(title: String, label: String, initial: String = "", onResult: (String?) -> Unit) {
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
                Text(stringResource(R.string.editor_ok))
            }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text(stringResource(R.string.editor_cancel)) } },
    )
}
