package com.ai.assistance.operit.shell

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Installs the rootfs from the APK-bundled asset — no network, no GitHub Release, no
 * detached-signature step.
 *
 * This is the path that actually makes the terminal come up. The old remote pipeline in
 * [ShellRootfsDownloader] pointed at a release URL that does not exist, so every setup
 * attempt looped on connection failures and never provisioned anything. The rootfs is now
 * shipped inside the APK at [ShellRootfsRelease.BUNDLED_ASSET]; the trust anchor is the
 * signed APK itself, so signature verification is unnecessary. We still verify the asset's
 * SHA-256 against [ShellRootfsRelease.EXPECTED_SHA256] so a corrupt/mismatched asset fails
 * closed instead of extracting garbage.
 *
 * Flow: copy asset out of the APK (streamed, hashed) -> digest check -> extract via
 * [ShellRootfsExtractor] -> provision dispatcher -> write manifest.
 */
class ShellRootfsBundledInstaller(
    private val context: Context,
    private val extractor: ShellRootfsExtractor = ShellRootfsExtractor(),
) {
    companion object {
        private const val TAG = "ShellRootfsBundledInstaller"
        private const val BUFFER_SIZE = 64 * 1024
    }

    sealed class Result {
        data class Ok(val version: String, val sha256: String) : Result()
        /** The build wasn't shipped with a bundled asset — caller may fall back to remote. */
        data class AssetMissing(val name: String) : Result()
        data class DigestMismatch(val expected: String, val actual: String) : Result()
        data class ExtractionFailed(val reason: String) : Result()
        data class Failed(val cause: Throwable) : Result()
    }

    /** True when this build actually ships a bundled rootfs asset that can be opened. */
    fun isBundlePresent(): Boolean = try {
        context.assets.open(ShellRootfsRelease.BUNDLED_ASSET).use { true }
    } catch (_: IOException) {
        false
    }

    /**
     * Copy the bundled asset to [staging], verify its digest, extract into the rootfs root,
     * provision the dispatcher, and write the manifest. Progress for the extract phase is
     * forwarded so the UI can render it.
     */
    fun install(
        staging: File = ShellRootfsLayout.stagingDir(context),
        onExtractProgress: ((entries: Int, bytes: Long) -> Unit)? = null,
    ): Result {
        staging.mkdirs()
        val artifact = File(staging, "rootfs.tar.zst")
        return try {
            val sha = copyAssetHashed(ShellRootfsRelease.BUNDLED_ASSET, artifact)
                ?: return Result.AssetMissing(ShellRootfsRelease.BUNDLED_ASSET)

            val expected = ShellRootfsRelease.EXPECTED_SHA256
            if (expected.isNotBlank() && !sha.equals(expected, ignoreCase = true)) {
                artifact.delete()
                return Result.DigestMismatch(expected.lowercase(), sha)
            }

            val rootDir = ShellRootfsLayout.rootDir(context)
            when (val extract = extractor.extract(
                artifact = artifact,
                destination = rootDir,
                progress = { entries, bytes -> onExtractProgress?.invoke(entries, bytes) },
            )) {
                is ShellRootfsExtractor.Result.Ok -> Unit
                is ShellRootfsExtractor.Result.TarSlip ->
                    return Result.ExtractionFailed("archive path escapes root: ${extract.entryPath}")
                is ShellRootfsExtractor.Result.AbsolutePath ->
                    return Result.ExtractionFailed("archive contained absolute path: ${extract.entryPath}")
                is ShellRootfsExtractor.Result.Failed ->
                    return Result.ExtractionFailed(
                        extract.cause.message ?: extract.cause::class.simpleName ?: "extraction failed"
                    )
            }

            // Provision the dispatcher script + auth secret. Best-effort: the interactive
            // /bin/sh session does not require it, so a dispatcher-asset gap must not block
            // a working terminal.
            runCatching {
                ShellRootfsDispatcherInstaller(context).install(rootDir = rootDir, rotateSecret = true)
            }.onFailure { e ->
                AppLogger.w(TAG, "dispatcher provisioning skipped: ${e.message}")
            }

            val manifest = ShellRootfsManifest(
                version = ShellRootfsRelease.EXPECTED_VERSION,
                abi = ShellRootfsRelease.EXPECTED_ABI,
                sha256 = sha,
                installedAtMillis = System.currentTimeMillis(),
            )
            manifest.writeTo(ShellRootfsLayout.manifestFile(context))
            Result.Ok(manifest.version, manifest.sha256)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "bundled install failed", t)
            Result.Failed(t)
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /** Streams [assetName] out of the APK into [destination], returning its lowercase-hex SHA-256, or null if the asset isn't present. */
    private fun copyAssetHashed(assetName: String, destination: File): String? {
        destination.parentFile?.mkdirs()
        val input = try {
            context.assets.open(assetName)
        } catch (_: IOException) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { src ->
            destination.outputStream().use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
