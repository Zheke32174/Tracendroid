package com.ai.assistance.operit.core.tools.javascript

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-call permission gate for JS-plugin-originated tool calls (§ 4.2).
 *
 * The gate keys on (pluginId × capability class). A grant for one (plugin, capability) pair
 * does not propagate to other capabilities or other plugins. By default no plugin holds any
 * grant, and any tool call from a plugin is denied. Grants are user-managed through a
 * settings surface (UI lands separately); this object exposes the read/write API the
 * settings surface and the JS bridge consume.
 *
 * Storage is in-memory for v1. DataStore-backed persistence lands with the settings UI.
 */
object JsPluginGate {

    private const val TAG = "JsPluginGate"

    /** A capability class can be granted, explicitly denied, or unset. */
    enum class GateState { GRANTED, DENIED, UNSET }

    /** One audit event per gated call. Consumed by the (future) settings UI. */
    data class AuditEvent(
        val pluginId: String?,
        val capability: JsCapabilityClass,
        val toolType: String,
        val toolName: String,
        val decision: GateState,
        val timestamp: Long
    )

    private data class GateKey(val pluginId: String, val capability: JsCapabilityClass)

    private val grants = ConcurrentHashMap<GateKey, GateState>()

    // A bounded ring of recent audit events. Sized for one chat session's worth of calls;
    // the settings UI can drain or filter as needed.
    private const val AUDIT_BUFFER_SIZE = 256
    private val auditBuffer = CopyOnWriteArrayList<AuditEvent>()

    /**
     * Master switch. When false the gate logs but does not block — used during the rollout
     * window so existing plugins keep working while the user populates grants. When true
     * the gate is authoritative: denied calls return a structured error to the JS side.
     *
     * Per AGENTS.md no-fallback rule, this is set true by default. Set false only to
     * temporarily debug; do not ship false.
     */
    @Volatile
    var enforceGate: Boolean = true

    /**
     * Returns the recorded state for (plugin, capability), or UNSET if neither granted
     * nor explicitly denied.
     */
    fun stateFor(pluginId: String, capability: JsCapabilityClass): GateState {
        return grants[GateKey(pluginId, capability)] ?: GateState.UNSET
    }

    /** Record an explicit user grant. */
    fun grant(pluginId: String, capability: JsCapabilityClass) {
        grants[GateKey(pluginId, capability)] = GateState.GRANTED
        AppLogger.d(TAG, "grant: plugin=$pluginId capability=$capability")
    }

    /** Record an explicit user deny. */
    fun deny(pluginId: String, capability: JsCapabilityClass) {
        grants[GateKey(pluginId, capability)] = GateState.DENIED
        AppLogger.d(TAG, "deny: plugin=$pluginId capability=$capability")
    }

    /** Forget any explicit decision, returning the pair to UNSET. */
    fun forget(pluginId: String, capability: JsCapabilityClass) {
        grants.remove(GateKey(pluginId, capability))
    }

    /** Snapshot for the settings UI. */
    fun allGrants(): Map<Pair<String, JsCapabilityClass>, GateState> {
        return grants.mapKeys { (k, _) -> k.pluginId to k.capability }.toMap()
    }

    /** Snapshot for the settings UI / debug surface. */
    fun recentAudit(): List<AuditEvent> = auditBuffer.toList()

    /**
     * Evaluate whether a JS-originated tool call should be allowed. Returns true to allow
     * dispatch, false to deny.
     *
     * Decision rule:
     *  - if [pluginId] is null, the caller is anonymous — default-deny.
     *  - GRANTED → allow.
     *  - DENIED → deny.
     *  - UNSET → deny (default-deny).
     *
     * When [enforceGate] is false the decision is still computed and audited but the
     * function returns true (the call dispatches). This is intentional: the audit trail
     * stays accurate even when enforcement is disabled.
     */
    fun shouldAllow(
        pluginId: String?,
        capability: JsCapabilityClass,
        toolType: String,
        toolName: String
    ): Boolean {
        val state = when {
            pluginId == null -> GateState.UNSET
            else -> stateFor(pluginId, capability)
        }

        recordAudit(
            AuditEvent(
                pluginId = pluginId,
                capability = capability,
                toolType = toolType,
                toolName = toolName,
                decision = state,
                timestamp = System.currentTimeMillis()
            )
        )

        val computedAllow = (state == GateState.GRANTED)
        return if (enforceGate) computedAllow else true
    }

    /** Constructs the deny payload that the JS bridge returns to plugin code. */
    fun denialMessage(
        pluginId: String?,
        capability: JsCapabilityClass,
        toolType: String,
        toolName: String
    ): String {
        val who = pluginId ?: "<anonymous>"
        return "Plugin '$who' is not granted capability $capability (tool $toolType:$toolName). " +
            "User-grant required. See § 4.2 in docs/THREAT_MODEL.md."
    }

    private fun recordAudit(event: AuditEvent) {
        auditBuffer.add(event)
        // Trim from the front when oversized. CopyOnWriteArrayList is fine for occasional trims.
        while (auditBuffer.size > AUDIT_BUFFER_SIZE) {
            auditBuffer.removeAt(0)
        }
    }
}
