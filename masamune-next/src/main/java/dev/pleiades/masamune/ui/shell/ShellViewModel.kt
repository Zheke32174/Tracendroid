package dev.pleiades.masamune.ui.shell

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.shell.EnvironmentProbes
import dev.pleiades.masamune.shell.ShellDispatcher
import dev.pleiades.masamune.shell.TermuxContract
import dev.pleiades.masamune.shell.TermuxShellBackend
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Terminal surface: named run-groups (sessions), a non-blocking background-job
 * registry, and the Environments probes — all over the single gated [ShellDispatcher].
 *
 * No PTY, no bundled shell, no rootfs are emulated here; those would each need native code and
 * every native tree in this repository is empty. What is real is faithfully backed: sessions are
 * run-groups, jobs are correlated dispatches, and every Environments row is a `command -v`/`pkg`/
 * `proot-distro` probe whose absent result the panel reports as absent.
 */
class ShellViewModel(appContext: Context) : ViewModel() {

    private val dispatcher = ShellDispatcher(appContext)
    private val gate = CapabilityGate.get(appContext)
    private val ids = AtomicLong(1L)

    val designName: String = dispatcher.designName

    private val _state = MutableStateFlow(
        ShellUiState(
            availability = dispatcher.availability(),
            capabilityGranted = gate.isGranted(Caller.User, Capability.SHELL),
            sessions = listOf(ShellSession(id = 1L, name = "session 1", workdir = TermuxContract.HOME, failsafe = false)),
            activeSessionId = 1L,
        )
    )
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    init { ids.set(2L) }

    // ---------------------------------------------------------------------------------------------
    // Availability / capability
    // ---------------------------------------------------------------------------------------------

    fun refreshAvailability() {
        _state.value = _state.value.copy(
            availability = dispatcher.availability(),
            capabilityGranted = gate.isGranted(Caller.User, Capability.SHELL),
        )
    }

    fun grantShellCapability() {
        gate.grant(Caller.User, Capability.SHELL)
        _state.value = _state.value.copy(capabilityGranted = true, gateMessage = null)
    }

    // ---------------------------------------------------------------------------------------------
    // Sessions  (§4 line 78)
    // ---------------------------------------------------------------------------------------------

    fun newSession() {
        val id = ids.getAndIncrement()
        val name = "session ${_state.value.sessions.size + 1}"
        val session = ShellSession(id, name, TermuxContract.HOME, failsafe = false)
        _state.value = _state.value.copy(
            sessions = _state.value.sessions + session,
            activeSessionId = id,
            panel = ShellPanel.TERMINAL,
        )
    }

    /** A clean-environment session — Termux's own "Failsafe" answer to a broken `~/.bashrc`. */
    fun newFailsafeSession() {
        val id = ids.getAndIncrement()
        val session = ShellSession(id, "failsafe", TermuxContract.HOME, failsafe = true)
        _state.value = _state.value.copy(
            sessions = _state.value.sessions + session,
            activeSessionId = id,
            panel = ShellPanel.TERMINAL,
        )
    }

    fun setActiveSession(id: Long) {
        _state.value = _state.value.copy(activeSessionId = id, panel = ShellPanel.TERMINAL)
    }

    fun renameSession(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updateSession(id) { it.copy(name = trimmed) }
    }

