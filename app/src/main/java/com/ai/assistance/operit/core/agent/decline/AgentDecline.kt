package com.ai.assistance.operit.core.agent.decline

/**
 * First-class "AI declined" outcome (§ 4.13 of docs/THREAT_MODEL.md, principle 8 of
 * docs/SECURITY.md).
 *
 * A decline is the AI choosing not to act — not an error, not a failure mode. The
 * project treats this as a real signal: it surfaces in the UI alongside the AI's stated
 * reason and gives the user the next move. There is no automatic-retry path; the agent
 * loop does not paper over a decline.
 *
 * Per `docs/AGENT_CORE.md § Decline channel`, the carrier:
 *  - [reason]                — verbatim natural-language text the AI provided.
 *  - [classification]        — informative, not gating; the app does not branch on it.
 *  - [suggestedAlternatives] — the AI's own suggestions, if any. May be null/empty.
 *  - [recordedAtMillis]      — when the decline was published to the registry.
 *  - [conversationId]        — optional link back to the chat session that produced it.
 */
data class AgentDecline(
    val reason: String,
    val classification: DeclineClass,
    val suggestedAlternatives: List<String>? = null,
    val recordedAtMillis: Long = System.currentTimeMillis(),
    val conversationId: String? = null,
)

/**
 * Decline classifications per `docs/AGENT_CORE.md`. Classifying is informative — the app
 * does not behave differently based on the class, but the audit log captures it so
 * downstream analysis can group declines by kind without having to NLP the reason text.
 */
enum class DeclineClass {
    /** The AI declined because it lacks (or refuses to claim) the capability to act. */
    CapabilityRefusal,

    /** The AI declined on safety grounds (its own framing, not the app's). */
    SafetyRefusal,

    /** The AI declined pending clarification from the user. */
    NeedsClarification,

    /** The AI declined because the request fell outside its context window. */
    ContextLimit,

    /** Anything else. Use sparingly — prefer one of the above if it fits. */
    Other,
}
