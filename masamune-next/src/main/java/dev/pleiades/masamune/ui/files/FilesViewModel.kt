package dev.pleiades.masamune.ui.files

import android.content.Context
import android.net.Uri
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
import dev.pleiades.masamune.fs.looksTextual
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Clipboard(val fsId: String, val paths: List<String>, val move: Boolean)

data class ViewerState(
    val entry: FsEntry,
    val text: String,
    val truncated: Boolean,
    val totalBytes: Long,
    val editable: Boolean,
)

data class FilesUiState(
    val fsId: String = "",
    val fsName: String = "",
    val path: String = "",
    val displayPath: String = "/",
    val entries: List<FsEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val selection: Set<String> = emptySet(),
    val clipboard: Clipboard? = null,
    val searchQuery: String = "",
    val searchResults: List<FsEntry> = emptyList(),
    val searching: Boolean = false,
    val searchExamined: Int = 0,
    val viewer: ViewerState? = null,
    val busy: String? = null,
    val canWrite: Boolean = false,
    val canFind: Boolean = false,
    val atRoot: Boolean = true,
)

/**
 * All file-explorer state and every filesystem call the UI makes.
 *
 * The transport is the [FileSystem] interface directly. It is deliberately NOT an AI tool bus:
 * in the donor tree a user tapping a folder produced an `AITool(name = "list_files")` routed
 * through the agent handler. Here the interface owns the operation, and an AI adapter — if one
 * is ever added — would call into the same interface, not the other way round.
 *
 * Every mutation passes through [CapabilityGate] as caller `user`. Denials are surfaced in the
 * UI and recorded in the refusal log; nothing fails silently.
 */
class FilesViewModel(private val appContext: Context) : ViewModel() {

    private val registry = FileSystemRegistry.get(appContext)
    private val gate = CapabilityGate.get(appContext)

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    val mounts: StateFlow<List<FileSystem>> = registry.mounts

    private var searchJob: Job? = null

    init {
        openMount(registry.default().id)
    }

    private fun fs(): FileSystem? = registry.byId(_state.value.fsId)

    // --- navigation --------------------------------------------------------------------

    fun openMount(fsId: String) {
        val target = registry.byId(fsId) ?: return
        _state.value = _state.value.copy(
            fsId = target.id,
            fsName = target.displayName,
            path = target.rootPath,
            selection = emptySet(),
            searchResults = emptyList(),
            searchQuery = "",
            viewer = null,
            canWrite = FsOp.WRITE in target.capabilities,
            canFind = FsOp.FIND in target.capabilities,
        )
        refresh()
    }

    fun navigateTo(entry: FsEntry) {
        if (entry.isDirectory) {
            _state.value = _state.value.copy(path = entry.path, selection = emptySet(), searchResults = emptyList())
            refresh()
        } else {
            openViewer(entry)
        }
    }

    fun navigateUp(): Boolean {
        val backend = fs() ?: return false
        val parent = backend.parentOf(_state.value.path) ?: return false
        _state.value = _state.value.copy(path = parent, selection = emptySet(), searchResults = emptyList())
        refresh()
        return true
    }

