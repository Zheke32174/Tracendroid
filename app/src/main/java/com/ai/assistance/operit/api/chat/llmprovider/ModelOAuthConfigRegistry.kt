package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiProviderType

/**
 * Per-provider OAuth 2.0 + PKCE configuration registry.
 *
 * Maps an [ApiProviderType] to its [ModelOAuthConfig] (authorize/token endpoints, public client id,
 * redirect URI, scopes). [ModelOAuthClient] and the settings UI consult [configFor] to decide whether
 * the OAuth "Authorize" action is available for a given provider.
 *
 * TODO(offbox-verify + operator): This ships INTENTIONALLY EMPTY. Populate real, per-provider values
 * off-box after they are verified against each vendor's official OAuth documentation:
 *   - authorizeEndpoint / tokenEndpoint  (exact vendor URLs)
 *   - clientId                           (a real registered PUBLIC mobile client id — never invented)
 *   - redirectUri                        (a custom scheme this app owns; may also need an
 *                                          AndroidManifest intent-filter for an external-browser path)
 *   - scopes                             (the minimal scopes each provider requires)
 * Do NOT invent client ids or endpoints here. While the map is empty, [configFor] returns null for
 * every provider, which keeps the "Authorize" button disabled and the OAuth flow non-functional.
 *
 * EXTENSION POINT — subscription auth (NEXT BRICK): the western vendors added as built-in providers
 * (XAI/GROQ/PERPLEXITY/TOGETHER/FIREWORKS/DEEPINFRA/COHERE/AZURE_OPENAI) are all API-key / Bearer today.
 * The user's real goal — "plug in my subscription, not pay per API call" — needs OAuth, but note:
 *   - There is NO official third-party OAuth-to-API for CONSUMER ChatGPT / Claude.ai / Gemini
 *     subscriptions. Do NOT fabricate authorize/token endpoints for them here.
 *   - The intended approach is instead to RECYCLE the OAuth access/refresh tokens minted on-device by
 *     each vendor's official CLI — claude-cli (Claude), codex (OpenAI/ChatGPT), gemini-cli (Gemini) —
 *     reading their stored credential files and refreshing via the CLI's own public client. That is a
 *     credential-SOURCE concern (a new ApiKeyProvider, sibling of OAuthCredentialProvider) far more
 *     than a [ModelOAuthConfig] concern; a genuine 3-legged PKCE entry belongs here ONLY for a vendor
 *     that publishes a real developer OAuth app (verify per-vendor off-box before adding).
 *   - Shape to add, once verified, keyed by [ApiProviderType]:
 *       ApiProviderType.XXX to ModelOAuthConfig(
 *           authorizeEndpoint = "https://.../authorize",
 *           tokenEndpoint     = "https://.../token",
 *           clientId          = "<real public client id>",
 *           redirectUri       = "<app-owned scheme>://oauth",
 *           scopes            = listOf(/* minimal */),
 *       )
 * See the CLI-token-recycle path (skill: underhall-oauth-cli-recycle) — flagged as the next brick.
 */
object ModelOAuthConfigRegistry {

    // Empty by design — see the class TODO. Add verified entries keyed by ApiProviderType.
    private val configs: Map<ApiProviderType, ModelOAuthConfig> = emptyMap()

    /** Returns the OAuth config for [providerType], or null if OAuth is not configured for it yet. */
    fun configFor(providerType: ApiProviderType): ModelOAuthConfig? = configs[providerType]
}
