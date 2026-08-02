package dev.pleiades.masamune.ai

import kotlinx.coroutines.flow.Flow

/**
 * The BYOK provider seam.
 *
 * Shape salvaged from the donor tree's api/chat/llmprovider/AIService.kt, with its one fatal
 * flaw fixed: the donor returned an in-house `util.stream.Stream<String>`, which drags a
 * 4,568-line custom stream library along. This declares [kotlinx.coroutines.flow.Flow], so
 * cancellation, back-pressure and testing are all standard.
 *
 * Exactly two implementations ship: [OpenAiCompatProvider] and [AnthropicProvider]. Every
 * extra provider is a surface that gets claimed and never tested, so there are no others.
 */
interface AiService {

    /** "openai:gpt-4o-mini" style identity, shown in the chat header. */
    val providerModel: String

    /**
     * Streams the assistant's reply as it arrives. Each emission is an incremental chunk, not
     * the accumulated text. Throws [AiException] on any non-2xx or malformed response.
     */
    fun stream(turns: List<PromptTurn>): Flow<String>

    /** One cheap round trip that proves the key, base URL and model all work. */
    suspend fun testConnection(): Result<String>
}

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class ProviderKind(val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI_COMPATIBLE(
        "OpenAI-compatible",
        "https://api.openai.com/v1",
        "gpt-4o-mini",
    ),
    ANTHROPIC(
        "Anthropic",
        "https://api.anthropic.com",
        "claude-sonnet-4-20250514",
    ),
}

data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
) {
    val isUsable: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}
