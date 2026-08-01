package com.ai.assistance.operit.core.nethunter.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique

/**
 * ObjectBox row for an engagement's non-evidence state. Nested value objects (scope, authorization,
 * targets) are stored as JSON columns rather than relations — they are small, always loaded whole,
 * and free to evolve with the domain model. Evidence is NOT stored here: it lives in [EvidenceEntity]
 * as an append-only, ordered, hash-chained series (invariant 5, docs/NETHUNTER_MODULE.md § 4).
 */
@Entity
data class EngagementEntity(
    @Id var id: Long = 0,
    /** Stable business key (domain `Engagement.id`); unique so upsert is idempotent. */
    @Unique var engagementId: String = "",
    var title: String = "",
    var createdAtMillis: Long = 0,
    var state: String = "",
    var scopeJson: String = "",
    var authorizationJson: String? = null,
    var targetsJson: String = ""
)
