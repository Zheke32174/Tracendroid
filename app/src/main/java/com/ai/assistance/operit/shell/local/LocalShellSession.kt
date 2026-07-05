package com.ai.assistance.operit.shell.local

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A local-exec shell session.
 *
 * Tracendroid (hard fork of Operit, © AAswordman, LGPL) needs terminal tools that actually run
 * on any device. The submodule/proot backend behind
 * [com.ai.assistance.operit.core.tools.defaultTool.standard.StandardTerminalCommandExecutor]
 * requires a rootfs that never comes up at runtime, so nothing executes. This session runs
 * commands via `ProcessBuilder("sh","-c", ...)` inside the app's own sandbox — always available,
 * no rootfs/SSH/submodule required. It runs with the app's own privileges (NO escalation),
 * consistent with the fork's threat model, which removed root/Shizuku/UI-automation, not the
 * app-context shell.
 *
 * Per-session state:
 *  - a working directory rooted at `context.filesDir/terminal/<sessionId>` (created on demand);
 *  - a persisted current directory that survives `cd` between commands;
 *  - environment overrides applied on top of the inherited process environment;
 *  - a bounded rolling output buffer (last [MAX_BUFFER_BYTES]) for `getSessionScreen`.
 */
class LocalShellSession(
    context: Context,
    val sessionId: String,
    val sessionName: String
) {
    /** Root working directory for this session; also the initial cwd. */
    private val rootDir: File = File(File(context.filesDir, "terminal"), sessionId).apply { mkdirs() }

    /** Current working directory; updated after every command via the `__CWD__` marker. */
    @Volatile
    private var currentDir: File = rootDir

    /** Environment overrides layered on top of the inherited environment. */
    private val env = ConcurrentHashMap<String, String>()

    /** Bounded rolling buffer of recent merged stdout/stderr, for getSessionScreen(). */
    private val outputBuffer = StringBuilder()
    private val bufferLock = Any()

    val currentDirPath: String
        get() = currentDir.absolutePath

    /** Result of a single local-exec command. */
    data class LocalCommandResult(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean
    )

    fun setEnv(key: String, value: String) {
        env[key] = value
    }

    /**
     * Run [command] via `sh -c`, honoring the persisted cwd and capturing merged stdout/stderr.
     *
     * The command is wrapped so that: (1) it starts in the session's current directory, (2) the
     * caller's command runs in a subshell so its own exit code is preserved, and (3) the resulting
     * working directory is emitted on a trailing `__CWD__<path>` line which we parse and strip.
     * This gives `cd` persistence across separate tool calls without a long-lived shell process.
     *
     * @param command the user command
     * @param timeoutMs wall-clock timeout; on expiry the process is force-destroyed and
     *                  [LocalCommandResult.timedOut] is true.
     */
    fun runCommand(command: String, timeoutMs: Long): LocalCommandResult {
        val cwd = currentDir
        val cwdQuoted = singleQuote(cwd.absolutePath)
        // Start in the session cwd, run the user command in a subshell (preserve its rc), then
        // print a cwd marker so we can recover any `cd` the command performed.
        val composed =
            "cd $cwdQuoted 2>/dev/null; ( $command ); __rc=\$?; printf '\\n__CWD__%s\\n' \"\$(pwd)\"; exit \$__rc"

        return try {
            val pb = ProcessBuilder("sh", "-c", composed)
                .directory(cwd)
                .redirectErrorStream(true)
            if (env.isNotEmpty()) {
                pb.environment().putAll(env)
            }
            val process = pb.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                val partial = readStream(process)
                val cleaned = parseAndApplyCwd(partial)
                appendToBuffer(cleaned)
                return LocalCommandResult(output = cleaned, exitCode = -1, timedOut = true)
            }

            val raw = readStream(process)
            val exitCode = process.exitValue()
            val cleaned = parseAndApplyCwd(raw)
            appendToBuffer(cleaned)
            LocalCommandResult(output = cleaned, exitCode = exitCode, timedOut = false)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Local command execution failed: $command", e)
            val message = "Error: ${e.message ?: e.javaClass.simpleName}"
            appendToBuffer(message)
            LocalCommandResult(output = message, exitCode = -1, timedOut = false)
        }
    }

    /** Current rolling buffer content (for get_terminal_session_screen). */
    fun screenContent(): String = synchronized(bufferLock) { outputBuffer.toString() }

    private fun readStream(process: Process): String {
        return process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * Strip the trailing `__CWD__<path>` marker line, update [currentDir] from it, and return the
     * remaining output. If the marker is absent (e.g. process killed before printf), the output is
     * returned unchanged and the cwd is left as-is.
     */
    private fun parseAndApplyCwd(raw: String): String {
        val markerIndex = raw.lastIndexOf(CWD_MARKER)
        if (markerIndex < 0) {
            return raw
        }
        val pathStart = markerIndex + CWD_MARKER.length
        val lineEnd = raw.indexOf('\n', pathStart).let { if (it < 0) raw.length else it }
        val path = raw.substring(pathStart, lineEnd).trim()
        if (path.isNotEmpty()) {
            val dir = File(path)
            if (dir.isDirectory) {
                currentDir = dir
            }
        }
        // Drop the marker line (and the newline printf emitted before it, if present).
        var cut = markerIndex
        if (cut > 0 && raw[cut - 1] == '\n') {
            cut -= 1
        }
        return raw.substring(0, cut)
    }

    private fun appendToBuffer(text: String) {
        if (text.isEmpty()) return
        synchronized(bufferLock) {
            outputBuffer.append(text)
            if (outputBuffer.length > MAX_BUFFER_BYTES) {
                val overflow = outputBuffer.length - MAX_BUFFER_BYTES
                outputBuffer.delete(0, overflow)
            }
        }
    }

    companion object {
        private const val TAG = "LocalShellSession"
        private const val CWD_MARKER = "__CWD__"

        /** Rolling screen buffer cap: last 32 KB of merged output. */
        private const val MAX_BUFFER_BYTES = 32 * 1024

        /** POSIX-safe single-quote for embedding a path in the composed command. */
        private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
