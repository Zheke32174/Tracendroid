package com.ai.assistance.operit.core.plugintrust

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trust-on-first-use record for plugin publisher keys (`AUDIT_PLAN.md § 1.1`).
 *
 * Maps `pluginId` → `(publisherKeyFingerprint, publisherName, recordedAtMillis)`. First
 * install adds the entry; subsequent updates verify the publisher fingerprint matches.
 * A mismatch refuses the update — the user has to deliberately remove the existing
 * entry (and lose every grant on that pluginId) to re-TOFU under a different publisher.
 *
 * The publisher name is stored alongside the fingerprint for surface display (the
 * install / update prompt shows both), but the trust binding is on the fingerprint
 * only — a publisher can change their display name freely without breaking trust.
 *
 * Storage is plain SharedPreferences. The records are user policy, not authentication
 * material; an attacker with on-device write access has bigger problems than swapping a
 * fingerprint here.
 */
class PluginPublisherTofuStore(context: Context) {

    companion object {
        private const val TAG = "PluginPublisherTofuStore"
        private const val PREFS_NAME = "plugin_publisher_tofu"
        private const val KEY_PREFIX_FINGERPRINT = "fp:"
        private const val KEY_PREFIX_NAME = "name:"
        private const val KEY_PREFIX_RECORDED_AT = "at:"
    }

    /** One TOFU record. */
    data class Record(
        val pluginId: String,
        val publisherKeyFingerprint: String,
        val publisherName: String,
        val recordedAtMillis: Long,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _records = MutableStateFlow(loadAll())
    val records: StateFlow<List<Record>> = _records.asStateFlow()

    /** Record for [pluginId], or null if no first-install has happened yet. */
    fun get(pluginId: String): Record? {
        val fp = prefs.getString(KEY_PREFIX_FINGERPRINT + pluginId, null) ?: return null
        val name = prefs.getString(KEY_PREFIX_NAME + pluginId, null).orEmpty()
        val at = prefs.getLong(KEY_PREFIX_RECORDED_AT + pluginId, 0L)
        return Record(pluginId, fp, name, at)
    }

    /**
     * Record the first-install pairing. Idempotent only when the new fingerprint matches
     * the existing one — caller is expected to consult [get] first when handling an
     * update; calling [recordFirstInstall] over a mismatching existing entry overwrites
     * silently and is a programming error.
     */
    fun recordFirstInstall(pluginId: String, fingerprint: String, publisherName: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_PREFIX_FINGERPRINT + pluginId, fingerprint)
            .putString(KEY_PREFIX_NAME + pluginId, publisherName)
            .putLong(KEY_PREFIX_RECORDED_AT + pluginId, now)
            .apply()
        _records.value = loadAll()
        AppLogger.d(TAG, "TOFU recorded: pluginId=$pluginId fp=$fingerprint")
    }

    /**
     * Forget the TOFU record. Used when the user deliberately wants to re-trust under a
     * different publisher key. The caller is responsible for clearing the matching
     * capability grants from `JsPluginGate` if appropriate.
     */
    fun forget(pluginId: String) {
        prefs.edit()
            .remove(KEY_PREFIX_FINGERPRINT + pluginId)
            .remove(KEY_PREFIX_NAME + pluginId)
            .remove(KEY_PREFIX_RECORDED_AT + pluginId)
            .apply()
        _records.value = loadAll()
        AppLogger.d(TAG, "TOFU forgotten: pluginId=$pluginId")
    }

    private fun loadAll(): List<Record> {
        val all = prefs.all
        val pluginIds = all.keys
            .filter { it.startsWith(KEY_PREFIX_FINGERPRINT) }
            .map { it.removePrefix(KEY_PREFIX_FINGERPRINT) }
            .sorted()
        return pluginIds.mapNotNull { get(it) }
    }
}
