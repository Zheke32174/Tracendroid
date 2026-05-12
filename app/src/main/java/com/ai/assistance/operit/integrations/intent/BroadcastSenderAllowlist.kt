package com.ai.assistance.operit.integrations.intent

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sender allowlist for exported broadcast receivers (§ 4.1).
 *
 * Receivers that need to accept intents from external apps consult this object before
 * doing any work. The allowlist is per-receiver (keyed on a label like "external_chat"
 * or "workflow_tasker") and persists in SharedPreferences. Default state for every
 * receiver is the empty set — no external sender is allowed until the user adds one.
 *
 * The receiver gets the sender package via [resolveSender], which reads
 * [Intent.getPackage] from the originating intent (set by callers that opt in to
 * cross-app dispatch), falling back to null when no caller package is known. Allowlist
 * miss → receiver refuses.
 *
 * This is intentionally simpler than Android's signature permission system: signature
 * perms require the legitimate caller to be co-signed with us, which doesn't fit Tasker
 * or arbitrary user apps. A package-name allowlist is the right granularity for v1, with
 * the obvious caveat that a malicious app could spoof a package name on a rooted device.
 * Per docs/SECURITY.md the project does not target rooted devices.
 */
class BroadcastSenderAllowlist(context: Context) {

    companion object {
        private const val TAG = "BroadcastSenderAllowlist"
        private const val PREFS_NAME = "broadcast_sender_allowlist"
        private const val KEY_PREFIX = "allowed:"
        private const val SEPARATOR = "\n"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val flows = HashMap<String, MutableStateFlow<Set<String>>>()

    /** Observe the current allowlist for [receiverLabel]. */
    @Synchronized
    fun observe(receiverLabel: String): StateFlow<Set<String>> {
        return flows.getOrPut(receiverLabel) {
            MutableStateFlow(loadFromPrefs(receiverLabel))
        }.asStateFlow()
    }

    /** Snapshot for callers that don't want to subscribe. */
    fun current(receiverLabel: String): Set<String> = loadFromPrefs(receiverLabel)

    /** Returns true when [senderPackage] is currently allowed for [receiverLabel]. */
    fun isAllowed(receiverLabel: String, senderPackage: String?): Boolean {
        if (senderPackage.isNullOrBlank()) return false
        return loadFromPrefs(receiverLabel).contains(senderPackage)
    }

    @Synchronized
    fun add(receiverLabel: String, senderPackage: String) {
        val pkg = senderPackage.trim()
        if (pkg.isEmpty()) return
        val updated = loadFromPrefs(receiverLabel) + pkg
        persist(receiverLabel, updated)
        AppLogger.d(TAG, "added $pkg to $receiverLabel")
    }

    @Synchronized
    fun remove(receiverLabel: String, senderPackage: String) {
        val pkg = senderPackage.trim()
        if (pkg.isEmpty()) return
        val updated = loadFromPrefs(receiverLabel) - pkg
        persist(receiverLabel, updated)
        AppLogger.d(TAG, "removed $pkg from $receiverLabel")
    }

    private fun loadFromPrefs(receiverLabel: String): Set<String> {
        val raw = prefs.getString(KEY_PREFIX + receiverLabel, null) ?: return emptySet()
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun persist(receiverLabel: String, allowed: Set<String>) {
        prefs.edit()
            .putString(KEY_PREFIX + receiverLabel, allowed.joinToString(SEPARATOR))
            .apply()
        flows[receiverLabel]?.value = allowed
    }
}
