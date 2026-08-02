package dev.pleiades.masamune.ai

import dev.pleiades.masamune.ai.auth.AuthMode
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

/**
 * What actually goes on the wire as authentication.
 *
 * Split out because "the credential" used to be a single field on [ProviderConfig], which
 * hardcoded the assumption that it is a pasted API key. It is not: in [AuthMode.SUBSCRIPTION]
 * it is a short-lived access token that a refresh may replace between one request and the
 * next, so it has to be fetched per call rather than captured at construction time.
 */
sealed interface Credential {
    data class ApiKey(val value: String) : Credential
    data class Bearer(val accessToken: String) : Credential
}

/**
 * Supplies the credential for one request. Suspending on purpose: the subscription
 * implementation may have to refresh a token, which is network I/O.
 *
 * Implementations throw [AiException] with a sentence naming what is missing rather than
 * returning a blank string — a request sent with an empty Authorization header produces a 401
 * that says nothing useful about the actual cause.
 */
fun interface CredentialSource {
    suspend fun credential(): Credential
}

data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    /** Which of the two auth models is in force. Defaults to the original one. */
    val authMode: AuthMode = AuthMode.API_KEY,
    /** An `OAuthCatalog` id, meaningful only when [authMode] is [AuthMode.SUBSCRIPTION]. */
    val oauthProfileId: String = "",
) {
    /**
     * "Configured enough to try a request." In subscription mode this deliberately does NOT
     * assert that a session exists — that is a live fact owned by `AccountStore`, and the chat
     * surface reads it from there so its header can say "signed out" rather than the much
     * vaguer "no provider configured".
     */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && when (authMode) {
            AuthMode.API_KEY -> apiKey.isNotBlank()
            AuthMode.SUBSCRIPTION -> oauthProfileId.isNotBlank()
        }
}
