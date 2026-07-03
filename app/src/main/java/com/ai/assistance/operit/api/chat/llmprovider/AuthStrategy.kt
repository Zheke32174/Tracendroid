package com.ai.assistance.operit.api.chat.llmprovider

import okhttp3.Request

/**
 * How a provider authenticates an outbound request.
 *
 * Historically every provider hard-coded its own auth: OpenAIProvider added
 * `Authorization: Bearer <key>`, ClaudeProvider added `x-api-key` + `anthropic-version`,
 * GeminiProvider appended `?key=<key>` to the URL. That coupling meant a new credential
 * *type* — most importantly an OAuth access token — could not reach a model without
 * editing every provider's request builder.
 *
 * [AuthStrategy] separates two concerns:
 *  1. WHERE the secret comes from — the injected [credentialProvider] suspend lambda.
 *     Today that is [ApiKeyProvider.getApiKey]; a later brick swaps in an OAuth token
 *     store (with refresh) WITHOUT touching any provider, because the provider only
 *     ever talks to this interface.
 *  2. HOW the secret is applied — as a bearer header ([BearerAuth]), a custom header
 *     ([HeaderKeyAuth]), or a query parameter ([QueryParamAuth]).
 *
 * Application is split into [applyHeaders] and [applyUrl] because Gemini carries the
 * credential in the URL, not a header. A provider calls all three of [resolveCredential],
 * [applyUrl], and [applyHeaders]; each strategy no-ops the half it does not use, so the
 * three provider families invoke auth identically.
 *
 * NOTE: OAuth-bearer needs no new strategy — it is [BearerAuth] whose [credentialProvider]
 * yields a fresh access token. The "OAuth-ness" lives entirely in the credential source.
 */
interface AuthStrategy {
    /** Stable id for logs/tests, e.g. "bearer", "header:x-api-key", "query:key". */
    val id: String

    /** Resolve the current secret (API key or access token). Empty string = none. */
    suspend fun resolveCredential(): String

    /** Apply [credential] to [builder] as header(s). No-op for query-param auth. */
    fun applyHeaders(builder: Request.Builder, credential: String) {}

    /** Return [url] carrying [credential] as a query param. Identity for header auth. */
    fun applyUrl(url: String, credential: String): String = url
}

/**
 * `Authorization: Bearer <credential>` — the OpenAI-compatible family, and the shape an
 * OAuth access token uses. Preserves the prior OpenAIProvider behavior exactly: the
 * credential is optionally trimmed and the header is only added when non-empty (a blank
 * key on a keyless local endpoint must NOT send an empty Authorization header).
 */
class BearerAuth(
    private val credentialProvider: suspend () -> String,
    private val trim: Boolean = false,
) : AuthStrategy {
    override val id: String = "bearer"

    override suspend fun resolveCredential(): String =
        credentialProvider().let { if (trim) it.trim() else it }

    override fun applyHeaders(builder: Request.Builder, credential: String) {
        if (credential.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $credential")
        }
    }
}

/**
 * A single custom auth header plus optional constant companion headers.
 * Anthropic uses `x-api-key: <credential>` alongside a constant `anthropic-version`.
 * The credential header is added unconditionally, matching prior ClaudeProvider behavior.
 */
class HeaderKeyAuth(
    private val headerName: String,
    private val credentialProvider: suspend () -> String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : AuthStrategy {
    override val id: String = "header:$headerName"

    override suspend fun resolveCredential(): String = credentialProvider()

    override fun applyHeaders(builder: Request.Builder, credential: String) {
        builder.addHeader(headerName, credential)
        extraHeaders.forEach { (name, value) -> builder.addHeader(name, value) }
    }
}

/**
 * `?<paramName>=<credential>` query-parameter auth — Gemini generativelanguage.
 * Replicates the prior GeminiProvider logic: append with `&` if the URL already has a
 * query string, otherwise start one with `?`.
 */
class QueryParamAuth(
    private val paramName: String,
    private val credentialProvider: suspend () -> String,
) : AuthStrategy {
    override val id: String = "query:$paramName"

    override suspend fun resolveCredential(): String = credentialProvider()

    override fun applyUrl(url: String, credential: String): String =
        if (url.contains("?")) "$url&$paramName=$credential" else "$url?$paramName=$credential"
}
