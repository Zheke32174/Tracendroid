package dev.pleiades.masamune.fs

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * java.io implementation of [FileSystem], rooted at a single directory.
 *
 * Containment is enforced on every path that enters the backend: the canonical form of the
 * requested path must sit under the canonical root, so `..` cannot walk out of the mount.
 * (Same hardening the donor tree applied to its DocumentsProvider.)
 */
class LocalFileSystem(
    override val id: String,
    override val displayName: String,
    private val root: File,
    override val boundaryNote: String,
    private val writable: Boolean = true,
) : FileSystem {

    private val canonicalRoot: String = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)

    override val rootPath: String = canonicalRoot

    override val capabilities: Set<FsOp> = buildSet {
        addAll(listOf(FsOp.LIST, FsOp.READ, FsOp.FIND))
        if (writable) {
            addAll(listOf(FsOp.WRITE, FsOp.CREATE, FsOp.DELETE, FsOp.RENAME, FsOp.COPY, FsOp.MOVE))
        }
    }

    // --- containment -------------------------------------------------------------------

    private fun resolve(path: String): File {
        val f = File(path)
        val canonical = runCatching { f.canonicalPath }.getOrElse { f.absolutePath }
        if (canonical != canonicalRoot && !canonical.startsWith("$canonicalRoot${File.separator}")) {
            throw FsException("Path escapes the \"$displayName\" mount: $path")
        }
        return File(canonical)
    }

    private fun requireWritable() {
        if (!writable) throw FsException("\"$displayName\" is mounted read-only.")
    }

    private fun File.toEntry() = FsEntry(
        name = name,
        path = absolutePath,
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) 0L else length(),
        lastModified = lastModified(),
        mimeType = if (isDirectory) null else guessMime(name),
        fsId = id,
    )

    // --- reads -------------------------------------------------------------------------

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (!dir.exists()) throw FsException("Not found: ${displayPath(path)}")
        if (!dir.isDirectory) throw FsException("Not a directory: ${displayPath(path)}")
        val children = dir.listFiles()
            ?: throw FsException(
                "Cannot list ${displayPath(path)}. On Android 11+ shared storage is not " +
                    "readable with java.io; add it through \"Add storage\" (SAF) instead."
            )
        children.map { it.toEntry() }
            .sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): FsEntry? = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (f.exists()) f.toEntry() else null
    }

    override suspend fun readText(path: String, maxBytes: Int): TextRead =
        withContext(Dispatchers.IO) {
            val f = resolve(path)
            if (!f.isFile) throw FsException("Not a file: ${displayPath(path)}")
            val total = f.length()
            val buf = ByteArray(minOf(total, maxBytes.toLong()).toInt())
            try {
                f.inputStream().use { input ->
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    TextRead(String(buf, 0, read), truncated = total > buf.size, totalBytes = total)
                }
            } catch (e: IOException) {
                throw FsException("Read failed: ${e.message}", e)
            }
        }

    // --- writes ------------------------------------------------------------------------

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        requireWritable()
        val f = resolve(path)
        try {
            f.outputStream().use { it.write(content.toByteArray()) }
        } catch (e: IOException) {
            throw FsException("Write failed: ${e.message}", e)
        }
    }

    override suspend fun mkdir(parent: String, name: String): String = withContext(Dispatchers.IO) {
        requireWritable()
        validateName(name)
        val target = resolve(File(resolve(parent), name).path)
        if (target.exists()) throw FsException("Already exists: $name")
        if (!target.mkdirs()) throw FsException("Could not create directory: $name")
        target.absolutePath
    }

    override suspend fun createFile(parent: String, name: String): String =
        withContext(Dispatchers.IO) {
            requireWritable()
            validateName(name)
            val target = resolve(File(resolve(parent), name).path)
            if (target.exists()) throw FsException("Already exists: $name")
            try {
                if (!target.createNewFile()) throw FsException("Could not create file: $name")
            } catch (e: IOException) {
                throw FsException("Could not create file: ${e.message}", e)
            }
            target.absolutePath
        }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        requireWritable()
        val f = resolve(path)
        if (f.absolutePath == canonicalRoot) throw FsException("Refusing to delete the mount root.")
        if (!f.exists()) throw FsException("Not found: ${displayPath(path)}")
        if (!deleteRecursive(f)) throw FsException("Delete failed: ${displayPath(path)}")
    }

    private fun deleteRecursive(f: File): Boolean {
        if (f.isDirectory) {
            f.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
        }
        return f.delete()
    }

    override suspend fun rename(path: String, newName: String): String =
        withContext(Dispatchers.IO) {
            requireWritable()
            validateName(newName)
            val f = resolve(path)
            if (f.absolutePath == canonicalRoot) throw FsException("Refusing to rename the mount root.")
            val target = File(f.parentFile, newName)
            if (target.exists()) throw FsException("Already exists: $newName")
            if (!f.renameTo(target)) throw FsException("Rename failed: ${f.name} -> $newName")
            target.absolutePath
        }

    override suspend fun copy(
        src: String,
        destParent: String,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        requireWritable()
        val source = resolve(src)
        val destDir = resolve(destParent)
        if (!source.exists()) throw FsException("Not found: ${displayPath(src)}")
        if (!destDir.isDirectory) throw FsException("Destination is not a directory.")
        val target = uniqueTarget(destDir, source.name)
        if (source.isDirectory && destDir.canonicalPath.startsWith(source.canonicalPath)) {
            throw FsException("Cannot copy a directory into itself.")
        }
        copyRecursive(source, target, onProgress)
        target.absolutePath
    }

    private suspend fun copyRecursive(src: File, dest: File, onProgress: (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        if (src.isDirectory) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw FsException("Could not create ${dest.name}")
            }
            src.listFiles()?.forEach { copyRecursive(it, File(dest, it.name), onProgress) }
        } else {
            try {
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
            } catch (e: IOException) {
                throw FsException("Copy failed at ${src.name}: ${e.message}", e)
            }
        }
        onProgress(src.name)
    }

    override suspend fun move(
        src: String,
        destParent: String,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        requireWritable()
        val source = resolve(src)
        val destDir = resolve(destParent)
        val target = uniqueTarget(destDir, source.name)
        // Fast path: same volume, atomic rename.
        if (source.renameTo(target)) {
            onProgress(source.name)
            return@withContext target.absolutePath
        }
        // Fall back to copy-then-delete across volumes.
        copyRecursive(source, target, onProgress)
        if (!deleteRecursive(source)) {
            throw FsException("Copied to ${target.name} but could not remove the original.")
        }
        target.absolutePath
    }

    override suspend fun find(
        root: String,
        query: String,
        maxResults: Int,
        onMatch: (FsEntry) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@withContext 0
        var examined = 0
        var found = 0
        val stack = ArrayDeque<File>()
        stack.addLast(resolve(root))
        while (stack.isNotEmpty() && found < maxResults) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                examined++
                if (child.name.lowercase().contains(needle)) {
                    onMatch(child.toEntry())
                    found++
                    if (found >= maxResults) break
                }
                if (child.isDirectory) stack.addLast(child)
            }
        }
        examined
    }

    override fun parentOf(path: String): String? {
        val f = runCatching { resolve(path) }.getOrNull() ?: return null
        if (f.absolutePath == canonicalRoot) return null
        return f.parentFile?.absolutePath
    }

    override fun displayPath(path: String): String {
        val p = path.removePrefix(canonicalRoot)
        return if (p.isEmpty()) "/" else p
    }

    private fun uniqueTarget(destDir: File, name: String): File {
        var candidate = File(destDir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (candidate.exists() && n < 1000) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(destDir, "$stem ($n)$suffix")
            n++
        }
        return candidate
    }
}

internal fun validateName(name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) throw FsException("Name cannot be empty.")
    if (trimmed == "." || trimmed == "..") throw FsException("Reserved name: $trimmed")
    // Spaces are fine. Separators and NUL are not — they are how a name becomes a path.
    if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) {
        throw FsException("Name cannot contain a path separator or a NUL byte.")
    }
}

internal fun guessMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "md", "log", "cfg", "conf", "ini", "properties" -> "text/plain"
    "json" -> "application/json"
    "xml" -> "text/xml"
    "kt", "java", "c", "h", "cpp", "py", "sh", "rs", "go", "js", "ts" -> "text/x-source"
    "html", "htm" -> "text/html"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    else -> null
}

/** Whether the entry is worth opening in the built-in text viewer. */
fun FsEntry.looksTextual(): Boolean {
    if (isDirectory) return false
    val m = mimeType ?: return name.substringAfterLast('.', "").isEmpty()
    return m.startsWith("text/") || m == "application/json"
}
