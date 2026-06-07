package com.ai.assistance.operit.core.aioutput

import android.net.Uri

/**
 * Scheme allowlist for links that originate from AI output (§ 4.6).
 *
 * AI output is a validated channel: the AI itself is not the threat, but the input side
 * of its reasoning (prompt injection, malicious tool result fed back into the
 * conversation) is reachable by adversaries. A URL the AI emits must be checked before
 * the app launches it.
 *
 * The allowlist is intentionally tight:
 *  - `http` and `https` — the navigable web; user opens these knowingly via the link
 *    preview dialog.
 *  - `mailto`           — email composition; the OS handles authorization.
 *
 * Refused on principle:
 *  - `javascript:` — would execute in whichever WebView grabs the navigation.
 *  - `intent:`     — Android intent-URL syntax can launch arbitrary activities and
 *                    components.
 *  - `content:`    — reads from arbitrary content providers; the AI shouldn't be
 *                    pointing the user at one.
 *  - `file:`       — opens local files; never appropriate for an AI-emitted link.
 *  - any other scheme — refused by default; add explicitly when a real use case appears.
 *
 * The check is on the URL scheme only — host validation is out of scope here. The user
 * confirms the destination via the link preview dialog before this check fires.
 */
object AiOutputLinkPolicy {

    private val ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "mailto")

    /**
     * Returns true if the URL is safe to hand to `Intent(ACTION_VIEW)` from an
     * AI-emitted link. Returns false on parse failure, missing scheme, or any scheme
     * outside the allowlist.
     */
    fun isAllowed(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme in ALLOWED_SCHEMES
    }

    /**
     * Returns the URL when the policy allows it, null otherwise. Convenience for
     * call sites that want to short-circuit with the same predicate.
     */
    fun safeUrlOrNull(url: String?): String? = if (isAllowed(url)) url else null

    /** Standard refusal text for the user-facing toast / dialog. */
    fun refusalMessage(url: String?): String {
        val scheme = runCatching { Uri.parse(url ?: "").scheme }.getOrNull() ?: "<none>"
        return "Refused to open AI-suggested link: scheme '$scheme' not on the allowlist " +
            "(http, https, mailto). See § 4.6 in docs/THREAT_MODEL.md."
    }
}
