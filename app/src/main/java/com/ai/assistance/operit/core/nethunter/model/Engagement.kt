package com.ai.assistance.operit.core.nethunter.model

import kotlinx.serialization.Serializable

/**
 * Domain model for the NetHunter authorized-pentest module (spec: `docs/NETHUNTER_MODULE.md § 5.3`).
 *
 * These are pure, immutable, `@Serializable` value types — no Android / ObjectBox coupling — so the
 * scope gate (invariant 1, [com.ai.assistance.operit.core.nethunter.ScopeEnforcer]) and the
 * hash-chained evidence log (invariant 5, [com.ai.assistance.operit.core.nethunter.EvidenceLog]) can
 * be reasoned about and unit-tested in isolation. Persistence is a separate seam
 * ([com.ai.assistance.operit.core.nethunter.EngagementStore]).
 */

@Serializable
enum class EngagementState { DRAFT, ACTIVE, HALTED, CLOSED }

/** How a scope entry / target reference is interpreted. */
@Serializable
enum class ScopeKind { HOST, CIDR, SSID, BSSID, DOMAIN }

/**
 * A single authorized-scope entry. [value] is interpreted per [kind]:
 *  - [ScopeKind.HOST]   — an IPv4 literal or a hostname (matched case-insensitively)
 *  - [ScopeKind.CIDR]   — an IPv4 CIDR block, e.g. `10.0.0.0/24` (covers HOST targets inside it)
 *  - [ScopeKind.SSID]   — a Wi-Fi network name (exact match)
 *  - [ScopeKind.BSSID]  — a Wi-Fi AP MAC (case-insensitive, separator-agnostic)
 *  - [ScopeKind.DOMAIN] — a domain suffix, e.g. `example.com` or the subdomain-only glob `*.example.com`
 */
@Serializable
data class ScopeEntry(val kind: ScopeKind, val value: String)

@Serializable
data class Scope(val entries: List<ScopeEntry> = emptyList())

/** The user's affirmation that this engagement's targets are authorized for testing (invariant 1). */
@Serializable
data class Authorization(
    val attestedBy: String,
    val attestedAtMillis: Long,
    val note: String = ""
)

@Serializable
data class Target(
    val ref: String,
    val notes: String = "",
    val findings: List<String> = emptyList()
)

/** The four categories of module capability an evidence item can originate from. */
@Serializable
enum class EvidenceCapability { RECON, CAPTURE, INTERCEPT, PAYLOAD }

/**
 * One tamper-evident evidence record. Items form an append-only hash chain within an engagement:
 * `recordHash = SHA-256(prevHash | ts | capability | tool | target | contentHash)`, and each item
 * carries the previous item's [recordHash] as [prevHash] (the genesis item uses
 * [com.ai.assistance.operit.core.nethunter.EvidenceLog.GENESIS_PREV_HASH]). Build items only through
 * [com.ai.assistance.operit.core.nethunter.EvidenceLog.append] so the chain stays well-formed.
 */
@Serializable
data class EvidenceItem(
    val timestampMillis: Long,
    val capability: EvidenceCapability,
    val tool: String,
    val target: String,
    /** Hex SHA-256 of the captured content/blob this record attests to. */
    val contentHash: String,
    /** The previous record's [recordHash], linking the chain. */
    val prevHash: String,
    /** Hex SHA-256 over (prevHash, timestamp, capability, tool, target, contentHash). */
    val recordHash: String,
    /** Optional reference to the stored blob (path / uri / box id). The hash, not the blob, is the chain. */
    val blobRef: String? = null,
    /** When an AI initiated the action, a snapshot of its reasoning at that moment (invariant 5). */
    val reasoningSnapshot: String? = null
)

@Serializable
data class Engagement(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val state: EngagementState = EngagementState.DRAFT,
    val scope: Scope = Scope(),
    val authorization: Authorization? = null,
    val targets: List<Target> = emptyList(),
    val evidence: List<EvidenceItem> = emptyList()
) {
    val isActive: Boolean get() = state == EngagementState.ACTIVE
}
