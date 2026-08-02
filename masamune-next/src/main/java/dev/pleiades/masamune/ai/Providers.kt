package dev.pleiades.masamune.ai

import android.content.Context
import dev.pleiades.masamune.ai.auth.AccountStore
import dev.pleiades.masamune.ai.auth.AuthMode
import dev.pleiades.masamune.ai.auth.OAuthCatalog
import dev.pleiades.masamune.core.halt.HaltController
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val JSON = "application/json; charset=utf-8".toMediaType()

internal val sharedClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/** Trims a trailing slash so `base + "/chat/completions"` never doubles up. */
private fun String.asBase(): String = trimEnd('/')

/**
 * OpenAI-compatible endpoints take both credential kinds in the same header: an API key IS a
 * bearer token there, and an account access token is one too.
 */
private fun Request.Builder.authorize(credential: Credential): Request.Builder = when (credential) {
    is Credential.ApiKey -> addHeader("Authorization", "Bearer ${credential.value}")
    is Credential.Bearer -> addHeader("Authorization", "Bearer ${credential.accessToken}")
}

/**
 * Anthropic's own documented header for a key is `x-api-key`, not `Authorization`. An OAuth
 * access token is not an API key and does not belong in that header, so the two kinds go to
 * different headers rather than being coerced into one.
 */
private fun Request.Builder.authorizeAnthropic(credential: Credential): Request.Builder =
    when (credential) {
        is Credential.ApiKey -> addHeader("x-api-key", credential.value)
        is Credential.Bearer -> addHeader("Authorization", "Bearer ${credential.accessToken}")
    }

/**
 * OpenAI-compatible `/chat/completions` provider.
 *
 * Works against api.openai.com and anything that speaks the same shape (a local llama.cpp
 * server, OpenRouter, Groq, ...) — the base URL is user-supplied, which is the whole point of
 * BYOK. Payloads are built and parsed with org.json, so there is no reflective serialization
 * for R8 to break.
 */
class OpenAiCompatProvider(
    private val config: ProviderConfig,
    private val credentials: CredentialSource,
) : AiService {

    override val providerModel: String = "openai:${config.model}"

    override fun stream(turns: List<PromptTurn>): Flow<String> = flow {
        // Resolved here, not at construction: a subscription token can be refreshed between
        // the moment the service was built and the moment the request goes out.
        val credential = credentials.credential()
        val body = JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put("messages", buildMessages(turns, config.systemPrompt))
        }
        val request = Request.Builder()
            .url("${config.baseUrl.asBase()}/chat/completions")
            .authorize(credential)
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val call = sharedClient.newCall(request)
        try {
            call.execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful) {
                    throw AiException(
                        "HTTP ${response.code} from ${config.baseUrl.asBase()}: " +
                            (source?.readUtf8()?.take(600) ?: response.message)
                    )
                }
                if (source == null) throw AiException("Empty response body.")
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    if (HaltController.isHalted) break
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(payload)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) emit(delta)
                }
            }
        } catch (e: IOException) {
            throw AiException("Network error talking to ${config.baseUrl.asBase()}: ${e.message}", e)
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", config.model)
            put("stream", false)
            put("max_tokens", 8)
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
            )
        }
        runCatching {
            val request = Request.Builder()
                .url("${config.baseUrl.asBase()}/chat/completions")
                .authorize(credentials.credential())
                .post(body.toString().toRequestBody(JSON))
                .build()
            sharedClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AiException("HTTP ${response.code}: ${text.take(600)}")
                }
                "OK — ${config.model} answered (HTTP ${response.code})."
            }
        }
    }

    private fun buildMessages(turns: List<PromptTurn>, systemPrompt: String): JSONArray {
        val arr = JSONArray()
        if (systemPrompt.isNotBlank()) {
            arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (t in turns) {
            arr.put(JSONObject().put("role", t.wireRole()).put("content", t.content))
        }
        return arr
    }
}

/**
 * Anthropic `/v1/messages` provider.
 *
 * Anthropic's SSE has typed events; only `content_block_delta` with `text_delta` carries
 * visible text, and `error` events carry a message we surface verbatim.
 */
