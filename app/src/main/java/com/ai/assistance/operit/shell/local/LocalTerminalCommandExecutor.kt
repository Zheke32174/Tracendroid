package com.ai.assistance.operit.shell.local

import android.content.Context
import com.ai.assistance.operit.core.tools.HiddenTerminalCommandResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.TerminalCommandResultData
import com.ai.assistance.operit.core.tools.TerminalSessionCloseResultData
import com.ai.assistance.operit.core.tools.TerminalSessionCreationResultData
import com.ai.assistance.operit.core.tools.TerminalSessionScreenResultData
import com.ai.assistance.operit.core.tools.TerminalStreamEventData
import com.ai.assistance.operit.core.tools.defaultTool.TerminalCommandExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Local-exec terminal executor for Tracendroid (hard fork of Operit, © AAswordman, LGPL).
 *
 * This is the reliable default tier: it runs every terminal_* tool against a [LocalShellSession]
 * that spawns `ProcessBuilder("sh","-c", cmd)` inside the app sandbox. Unlike
 * [com.ai.assistance.operit.core.tools.defaultTool.standard.StandardTerminalCommandExecutor],
 * it has no dependency on the :terminal submodule / proot rootfs (which never comes up at
 * runtime), so terminal tools actually execute on any device.
 *
 * Security: commands run with the app's own privileges (NO escalation) — consistent with the
 * fork's threat model, which removed root/Shizuku/UI-automation, not the app-context shell.
 * proot/ryznix remain available as future upgrades behind a separate getter.
 */
class LocalTerminalCommandExecutor(private val context: Context) : TerminalCommandExecutor {

    private val sessions = ConcurrentHashMap<String, LocalShellSession>()

    /** Ephemeral sessions for execute_hidden_terminal_command, keyed by executor_key. */
    private val hiddenSessions = ConcurrentHashMap<String, LocalShellSession>()

    override fun createOrGetSession(tool: AITool): ToolResult {
        val sessionName = tool.parameters.find { it.name == "session_name" }?.value
        if (sessionName.isNullOrBlank()) {
            return failure(tool, "Missing required parameter: session_name")
        }

        val existing = sessions.values.find { it.sessionName == sessionName }
        if (existing != null) {
            return ToolResult(
                toolName = tool.name,
                success = true,
                result = TerminalSessionCreationResultData(
                    sessionId = existing.sessionId,
                    sessionName = sessionName,
                    isNewSession = false
                )
            )
        }

        val sessionId = UUID.randomUUID().toString()
        val session = LocalShellSession(context, sessionId, sessionName)
        sessions[sessionId] = session
        return ToolResult(
            toolName = tool.name,
            success = true,
            result = TerminalSessionCreationResultData(
                sessionId = sessionId,
                sessionName = sessionName,
                isNewSession = true
            )
        )
    }

    override fun executeCommandInSession(tool: AITool): ToolResult {
        val command = tool.parameters.find { it.name == "command" }?.value ?: ""
        val sessionId = tool.parameters.find { it.name == "session_id" }?.value
        if (sessionId.isNullOrBlank()) {
            return failure(tool, "Missing required parameter: session_id")
        }
        val session = sessions[sessionId]
            ?: return failure(tool, "Terminal session does not exist: $sessionId")

        val timeout = timeoutParam(tool, DEFAULT_TIMEOUT_MS)
        return try {
            val result = session.runCommand(command, timeout)
            ToolResult(
                toolName = tool.name,
                success = !result.timedOut && result.exitCode == 0,
                result = TerminalCommandResultData(
                    command = command,
                    output = result.output,
                    exitCode = result.exitCode,
                    sessionId = sessionId,
                    timedOut = result.timedOut
                ),
                error = if (result.timedOut) "Command timed out after ${timeout}ms" else null
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "executeCommandInSession failed", e)
            failure(tool, "Error executing command: ${e.message ?: ""}")
        }
    }

    override fun executeCommandInSessionStream(tool: AITool): Flow<ToolResult> = flow {
        val command = tool.parameters.find { it.name == "command" }?.value ?: ""
        val sessionId = tool.parameters.find { it.name == "session_id" }?.value
        if (sessionId.isNullOrBlank()) {
            emit(failure(tool, "Missing required parameter: session_id"))
            return@flow
        }
        val session = sessions[sessionId]
        if (session == null) {
            emit(failure(tool, "Terminal session does not exist: $sessionId"))
            return@flow
        }

        val timeout = timeoutParam(tool, DEFAULT_TIMEOUT_MS)

        // 1) start event
        emit(
            ToolResult(
                toolName = tool.name,
                success = true,
                result = TerminalStreamEventData(
                    type = "start",
                    command = command,
                    sessionId = sessionId,
                    chunkIndex = 0,
                    receivedChars = 0
                ),
                error = ""
            )
        )

        val result =
            try {
                session.runCommand(command, timeout)
            } catch (e: Exception) {
                AppLogger.e(TAG, "executeCommandInSessionStream failed", e)
                emit(failure(tool, "Error executing command: ${e.message ?: ""}"))
                return@flow
            }

        // 2) single output chunk (v1: one chunk is fine)
        if (result.output.isNotEmpty()) {
            emit(
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = TerminalStreamEventData(
                        type = "chunk",
                        command = command,
                        sessionId = sessionId,
                        chunk = result.output,
                        chunkIndex = 0,
                        receivedChars = result.output.length
                    ),
                    error = ""
                )
            )
        }

