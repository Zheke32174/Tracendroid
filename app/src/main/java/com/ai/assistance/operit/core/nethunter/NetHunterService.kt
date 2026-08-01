package com.ai.assistance.operit.core.nethunter

import android.content.Context
import com.ai.assistance.operit.core.halt.HaltController
import com.ai.assistance.operit.core.nethunter.data.ObjectBoxEngagementStore
import com.ai.assistance.operit.core.nethunter.model.EvidenceCapability
import com.ai.assistance.operit.core.nethunter.model.EvidenceItem
import com.ai.assistance.operit.core.nethunter.model.ScopeKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Integration backbone for the NetHunter module (docs/NETHUNTER_MODULE.md § 5): the single
 * gated-execution path every `nh_*` tool routes through. It ties together the durable per-profile
 * store, the currently-active engagement, the scope gate (invariant 1), the sovereign halt
 * (invariant 4), and the append-only hash-chained evidence log (invariant 5). Manual-singleton DI,
 * matching the rest of the app.
 *
 * Tools MUST call [gate] before any active operation and [recordEvidence] after producing a result;
 * they must not reach ScopeEnforcer / EvidenceLog / the store directly, so each invariant is enforced
 * in exactly one place. Passive, target-less environment survey (listing what Wi-Fi/BLE is in the
 * air) is the one thing allowed to skip [gate] — it is still classified + confirmed through the tool
 * gate and never associates an observation with a target until the user adds it to scope.
 */
object NetHunterService {

    private val stores = ConcurrentHashMap<String, EngagementStore>()

    /** The per-profile durable store (created lazily; the app keeps one ObjectBox store per profile). */
    fun store(context: Context, profileId: String): EngagementStore =
        stores.getOrPut(profileId) { ObjectBoxEngagementStore(context.applicationContext, profileId) }

    private val _activeEngagementId = MutableStateFlow<String?>(null)

    /** The engagement active operations run against; null until the user creates/activates one. */
    val activeEngagementId: StateFlow<String?> = _activeEngagementId.asStateFlow()

    fun setActiveEngagement(id: String?) {
        _activeEngagementId.value = id
    }

    /** Result of the pre-operation gate. */
    sealed interface GateOutcome {
        data class Allowed(val engagementId: String) : GateOutcome
        data class Denied(val reason: String) : GateOutcome
    }

    /**
     * The one gate every active `nh_*` operation passes: halt first (invariant 4), then an active
     * engagement, then scope (invariant 1). [target] is interpreted as [targetKind].
     */
    fun gate(store: EngagementStore, targetKind: ScopeKind, target: String): GateOutcome {
        if (HaltController.isHalted) {
            return GateOutcome.Denied(HaltController.haltedRefusal("nethunter"))
        }
        val id = _activeEngagementId.value
            ?: return GateOutcome.Denied(
                "no active engagement — create and activate one before running operations"
            )
        val engagement = store.get(id)
            ?: return GateOutcome.Denied("active engagement '$id' no longer exists")
        return when (val decision = ScopeEnforcer.check(engagement, targetKind, target)) {
            is ScopeDecision.Allowed -> GateOutcome.Allowed(id)
            is ScopeDecision.Denied -> GateOutcome.Denied(decision.reason)
        }
    }

    /**
     * Append one evidence record to [engagementId]'s chain (invariant 5), linked to the current tail.
     * [contentHash] comes from [EvidenceLog.contentHashOf] over the captured content; [nowMillis] is
     * supplied by the caller (testable). Fail-closed: propagates if the store rejects the append, so
     * the caller aborts rather than proceeding unrecorded.
     */
    fun recordEvidence(
        store: EngagementStore,
        engagementId: String,
        capability: EvidenceCapability,
        tool: String,
        target: String,
        contentHash: String,
        nowMillis: Long,
        blobRef: String? = null,
        reasoningSnapshot: String? = null
    ): EvidenceItem {
        val engagement = store.get(engagementId)
            ?: throw IllegalStateException("recordEvidence: no engagement '$engagementId'")
        val item = EvidenceLog.append(
            previous = engagement.evidence.lastOrNull(),
            timestampMillis = nowMillis,
            capability = capability,
            tool = tool,
            target = target,
            contentHash = contentHash,
            blobRef = blobRef,
            reasoningSnapshot = reasoningSnapshot
        )
        store.appendEvidence(engagementId, item)
        return item
    }
}
