package com.ai.assistance.operit.shell

/**
 * State machine for the shell bootstrap flow (PR 2/N).
 *
 * The bootstrap UI (PR 2/N follow-up) renders one panel per state. Transitions are linear
 * on the happy path and surface a [Failed] terminal state on any error — there is no
 * silent retry, no fallback (see docs/SHELL_REBUILD.md and AGENTS.md).
 */
sealed class ShellBootstrapState {

    /** Nothing inspected yet. */
    data object Idle : ShellBootstrapState()

    /** Checking whether the expected rootfs is already extracted on disk. */
    data object Inspecting : ShellBootstrapState()

    /** The expected rootfs is present and the manifest matches. No download needed. */
    data class Ready(val version: String, val sha256: String) : ShellBootstrapState()

    /** Waiting for user confirmation before downloading. */
    data class AwaitingConfirmation(
        val expectedVersion: String,
        val artifactUrl: String,
    ) : ShellBootstrapState()

    /** Downloading the .tar.zst artifact. */
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long?) : ShellBootstrapState()

    /** Verifying the SHA-256 digest pin (built-in check inside the downloader). */
    data object VerifyingDigest : ShellBootstrapState()

    /** Verifying the detached Ed25519 signature. */
    data object VerifyingSignature : ShellBootstrapState()

    /** Extracting the artifact into app-private storage. */
    data class Extracting(val entriesProcessed: Int, val bytesProcessed: Long) : ShellBootstrapState()

    /** Wrote the post-install manifest; ready for use. */
    data class Installed(val version: String, val sha256: String) : ShellBootstrapState()

    /** Terminal failure. The [reason] is shown verbatim to the user — no "something went wrong". */
    data class Failed(val phase: Phase, val reason: String) : ShellBootstrapState() {
        enum class Phase {
            CONFIGURATION,
            DOWNLOAD,
            DIGEST,
            SIGNATURE,
            EXTRACTION,
            MANIFEST,
            UNKNOWN,
        }
    }
}
