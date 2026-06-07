package com.ai.assistance.operit.shell

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import java.security.MessageDigest

/**
 * Installs the rootfs verification public key into app-private storage (PR 4/N).
 *
 * The key ships inside the APK as an asset (`assets/rootfs/operit-rootfs-pubkey.pem`).
 * On every app start the provisioner copies the asset to
 * [ShellRootfsLayout.publicKeyFile] if the on-disk copy is missing or its content has
 * drifted. This makes app updates the rotation path: rebuilding the APK with a new
 * embedded key transparently replaces the on-disk copy on next launch.
 *
 * No fallback: if the asset is missing, install fails and the bootstrap manager surfaces
 * `PublicKeyMissing` on the next signature check — exactly the visible failure mode
 * described in docs/SHELL_REBUILD.md.
 */
class ShellRootfsKeyProvisioner(private val context: Context) {

    companion object {
        private const val TAG = "ShellRootfsKeyProvisioner"
        private const val ASSET_PATH = "rootfs/operit-rootfs-pubkey.pem"
    }

    /**
     * Copies the bundled public key into app-private storage if it isn't already there
     * with matching content. Returns whether the on-disk key now matches the bundled
     * asset.
     */
    fun provision(): Boolean {
        val target = ShellRootfsLayout.publicKeyFile(context)
        val bundled = try {
            context.assets.open(ASSET_PATH).use { it.readBytes() }
        } catch (e: IOException) {
            AppLogger.w(TAG, "bundled public key asset missing: $ASSET_PATH")
            return false
        }
        if (bundled.isEmpty()) {
            AppLogger.w(TAG, "bundled public key asset is empty")
            return false
        }
        if (target.exists()) {
            val existing = runCatching { target.readBytes() }.getOrDefault(ByteArray(0))
            if (digestEquals(existing, bundled)) {
                return true
            }
        }
        target.parentFile?.mkdirs()
        return runCatching {
            target.writeBytes(bundled)
            true
        }.onFailure { e ->
            AppLogger.w(TAG, "writing public key failed: ${e.message}")
        }.getOrDefault(false)
    }

    private fun digestEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        val md = MessageDigest.getInstance("SHA-256")
        val da = md.digest(a)
        val mdb = MessageDigest.getInstance("SHA-256").digest(b)
        return MessageDigest.isEqual(da, mdb)
    }
}
