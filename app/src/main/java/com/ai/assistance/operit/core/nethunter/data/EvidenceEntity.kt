package com.ai.assistance.operit.core.nethunter.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * ObjectBox row for one evidence record (invariant 5, docs/NETHUNTER_MODULE.md § 4). Rows are
 * append-only and ordered by [seq] within an engagement; the hash chain ([prevHash] / [recordHash])
 * is computed in [com.ai.assistance.operit.core.nethunter.EvidenceLog] before insert and never
 * mutated afterward.
 */
@Entity
data class EvidenceEntity(
    @Id var id: Long = 0,
    @Index var engagementId: String = "",
    /** Monotonic per-engagement ordering index (0-based), preserving append order. */
    var seq: Long = 0,
    var timestampMillis: Long = 0,
    var capability: String = "",
    var tool: String = "",
    var target: String = "",
    var contentHash: String = "",
    var prevHash: String = "",
    var recordHash: String = "",
    var blobRef: String? = null,
    var reasoningSnapshot: String? = null
)
