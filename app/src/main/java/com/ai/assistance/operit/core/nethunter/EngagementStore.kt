package com.ai.assistance.operit.core.nethunter

import com.ai.assistance.operit.core.nethunter.model.Engagement
import com.ai.assistance.operit.core.nethunter.model.EvidenceItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence seam for engagements and their append-only evidence chains
 * (`docs/NETHUNTER_MODULE.md § 5.3`). Per invariant 5 the evidence log is append-only; a caller that
 * cannot record evidence must fail the action closed — [appendEvidence] therefore throws rather than
 * silently dropping or reordering a record, and the caller treats the throw as abort.
 *
 * [InMemoryEngagementStore] is the reference backend that pins the contract. The durable
 * ObjectBox-backed store (`@Entity` + `ObjectBoxManager`, following `data/repository/*`) is the
 * immediate next increment. This is a defined seam, not a fallback: there is no silent-degradation
 * path between the two.
 */
interface EngagementStore {
    /** All engagements, newest-first; updates as engagements are upserted or evidence is appended. */
    val engagements: StateFlow<List<Engagement>>

    fun get(id: String): Engagement?

    /** Create or replace an engagement's non-evidence state (title, scope, authorization, targets). */
    fun upsert(engagement: Engagement)

    /**
     * Append a pre-linked evidence [item] to the engagement, atomically. Build [item] with
     * [EvidenceLog.append] from the engagement's current last evidence item so it extends the chain.
     * Throws if the engagement is unknown or if [item] does not correctly extend the current chain —
     * the caller treats a throw as fail-closed and aborts the operation.
     */
    fun appendEvidence(engagementId: String, item: EvidenceItem)
}

/**
 * In-memory reference implementation of [EngagementStore]. Thread-safe and correct against the
 * contract, but not durable across process death — superseded by the ObjectBox-backed store next.
 * Retained afterwards as the contract oracle for tests.
 */
class InMemoryEngagementStore : EngagementStore {

    private val byId = ConcurrentHashMap<String, Engagement>()
    private val _engagements = MutableStateFlow<List<Engagement>>(emptyList())
    override val engagements: StateFlow<List<Engagement>> = _engagements.asStateFlow()

    override fun get(id: String): Engagement? = byId[id]

    override fun upsert(engagement: Engagement) {
        byId[engagement.id] = engagement
        publish()
    }

    @Synchronized
    override fun appendEvidence(engagementId: String, item: EvidenceItem) {
        val current = byId[engagementId]
            ?: throw IllegalStateException("appendEvidence: no engagement '$engagementId'")
        val expectedPrev = current.evidence.lastOrNull()?.recordHash ?: EvidenceLog.GENESIS_PREV_HASH
        require(item.prevHash == expectedPrev) {
            "appendEvidence: item does not extend the chain (prevHash != last recordHash) — refusing to break append-only integrity"
        }
        byId[engagementId] = current.copy(evidence = current.evidence + item)
        publish()
    }

    private fun publish() {
        _engagements.value = byId.values.sortedByDescending { it.createdAtMillis }
    }
}
