package dev.pleiades.masamune.ui.shell

import dev.pleiades.masamune.shell.EnvironmentProbes
import dev.pleiades.masamune.shell.TermuxShellBackend

/**
 * UI-facing data model for the Terminal surface.
 *
 * A "session" here is deliberately not a live PTY — the RUN_COMMAND contract this build drives is
 * one-shot and background, and every native tree that could host a real terminal emulator is an
 * empty submodule. What a session *is*, honestly, is a named run-group: its own working directory,
 * its own transcript, and (for a failsafe session) a clean-environment dispatch. The surface says
 * so in as many words; it never dresses a run-group up as an interactive shell.
 */

/** One entry in a session transcript: a command and, once it returns, its honest outcome. */
data class ShellTranscriptEntry(
    val id: Long,
    val command: String,
    val workdir: String,
    val failsafe: Boolean,
    /** True while the dispatch is still outstanding; the block renders a spinner. */
    val running: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    /** Non-null when the call never produced output: refused, timed out, or undeliverable. */
    val failure: String?,
    val at: Long = System.currentTimeMillis(),
)

/** A named run-group. Failsafe sessions dispatch under `bash --norc --noprofile`. */
data class ShellSession(
    val id: Long,
    val name: String,
    val workdir: String,
    val failsafe: Boolean,
    val transcript: List<ShellTranscriptEntry> = emptyList(),
)

/** Lifecycle of a background dispatch, mirrored from the backend's outcome shapes. */
enum class ShellJobState { RUNNING, COMPLETED, REFUSED, DISPATCH_FAILED, TIMED_OUT }

/**
 * A background job: one dispatch tracked independently of the composer, so a long command no
 * longer freezes input for up to two minutes. The id is the same id carried by the transcript
 * entry it produced, so "read output" jumps straight to it.
 */
data class ShellJob(
    val id: Long,
    val sessionId: Long,
    val sessionName: String,
    val command: String,
    val workdir: String,
    val failsafe: Boolean,
    val state: ShellJobState,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    /** Short one-line summary for a non-completed job (refusal, dispatch failure, timeout). */
    val failure: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)

/**
 * Environments panel state. Every field is either a parsed probe result or a loading/error flag —
 * there is no field the UI could render as a live capability we cannot back.
 */
data class EnvState(
    val checklist: List<EnvironmentProbes.ToolStatus> = emptyList(),
    val checklistLoading: Boolean = false,
    val checklistError: String? = null,

    val health: List<EnvironmentProbes.HealthStatus> = emptyList(),
    val healthLoading: Boolean = false,
    val healthError: String? = null,

    val packages: List<EnvironmentProbes.InstalledPackage> = emptyList(),
    val packagesLoading: Boolean = false,
    val packagesError: String? = null,
    val diskUsage: String? = null,
    val packageOpRunning: Boolean = false,
    val packageOpResult: String? = null,

    /** null => proot-distro presence not yet probed; empty list => present but no distros parsed. */
    val prootDistroPresent: Boolean? = null,
    val rootfs: List<EnvironmentProbes.DistroEntry> = emptyList(),
    val rootfsLoading: Boolean = false,

    /** null => ~/.termux/boot does not exist yet (or not yet probed with [bootProbed] false). */
    val bootTasks: List<EnvironmentProbes.BootTask>? = null,
    val bootProbed: Boolean = false,
    val bootLoading: Boolean = false,
    val bootError: String? = null,
    /** Non-null while the boot-task editor is open (new or editing an existing script). */
    val bootEdit: BootEditState? = null,

    val backupRunning: Boolean = false,
    val backupResult: String? = null,
)

/** Open state of the boot-task editor. [existing] locks the name field when editing a script. */
data class BootEditState(val name: String, val body: String, val existing: Boolean)

/** Which full-surface panel is currently shown over the terminal, if any. */
enum class ShellPanel { TERMINAL, ENVIRONMENTS, JOBS }

data class ShellUiState(
    val availability: TermuxShellBackend.Availability = TermuxShellBackend.Availability.NotInstalled,
    val capabilityGranted: Boolean = false,
    val sessions: List<ShellSession> = emptyList(),
    val activeSessionId: Long = 0L,
    val jobs: List<ShellJob> = emptyList(),
    val gateMessage: String? = null,
    val panel: ShellPanel = ShellPanel.TERMINAL,
    val env: EnvState = EnvState(),
) {
    val activeSession: ShellSession?
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()

    val runningJobCount: Int get() = jobs.count { it.state == ShellJobState.RUNNING }
}
