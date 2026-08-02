package dev.pleiades.masamune.fs

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The `zip` half of Total Commander's Compress / Extract (DONOR-SURFACES §6, Amaze "Compress"
 * and "Extract"). Deflate-only, backed by `java.util.zip` from the platform — no dependency is
 * added, which is why the format is zip and not 7z/rar/tar.xz.
 *
 * This helper works on real on-disk [File]s only, so the Explorer offers it against a
 * [LocalFileSystem] mount and gates it off for SAF / remote mounts, which expose no java.io path
 * ([FileSystem.localPathOf] is null there). Every path this receives has already passed the
 * backend's containment check via `localPathOf`; the extract side additionally refuses any entry
 * whose resolved target escapes the destination directory (the "zip-slip" guard), so a crafted
 * archive cannot write outside where the user pointed it.
 *
 * All work runs on [Dispatchers.IO] and honours coroutine cancellation between entries, so a large
 * archive can be abandoned without leaving the UI wedged.
 */
object ZipArchiver {

    /** One entry's progress, streamed to the caller as the operation walks the tree. */
    data class Progress(val entryName: String, val index: Int, val total: Int)

    /**
     * Compresses [sources] into a new `.zip` under [destDir].
     *
     * Directories are added recursively with their relative paths preserved; a name collision with
     * an existing file is side-stepped by suffixing " (n)" the same way the copy path does. Returns
     * the created archive file.
     */
    suspend fun compress(
        sources: List<File>,
        destDir: File,
        archiveName: String,
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) throw FsException("Nothing selected to compress.")
        if (!destDir.isDirectory) throw FsException("Destination is not a directory.")
        val leafList = sources.flatMap { flatten(it) }
        val total = leafList.size
        val target = uniqueFile(destDir, ensureZipSuffix(archiveName))
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                var index = 0
                for (source in sources) {
                    currentCoroutineContext().ensureActive()
                    val base = source.parentFile ?: destDir
                    addEntry(zip, source, base) { file ->
                        index++
                        onProgress(Progress(relativeName(file, base), index, total))
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw FsException("Compression failed: ${e.message}", e)
        }
        target
    }

    /**
     * Extracts [archive] into a freshly-created sibling directory under [destDir].
     *
     * The directory is named after the archive stem (collision-suffixed), so an extract never
     * scatters entries loose into the current folder. Each entry's resolved path is verified to
     * stay under that directory before a single byte is written. Returns the directory created.
     */
    suspend fun extract(
        archive: File,
        destDir: File,
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (!archive.isFile) throw FsException("Not a file: ${archive.name}")
        if (!destDir.isDirectory) throw FsException("Destination is not a directory.")
        val total = runCatching { countEntries(archive) }.getOrDefault(0)
        val outDir = uniqueFile(destDir, archive.nameWithoutExtension.ifBlank { "extracted" })
        if (!outDir.mkdirs()) throw FsException("Could not create ${outDir.name}")
        val outCanonical = outDir.canonicalPath
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zin ->
                var index = 0
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val resolved = File(outDir, entry.name)
                    val resolvedCanonical = resolved.canonicalPath
                    if (resolvedCanonical != outCanonical &&
                        !resolvedCanonical.startsWith("$outCanonical${File.separator}")
                    ) {
                        throw FsException(
                            "Refusing to extract \"${entry.name}\": it points outside the " +
                                "destination folder (zip-slip)."
                        )
                    }
                    if (entry.isDirectory) {
                        resolved.mkdirs()
                    } else {
                        resolved.parentFile?.mkdirs()
                        resolved.outputStream().buffered().use { out -> zin.copyTo(out) }
                        index++
                        onProgress(Progress(entry.name, index, total))
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        } catch (e: ZipException) {
            throw FsException("Not a readable zip archive: ${e.message}", e)
        } catch (e: IOException) {
            throw FsException("Extraction failed: ${e.message}", e)
        }
        outDir
    }

    /** True for a name this helper can extract — the browser only offers Extract on these. */
    fun isZip(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "zip"

    // --- internals ---------------------------------------------------------------------

    private fun addEntry(zip: ZipOutputStream, file: File, base: File, onFile: (File) -> Unit) {
        val name = relativeName(file, base)
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                zip.putNextEntry(ZipEntry("$name/"))
                zip.closeEntry()
            } else {
                children.forEach { addEntry(zip, it, base, onFile) }
            }
        } else {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
            onFile(file)
        }
    }

    private fun flatten(file: File): List<File> =
        if (file.isDirectory) file.listFiles()?.flatMap { flatten(it) } ?: emptyList()
        else listOf(file)

    private fun countEntries(archive: File): Int =
        ZipFile(archive).use { zf -> zf.entries().asSequence().count { !it.isDirectory } }

    private fun relativeName(file: File, base: File): String =
        file.absolutePath.removePrefix(base.absolutePath).trimStart(File.separatorChar)

    private fun ensureZipSuffix(name: String): String =
        if (name.substringAfterLast('.', "").lowercase() == "zip") name else "$name.zip"

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (candidate.exists() && n < 1000) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$stem ($n)$suffix")
            n++
        }
        return candidate
    }
}
