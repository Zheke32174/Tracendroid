package dev.pleiades.masamune.ai.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Keystore-backed storage for account tokens.
 *
 * `ProviderStore` keeps the API key in plain app-private SharedPreferences and says so on
 * screen. An OAuth refresh token is worse to lose than an API key — it is a standing grant on
 * the user's whole account — so it does not go there. Every value written here is sealed with
 * an AES-256-GCM key generated inside `AndroidKeyStore`, which means the key material never
 * enters this process's address space and is hardware-backed on any device with a TEE or
 * StrongBox. What lands in SharedPreferences is `iv:ciphertext`, base64.
 *
 * Honest boundary, stated on the Account screen too: `setUserAuthenticationRequired` is NOT
 * set. Sealing is bound to this app on this device, not to a screen unlock — a running root
 * shell can still ask the Keystore to unseal. Requiring an unlock per request would break
 * background refresh, so the tradeoff is made deliberately rather than silently.
 *
 * If the Keystore is unavailable (some heavily modified ROMs, or a wiped key after a device
 * credential reset), [unavailableReason] is non-null and the whole subscription mode renders
 * disabled with that sentence. Tokens are never quietly downgraded to plaintext.
 */
class TokenVault private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Non-null means sealing does not work on this device; nothing is stored. */
    var unavailableReason: String? = null
        private set

    private val key: SecretKey? = runCatching { loadOrCreateKey() }
        .onFailure {
            unavailableReason = "The Android Keystore refused to provide an AES-GCM key on " +
                "this device (${it.javaClass.simpleName}: ${it.message}). Account tokens are " +
                "only ever stored sealed, so subscription sign-in is unavailable here rather " +
                "than falling back to plaintext. API key mode still works."
        }
        .getOrNull()

    val isUsable: Boolean get() = key != null

    // ---- session records -------------------------------------------------------------------

    fun readSession(profileId: String): AccountSession? {
        val json = readJson(sessionKey(profileId)) ?: return null
        return runCatching {
            AccountSession(
                profileId = json.getString("profile_id"),
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresAt = json.getLong("expires_at"),
                scope = json.optString("scope"),
                identity = json.optJSONObject("identity")?.let {
                    AccountIdentity(
                        subject = it.optString("sub"),
                        email = it.optString("email").takeIf { v -> v.isNotBlank() },
                        name = it.optString("name").takeIf { v -> v.isNotBlank() },
                    )
                },
                signedInAt = json.optLong("signed_in_at"),
            )
        }.getOrNull()
    }

    fun writeSession(session: AccountSession) {
        val json = JSONObject().apply {
            put("profile_id", session.profileId)
            put("access_token", session.accessToken)
            put("refresh_token", session.refreshToken ?: "")
            put("expires_at", session.expiresAt)
            put("scope", session.scope)
            put("signed_in_at", session.signedInAt)
            session.identity?.let {
                put(
                    "identity",
                    JSONObject().apply {
                        put("sub", it.subject)
                        put("email", it.email ?: "")
                        put("name", it.name ?: "")
                    },
                )
            }
        }
        writeJson(sessionKey(session.profileId), json)
    }

    fun clearSession(profileId: String) {
        prefs.edit().remove(sessionKey(profileId)).apply()
    }

    // ---- client registrations --------------------------------------------------------------

    fun readRegistration(profileId: String): OAuthClientRegistration? {
        val json = readJson(clientKey(profileId)) ?: return null
        return runCatching {
            OAuthClientRegistration(
                profileId = profileId,
                clientId = json.getString("client_id"),
                clientSecret = json.optString("client_secret").takeIf { it.isNotBlank() },
                issuer = json.optString("issuer"),
                selfRegistered = json.optBoolean("self_registered", false),
            )
        }.getOrNull()
    }

    fun writeRegistration(registration: OAuthClientRegistration) {
        writeJson(
            clientKey(registration.profileId),
            JSONObject().apply {
                put("client_id", registration.clientId)
                put("client_secret", registration.clientSecret ?: "")
                put("issuer", registration.issuer)
                put("self_registered", registration.selfRegistered)
            },
        )
    }

    fun clearRegistration(profileId: String) {
        prefs.edit().remove(clientKey(profileId)).apply()
    }

    /** Endpoints discovered at runtime for the custom issuer, cached so sign-in is one step. */
    fun readDiscovery(profileId: String): JSONObject? = readJson(discoveryKey(profileId))

    fun writeDiscovery(profileId: String, doc: JSONObject) = writeJson(discoveryKey(profileId), doc)

    // ---- sealing -----------------------------------------------------------------------------

    private fun readJson(name: String): JSONObject? {
        val k = key ?: return null
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val parts = stored.split(':', limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        }.getOrNull()
    }

    private fun writeJson(name: String, json: JSONObject) {
        val k = key ?: return
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val sealed = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(sealed, Base64.NO_WRAP)
            prefs.edit().putString(name, encoded).apply()
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately false: automatic refresh has to run without a screen unlock.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun sessionKey(profileId: String) = "session.$profileId"
    private fun clientKey(profileId: String) = "client.$profileId"
    private fun discoveryKey(profileId: String) = "discovery.$profileId"

    companion object {
        private const val PREFS_NAME = "masamune_account_vault"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "masamune.account.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        @Volatile
        private var instance: TokenVault? = null

        fun get(context: Context): TokenVault =
            instance ?: synchronized(this) {
                instance ?: TokenVault(context).also { instance = it }
            }
    }
}
