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
import dev.pleiades.masamune.fs.ZipArchiver
import dev.pleiades.masamune.fs.looksTextual
import dev.pleiades.masamune.shell.ShellDispatcher
import dev.pleiades.masamune.shell.TermuxShellBackend
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Clipboard(val fsId: String, val paths: List<String>, val move: Boolean)

/** Sort key for the listing (Amaze "Sort By"). Direction is carried separately. */
enum class SortMode(val label: String) { NAME("Name"), SIZE("Size"), DATE("Date") }

/**
 * The per-listing view settings (DONOR-SURFACES §6 line 117; Amaze "Sort", "Hidden Files").
 *
 * These are applied in the ViewModel over whatever a backend's [FileSystem.list] returned, so the
 * same control drives java.io and SAF mounts identically. [compactFolders] and [indexing] have no
 * engine in this build and are carried only so the sheet can render them as disabled with a sentence
 * naming what is missing — they never silently do nothing.
 */
data class ViewSettings(
    val showHidden: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true,
    val nameMask: String = "",
)

/** What the Properties sheet shows (DONOR-SURFACES §6 line 116; Amaze "Properties"). */
data class PropertiesState(
    val entry: FsEntry,
    val displayPath: String,
    val childFolders: Int? = null,
    val childFiles: Int? = null,
    val computing: Boolean = false,
)