        // 3) final completion result
        emit(
            ToolResult(
                toolName = tool.name,
                success = !result.timedOut && result.exitCode == 0,
                result = TerminalCommandResultData(
                    command = command,
                    output = result.output,
                    exitCode = result.exitCode,
                    sessionId = sessionId,
                    timedOut = result.timedOut
                ),
                error = if (result.timedOut) "Command timed out after ${timeout}ms" else null
            )
        )
    }

    override fun executeHiddenCommand(tool: AITool): ToolResult {
        val command = tool.parameters.find { it.name == "command" }?.value ?: ""
        if (command.isBlank()) {
            return failure(tool, "Missing required parameter: command")
        }
        val executorKey =
            tool.parameters.find { it.name == "executor_key" }?.value?.trim()?.ifEmpty { "default" }
                ?: "default"
        val timeout = timeoutParam(tool, DEFAULT_HIDDEN_TIMEOUT_MS)

        val session =
            hiddenSessions.getOrPut(executorKey) {
                LocalShellSession(context, "hidden-$executorKey", "hidden:$executorKey")
            }

        return try {
            val result = session.runCommand(command, timeout)
            ToolResult(
                toolName = tool.name,
                success = !result.timedOut && result.exitCode == 0,
                result = HiddenTerminalCommandResultData(
                    command = command,
                    output = result.output,
                    exitCode = result.exitCode,
                    executorKey = executorKey,
                    timedOut = result.timedOut
                ),
                error = if (result.timedOut) "Hidden command timed out after ${timeout}ms" else null
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "executeHiddenCommand failed", e)
            failure(tool, "Error executing hidden command: ${e.message ?: ""}")
        }
    }

    override fun inputInSession(tool: AITool): ToolResult {
        // Local-exec runs each command as a discrete process; there is no long-lived interactive
        // shell to receive keystrokes. Report this clearly rather than silently succeeding.
        val sessionId = tool.parameters.find { it.name == "session_id" }?.value
        if (sessionId.isNullOrBlank()) {
            return failure(tool, "Missing required parameter: session_id")
        }
        if (sessions[sessionId] == null) {
            return failure(tool, "Terminal session does not exist: $sessionId")
        }
        return failure(
            tool,
            "Interactive input is not supported by the local-exec terminal tier. " +
                "Send a full command via execute_in_terminal_session instead."
        )
    }

    override fun closeSession(tool: AITool): ToolResult {
        val sessionId = tool.parameters.find { it.name == "session_id" }?.value
        if (sessionId.isNullOrBlank()) {
            return failure(tool, "Missing required parameter: session_id")
        }
        sessions.remove(sessionId)
        return ToolResult(
            toolName = tool.name,
            success = true,
            result = TerminalSessionCloseResultData(
                sessionId = sessionId,
                success = true,
                message = "Terminal session closed: $sessionId"
            )
        )
    }

    override fun getSessionScreen(tool: AITool): ToolResult {
        val sessionId = tool.parameters.find { it.name == "session_id" }?.value
        if (sessionId.isNullOrBlank()) {
            return failure(tool, "Missing required parameter: session_id")
        }
        val session = sessions[sessionId]
            ?: return failure(tool, "Terminal session does not exist: $sessionId")

        val content = session.screenContent()
        return ToolResult(
            toolName = tool.name,
            success = true,
            result = TerminalSessionScreenResultData(
                sessionId = sessionId,
                rows = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1,
                cols = content.lineSequence().maxOfOrNull { it.length } ?: 0,
                content = content,
                commandRunning = false
            )
        )
    }

    private fun timeoutParam(tool: AITool, default: Long): Long =
        tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull() ?: default

    private fun failure(tool: AITool, error: String): ToolResult =
        ToolResult(
            toolName = tool.name,
            success = false,
            result = StringResultData(""),
            error = error
        )

    companion object {
        private const val TAG = "LocalTerminalCommandExecutor"
        private const val DEFAULT_TIMEOUT_MS = 1800000L // 30 minutes, matching Standard executor
        private const val DEFAULT_HIDDEN_TIMEOUT_MS = 120000L // 2 minutes, matching Standard executor
    }
}
