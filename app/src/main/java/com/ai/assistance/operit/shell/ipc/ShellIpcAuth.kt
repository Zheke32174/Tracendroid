package com.ai.assistance.operit.shell.ipc

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.util.AppLogger
import java.security.SecureRandom

/**
 * Per-session secret minted at rootfs bootstrap and presented as the first IPC frame
 * (Shell rebuild PR 3/N).
 *
 * Storage is EncryptedSharedPreferences (AES-256-GCM under a hardware-backed master key
 * when the device supports it). The secret rotates on:
 *  - app upgrade (PR 3/N treats every new install of the app as a fresh session and
 *    re-mints on next launch when [rotateOnLaunch] is true)
 *  - explicit user request through [rotate].
 *
 * The proot side reads the same secret from `/var/lib/operit/auth.secret` inside the
 * rootfs, written by the launcher when it spawns the proot child. No exemption for any
 * caller class — per docs/SHELL_REBUILD.md § IPC protocol.
 */
class ShellIpcAuth(context: Context) {

    companion object {
        private const val TAG = "ShellIpcAuth"
        private const val PREFS_NAME = "shell_ipc_auth"
        private const val KEY_SECRET = "ipc_session_secret"
        private const val SECRET_BYTES = 32 // 256 bits
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        AppLogger.w(TAG, "EncryptedSharedPreferences unavailable; falling back to plain prefs: ${e.message}")
        context.applicationContext.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
    }

    /** Returns the current secret, minting one if needed. */
    fun currentOrMint(): String {
        val existing = prefs.getString(KEY_SECRET, null)
        if (!existing.isNullOrBlank()) return existing
        return rotate()
    }

    /** Forces a fresh secret. Returns the new value. */
    fun rotate(): String {
        val bytes = ByteArray(SECRET_BYTES)
        SecureRandom().nextBytes(bytes)
        val encoded = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING,
        )
        prefs.edit().putString(KEY_SECRET, encoded).apply()
        AppLogger.d(TAG, "rotated IPC session secret (${encoded.length} chars)")
        return encoded
    }

    /** Removes the secret. Subsequent [currentOrMint] will produce a fresh one. */
    fun forget() {
        prefs.edit().remove(KEY_SECRET).apply()
    }
}
