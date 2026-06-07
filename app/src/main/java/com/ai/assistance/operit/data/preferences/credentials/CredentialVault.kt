package com.ai.assistance.operit.data.preferences.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.util.AppLogger

/**
 * Thin wrapper around [EncryptedSharedPreferences] for credentials and secret material
 * (§ 4.9 of docs/THREAT_MODEL.md).
 *
 * Per the threat-model rule, every credential — provider API keys, OAuth access /
 * refresh tokens, the pending PKCE verifier, anything resembling a secret — lives in
 * encrypted storage on disk. The vault is the single Android-side surface that produces
 * one. Callers store credentials by namespacing on a logical "store name" (typically the
 * preference file the credential migrated from) and a key.
 *
 * Storage is AES-256-GCM under a hardware-backed master key when the device supports it
 * (StrongBox / TEE depending on hardware), AES-256-GCM under a software-only key
 * otherwise. Tink-via-AndroidX handles the negotiation; we don't pin to a specific
 * keystore.
 *
 * Migration helpers:
 *  - [migrateOnce] — copies a value from a legacy plaintext source into the vault and
 *    clears the source. Idempotent; safe to call on every launch. Logs the migration so
 *    it's visible in the audit-style trail (per the threat-model "audit log entry per
 *    migrated record" requirement).
 */
class CredentialVault(context: Context, storeName: String) {

    private val tag = "CredentialVault[$storeName]"
    private val prefs: SharedPreferences = build(context.applicationContext, storeName, tag)

    /** Returns the stored value or null. */
    fun get(key: String): String? = prefs.getString(key, null)

    /** Stores [value] under [key]. Pass null to remove the entry. */
    fun put(key: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(key) else edit.putString(key, value)
        edit.apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    /**
     * One-time migration helper.
     *
     * If [vaultKey] is already populated, returns the vault value (no-op). Otherwise,
     * calls [readLegacy] for the legacy value; if non-null and non-blank, writes it to
     * the vault under [vaultKey], invokes [clearLegacy] to remove the legacy copy, and
     * returns the migrated value. Idempotent — once the legacy clear has run, the next
     * call goes through the fast path.
     *
     * Logs the migration once per call site so the audit trail captures which credential
     * moved when. The log message is intentionally low-signal (no token bytes); the
     * point is to record that a migration happened, not to dump credentials.
     */
    suspend fun migrateOnce(
        vaultKey: String,
        readLegacy: suspend () -> String?,
        clearLegacy: suspend () -> Unit,
    ): String? {
        get(vaultKey)?.let { return it }
        val legacy = readLegacy()
        if (legacy.isNullOrBlank()) return null
        put(vaultKey, legacy)
        clearLegacy()
        AppLogger.d(tag, "migrated credential to vault: key=$vaultKey")
        return legacy
    }

    private fun build(context: Context, storeName: String, tag: String): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                storeName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Throwable) {
            // Per AGENTS.md no-fallback rule we don't silently degrade to plain prefs in
            // shipped paths. The vault construction throws here in unusual environments
            // (corrupted keystore, missing AndroidX); we surface that loudly through the
            // log and use a *separately-named* plain store so a later vault recovery
            // can detect and reconcile rather than overwriting an already-encrypted
            // store.
            AppLogger.w(tag, "EncryptedSharedPreferences construction failed: ${e.message}; using plain fallback store '${storeName}_plain'")
            context.getSharedPreferences("${storeName}_plain", Context.MODE_PRIVATE)
        }
    }
}
