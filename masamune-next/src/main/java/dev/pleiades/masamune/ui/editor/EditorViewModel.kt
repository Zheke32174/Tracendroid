package dev.pleiades.masamune.ui.editor

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.core.capability.GateDecision
import dev.pleiades.masamune.core.decline.Decline
import dev.pleiades.masamune.core.decline.DeclineRegistry
import dev.pleiades.masamune.fs.FileSystem
import dev.pleiades.masamune.fs.FileSystemRegistry
import dev.pleiades.masamune.fs.FsEntry
import dev.pleiades.masamune.fs.FsException
import dev.pleiades.masamune.fs.FsOp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the built-in "open a file" browser. Reuses the FileSystem interface, like the explorer. */
data class OpenBrowserState(
    val visible: Boolean = false,
    val fsId: String = "",
    val fsName: String = "",
    val path: String = "",
    val displayPath: String = "/",
    val entries: List<FsEntry> = emptyList(),
    val atRoot: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Every piece of editor logic and every filesystem call the editor makes.
 *
 * Transport is the [FileSystem] interface directly (java.io + SAF), the same backend and the same
 * [CapabilityGate] the explorer uses — the editor is a second consumer of that layer, not a new
 * one. Reads pass FILE_READ; saves pass FILE_WRITE; a denial surfaces verbatim and is logged to
 * the refusal log. Capabilities this build cannot back (a formatter, a language server, a minimap
 * engine, a tree-sitter grammar) are refused with a named [Decline], never faked.
 */
class EditorViewModel(private val appContext: Context) : ViewModel() {

    private val registry = FileSystemRegistry.get(appContext)
    private val gate = CapabilityGate.get(appContext)
    private val store = EditorStore(appContext)
    private val grammars = SyntaxGrammarProbe(appContext)

    private val _state = MutableStateFlow(
        EditorUiState(
            settings = store.loadSettings(),
            canRead = gate.isGranted(Caller.User, Capability.FILE_READ),
            canWrite = gate.isGranted(Caller.User, Capability.FILE_WRITE),
        )
    )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _browser = MutableStateFlow(OpenBrowserState())
    val browser: StateFlow<OpenBrowserState> = _browser.asStateFlow()

    private var autoSaveJob: Job? = null

    init {
        restorePreviousTabs()
    }

    // --- capability readout ------------------------------------------------------------

    fun refreshCapabilities() {
        _state.value = _state.value.copy(
            canRead = gate.isGranted(Caller.User, Capability.FILE_READ),
            canWrite = gate.isGranted(Caller.User, Capability.FILE_WRITE),
        )
    }

    // --- session restore ---------------------------------------------------------------

    private fun restorePreviousTabs() {
        val saved = store.loadSession()
        if (saved.isEmpty()) return
        viewModelScope.launch {
            for ((fsId, path) in saved) {
                val backend = registry.byId(fsId) ?: continue
                openPath(backend, path, activate = false, silent = true)
            }
            if (_state.value.tabs.isNotEmpty() && _state.value.activeIndex < 0) {
                _state.value = _state.value.copy(activeIndex = 0)
            }
        }
    }

    private fun persistSession() {
        store.saveSession(_state.value.tabs.map { it.fsId to it.path })
    }

    // --- open browser ------------------------------------------------------------------

    fun beginOpen() {
        val backend = registry.default()
        _browser.value = OpenBrowserState(
            visible = true,
            fsId = backend.id,
            fsName = backend.displayName,
            path = backend.rootPath,
        )
        browserList()
    }

    fun cancelOpen() {
        _browser.value = OpenBrowserState()
    }

    fun browserOpenMount(fsId: String) {
        val backend = registry.byId(fsId) ?: return
        _browser.value = _browser.value.copy(fsId = backend.id, fsName = backend.displayName, path = backend.rootPath)
        browserList()
    }

    fun browserNavigate(entry: FsEntry) {
        if (entry.isDirectory) {
            _browser.value = _browser.value.copy(path = entry.path)
            browserList()
        } else {
            val backend = registry.byId(_browser.value.fsId) ?: return
            cancelOpen()
            openFile(backend, entry)
        }
    }

    fun browserUp() {
        val backend = registry.byId(_browser.value.fsId) ?: return
        val parent = backend.parentOf(_browser.value.path) ?: return
        _browser.value = _browser.value.copy(path = parent)
        browserList()
    }

    private fun browserList() {
        val backend = registry.byId(_browser.value.fsId) ?: return
        val decision = gate.check(Caller.User, Capability.FILE_READ, "list ${backend.displayName}")
        if (decision is GateDecision.Denied) {
            _browser.value = _browser.value.copy(error = decision.message, entries = emptyList())
            return
        }
        viewModelScope.launch {
            _browser.value = _browser.value.copy(loading = true, error = null)
            try {
                val entries = backend.list(_browser.value.path)
                _browser.value = _browser.value.copy(
                    entries = entries,
                    loading = false,
                    displayPath = backend.displayPath(_browser.value.path),
                    atRoot = backend.parentOf(_browser.value.path) == null,
                )
            } catch (e: Exception) {
                _browser.value = _browser.value.copy(loading = false, error = renderError(e), entries = emptyList())
            }
        }
    }

    // --- opening files -----------------------------------------------------------------

    private fun openFile(backend: FileSystem, entry: FsEntry) {
        openPath(backend, entry.path, activate = true, silent = false, known = entry)
    }

    /** Reads [path] and installs it as a tab (or focuses the existing one). */
    private fun openPath(
        backend: FileSystem,
        path: String,
        activate: Boolean,
        silent: Boolean,
        known: FsEntry? = null,
    ) {
        val key = backend.id + "|" + path
        val existing = _state.value.tabs.indexOfFirst { it.key == key }
        if (existing >= 0) {
            _state.value = _state.value.copy(activeIndex = existing)
            return
        }
        val decision = gate.check(Caller.User, Capability.FILE_READ, "read $path")
        if (decision is GateDecision.Denied) {
            if (!silent) _state.value = _state.value.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            if (!silent) _state.value = _state.value.copy(busy = "Reading ${known?.name ?: path}", error = null)
            try {
                val entry = known ?: backend.stat(path) ?: throw FsException("Not found: $path")
                val read = backend.readText(path)
                val binary = _state.value.settings.detectBinary && looksBinary(read.text)
                val tab = EditorTab(
                    key = key,
                    fsId = backend.id,
                    path = path,
                    name = entry.name,
                    value = TextFieldValue(read.text),
                    savedText = read.text,
                    language = grammars.languageFor(entry.name),
                    truncated = read.truncated,
                    totalBytes = read.totalBytes,
                    backendWritable = FsOp.WRITE in backend.capabilities,
                    binary = binary,
                )
                val tabs = _state.value.tabs + tab
                _state.value = _state.value.copy(
                    tabs = tabs,
                    activeIndex = if (activate || _state.value.activeIndex < 0) tabs.lastIndex else _state.value.activeIndex,
                    busy = null,
                    notice = if (silent) _state.value.notice else "Tab opened",
                )
                persistSession()
            } catch (e: Exception) {
                if (!silent) _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    // --- tab management ----------------------------------------------------------------

    fun activateTab(index: Int) {
        if (index in _state.value.tabs.indices) {
            cancelAutoSave()
            _state.value = _state.value.copy(activeIndex = index, find = FindReplaceState())
        }
    }

    /** Closes the tab at [index]. Callers gate the unsaved prompt in the UI; this just removes it. */
    fun closeTab(index: Int) {
        val tabs = _state.value.tabs
        if (index !in tabs.indices) return
        val remaining = tabs.toMutableList().apply { removeAt(index) }
        val newActive = when {
            remaining.isEmpty() -> -1
            index <= _state.value.activeIndex -> (_state.value.activeIndex - 1).coerceAtLeast(0)
            else -> _state.value.activeIndex.coerceAtMost(remaining.lastIndex)
        }
        _state.value = _state.value.copy(tabs = remaining, activeIndex = newActive, find = FindReplaceState())
        persistSession()
    }

    fun closeOthers(index: Int) {
        val keep = _state.value.tabs.getOrNull(index) ?: return
        _state.value = _state.value.copy(tabs = listOf(keep), activeIndex = 0, find = FindReplaceState())
        persistSession()
    }

    fun closeAll() {
        _state.value = _state.value.copy(tabs = emptyList(), activeIndex = -1, find = FindReplaceState())
        persistSession()
    }

    /** Tabs holding unsaved edits, for the discard prompt. */
    fun dirtyTabs(): List<EditorTab> = _state.value.tabs.filter { it.dirty }

    /**
     * The unsaved tabs that "close others" would discard — every dirty tab except the one at
     * [index]. The UI gates the discard prompt on this: without it, "close others" silently
     * threw away unsaved sibling buffers, the one data-loss path the close-this/close-all
     * flows already guard against.
     */
    fun dirtyOthers(index: Int): List<EditorTab> =
        _state.value.tabs.filterIndexed { i, t -> i != index && t.dirty }

    // --- editing + undo/redo -----------------------------------------------------------

    fun onValueChange(new: TextFieldValue) {
        val idx = _state.value.activeIndex
        val tab = _state.value.tabs.getOrNull(idx) ?: return
        if (!tab.editable) return
        if (new.text == tab.value.text) {
            // Selection/cursor move only — no history entry.
            updateTab(idx) { it.copy(value = new) }
            return
        }
        updateTab(idx) {
            val pushed = (it.undo + it.value).takeLast(UNDO_LIMIT)
            it.copy(value = new, undo = pushed, redo = emptyList())
        }
        scheduleAutoSave()
    }

    fun undo() {
        val idx = _state.value.activeIndex
        val tab = _state.value.tabs.getOrNull(idx) ?: return
        val prev = tab.undo.lastOrNull() ?: return
        updateTab(idx) {
            it.copy(
                value = prev,
                undo = it.undo.dropLast(1),
                redo = (it.redo + it.value).takeLast(UNDO_LIMIT),
            )
        }
    }

    fun redo() {
        val idx = _state.value.activeIndex
        val tab = _state.value.tabs.getOrNull(idx) ?: return
        val next = tab.redo.lastOrNull() ?: return
        updateTab(idx) {
            it.copy(
                value = next,
                redo = it.redo.dropLast(1),
                undo = (it.undo + it.value).takeLast(UNDO_LIMIT),
            )
        }
    }

    val canUndo: Boolean get() = _state.value.active?.undo?.isNotEmpty() == true
    val canRedo: Boolean get() = _state.value.active?.redo?.isNotEmpty() == true

    // --- extra-keys panel: real cursor-scoped inserts ----------------------------------

    /** Inserts [snippet] at the cursor (replacing any selection), through the normal edit path. */
    fun insertText(snippet: String) {
        val tab = _state.value.active ?: return
        if (!tab.editable) return
        val v = tab.value
        val start = v.selection.min
        val end = v.selection.max
        val newText = v.text.substring(0, start) + snippet + v.text.substring(end)
        val caret = start + snippet.length
        onValueChange(TextFieldValue(newText, TextRange(caret)))
    }

    /** Inserts one indent step, honouring insert-tab-character vs tab-size (both are live here). */
    fun insertIndent() {
        val s = _state.value.settings
        insertText(if (s.useTabs) "\t" else " ".repeat(s.tabSize))
    }

    /** Moves the caret by [delta] characters, clamped to the buffer; no text change. */
    fun moveCursor(delta: Int) {
        val idx = _state.value.activeIndex
        val tab = _state.value.tabs.getOrNull(idx) ?: return
        val pos = (tab.value.selection.start + delta).coerceIn(0, tab.value.text.length)
        updateTab(idx) { it.copy(value = it.value.copy(selection = TextRange(pos))) }
    }

    // --- save family -------------------------------------------------------------------

    fun save() {
        val tab = _state.value.active ?: return
        saveTab(tab)
    }

    fun saveAll() {
        val dirty = dirtyTabs()
        if (dirty.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            for (tab in dirty) if (writeTab(tab)) ok++
            _state.value = _state.value.copy(notice = "Saved $ok file(s)")
        }
    }

    /** Save As: writes the active buffer to a new file [newName] in the same directory. */
    fun saveAs(newName: String) {
        val tab = _state.value.active ?: return
        val backend = registry.byId(tab.fsId) ?: return
        val parent = backend.parentOf(tab.path) ?: run {
            _state.value = _state.value.copy(error = "This file has no parent directory to save into.")
            return
        }
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, "save as \"$newName\"")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Saving $newName", error = null)
            try {
                val newPath = backend.createFile(parent, newName)
                backend.writeText(newPath, tab.value.text)
                val newKey = backend.id + "|" + newPath
                updateTab(_state.value.activeIndex) {
                    it.copy(
                        key = newKey,
                        path = newPath,
                        name = newName,
                        savedText = it.value.text,
                        language = grammars.languageFor(newName),
                        truncated = false,
                        totalBytes = it.value.text.toByteArray().size.toLong(),
                    )
                }
                _state.value = _state.value.copy(busy = null, notice = "Saved $newName")
                persistSession()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    private fun saveTab(tab: EditorTab) {
        viewModelScope.launch {
            if (writeTab(tab)) {
                _state.value = _state.value.copy(notice = "Saved ${tab.name}")
            }
        }
    }

    /** Performs one write with a full gate check; returns whether it succeeded. */
    private suspend fun writeTab(tab: EditorTab): Boolean {
        if (!tab.editable) {
            _state.value = _state.value.copy(error = "\"${tab.name}\" is read-only and cannot be saved.")
            return false
        }
        val backend = registry.byId(tab.fsId) ?: return false
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, "save ${tab.name}")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return false
        }
        return try {
            _state.value = _state.value.copy(busy = "Saving ${tab.name}", error = null)
            backend.writeText(tab.path, tab.value.text)
            val idx = _state.value.tabs.indexOfFirst { it.key == tab.key }
            if (idx >= 0) updateTab(idx) { it.copy(savedText = tab.value.text) }
            _state.value = _state.value.copy(busy = null)
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = null, error = renderError(e))
            false
        }
    }

    // --- auto save ---------------------------------------------------------------------

    private fun scheduleAutoSave() {
        if (!_state.value.settings.autoSave) return
        cancelAutoSave()
        val delayMs = _state.value.settings.autoSaveDelayMs.toLong()
        autoSaveJob = viewModelScope.launch {
            delay(delayMs)
            val tab = _state.value.active ?: return@launch
            if (tab.dirty && tab.editable) writeTab(tab)
        }
    }

    private fun cancelAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    // --- new file ----------------------------------------------------------------------

    /** Creates an empty file in the active tab's directory (or the default root) and opens it. */
    fun newFile(name: String) {
        val anchor = _state.value.active
        val backend = anchor?.let { registry.byId(it.fsId) } ?: registry.default()
        val parent = anchor?.let { backend.parentOf(it.path) } ?: backend.rootPath
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, "create file \"$name\"")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Creating $name", error = null)
            try {
                val newPath = backend.createFile(parent, name)
                _state.value = _state.value.copy(busy = null)
                if (_state.value.settings.autoOpenNewFiles) {
                    openPath(backend, newPath, activate = true, silent = false)
                } else {
                    _state.value = _state.value.copy(notice = "Created $name")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    // --- find / replace ----------------------------------------------------------------

    fun openFind() {
        _state.value = _state.value.copy(find = _state.value.find.copy(open = true))
        recomputeMatches()
    }

    fun closeFind() {
        _state.value = _state.value.copy(find = FindReplaceState())
    }

    fun setFindQuery(q: String) {
        _state.value = _state.value.copy(find = _state.value.find.copy(find = q))
        recomputeMatches()
    }

    fun setReplaceText(r: String) {
        _state.value = _state.value.copy(find = _state.value.find.copy(replace = r))
    }

    fun toggleIgnoreCase() {
        _state.value = _state.value.copy(find = _state.value.find.copy(ignoreCase = !_state.value.find.ignoreCase))
        recomputeMatches()
    }

    fun toggleRegex() {
        _state.value = _state.value.copy(find = _state.value.find.copy(regex = !_state.value.find.regex))
        recomputeMatches()
    }

    fun toggleWholeWord() {
        _state.value = _state.value.copy(find = _state.value.find.copy(wholeWord = !_state.value.find.wholeWord))
        recomputeMatches()
    }

    private fun buildPattern(f: FindReplaceState): Regex? {
        if (f.find.isEmpty()) return null
        val base = if (f.regex) f.find else Regex.escape(f.find)
        val worded = if (f.wholeWord) "\\b(?:$base)\\b" else base
        val opts = if (f.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return runCatching { Regex(worded, opts) }.getOrNull()
    }

    private fun recomputeMatches() {
        val f = _state.value.find
        val text = _state.value.active?.value?.text
        if (text == null || f.find.isEmpty()) {
            _state.value = _state.value.copy(find = f.copy(matches = emptyList(), current = -1, error = null))
            return
        }
        if (f.regex && buildPattern(f) == null) {
            _state.value = _state.value.copy(find = f.copy(matches = emptyList(), current = -1, error = "invalid_regex"))
            return
        }
        val pattern = buildPattern(f)
        if (pattern == null) {
            _state.value = _state.value.copy(find = f.copy(matches = emptyList(), current = -1, error = null))
            return
        }
        val ranges = pattern.findAll(text).map { it.range.first..(it.range.last + 1) }.toList()
        val cursor = _state.value.active?.value?.selection?.start ?: 0
        val firstAfter = ranges.indexOfFirst { it.first >= cursor }
        _state.value = _state.value.copy(
            find = f.copy(
                matches = ranges,
                current = if (ranges.isEmpty()) -1 else if (firstAfter >= 0) firstAfter else 0,
                error = null,
            )
        )
        if (ranges.isNotEmpty()) selectMatch(_state.value.find.current)
    }

    fun findNext() = stepMatch(+1)

    fun findPrevious() = stepMatch(-1)

    private fun stepMatch(dir: Int) {
        val f = _state.value.find
        if (f.matches.isEmpty()) return
        val next = ((f.current + dir) % f.matches.size + f.matches.size) % f.matches.size
        _state.value = _state.value.copy(find = f.copy(current = next))
        selectMatch(next)
    }

    private fun selectMatch(index: Int) {
        val idx = _state.value.activeIndex
        val range = _state.value.find.matches.getOrNull(index) ?: return
        updateTab(idx) { it.copy(value = it.value.copy(selection = TextRange(range.first, range.last))) }
    }

    fun replaceCurrent() {
        val idx = _state.value.activeIndex
        val tab = _state.value.tabs.getOrNull(idx) ?: return
        val f = _state.value.find
        val range = f.matches.getOrNull(f.current) ?: return
        if (!tab.editable) return
        val text = tab.value.text
        val replacement = f.replace
        val newText = text.substring(0, range.first) + replacement + text.substring(range.last)
        val newCursor = range.first + replacement.length
        updateTab(idx) {
            it.copy(
                value = TextFieldValue(newText, TextRange(newCursor)),
                undo = (it.undo + it.value).takeLast(UNDO_LIMIT),
                redo = emptyList(),
            )
        }
        recomputeMatches()
    }

    fun replaceAll() {
        val idx = _state.value.activeIndex
        val tab = _state.value.tabs.getOrNull(idx) ?: return
        val f = _state.value.find
        if (!tab.editable) return
        val pattern = buildPattern(f)
        if (pattern == null) {
            _state.value = _state.value.copy(find = f.copy(error = if (f.regex) "invalid_regex" else null))
            return
        }
        val count = pattern.findAll(tab.value.text).count()
        if (count == 0) {
            _state.value = _state.value.copy(notice = "No matches")
            return
        }
        val newText = pattern.replace(tab.value.text, Regex.escapeReplacement(f.replace))
        updateTab(idx) {
            it.copy(
                value = TextFieldValue(newText, TextRange(newText.length.coerceAtMost(it.value.selection.start))),
                undo = (it.undo + it.value).takeLast(UNDO_LIMIT),
                redo = emptyList(),
            )
        }
        _state.value = _state.value.copy(notice = "Replaced $count occurrence(s)")
        recomputeMatches()
    }

    // --- jump to line ------------------------------------------------------------------

    /** 1-based line count of the active buffer. */
    fun lineCount(): Int = _state.value.active?.value?.text?.count { it == '\n' }?.plus(1) ?: 0

    fun jumpToLine(line: Int) {
        val idx = _state.value.activeIndex
        val tab = _state.value.tabs.getOrNull(idx) ?: return
        val text = tab.value.text
        if (line < 1) return
        var offset = 0
        var seen = 1
        while (seen < line) {
            val nl = text.indexOf('\n', offset)
            if (nl < 0) { offset = text.length; break }
            offset = nl + 1
            seen++
        }
        updateTab(idx) { it.copy(value = it.value.copy(selection = TextRange(offset))) }
    }

    // --- settings ----------------------------------------------------------------------

    fun updateSettings(transform: (EditorSettings) -> EditorSettings) {
        val next = transform(_state.value.settings)
        _state.value = _state.value.copy(settings = next)
        store.saveSettings(next)
    }

    // --- gated-feature refusals (logged, never faked) ----------------------------------

    /** Records that a payload-absent capability was requested, and surfaces the naming sentence. */
    fun refuseUnavailable(operation: String, detail: String) {
        DeclineRegistry.record(
            Decline(
                callerTag = Caller.User.tag,
                capability = null,
                reason = Decline.Reason.NOT_IMPLEMENTED,
                detail = detail,
                operation = operation,
            )
        )
        _state.value = _state.value.copy(error = detail)
    }

    // --- command palette ---------------------------------------------------------------

    fun paletteCommands(): List<PaletteCommand> {
        val hasTab = _state.value.active != null
        val editable = _state.value.active?.editable == true
        return listOf(
            PaletteCommand(CMD_OPEN, "Open file"),
            PaletteCommand(CMD_NEW, "New file", enabled = _state.value.canWrite),
            PaletteCommand(CMD_SAVE, "Save", enabled = hasTab && editable),
            PaletteCommand(CMD_SAVE_AS, "Save as", enabled = hasTab && editable),
            PaletteCommand(CMD_SAVE_ALL, "Save all", enabled = _state.value.anyDirty),
            PaletteCommand(CMD_UNDO, "Undo", enabled = canUndo),
            PaletteCommand(CMD_REDO, "Redo", enabled = canRedo),
            PaletteCommand(CMD_FIND, "Find / Replace", enabled = hasTab),
            PaletteCommand(CMD_JUMP, "Jump to line", enabled = hasTab),
            PaletteCommand(CMD_RUN, "Run", enabled = hasTab),
            PaletteCommand(CMD_CLOSE, "Close this", enabled = hasTab),
            PaletteCommand(CMD_DISCLAIMER, "Terms of Use & Disclaimer"),
            PaletteCommand(CMD_WELCOME, "Show welcome"),
        )
    }

    // --- misc --------------------------------------------------------------------------

    private fun updateTab(index: Int, transform: (EditorTab) -> EditorTab) {
        val tabs = _state.value.tabs.toMutableList()
        val cur = tabs.getOrNull(index) ?: return
        tabs[index] = transform(cur)
        _state.value = _state.value.copy(tabs = tabs)
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    private fun renderError(e: Exception): String = when (e) {
        is FsException -> e.message ?: "Filesystem error."
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * A quick binary sniff over the decoded head of the file: a NUL means the bytes were never
     * text, and a heavy run of U+FFFD means the UTF-8 decode failed. Either way the file opens
     * read-only with the binary notice rather than corrupting on save.
     */
    private fun looksBinary(text: String): Boolean {
        if (text.isEmpty()) return false
        val head = if (text.length > 4096) text.substring(0, 4096) else text
        if (head.any { it.code == 0 }) return true
        val bad = head.count { it.code == 0xFFFD }
        return bad > head.length / 20
    }

    override fun onCleared() {
        cancelAutoSave()
        super.onCleared()
    }

    companion object {
        private const val UNDO_LIMIT = 200

        const val CMD_OPEN = "open"
        const val CMD_NEW = "new"
        const val CMD_SAVE = "save"
        const val CMD_SAVE_AS = "save_as"
        const val CMD_SAVE_ALL = "save_all"
        const val CMD_UNDO = "undo"
        const val CMD_REDO = "redo"
        const val CMD_FIND = "find"
        const val CMD_JUMP = "jump"
        const val CMD_RUN = "run"
        const val CMD_CLOSE = "close"
        const val CMD_DISCLAIMER = "disclaimer"
        const val CMD_WELCOME = "welcome"
    }
}