class AnthropicProvider(
    private val config: ProviderConfig,
    private val credentials: CredentialSource,
) : AiService {

    override val providerModel: String = "anthropic:${config.model}"

    override fun stream(turns: List<PromptTurn>): Flow<String> = flow {
        val credential = credentials.credential()
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", 4096)
            put("stream", true)
            if (config.systemPrompt.isNotBlank()) put("system", config.systemPrompt)
            put("messages", buildMessages(turns))
        }
        val request = Request.Builder()
            .url("${config.baseUrl.asBase()}/v1/messages")
            .authorizeAnthropic(credential)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val call = sharedClient.newCall(request)
        try {
            call.execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful) {
                    throw AiException(
                        "HTTP ${response.code} from ${config.baseUrl.asBase()}: " +
                            (source?.readUtf8()?.take(600) ?: response.message)
                    )
                }
                if (source == null) throw AiException("Empty response body.")
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    if (HaltController.isHalted) break
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    when (json.optString("type")) {
                        "content_block_delta" -> {
                            val text = json.optJSONObject("delta")?.optString("text").orEmpty()
                            if (text.isNotEmpty()) emit(text)
                        }
                        "message_stop" -> return@use
                        "error" -> throw AiException(
                            json.optJSONObject("error")?.optString("message")
                                ?: "Anthropic returned an unspecified error."
                        )
                    }
                }
            }
        } catch (e: IOException) {
            throw AiException("Network error talking to ${config.baseUrl.asBase()}: ${e.message}", e)
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", 8)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        }
        runCatching {
            val request = Request.Builder()
                .url("${config.baseUrl.asBase()}/v1/messages")
                .authorizeAnthropic(credentials.credential())
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .post(body.toString().toRequestBody(JSON))
                .build()
            sharedClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AiException("HTTP ${response.code}: ${text.take(600)}")
                }
                "OK — ${config.model} answered (HTTP ${response.code})."
            }
        }
    }

    /**
     * Anthropic rejects two consecutive turns with the same role and requires the first turn
     * to be `user`, so adjacent same-role turns are merged and a leading assistant turn is
     * dropped rather than sent and rejected.
     */
    private fun buildMessages(turns: List<PromptTurn>): JSONArray {
        val wire = turns
            .filter { it.kind != PromptTurnKind.SYSTEM }
            .map { PromptTurn(PromptTurnKind.fromRole(it.wireRole()), it.content) }
            .mergeAdjacentTurns()
            .dropWhile { it.kind != PromptTurnKind.USER }
        val arr = JSONArray()
        for (t in wire) {
            arr.put(JSONObject().put("role", t.wireRole()).put("content", t.content))
        }
        if (arr.length() == 0) {
            arr.put(JSONObject().put("role", "user").put("content", "(empty)"))
        }
        return arr
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** The only place a [ProviderConfig] becomes a live client. */
object AiServiceFactory {

    fun create(config: ProviderConfig, credentials: CredentialSource): AiService =
        when (config.kind) {
            ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatProvider(config, credentials)
            ProviderKind.ANTHROPIC -> AnthropicProvider(config, credentials)
        }

    /**
     * The call every surface should use: picks the credential source from the config's own
     * [AuthMode], so no caller has to remember which mode is active.
     */
    fun create(context: Context, config: ProviderConfig): AiService =
        create(config, credentialSource(context, config))

    fun credentialSource(context: Context, config: ProviderConfig): CredentialSource =
        when (config.authMode) {
            AuthMode.API_KEY -> CredentialSource {
                if (config.apiKey.isBlank()) {
                    throw AiException(
                        "API key mode is selected but no key is set. Set one at About -> AI " +
                            "provider, or switch to subscription sign-in at About -> Account."
                    )
                }
                Credential.ApiKey(config.apiKey)
            }

            AuthMode.SUBSCRIPTION -> {
                val store = AccountStore.get(context)
                val profileId = config.oauthProfileId
                CredentialSource {
                    val label = OAuthCatalog.byId(profileId)?.label ?: profileId
                    if (profileId.isBlank()) {
                        throw AiException(
                            "Subscription mode is selected but no account provider is chosen. " +
                                "Pick one at About -> Account."
                        )
                    }
                    Credential.Bearer(
                        store.accessToken(profileId).getOrElse {
                            // The account layer's message already names the specific problem
                            // (not signed in / expired / refresh refused); do not flatten it.
                            throw AiException("$label sign-in problem: ${it.message}", it)
                        }
                    )
                }
            }
        }
}
