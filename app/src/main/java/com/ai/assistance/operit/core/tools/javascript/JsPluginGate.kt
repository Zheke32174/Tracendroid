package com.ai.assistance.operit.core.tools.javascript

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-call permission gate for JS-plugin-originated tool calls (§ 4.2).
 *
 * The gate keys on (pluginId × capability class). A grant for one (plugin, capability) pair
 * does not propagate to other capabilities or other plugins. By default no plugin holds any
 * grant, and any tool call from a plugin is denied. Grants are user-managed through the
 * settings UI (see [com.ai.assistance.operit.ui.features.plugingate]); this object exposes
 * the read/write API the UI and the JS bridge consume.
 *
 * Persistence is supplied by an external [Persister] (see [JsPluginGatePersistence]) wired
 * in at app init so this object stays Android-independent.
 */
object JsPluginGate {

    private const val TAG = "JsPluginGate"

    /** A capability class can be granted, explicitly denied, or unset. */
    enum class GateState { GRANTED, DENIED, UNSET }

    /** One audit event per gated call. Consumed by the settings UI. */
    data class AuditEvent(
        val pluginId: String?,
        val capability: JsCapabilityClass,
        val toolType: String,
        val toolName: String,
        val decision: GateState,
        val timestamp: Long
    )

    /** A grant entry keyed by (pluginId, capability). */
    data class GrantEntry(
        val pluginId: String,
        val capability: JsCapabilityClass,
        val state: GateState,
    )

    /**
     * One pending decision waiting for the user. Created whenever [shouldAllow] denies a
     * call because the (plugin, capability) pair is in [GateState.UNSET]. The UI overlay
     * observes [pendingFlow] and surfaces a dialog; user action calls [grant] or [deny],
     * which removes the entry from the pending set.
     */
    data class PendingRequest(
        val pluginId: String,
        val capability: JsCapabilityClass,
        val toolType: String,
        val toolName: String,
        val firstSeenAtMillis: Long,
    )

    /**
     * Storage backend. Implementations decide where grants live (DataStore, in-memory,
     * file, etc.). The gate calls [save] on every grant/deny/forget. [loadInto] is
     * invoked once at startup to populate the in-memory cache.
     */
    interface Persister {
        fun loadInto(sink: (GrantEntry) -> Unit)
        fun save(entry: GrantEntry)
        fun remove(pluginId: String, capability: JsCapabilityClass)
    }

    private data class GateKey(val pluginId: String, val capability: JsCapabilityClass)

    private val grants = ConcurrentHashMap<GateKey, GateState>()

    private val _grantsFlow = MutableStateFlow<Map<Pair<String, JsCapabilityClass>, GateState>>(emptyMap())
    val grantsFlow: StateFlow<Map<Pair<String, JsCapabilityClass>, GateState>> = _grantsFlow.asStateFlow()

    private val _auditFlow = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditFlow: StateFlow<List<AuditEvent>> = _auditFlow.asStateFlow()

    private val pending = ConcurrentHashMap<GateKey, PendingRequest>()
    private val _pendingFlow = MutableStateFlow<List<PendingRequest>>(emptyList())
    val pendingFlow: StateFlow<List<PendingRequest>> = _pendingFlow.asStateFlow()

    private const val AUDIT_BUFFER_SIZE = 256
    private val auditBuffer = CopyOnWriteArrayList<AuditEvent>()

    @Volatile
    private var persister: Persister? = null

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
     * Install a persistence backend and load any persisted grants. Idempotent: a second
     * call replaces the backend but does not reload (the caller is responsible for
     * sequencing).
     */
    fun installPersister(p: Persister) {
        persister = p
        val loaded = mutableMapOf<GateKey, GateState>()
        p.loadInto { entry ->
            if (entry.state != GateState.UNSET) {
                loaded[GateKey(entry.pluginId, entry.capability)] = entry.state
            }
        }
        grants.clear()
        grants.putAll(loaded)
        refreshFlow()
        AppLogger.d(TAG, "persister installed; ${grants.size} grants loaded")
    }

    /**
     * Returns the recorded state for (plugin, capability), or UNSET if neither granted
     * nor explicitly denied.
     */
    fun stateFor(pluginId: String, capability: JsCapabilityClass): GateState {
        return grants[GateKey(pluginId, capability)] ?: GateState.UNSET
    }

    fun grant(pluginId: String, capability: JsCapabilityClass) {
        grants[GateKey(pluginId, capability)] = GateState.GRANTED
        persister?.save(GrantEntry(pluginId, capability, GateState.GRANTED))
        clearPending(pluginId, capability)
        refreshFlow()
        AppLogger.d(TAG, "grant: plugin=$pluginId capability=$capability")
    }

    fun deny(pluginId: String, capability: JsCapabilityClass) {
        grants[GateKey(pluginId, capability)] = GateState.DENIED
        persister?.save(GrantEntry(pluginId, capability, GateState.DENIED))
        clearPending(pluginId, capability)
        refreshFlow()
        AppLogger.d(TAG, "deny: plugin=$pluginId capability=$capability")
    }

    fun forget(pluginId: String, capability: JsCapabilityClass) {
        grants.remove(GateKey(pluginId, capability))
        persister?.remove(pluginId, capability)
        clearPending(pluginId, capability)
        refreshFlow()
    }

    /** Dismiss a pending request without recording a grant or deny. */
    fun dismissPending(pluginId: String, capability: JsCapabilityClass) {
        clearPending(pluginId, capability)
    }

    private fun clearPending(pluginId: String, capability: JsCapabilityClass) {
        if (pending.remove(GateKey(pluginId, capability)) != null) {
            _pendingFlow.value = pending.values.toList()
        }
    }

    /** Snapshot for callers that want a pull rather than an observe. */
    fun allGrants(): Map<Pair<String, JsCapabilityClass>, GateState> = _grantsFlow.value

    /** Snapshot of the audit ring. */
    fun recentAudit(): List<AuditEvent> = _auditFlow.value

    /**
     * Evaluate whether a JS-originated tool call should be allowed. Returns true to allow
     * dispatch, false to deny.
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

        val now = System.currentTimeMillis()
        recordAudit(
            AuditEvent(
                pluginId = pluginId,
                capability = capability,
                toolType = toolType,
                toolName = toolName,
                decision = state,
                timestamp = now,
            )
        )

        // An UNSET decision from a known caller becomes a pending confirmation for the
        // UI overlay to surface. Explicit DENIED never becomes pending — the user has
        // already said no.
        if (pluginId != null && state == GateState.UNSET) {
            val key = GateKey(pluginId, capability)
            if (!pending.containsKey(key)) {
                pending[key] = PendingRequest(
                    pluginId = pluginId,
                    capability = capability,
                    toolType = toolType,
                    toolName = toolName,
                    firstSeenAtMillis = now,
                )
                _pendingFlow.value = pending.values.toList()
            }
        }

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

    private fun refreshFlow() {
        _grantsFlow.value = grants.mapKeys { (k, _) -> k.pluginId to k.capability }.toMap()
    }

    private fun recordAudit(event: AuditEvent) {
        auditBuffer.add(event)
        while (auditBuffer.size > AUDIT_BUFFER_SIZE) {
            auditBuffer.removeAt(0)
        }
        _auditFlow.value = auditBuffer.toList()
    }
}
