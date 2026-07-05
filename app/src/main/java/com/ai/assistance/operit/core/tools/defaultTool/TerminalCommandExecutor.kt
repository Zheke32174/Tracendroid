package com.ai.assistance.operit.core.tools.defaultTool

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.flow.Flow

/**
 * Common contract for the terminal command executor behind the terminal_* tools.
 *
 * Tracendroid (hard fork of Operit, © AAswordman, LGPL) ships two implementations:
 *  - [com.ai.assistance.operit.core.tools.defaultTool.standard.StandardTerminalCommandExecutor]
 *    — the submodule/proot-backed path (com.ai.assistance.operit.terminal.TerminalManager);
 *  - [com.ai.assistance.operit.shell.local.LocalTerminalCommandExecutor]
 *    — a reliable local-exec tier that runs commands via ProcessBuilder("sh","-c", cmd)
 *    inside the app sandbox, always available with no rootfs/SSH/submodule dependency.
 *
 * The method set below is exactly the externally-called surface used by
 * [com.ai.assistance.operit.core.tools.ToolRegistration] (every terminalTool.* call site).
 * Signatures are byte-exact copies of the ones on StandardTerminalCommandExecutor.
 */
interface TerminalCommandExecutor {

    /** create_terminal_session */
    fun createOrGetSession(tool: AITool): ToolResult

    /** execute_in_terminal_session (also the non-streaming fallback of the streaming tool) */
    fun executeCommandInSession(tool: AITool): ToolResult

    /** execute_in_terminal_session_streaming */
    fun executeCommandInSessionStream(tool: AITool): Flow<ToolResult>

    /** execute_hidden_terminal_command */
    fun executeHiddenCommand(tool: AITool): ToolResult

    /** input_in_terminal_session */
    fun inputInSession(tool: AITool): ToolResult

    /** close_terminal_session */
    fun closeSession(tool: AITool): ToolResult

    /** get_terminal_session_screen */
    fun getSessionScreen(tool: AITool): ToolResult
}
