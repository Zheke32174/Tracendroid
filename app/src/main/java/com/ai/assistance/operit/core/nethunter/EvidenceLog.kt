package com.ai.assistance.operit.core.nethunter

import com.ai.assistance.operit.core.nethunter.model.EvidenceCapability
import com.ai.assistance.operit.core.nethunter.model.EvidenceItem
import java.security.MessageDigest

/**
 * Invariant 5 (`docs/NETHUNTER_MODULE.md § 4`): the engagement evidence log is append-only and
 * hash-chained, giving a pentest report a tamper-evident chain of custody. The app-wide audit rings
 * ([com.ai.assistance.operit.core.halt.HaltController] / `JsPluginGate`) are bounded, in-memory, and
 * evictable, so they are deliberately NOT reused for evidence.
 *
 * Chain rule: `recordHash = SHA-256(prevHash | ts | capability | tool | target | contentHash)`; each
 * item carries the previous item's [EvidenceItem.recordHash] as its [EvidenceItem.prevHash]; the
 * genesis item links to [GENESIS_PREV_HASH]. [verify] recomputes every record hash and checks the
 * prev-hash links, so any post-hoc edit to a field or any reordering breaks the chain.
 */
object EvidenceLog {

    /** The [EvidenceItem.prevHash] of the first item in a chain (64 hex zeros). */
    const val GENESIS_PREV_HASH: String =
        "0000000000000000000000000000000000000000000000000000000000000000"

    /** Hex SHA-256 of an arbitrary blob — use to produce an item's [EvidenceItem.contentHash]. */
    fun contentHashOf(bytes: ByteArray): String = sha256Hex(bytes)

    /** Hex SHA-256 of text (UTF-8). */
    fun contentHashOf(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * Build the next evidence item, linked to [previous] (or to genesis when null). Callers pass the
     * already-computed [contentHash] (via [contentHashOf]) so the blob itself never has to live here.
     */
    fun append(
        previous: EvidenceItem?,
        timestampMillis: Long,
        capability: EvidenceCapability,
        tool: String,
        target: String,
        contentHash: String,
        blobRef: String? = null,
        reasoningSnapshot: String? = null
    ): EvidenceItem {
        val prevHash = previous?.recordHash ?: GENESIS_PREV_HASH
        val recordHash = recordHash(prevHash, timestampMillis, capability, tool, target, contentHash)
        return EvidenceItem(
            timestampMillis = timestampMillis,
            capability = capability,
            tool = tool,
            target = target,
            contentHash = contentHash,
            prevHash = prevHash,
            recordHash = recordHash,
            blobRef = blobRef,
            reasoningSnapshot = reasoningSnapshot
        )
    }

    /** Recompute the canonical record hash for an item's fields. */
    fun recordHash(
        prevHash: String,
        timestampMillis: Long,
        capability: EvidenceCapability,
        tool: String,
        target: String,
        contentHash: String
    ): String {
        // Length-prefixed join so field boundaries can't be forged by shifting content between fields.
        val canonical = buildString {
            appendField(prevHash)
            appendField(timestampMillis.toString())
            appendField(capability.name)
            appendField(tool)
            appendField(target)
            appendField(contentHash)
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * Verify a full chain: each item's [EvidenceItem.recordHash] must recompute, and each
     * [EvidenceItem.prevHash] must equal the prior item's record hash (the genesis item links to
     * [GENESIS_PREV_HASH]). Returns the first break, or [VerificationResult.Intact].
     */
    fun verify(chain: List<EvidenceItem>): VerificationResult {
        var expectedPrev = GENESIS_PREV_HASH
        chain.forEachIndexed { index, item ->
            if (item.prevHash != expectedPrev) {
                return VerificationResult.Broken(index, "prevHash link mismatch")
            }
            val recomputed = recordHash(
                item.prevHash, item.timestampMillis, item.capability, item.tool, item.target, item.contentHash
            )
            if (recomputed != item.recordHash) {
                return VerificationResult.Broken(index, "recordHash mismatch — item was altered")
            }
            expectedPrev = item.recordHash
        }
        return VerificationResult.Intact
    }

    sealed interface VerificationResult {
        data object Intact : VerificationResult
        data class Broken(val index: Int, val reason: String) : VerificationResult
    }

    private fun StringBuilder.appendField(field: String) {
        append(field.length)
        append(':')
        append(field)
        append('|')
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
