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
 */
object ModelOAuthConfigRegistry {

    // Empty by design — see the class TODO. Add verified entries keyed by ApiProviderType.
    private val configs: Map<ApiProviderType, ModelOAuthConfig> = emptyMap()

    /** Returns the OAuth config for [providerType], or null if OAuth is not configured for it yet. */
    fun configFor(providerType: ApiProviderType): ModelOAuthConfig? = configs[providerType]
}
