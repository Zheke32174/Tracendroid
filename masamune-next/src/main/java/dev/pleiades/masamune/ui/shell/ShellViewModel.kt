package dev.pleiades.masamune.ui.shell

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.core.capability.GateDecision
import dev.pleiades.masamune.core.decline.Decline
import dev.pleiades.masamune.core.decline.DeclineRegistry
import dev.pleiades.masamune.shell.TermuxContract
import dev.pleiades.masamune.shell.TermuxShellBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One entry in the shell transcript. */
data class ShellTranscriptEntry(
    val command: String,
    val workdir: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    /** Non-null when the call never produced output: refused, timed out, or undeliverable. */
    val failure: String?,
    val at: Long = System.currentTimeMillis(),
)

data class ShellUiState(
    val availability: TermuxShellBackend.Availability = TermuxShellBackend.Availability.NotInstalled,
    val transcript: List<ShellTranscriptEntry> = emptyList(),
    val running: Boolean = false,
    val workdir: String = TermuxContract.HOME,
    val gateMessage: String? = null,
    val capabilityGranted: Boolean = false,
)

/**
 * Drives the one shell design this build ships. No PTY, no bundled shell, no rootfs — those
 * would all need native code, and every native source tree here is empty.
 */
class ShellViewModel(private val appContext: Context) : ViewModel() {

    private val backend = TermuxShellBackend(appContext)
    private val gate = CapabilityGate.get(appContext)

    private val _state = MutableStateFlow(
        ShellUiState(
            availability = backend.availability(),
            capabilityGranted = gate.isGranted(Caller.User, Capability.SHELL),
        )
    )
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    val designName: String = backend.designName

    fun refreshAvailability() {
        _state.value = _state.value.copy(
            availability = backend.availability(),
            capabilityGranted = gate.isGranted(Caller.User, Capability.SHELL),
        )
    }

    fun grantShellCapability() {
        gate.grant(Caller.User, Capability.SHELL)
        _state.value = _state.value.copy(capabilityGranted = true, gateMessage = null)
    }

    fun setWorkdir(dir: String) {
        _state.value = _state.value.copy(workdir = dir)
    }

    fun run(commandLine: String) {
        if (commandLine.isBlank() || _state.value.running) return

        val decision = gate.check(Caller.User, Capability.SHELL, "run \"$commandLine\"")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(gateMessage = decision.message)
            return
        }

        when (val availability = backend.availability()) {
            TermuxShellBackend.Availability.NotInstalled -> {
                DeclineRegistry.record(
                    Decline(
                        callerTag = Caller.User.tag,
                        capability = Capability.SHELL,
                        reason = Decline.Reason.TARGET_APP_ABSENT,
                        detail = "${TermuxContract.PACKAGE} is not installed.",
                        operation = commandLine,
                    )
                )
                _state.value = _state.value.copy(availability = availability)
                return
            }
            TermuxShellBackend.Availability.PermissionNotGranted -> {
                DeclineRegistry.record(
                    Decline(
                        callerTag = Caller.User.tag,
                        capability = Capability.SHELL,
                        reason = Decline.Reason.TARGET_PERMISSION_MISSING,
                        detail = "${TermuxContract.PERMISSION} not granted.",
                        operation = commandLine,
                    )
                )
                _state.value = _state.value.copy(availability = availability)
                return
            }
            TermuxShellBackend.Availability.Ready -> Unit
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, gateMessage = null)
            val workdir = _state.value.workdir
            val entry = when (val outcome = backend.run(commandLine, workdir)) {
                is TermuxShellBackend.Outcome.Completed -> ShellTranscriptEntry(
                    command = commandLine,
                    workdir = workdir,
                    stdout = outcome.stdout,
                    stderr = outcome.stderr,
                    exitCode = outcome.exitCode,
                    failure = null,
                )
                is TermuxShellBackend.Outcome.RefusedByTermux -> ShellTranscriptEntry(
                    command = commandLine,
                    workdir = workdir,
                    stdout = "",
                    stderr = "",
                    exitCode = null,
                    failure = "Termux refused the call (err=${outcome.err}): ${outcome.errmsg}",
                )
                is TermuxShellBackend.Outcome.DispatchFailed -> ShellTranscriptEntry(
                    command = commandLine,
                    workdir = workdir,
                    stdout = "",
                    stderr = "",
                    exitCode = null,
                    failure = outcome.message,
                )
                is TermuxShellBackend.Outcome.TimedOut -> ShellTranscriptEntry(
                    command = commandLine,
                    workdir = workdir,
                    stdout = "",
                    stderr = "",
                    exitCode = null,
                    failure = "No result came back within ${outcome.afterMillis / 1000}s. " +
                        "The command may still be running inside Termux.",
                )
            }
            _state.value = _state.value.copy(
                running = false,
                transcript = _state.value.transcript + entry,
            )
        }
    }

    fun clearTranscript() {
        _state.value = _state.value.copy(transcript = emptyList())
    }
}
