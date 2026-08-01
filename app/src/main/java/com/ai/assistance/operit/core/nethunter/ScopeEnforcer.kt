package com.ai.assistance.operit.core.nethunter

import com.ai.assistance.operit.core.nethunter.model.Engagement
import com.ai.assistance.operit.core.nethunter.model.EngagementState
import com.ai.assistance.operit.core.nethunter.model.ScopeEntry
import com.ai.assistance.operit.core.nethunter.model.ScopeKind

/** Outcome of a scope check. */
sealed interface ScopeDecision {
    data object Allowed : ScopeDecision
    data class Denied(val reason: String) : ScopeDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Invariant 1 (`docs/NETHUNTER_MODULE.md § 4`): no active recon / capture / intercept / payload
 * operation runs outside an [EngagementState.ACTIVE] engagement whose scope covers the target and
 * whose [com.ai.assistance.operit.core.nethunter.model.Authorization] the user affirmed. A target
 * not in scope is refused with a reason naming the gap — scope is never silently widened. This is the
 * pentest analogue of default-deny.
 *
 * Pure logic, no Android dependencies, so it is reviewable and unit-testable in isolation. Matchers
 * never throw: any parse problem yields "no match" (default-deny), never an exception.
 */
object ScopeEnforcer {

    /**
     * Decide whether an active operation against [target] (interpreted as [targetKind]) is permitted
     * under [engagement]. Checks run in default-deny order: engagement live → authorized → in scope.
     */
    fun check(engagement: Engagement, targetKind: ScopeKind, target: String): ScopeDecision {
        if (engagement.state != EngagementState.ACTIVE) {
            return ScopeDecision.Denied(
                "engagement '${engagement.id}' is ${engagement.state}, not ACTIVE — activate it before running active operations"
            )
        }
        if (engagement.authorization == null) {
            return ScopeDecision.Denied(
                "engagement '${engagement.id}' has no authorization attestation — affirm authorized scope before testing"
            )
        }
        val matched = engagement.scope.entries.any { covers(it, targetKind, target) }
        if (!matched) {
            return ScopeDecision.Denied(
                "target '$target' ($targetKind) is not in scope of engagement '${engagement.id}' — add it explicitly (scope is never widened implicitly)"
            )
        }
        return ScopeDecision.Allowed
    }

    /** True if [entry] covers the ([kind], [target]) pair. */
    fun covers(entry: ScopeEntry, kind: ScopeKind, target: String): Boolean = when (entry.kind) {
        ScopeKind.CIDR -> kind == ScopeKind.HOST && ipv4InCidr(target, entry.value)
        ScopeKind.HOST -> kind == ScopeKind.HOST && target.equals(entry.value, ignoreCase = true)
        ScopeKind.SSID -> kind == ScopeKind.SSID && target == entry.value
        ScopeKind.BSSID -> kind == ScopeKind.BSSID && normalizeMac(target) == normalizeMac(entry.value)
        ScopeKind.DOMAIN -> kind == ScopeKind.DOMAIN && domainMatches(target, entry.value)
    }

    // --- matchers (total functions: never throw, false on malformed input) ---

    /** IPv4 membership test. Returns false on any parse problem (default-deny). */
    fun ipv4InCidr(ip: String, cidr: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash < 0) return false
        val network = ipv4ToInt(cidr.substring(0, slash)) ?: return false
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix < 0 || prefix > 32) return false
        val addr = ipv4ToInt(ip) ?: return false
        if (prefix == 0) return true
        val mask = (-1).shl(32 - prefix)
        return (addr and mask) == (network and mask)
    }

    private fun ipv4ToInt(s: String): Int? {
        val parts = s.trim().split('.')
        if (parts.size != 4) return null
        var acc = 0
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet < 0 || octet > 255) return null
            acc = (acc shl 8) or octet
        }
        return acc
    }

    /** Lowercase, strip separators — so `AA:BB:CC:DD:EE:FF`, `aabb.ccdd.eeff`, `aabbccddeeff` compare equal. */
    private fun normalizeMac(mac: String): String = mac.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Domain suffix match. Entry `example.com` matches `example.com` and any subdomain
     * `a.example.com`; entry `*.example.com` matches only proper subdomains, not the apex.
     */
    fun domainMatches(host: String, pattern: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        val p = pattern.trim().lowercase().trimEnd('.')
        if (p.startsWith("*.")) {
            val suffix = p.substring(2)
            return suffix.isNotEmpty() && h != suffix && h.endsWith(".$suffix")
        }
        return h == p || h.endsWith(".$p")
    }
}
