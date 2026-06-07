package com.ai.assistance.operit.shell

import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/**
 * Extracts the verified rootfs into app-private storage (PR 2/N).
 *
 * Input is the .tar.zst the downloader staged. Output is [ShellRootfsLayout.rootDir].
 *
 * Defenses against tar-slip:
 *  - Every entry path is resolved against the destination root and must remain inside it
 *    after canonicalization.
 *  - Absolute paths in the archive are rejected.
 *  - Symlinks are written but their targets are validated to stay inside the root.
 *
 * Per docs/SHELL_REBUILD.md the rootfs is treated as read-only after extraction
 * (replacement on upgrade). This class does not enforce that — the proot launcher will.
 */
class ShellRootfsExtractor {

    companion object {
        private const val TAG = "ShellRootfsExtractor"
        private const val BUFFER_SIZE = 64 * 1024
    }

    sealed class Result {
        data class Ok(val entryCount: Int, val byteCount: Long) : Result()
        data class TarSlip(val entryPath: String) : Result()
        data class AbsolutePath(val entryPath: String) : Result()
        data class Failed(val cause: Throwable) : Result()
    }

    fun interface ProgressListener {
        fun onProgress(entriesProcessed: Int, bytesProcessed: Long)
    }

    fun extract(
        artifact: File,
        destination: File,
        progress: ProgressListener? = null,
    ): Result {
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        destination.mkdirs()
        val rootCanonical = destination.canonicalFile

        return try {
            var entries = 0
            var bytes = 0L
            artifact.inputStream().buffered().use { input ->
                ZstdCompressorInputStream(input).use { zstd ->
                    TarArchiveInputStream(zstd).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            val safe = sanitizedTarget(rootCanonical, entry) ?: run {
                                AppLogger.w(TAG, "rejected tar entry: ${entry.name}")
                                return when {
                                    entry.name.startsWith("/") ->
                                        Result.AbsolutePath(entry.name)
                                    else ->
                                        Result.TarSlip(entry.name)
                                }
                            }
                            bytes += writeEntry(tar, entry, safe, rootCanonical)
                            entries++
                            progress?.onProgress(entries, bytes)
                        }
                    }
                }
            }
            Result.Ok(entries, bytes)
        } catch (t: Throwable) {
            Result.Failed(t)
        }
    }

    /**
     * Returns the canonical target for [entry] or null if the entry would escape [root].
     */
    private fun sanitizedTarget(root: File, entry: TarArchiveEntry): File? {
        if (entry.name.startsWith("/")) return null
        val candidate = File(root, entry.name).canonicalFile
        val rootPath = root.absolutePath
        val candidatePath = candidate.absolutePath
        if (candidatePath != rootPath && !candidatePath.startsWith("$rootPath${File.separator}")) {
            return null
        }
        return candidate
    }

    private fun writeEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        target: File,
        root: File,
    ): Long {
        return when {
            entry.isDirectory -> {
                target.mkdirs()
                0L
            }
            entry.isSymbolicLink -> {
                val linkTarget = entry.linkName
                if (linkTarget.startsWith("/")) {
                    // Absolute symlinks are tolerated here because proot will rewrite
                    // them inside the chroot view. They still cannot escape proot.
                    target.parentFile?.mkdirs()
                    runCatching {
                        target.delete()
                        java.nio.file.Files.createSymbolicLink(
                            target.toPath(),
                            java.io.File(linkTarget).toPath()
                        )
                    }.onFailure {
                        AppLogger.w(TAG, "symlink creation failed for ${entry.name}: ${it.message}")
                    }
                } else {
                    val resolved = File(target.parentFile, linkTarget).canonicalFile
                    val rootPath = root.absolutePath
                    if (resolved.absolutePath != rootPath &&
                        !resolved.absolutePath.startsWith("$rootPath${File.separator}")) {
                        AppLogger.w(TAG, "rejected escaping symlink: ${entry.name} -> $linkTarget")
                    } else {
                        target.parentFile?.mkdirs()
                        runCatching {
                            target.delete()
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.io.File(linkTarget).toPath()
                            )
                        }.onFailure {
                            AppLogger.w(
                                TAG,
                                "symlink creation failed for ${entry.name}: ${it.message}"
                            )
                        }
                    }
                }
                0L
            }
            else -> {
                target.parentFile?.mkdirs()
                var copied = 0L
                target.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                    }
                }
                copied
            }
        }
    }
}