    /**
     * Closes a run-group. This drops the transcript from view; it does not — and honestly cannot —
     * kill anything still executing inside Termux, because a background RUN_COMMAND returns no live
     * process handle. The never-empty invariant holds: closing the last session opens a fresh one.
     */
    fun killSession(id: Long) {
        val remaining = _state.value.sessions.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val fresh = ShellSession(ids.getAndIncrement(), "session 1", TermuxContract.HOME, false)
            _state.value = _state.value.copy(sessions = listOf(fresh), activeSessionId = fresh.id)
            return
        }
        val active = if (_state.value.activeSessionId == id) remaining.first().id else _state.value.activeSessionId
        _state.value = _state.value.copy(sessions = remaining, activeSessionId = active)
    }

    fun setWorkdir(dir: String) {
        updateSession(_state.value.activeSessionId) { it.copy(workdir = dir) }
    }

    /** Reset (donor "Reset"): clears the active session's transcript, nothing else. */
    fun clearTranscript() {
        updateSession(_state.value.activeSessionId) { it.copy(transcript = emptyList()) }
    }

    // ---------------------------------------------------------------------------------------------
    // Non-blocking dispatch + background jobs  (§4 line 91)
    // ---------------------------------------------------------------------------------------------

    fun run(commandLine: String) {
        if (commandLine.isBlank()) return
        val session = _state.value.activeSession ?: return
        dispatch(session, commandLine, session.workdir, session.failsafe)
    }

    /** Insert a common token/path from the text-input toolbar as its own dispatch is NOT done here;
     *  the toolbar only edits the composer text. This method exists for the Environments panel and
     *  context-menu shortcuts that need to run a canned line in the active session. */
    fun runInActiveSession(commandLine: String) = run(commandLine)

    private fun dispatch(session: ShellSession, commandLine: String, workdir: String, failsafe: Boolean) {
        val jobId = ids.getAndIncrement()
        val now = System.currentTimeMillis()

        val entry = ShellTranscriptEntry(
            id = jobId,
            command = commandLine,
            workdir = workdir,
            failsafe = failsafe,
            running = true,
            stdout = "",
            stderr = "",
            exitCode = null,
            failure = null,
            at = now,
        )
        val job = ShellJob(
            id = jobId,
            sessionId = session.id,
            sessionName = session.name,
            command = commandLine,
            workdir = workdir,
            failsafe = failsafe,
            state = ShellJobState.RUNNING,
            exitCode = null,
            stdout = "",
            stderr = "",
            failure = null,
            startedAt = now,
            finishedAt = null,
        )
        updateSession(session.id) { it.copy(transcript = it.transcript + entry) }
        _state.value = _state.value.copy(jobs = listOf(job) + _state.value.jobs, gateMessage = null)

        viewModelScope.launch {
            val dispatch = dispatcher.dispatch(Caller.User, commandLine, workdir, failsafe)
            applyDispatch(session.id, jobId, dispatch)
        }
    }

    /** Folds a dispatch outcome into the matching transcript entry and job, in place. */
    private fun applyDispatch(sessionId: Long, jobId: Long, dispatch: ShellDispatcher.Dispatch) {
        val finishedAt = System.currentTimeMillis()
        when (dispatch) {
            is ShellDispatcher.Dispatch.Ran ->
                applyOutcome(sessionId, jobId, dispatch.outcome, null, finishedAt)
            // Same rendering, plus the banner naming WHICH root answered. Carried into the
            // transcript rather than shown once and lost, so scrolling back still says it.
            is ShellDispatcher.Dispatch.RanAsContainerRoot ->
                applyOutcome(sessionId, jobId, dispatch.outcome, dispatch.note, finishedAt)
            is ShellDispatcher.Dispatch.Gated -> {
                _state.value = _state.value.copy(gateMessage = dispatch.message)
                updateEntry(sessionId, jobId) { it.copy(running = false, failure = dispatch.message) }
                updateJob(jobId) { it.copy(state = ShellJobState.DISPATCH_FAILED, failure = dispatch.message, finishedAt = finishedAt) }
            }
            is ShellDispatcher.Dispatch.Unavailable -> {
                _state.value = _state.value.copy(availability = dispatch.availability)
                val msg = "No shell backend to drive (${dispatch.availability::class.simpleName})."
                updateEntry(sessionId, jobId) { it.copy(running = false, failure = msg) }
                updateJob(jobId) { it.copy(state = ShellJobState.DISPATCH_FAILED, failure = msg, finishedAt = finishedAt) }
            }
        }
    }

    /**
     * Folds one backend outcome in. [note], when present, is prepended to whatever the command
     * itself said — on stderr for a completed run, ahead of the message for a failed one — so the
     * qualification travels with the result instead of being a separate thing a reader can miss.
     */
    private fun applyOutcome(
        sessionId: Long,
        jobId: Long,
        outcome: TermuxShellBackend.Outcome,
        note: String?,
        finishedAt: Long,
    ) {
        fun annotate(text: String): String =
            if (note == null) text else listOf(note, text).filter { it.isNotBlank() }.joinToString("\n\n")

        when (outcome) {
            is TermuxShellBackend.Outcome.Completed -> {
                val stderr = annotate(outcome.stderr)
                updateEntry(sessionId, jobId) {
                    it.copy(running = false, stdout = outcome.stdout, stderr = stderr, exitCode = outcome.exitCode)
                }
                updateJob(jobId) {
                    it.copy(state = ShellJobState.COMPLETED, exitCode = outcome.exitCode, stdout = outcome.stdout, stderr = stderr, finishedAt = finishedAt)
                }
            }
            is TermuxShellBackend.Outcome.RefusedByTermux -> {
                val msg = annotate("Termux refused the call (err=${outcome.err}): ${outcome.errmsg}")
                updateEntry(sessionId, jobId) { it.copy(running = false, failure = msg) }
                updateJob(jobId) { it.copy(state = ShellJobState.REFUSED, failure = msg, finishedAt = finishedAt) }
            }
            is TermuxShellBackend.Outcome.DispatchFailed -> {
                val msg = annotate(outcome.message)
                updateEntry(sessionId, jobId) { it.copy(running = false, failure = msg) }
                updateJob(jobId) { it.copy(state = ShellJobState.DISPATCH_FAILED, failure = msg, finishedAt = finishedAt) }
            }
            is TermuxShellBackend.Outcome.TimedOut -> {
                val msg = annotate(
                    "No result came back within ${outcome.afterMillis / 1000}s. " +
                        "The command may still be running."
                )
                updateEntry(sessionId, jobId) { it.copy(running = false, failure = msg) }
                updateJob(jobId) { it.copy(state = ShellJobState.TIMED_OUT, failure = msg, finishedAt = finishedAt) }
            }
        }
    }

    /**
     * Drops a job from the registry. This stops tracking it here; per the honest-gating rule it does
     * NOT kill a running RUN_COMMAND, which the one-shot contract cannot cleanly cancel — the UI's
     * [Stop] control stays disabled and says so. Dismiss is offered only for that reason.
     */
    fun dismissJob(id: Long) {
        _state.value = _state.value.copy(jobs = _state.value.jobs.filterNot { it.id == id })
    }

    fun clearFinishedJobs() {
        _state.value = _state.value.copy(jobs = _state.value.jobs.filter { it.state == ShellJobState.RUNNING })
    }

    // ---------------------------------------------------------------------------------------------
    // Panels
    // ---------------------------------------------------------------------------------------------

    fun openPanel(panel: ShellPanel) {
        _state.value = _state.value.copy(panel = panel)
        when (panel) {
            ShellPanel.ENVIRONMENTS -> if (_state.value.env.checklist.isEmpty()) recheckChecklist()
            else -> Unit
        }
    }

    fun closePanel() {
        _state.value = _state.value.copy(panel = ShellPanel.TERMINAL)
    }

    // ---------------------------------------------------------------------------------------------
    // Environments  (§4 lines 84-90)
    // ---------------------------------------------------------------------------------------------

    fun recheckChecklist() {
        updateEnv { it.copy(checklistLoading = true, checklistError = null) }
        viewModelScope.launch {
            when (val r = probe(EnvironmentProbes.checklistScript())) {
                is Probe.Ok -> updateEnv {
                    it.copy(checklistLoading = false, checklist = EnvironmentProbes.parseChecklist(r.stdout))
                }
                is Probe.Err -> updateEnv { it.copy(checklistLoading = false, checklistError = r.message) }
            }
        }
    }

    fun runHealthCheck() {
        updateEnv { it.copy(healthLoading = true, healthError = null) }
        viewModelScope.launch {
            when (val r = probe(EnvironmentProbes.healthScript())) {
                is Probe.Ok -> updateEnv {
                    it.copy(healthLoading = false, health = EnvironmentProbes.parseHealth(r.stdout))
                }
                is Probe.Err -> updateEnv { it.copy(healthLoading = false, healthError = r.message) }
            }
        }
    }

    fun loadPackages() {
        updateEnv { it.copy(packagesLoading = true, packagesError = null) }
        viewModelScope.launch {
            when (val r = probe(EnvironmentProbes.installedPackagesScript())) {
                is Probe.Ok -> updateEnv {
                    it.copy(packagesLoading = false, packages = EnvironmentProbes.parseInstalledPackages(r.stdout))
                }
                is Probe.Err -> updateEnv { it.copy(packagesLoading = false, packagesError = r.message) }
            }
            when (val d = probe(EnvironmentProbes.diskUsageScript())) {
                is Probe.Ok -> updateEnv { it.copy(diskUsage = d.stdout.trim().ifBlank { null }) }
                is Probe.Err -> Unit
            }
        }
    }

    fun installPackage(name: String) = packageOp(EnvironmentProbes.installScript(name.trim()), "install ${name.trim()}")
    fun upgradeAllPackages() = packageOp(EnvironmentProbes.upgradeAllScript(), "upgrade")
    fun removePackage(name: String) = packageOp(EnvironmentProbes.removeScript(name), "remove $name")

    private fun packageOp(command: String, label: String) {
        if (command.isBlank()) return
        updateEnv { it.copy(packageOpRunning = true, packageOpResult = null) }
        viewModelScope.launch {
            val result = when (val r = probe(command)) {
                is Probe.Ok -> "$label: exit ${r.exitCode}${if (r.stderr.isNotBlank()) " — ${r.stderr.trim().take(200)}" else ""}"
                is Probe.Err -> "$label failed: ${r.message}"
            }
            updateEnv { it.copy(packageOpRunning = false, packageOpResult = result) }
            loadPackages()
        }
    }

    fun probeProotDistro() {
        updateEnv { it.copy(rootfsLoading = true) }
        viewModelScope.launch {
            val present = when (val p = probe(EnvironmentProbes.prootDistroPresentScript())) {
                is Probe.Ok -> p.stdout.trim() == "PRESENT"
                is Probe.Err -> false
            }
            if (!present) {
                updateEnv { it.copy(rootfsLoading = false, prootDistroPresent = false, rootfs = emptyList()) }
                return@launch
            }
            when (val r = probe(EnvironmentProbes.prootDistroListScript())) {
                is Probe.Ok -> updateEnv {
                    it.copy(rootfsLoading = false, prootDistroPresent = true, rootfs = EnvironmentProbes.parseDistroList(r.stdout))
                }
                is Probe.Err -> updateEnv { it.copy(rootfsLoading = false, prootDistroPresent = true, rootfs = emptyList()) }
            }
        }
    }

    fun loadBootTasks() {
        updateEnv { it.copy(bootLoading = true, bootError = null) }
        viewModelScope.launch {
            when (val r = probe(EnvironmentProbes.bootTasksScript())) {
                is Probe.Ok -> updateEnv {
                    it.copy(bootLoading = false, bootProbed = true, bootTasks = EnvironmentProbes.parseBootTasks(r.stdout))
                }
                is Probe.Err -> updateEnv { it.copy(bootLoading = false, bootProbed = true, bootError = r.message) }
            }
        }
    }

    fun runBootTask(name: String) = run(EnvironmentProbes.runBootTaskScript(name))

    fun deleteBootTask(name: String) {
        viewModelScope.launch {
            probe(EnvironmentProbes.deleteBootTaskScript(name))
            loadBootTasks()
        }
    }

    fun setBootTaskEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            probe(EnvironmentProbes.setBootTaskEnabledScript(name, enabled))
            loadBootTasks()
        }
    }

    fun beginNewBootTask() {
        updateEnv {
            it.copy(bootEdit = BootEditState("", "#!/data/data/com.termux/files/usr/bin/sh\n", existing = false))
        }
    }

    /** Opens the editor pre-filled with an existing script's body, read via `cat`. */
    fun beginEditBootTask(name: String) {
        viewModelScope.launch {
            val body = when (val r = probe(EnvironmentProbes.readBootTaskScript(name))) {
                is Probe.Ok -> r.stdout
                is Probe.Err -> ""
            }
            updateEnv { it.copy(bootEdit = BootEditState(name, body, existing = true)) }
        }
    }

    fun cancelBootEdit() {
        updateEnv { it.copy(bootEdit = null) }
    }

    fun saveBootTask(name: String, contentBase64: String) {
        viewModelScope.launch {
            probe(EnvironmentProbes.writeBootTaskScript(name.trim(), contentBase64))
            updateEnv { it.copy(bootEdit = null) }
            loadBootTasks()
        }
    }

    fun runBackup() {
        updateEnv { it.copy(backupRunning = true, backupResult = null) }
        viewModelScope.launch {
            val result = when (val r = probe(EnvironmentProbes.backupScript())) {
                is Probe.Ok ->
                    if (r.exitCode == 0 && r.stdout.isNotBlank()) "Wrote ${r.stdout.trim()}"
                    else "Backup exit ${r.exitCode}${if (r.stderr.isNotBlank()) " — ${r.stderr.trim().take(200)}" else ""}"
                is Probe.Err -> "Backup failed: ${r.message}"
            }
            updateEnv { it.copy(backupRunning = false, backupResult = result) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Probe plumbing
    // ---------------------------------------------------------------------------------------------

    private sealed class Probe {
        data class Ok(val stdout: String, val stderr: String, val exitCode: Int) : Probe()
        data class Err(val message: String) : Probe()
    }

    private suspend fun probe(command: String): Probe =
        when (val d = dispatcher.dispatch(Caller.User, command)) {
            is ShellDispatcher.Dispatch.Ran -> probeOutcome(d.outcome)
            // The Environments panel's probes are `command -v`-style lines, so this branch is
            // reached only if a probe ever asks for root. It reports the container's answer as the
            // container's — the panel must not read a container-root success as a device fact.
            is ShellDispatcher.Dispatch.RanAsContainerRoot -> when (val p = probeOutcome(d.outcome)) {
                is Probe.Ok -> Probe.Ok(p.stdout, listOf(d.note, p.stderr).filter { it.isNotBlank() }.joinToString("\n\n"), p.exitCode)
                is Probe.Err -> Probe.Err("${d.note}\n\n${p.message}")
            }
            is ShellDispatcher.Dispatch.Gated -> Probe.Err(d.message)
            is ShellDispatcher.Dispatch.Unavailable -> {
                _state.value = _state.value.copy(availability = d.availability)
                Probe.Err("No shell backend to drive.")
            }
        }

    private fun probeOutcome(o: TermuxShellBackend.Outcome): Probe = when (o) {
        is TermuxShellBackend.Outcome.Completed -> Probe.Ok(o.stdout, o.stderr, o.exitCode)
        is TermuxShellBackend.Outcome.RefusedByTermux ->
            Probe.Err("Termux refused the call (err=${o.err}): ${o.errmsg}")
        is TermuxShellBackend.Outcome.DispatchFailed -> Probe.Err(o.message)
        is TermuxShellBackend.Outcome.TimedOut ->
            Probe.Err("No result within ${o.afterMillis / 1000}s.")
    }

    // ---------------------------------------------------------------------------------------------
    // Immutable-state helpers
    // ---------------------------------------------------------------------------------------------

    private fun updateSession(id: Long, transform: (ShellSession) -> ShellSession) {
        _state.value = _state.value.copy(
            sessions = _state.value.sessions.map { if (it.id == id) transform(it) else it },
        )
    }

    private fun updateEntry(sessionId: Long, entryId: Long, transform: (ShellTranscriptEntry) -> ShellTranscriptEntry) {
        updateSession(sessionId) { s ->
            s.copy(transcript = s.transcript.map { if (it.id == entryId) transform(it) else it })
        }
    }

    private fun updateJob(id: Long, transform: (ShellJob) -> ShellJob) {
        _state.value = _state.value.copy(
            jobs = _state.value.jobs.map { if (it.id == id) transform(it) else it },
        )
    }

    private fun updateEnv(transform: (EnvState) -> EnvState) {
        _state.value = _state.value.copy(env = transform(_state.value.env))
    }
}
