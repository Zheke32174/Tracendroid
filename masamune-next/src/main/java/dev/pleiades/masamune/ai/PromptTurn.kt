package dev.pleiades.masamune.ai

/**
 * Provider-neutral conversation model, salvaged from the donor tree's
 * core/chat/hooks/PromptTurn.kt. Zero Android imports, zero project imports — it compiled
 * standalone there and does here, and every provider in the donor tree already spoke it, so
 * the shape is proven rather than invented.
 */
enum class PromptTurnKind {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
    SUMMARY;

    companion object {
        fun fromRole(role: String): PromptTurnKind = when (role.trim().lowercase()) {
            "system" -> SYSTEM
            "user" -> USER
            "assistant", "ai" -> ASSISTANT
            "tool", "tool_result" -> TOOL_RESULT
            "tool_call", "tool_use" -> TOOL_CALL
            "summary" -> SUMMARY
            else -> USER
        }
    }
}

data class PromptTurn(
    val kind: PromptTurnKind,
    val content: String,
    val toolName: String? = null,
) {
    val role: String
        get() = when (kind) {
            PromptTurnKind.SYSTEM -> "system"
            PromptTurnKind.USER -> "user"
            PromptTurnKind.ASSISTANT -> "assistant"
            PromptTurnKind.TOOL_CALL -> "tool_call"
            PromptTurnKind.TOOL_RESULT -> "tool_result"
            PromptTurnKind.SUMMARY -> "summary"
        }

    companion object {
        fun fromRole(role: String, content: String, toolName: String? = null): PromptTurn =
            PromptTurn(kind = PromptTurnKind.fromRole(role), content = content, toolName = toolName)
    }
}

fun List<PromptTurn>.appendUserTurnIfMissing(message: String): List<PromptTurn> {
    if (message.isBlank()) return this
    val last = lastOrNull()
    return if (last?.kind == PromptTurnKind.USER && last.content == message) {
        this
    } else {
        this + PromptTurn(PromptTurnKind.USER, message)
    }
}

fun List<PromptTurn>.mergeAdjacentTurns(
    shouldMerge: (PromptTurn, PromptTurn) -> Boolean = { previous, current ->
        previous.kind == current.kind &&
            previous.kind !in setOf(
                PromptTurnKind.SYSTEM,
                PromptTurnKind.TOOL_CALL,
                PromptTurnKind.TOOL_RESULT,
            ) &&
            previous.toolName == current.toolName
    },
): List<PromptTurn> {
    if (size <= 1) return this
    val merged = mutableListOf<PromptTurn>()
    for (turn in this) {
        val previous = merged.lastOrNull()
        if (previous != null && shouldMerge(previous, turn)) {
            merged[merged.lastIndex] = previous.copy(content = previous.content + "\n" + turn.content)
        } else {
            merged.add(turn)
        }
    }
    return merged
}

/**
 * Collapses the kinds the wire APIs do not have. OpenAI-compatible and Anthropic both accept
 * only system/user/assistant here (no tool loop ships in this build), so TOOL_* and SUMMARY
 * are folded into the nearest wire role instead of being silently dropped.
 */
fun PromptTurn.wireRole(): String = when (kind) {
    PromptTurnKind.SYSTEM -> "system"
    PromptTurnKind.USER, PromptTurnKind.TOOL_RESULT -> "user"
    PromptTurnKind.ASSISTANT, PromptTurnKind.TOOL_CALL, PromptTurnKind.SUMMARY -> "assistant"
}
