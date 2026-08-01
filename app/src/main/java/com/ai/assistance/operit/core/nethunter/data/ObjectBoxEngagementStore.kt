package com.ai.assistance.operit.core.nethunter.data

import android.content.Context
import com.ai.assistance.operit.core.nethunter.EngagementStore
import com.ai.assistance.operit.core.nethunter.EvidenceLog
import com.ai.assistance.operit.core.nethunter.model.Authorization
import com.ai.assistance.operit.core.nethunter.model.Engagement
import com.ai.assistance.operit.core.nethunter.model.EngagementState
import com.ai.assistance.operit.core.nethunter.model.EvidenceCapability
import com.ai.assistance.operit.core.nethunter.model.EvidenceItem
import com.ai.assistance.operit.core.nethunter.model.Scope
import com.ai.assistance.operit.core.nethunter.model.Target
import com.ai.assistance.operit.data.db.ObjectBoxManager
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Durable [EngagementStore] backed by ObjectBox (mirrors `data/repository/*`). Engagement scalar
 * state lives in [EngagementEntity] (nested value objects as JSON columns); the append-only,
 * hash-chained evidence lives in [EvidenceEntity], one row per record ordered by `seq`.
 *
 * Per invariant 5 (docs/NETHUNTER_MODULE.md § 4), [appendEvidence] is fail-closed: it verifies the
 * new item extends the current chain and lets any ObjectBox failure propagate so the caller aborts.
 * Stores are per-profile, matching the rest of the app's ObjectBox usage.
 */
class ObjectBoxEngagementStore(context: Context, profileId: String) : EngagementStore {

    private val store = ObjectBoxManager.get(context, profileId)
    private val engagementBox: Box<EngagementEntity> = store.boxFor()
    private val evidenceBox: Box<EvidenceEntity> = store.boxFor()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _engagements = MutableStateFlow<List<Engagement>>(emptyList())
    override val engagements: StateFlow<List<Engagement>> = _engagements.asStateFlow()

    init { refresh() }

    override fun get(id: String): Engagement? {
        val entity = findEntity(id) ?: return null
        return entity.toDomain(loadEvidence(id))
    }

    override fun upsert(engagement: Engagement) {
        val existing = findEntity(engagement.id)
        val entity = (existing ?: EngagementEntity()).apply {
            engagementId = engagement.id
            title = engagement.title
            createdAtMillis = engagement.createdAtMillis
            state = engagement.state.name
            scopeJson = json.encodeToString(Scope.serializer(), engagement.scope)
            authorizationJson = engagement.authorization
                ?.let { json.encodeToString(Authorization.serializer(), it) }
            targetsJson = json.encodeToString(ListSerializer(Target.serializer()), engagement.targets)
        }
        engagementBox.put(entity)
        refresh()
    }

    @Synchronized
    override fun appendEvidence(engagementId: String, item: EvidenceItem) {
        findEntity(engagementId)
            ?: throw IllegalStateException("appendEvidence: no engagement '$engagementId'")
        val existing = loadEvidenceEntities(engagementId)
        val expectedPrev = existing.lastOrNull()?.recordHash ?: EvidenceLog.GENESIS_PREV_HASH
        require(item.prevHash == expectedPrev) {
            "appendEvidence: item does not extend the chain (prevHash != last recordHash) — refusing to break append-only integrity"
        }
        evidenceBox.put(item.toEntity(engagementId, existing.size.toLong()))
        refresh()
    }

    // --- helpers ---

    private fun findEntity(id: String): EngagementEntity? =
        engagementBox.query(EngagementEntity_.engagementId.equal(id)).build().use { it.findFirst() }

    private fun loadEvidenceEntities(id: String): List<EvidenceEntity> =
        evidenceBox.query(EvidenceEntity_.engagementId.equal(id))
            .order(EvidenceEntity_.seq).build().use { it.find() }

    private fun loadEvidence(id: String): List<EvidenceItem> =
        loadEvidenceEntities(id).map { it.toDomain() }

    private fun refresh() {
        _engagements.value = engagementBox.all
            .map { it.toDomain(loadEvidence(it.engagementId)) }
            .sortedByDescending { it.createdAtMillis }
    }

    private fun EngagementEntity.toDomain(evidence: List<EvidenceItem>): Engagement = Engagement(
        id = engagementId,
        title = title,
        createdAtMillis = createdAtMillis,
        state = runCatching { EngagementState.valueOf(state) }.getOrDefault(EngagementState.DRAFT),
        scope = if (scopeJson.isBlank()) Scope() else json.decodeFromString(Scope.serializer(), scopeJson),
        authorization = authorizationJson?.let { json.decodeFromString(Authorization.serializer(), it) },
        targets = if (targetsJson.isBlank()) emptyList()
        else json.decodeFromString(ListSerializer(Target.serializer()), targetsJson),
        evidence = evidence
    )

    private fun EvidenceEntity.toDomain(): EvidenceItem = EvidenceItem(
        timestampMillis = timestampMillis,
        capability = runCatching { EvidenceCapability.valueOf(capability) }
            .getOrDefault(EvidenceCapability.RECON),
        tool = tool,
        target = target,
        contentHash = contentHash,
        prevHash = prevHash,
        recordHash = recordHash,
        blobRef = blobRef,
        reasoningSnapshot = reasoningSnapshot
    )

    private fun EvidenceItem.toEntity(engagementId: String, seq: Long): EvidenceEntity = EvidenceEntity(
        engagementId = engagementId,
        seq = seq,
        timestampMillis = timestampMillis,
        capability = capability.name,
        tool = tool,
        target = target,
        contentHash = contentHash,
        prevHash = prevHash,
        recordHash = recordHash,
        blobRef = blobRef,
        reasoningSnapshot = reasoningSnapshot
    )
}