/** State of a single "Run in Termux (RUN_COMMAND)" dispatch launched from the shell action sheet. */
data class ShellRunState(
    val command: String,
    val workdir: String,
    val running: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val failure: String? = null,
)

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
    val view: ViewSettings = ViewSettings(),
    /** True only for a java.io mount: shell cwd and archive create/extract need a real path. */
    val isLocalMount: Boolean = false,
    val shellAvailability: TermuxShellBackend.Availability = TermuxShellBackend.Availability.NotInstalled,
    val shellGranted: Boolean = false,
    val shellRun: ShellRunState? = null,
    val properties: PropertiesState? = null,
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
    private val shell = ShellDispatcher(appContext)

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    val mounts: StateFlow<List<FileSystem>> = registry.mounts

    private var searchJob: Job? = null

    /** Unfiltered, unsorted listing straight from the backend; [applyView] derives what the UI shows. */
    private var rawEntries: List<FsEntry> = emptyList()

    init {
        openMount(registry.default().id)
        refreshShell()
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
            isLocalMount = target.localPathOf(target.rootPath) != null,
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
                rawEntries = backend.list(_state.value.path)
                _state.value = _state.value.copy(
                    entries = applyView(rawEntries, _state.value.view),
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

    // --- view settings  (§6 line 117) --------------------------------------------------

    /**
     * Filters and sorts [raw] per [v]. Hidden-file suppression drops dotfiles; the name mask keeps
     * only entries whose name contains the mask (case-insensitive substring, not a glob — an honest
     * live filter, distinct from the recursive Search). Sort applies the chosen key and direction,
     * with directories floated to the top when [ViewSettings.foldersFirst] is set.
     */
    private fun applyView(raw: List<FsEntry>, v: ViewSettings): List<FsEntry> {
        var out = raw.asSequence()
        if (!v.showHidden) out = out.filter { !it.name.startsWith(".") }
        val mask = v.nameMask.trim().lowercase()
        if (mask.isNotEmpty()) out = out.filter { it.name.lowercase().contains(mask) }
        val keyComparator: Comparator<FsEntry> = when (v.sortMode) {
            SortMode.NAME -> compareBy { it.name.lowercase() }
            SortMode.SIZE -> compareBy { it.sizeBytes }
            SortMode.DATE -> compareBy { it.lastModified }
        }
        val directioned = if (v.sortAscending) keyComparator else keyComparator.reversed()
        val comparator =
            if (v.foldersFirst) compareByDescending<FsEntry> { it.isDirectory }.then(directioned)
            else directioned
        return out.sortedWith(comparator).toList()
    }

    fun setView(transform: (ViewSettings) -> ViewSettings) {
        val next = transform(_state.value.view)
        _state.value = _state.value.copy(view = next, entries = applyView(rawEntries, next))
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

    /**
     * Cross-pane transfer (Total Commander's copy-across-panes, DONOR-SURFACES §6 line 105).
     *
     * The other pane hands us its selection; we load it as our clipboard and paste. Same-mount
     * transfers stream through the backend; a transfer whose source mount differs from this pane's
     * hits the same honest cross-filesystem decline [paste] already enforces — no fabricated bridge.
     */
    fun receiveTransfer(sourceFsId: String, paths: List<String>, move: Boolean) {
        if (paths.isEmpty()) return
        _state.value = _state.value.copy(clipboard = Clipboard(sourceFsId, paths, move))
        paste()
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

    // --- external hand-off  (§6 line 114: open with / share) ---------------------------

    /** The document content URI another app can read, or null for a mount that has none (java.io). */
    fun externalUriOf(entry: FsEntry): Uri? = fs()?.externalUri(entry.path)

    // --- shell action  (§6 line 119: Run here | Run in Termux) -------------------------

    fun refreshShell() {
        _state.value = _state.value.copy(
            shellAvailability = shell.availability(),
            shellGranted = gate.isGranted(Caller.User, Capability.SHELL),
        )
    }

    fun grantShell() {
        gate.grant(Caller.User, Capability.SHELL)
        _state.value = _state.value.copy(shellGranted = true)
    }

    /** The on-disk directory a shell should open in for [entry]: the dir itself, or a file's parent. */
    fun shellWorkdirFor(entry: FsEntry): String? {
        val backend = fs() ?: return null
        return if (entry.isDirectory) backend.localPathOf(entry.path)
        else backend.parentOf(entry.path)?.let { backend.localPathOf(it) }
    }

    /** The current directory's on-disk path, or null on a SAF / remote mount. */
    fun currentLocalPath(): String? = fs()?.localPathOf(_state.value.path)

    /**
     * Runs [command] inside Termux with [workdir] as the working directory, through the gated
     * [ShellDispatcher]. Every honest outcome the dispatcher can report — a capability denial, an
     * absent Termux, a Termux refusal, a timeout — lands in [ShellRunState] verbatim; nothing is a
     * fabricated session.
     */
    fun runHere(command: String, workdir: String) {
        if (command.isBlank()) return
        _state.value = _state.value.copy(
            shellRun = ShellRunState(command = command, workdir = workdir, running = true),
        )
        viewModelScope.launch {
            val run = when (val d = shell.dispatch(Caller.User, command, workdir)) {
                is ShellDispatcher.Dispatch.Ran -> when (val o = d.outcome) {
                    is TermuxShellBackend.Outcome.Completed ->
                        ShellRunState(command, workdir, false, o.stdout, o.stderr, o.exitCode, null)
                    is TermuxShellBackend.Outcome.RefusedByTermux ->
                        ShellRunState(command, workdir, false, failure = "Termux refused the call (err=${o.err}): ${o.errmsg}")
                    is TermuxShellBackend.Outcome.DispatchFailed ->
                        ShellRunState(command, workdir, false, failure = o.message)
                    is TermuxShellBackend.Outcome.TimedOut ->
                        ShellRunState(command, workdir, false, failure = "No result within ${o.afterMillis / 1000}s. The command may still be running inside Termux.")
                }
                is ShellDispatcher.Dispatch.Gated ->
                    ShellRunState(command, workdir, false, failure = d.message)
                is ShellDispatcher.Dispatch.Unavailable -> {
                    refreshShell()
                    ShellRunState(command, workdir, false, failure = "No shell backend to drive (${d.availability::class.simpleName}).")
                }
            }
            _state.value = _state.value.copy(shellRun = run)
        }
    }

    fun clearShellRun() {
        _state.value = _state.value.copy(shellRun = null)
    }

    // --- properties  (§6 line 116) -----------------------------------------------------

    fun openProperties(entry: FsEntry) {
        val backend = fs() ?: return
        _state.value = _state.value.copy(
            properties = PropertiesState(
                entry = entry,
                displayPath = backend.displayPath(entry.path),
                computing = entry.isDirectory,
            ),
        )
        if (!entry.isDirectory) return
        viewModelScope.launch {
            val (folders, files) = try {
                val children = backend.list(entry.path)
                children.count { it.isDirectory } to children.count { !it.isDirectory }
            } catch (_: Exception) {
                0 to 0
            }
            val current = _state.value.properties
            if (current?.entry?.path == entry.path) {
                _state.value = _state.value.copy(
                    properties = current.copy(childFolders = folders, childFiles = files, computing = false),
                )
            }
        }
    }

    fun closeProperties() {
        _state.value = _state.value.copy(properties = null)
    }

    // --- archive  (§6 line 114: compress / unzip) --------------------------------------

    /**
     * Zips the current selection into [archiveName] in the current directory. Local mounts only —
     * the helper needs real java.io paths, and a SAF selection has none, so this is gated off in the
     * UI there and defended here.
     */
    fun compressSelection(archiveName: String) {
        val backend = fs() ?: return
        val destDir = currentLocalPath()?.let { File(it) } ?: run {
            _state.value = _state.value.copy(
                error = "Compress needs on-disk paths. This mount exposes none (SAF / remote), so a " +
                    "zip cannot be written here.",
            )
            return
        }
        val sources = _state.value.entries
            .filter { it.path in _state.value.selection }
            .mapNotNull { backend.localPathOf(it.path)?.let(::File) }
        if (sources.isEmpty()) return
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, "compress ${sources.size} item(s)")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Compressing…", error = null)
            try {
                val out = ZipArchiver.compress(sources, destDir, archiveName) { p ->
                    _state.value = _state.value.copy(busy = "Compressing ${p.entryName} (${p.index}/${p.total})")
                }
                _state.value = _state.value.copy(busy = null, selection = emptySet(), notice = "Wrote ${out.name}")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

    /** Extracts [entry] (a local `.zip`) into a new sibling folder in the current directory. */
    fun extract(entry: FsEntry) {
        val backend = fs() ?: return
        val archive = backend.localPathOf(entry.path)?.let(::File) ?: run {
            _state.value = _state.value.copy(
                error = "Extract needs an on-disk path. This mount exposes none (SAF / remote).",
            )
            return
        }
        val destDir = currentLocalPath()?.let(::File) ?: archive.parentFile ?: return
        val decision = gate.check(Caller.User, Capability.FILE_WRITE, "extract ${entry.name}")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Extracting ${entry.name}…", error = null)
            try {
                val out = ZipArchiver.extract(archive, destDir) { p ->
                    _state.value = _state.value.copy(busy = "Extracting ${p.entryName} (${p.index}/${p.total})")
                }
                _state.value = _state.value.copy(busy = null, notice = "Extracted to ${out.name}")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null, error = renderError(e))
            }
        }
    }

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