    fun refresh() {
        val backend = fs() ?: return
        val decision = gate.check(Caller.User, Capability.FILE_READ, "list ${backend.displayName}")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(loading = false, error = decision.message, entries = emptyList())
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val entries = backend.list(_state.value.path)
                _state.value = _state.value.copy(
                    entries = entries,
                    loading = false,
                    error = null,
                    displayPath = backend.displayPath(_state.value.path),
                    atRoot = backend.parentOf(_state.value.path) == null,
                )
            } catch (e: FsException) {
                _state.value = _state.value.copy(loading = false, error = e.message, entries = emptyList())
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                    entries = emptyList(),
                )
            }
        }
    }

    // --- selection ---------------------------------------------------------------------

    fun toggleSelection(entry: FsEntry) {
        val current = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (entry.path in current) current - entry.path else current + entry.path,
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    fun selectAll() {
        _state.value = _state.value.copy(selection = _state.value.entries.map { it.path }.toSet())
    }

    // --- clipboard ---------------------------------------------------------------------

    fun copySelection(move: Boolean) {
        val s = _state.value
        if (s.selection.isEmpty()) return
        _state.value = s.copy(
            clipboard = Clipboard(s.fsId, s.selection.toList(), move),
            selection = emptySet(),
            notice = "${s.selection.size} item(s) ${if (move) "cut" else "copied"}.",
        )
    }

    fun clearClipboard() {
        _state.value = _state.value.copy(clipboard = null)
    }

    fun paste() {
        val s = _state.value
        val clip = s.clipboard ?: return
        val backend = fs() ?: return
        if (clip.fsId != s.fsId) {
            val msg = "Cross-filesystem paste is not implemented. The clipboard holds items " +
                "from a different mount, and this build has no stream bridge between backends."
            DeclineRegistry.record(
                Decline(
                    callerTag = Caller.User.tag,
                    capability = Capability.FILE_WRITE,
                    reason = Decline.Reason.BACKEND_UNSUPPORTED,
                    detail = msg,
                    operation = "paste",
                )
            )
            _state.value = s.copy(error = msg)
            return
        }
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, "paste into ${s.displayPath}")
        if (decision is GateDecision.Denied) {
            _state.value = s.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Pasting…", error = null)
            try {
                for (src in clip.paths) {
                    if (clip.move) {
                        backend.move(src, _state.value.path) { name ->
                            _state.value = _state.value.copy(busy = "Moving $name")
                        }
                    } else {
                        backend.copy(src, _state.value.path) { name ->
                            _state.value = _state.value.copy(busy = "Copying $name")
                        }
                    }
                }
                _state.value = _state.value.copy(
                    busy = null,
                    clipboard = if (clip.move) null else clip,
                    notice = "Pasted ${clip.paths.size} item(s).",
                )
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    // --- mutations ---------------------------------------------------------------------

    fun createDirectory(name: String) = mutate("create folder \"$name\"") { backend ->
        backend.mkdir(_state.value.path, name)
    }

    fun createFile(name: String) = mutate("create file \"$name\"") { backend ->
        backend.createFile(_state.value.path, name)
    }

    fun rename(entry: FsEntry, newName: String) = mutate("rename ${entry.name}") { backend ->
        backend.rename(entry.path, newName)
    }

    fun deleteSelection() {
        val targets = _state.value.entries.filter { it.path in _state.value.selection }
        if (targets.isEmpty()) return
        mutate("delete ${targets.size} item(s)") { backend ->
            targets.forEach { backend.delete(it.path) }
            _state.value = _state.value.copy(selection = emptySet())
        }
    }

    private fun mutate(what: String, block: suspend (FileSystem) -> Unit) {
        val backend = fs() ?: return
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, what)
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = what, error = null)
            try {
                block(backend)
                _state.value = _state.value.copy(busy = null, notice = "Done: $what")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    // --- viewer ------------------------------------------------------------------------

    fun openViewer(entry: FsEntry) {
        val backend = fs() ?: return
        val decision = gate.check(Caller.User, Capability.FILE_READ, "read ${entry.name}")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        if (!entry.looksTextual() && entry.sizeBytes > MAX_BLIND_READ) {
            _state.value = _state.value.copy(
                error = "\"${entry.name}\" is ${entry.sizeBytes} bytes and is not a text type. " +
                    "This build has a text viewer only — no image, media or archive viewer.",
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Reading ${entry.name}", error = null)
            try {
                val read = backend.readText(entry.path)
                _state.value = _state.value.copy(
                    busy = null,
                    viewer = ViewerState(
                        entry = entry,
                        text = read.text,
                        truncated = read.truncated,
                        totalBytes = read.totalBytes,
                        editable = FsOp.WRITE in backend.capabilities && !read.truncated,
                    ),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    fun closeViewer() {
        _state.value = _state.value.copy(viewer = null)
    }

    fun saveViewer(text: String) {
        val viewer = _state.value.viewer ?: return
        val backend = fs() ?: return
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, "save ${viewer.entry.name}")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Saving ${viewer.entry.name}", error = null)
            try {
                backend.writeText(viewer.entry.path, text)
                _state.value = _state.value.copy(
                    busy = null,
                    viewer = viewer.copy(text = text),
                    notice = "Saved ${viewer.entry.name}",
                )
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    // --- search ------------------------------------------------------------------------

    fun setSearchQuery(q: String) {
        _state.value = _state.value.copy(searchQuery = q)
    }

    fun runSearch() {
        val backend = fs() ?: return
        val query = _state.value.searchQuery
        if (query.isBlank()) return
        val decision = gate.check(Caller.User, Capability.FILE_READ, "search for \"$query\"")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, searchResults = emptyList(), error = null)
            val found = ArrayList<FsEntry>()
            try {
                val examined = backend.find(_state.value.path, query, maxResults = 300) { entry ->
                    found.add(entry)
                    if (found.size % 10 == 0) {
                        _state.value = _state.value.copy(searchResults = found.toList())
                    }
                }
                _state.value = _state.value.copy(
                    searching = false,
                    searchResults = found.toList(),
                    searchExamined = examined,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(searching = false, error = renderError(e))
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(searchQuery = "", searchResults = emptyList(), searching = false)
    }

    // --- mounts ------------------------------------------------------------------------

    fun addSafTree(uri: Uri) {
        try {
            val fs = registry.addSafTree(uri)
            openMount(fs.id)
            _state.value = _state.value.copy(notice = "Mounted \"${fs.displayName}\".")
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = renderError(e))
        }
    }

    fun removeMount(fsId: String) {
        registry.removeSafTree(fsId)
        if (_state.value.fsId == fsId) openMount(registry.default().id)
    }

    fun boundaryNoteFor(fsId: String): String = registry.byId(fsId)?.boundaryNote.orEmpty()

    // --- misc --------------------------------------------------------------------------

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun renderError(e: Exception): String = when (e) {
        is FsException -> e.message ?: "Filesystem error."
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private companion object {
        const val MAX_BLIND_READ = 256 * 1024L
    }
}
