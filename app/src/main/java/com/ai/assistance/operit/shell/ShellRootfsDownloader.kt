package com.ai.assistance.operit.shell

import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the rootfs artifact (PR 2/N).
 *
 * Per docs/SHELL_REBUILD.md the artifact is fetched from a GitHub Release. The URL is
 * fixed at build time via [ShellRootfsRelease]. There is no mirror fallback, no "retry on
 * a different host", no telemetry beyond what GitHub's CDN sees by default.
 *
 * SHA-256 is verified against the build-time pin before this class returns. Signature
 * verification is a separate step ([ShellRootfsSignatureVerifier]).
 */
class ShellRootfsDownloader(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    companion object {
        private const val TAG = "ShellRootfsDownloader"
        private const val BUFFER_SIZE = 64 * 1024
    }

    /**
     * Progress notifications surface to the bootstrap UI. The downloader does not throttle
     * or batch — the consumer is responsible for rate-limiting UI updates if needed.
     */
    fun interface ProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long?)
    }

    sealed class Result {
        data class Ok(val artifact: File, val sha256: String) : Result()
        data class PinNotConfigured(val expectedVersion: String) : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class DigestMismatch(val expected: String, val actual: String) : Result()
        data class IoFailure(val cause: Throwable) : Result()
    }

    /**
     * Downloads the artifact and the signature into [destinationDir]. The artifact file
     * is hashed during streaming so we can fail fast on digest mismatch without keeping
     * the full payload in memory.
     */
    suspend fun download(
        destinationDir: File,
        progress: ProgressListener? = null,
    ): Result {
        if (ShellRootfsRelease.EXPECTED_SHA256.isBlank()) {
            return Result.PinNotConfigured(ShellRootfsRelease.EXPECTED_VERSION)
        }

        destinationDir.mkdirs()
        val artifact = File(destinationDir, "rootfs.tar.zst")

        return try {
            val sha = streamDownload(
                url = ShellRootfsRelease.artifactUrl(),
                destination = artifact,
                progress = progress,
            )
            if (!sha.equals(ShellRootfsRelease.EXPECTED_SHA256, ignoreCase = true)) {
                artifact.delete()
                Result.DigestMismatch(
                    expected = ShellRootfsRelease.EXPECTED_SHA256.lowercase(),
                    actual = sha,
                )
            } else {
                // The signature file is downloaded too — the verifier reads it later.
                runCatching {
                    streamDownload(
                        url = ShellRootfsRelease.signatureUrl(),
                        destination = File(destinationDir, "rootfs.tar.zst.sig"),
                        progress = null,
                    )
                }.onFailure { e ->
                    AppLogger.w(TAG, "signature download failed: ${e.message}")
                }
                Result.Ok(artifact, sha)
            }
        } catch (e: HttpException) {
            Result.HttpError(e.code, e.bodyMessage)
        } catch (e: IOException) {
            Result.IoFailure(e)
        }
    }

    private fun streamDownload(
        url: String,
        destination: File,
        progress: ProgressListener?,
    ): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.code, response.message)
            }
            val total = response.body?.contentLength()?.takeIf { it > 0 }
            val source = response.body?.byteStream() ?: throw IOException("empty body")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            destination.outputStream().use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    downloaded += n
                    progress?.onProgress(downloaded, total)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private class HttpException(val code: Int, val bodyMessage: String) :
        IOException("HTTP $code: $bodyMessage")
}
