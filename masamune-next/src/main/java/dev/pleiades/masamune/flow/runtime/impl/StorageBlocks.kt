package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The Storage category's **local-filesystem** blocks — the ones that need nothing but a path the
 * process can reach.
 *
 * ### Why this subset and not the other twenty-five
 * Automate's Storage category spans FTP, Google Drive and OneDrive (each an account + a network
 * client), the SAF/`ContentResolver` pickers, and `storage_space`/`storage_media_*` (Android's
 * `StatFs`/`Environment`). None of those can run — or be honestly *tested* — without the subsystem
 * they wrap, so they stay behind the scheduler's gate-by-omission. What is left is the file/zip
 * core: `java.io.File`, `java.nio`, and `java.util.zip`. That core is exactly the filesystem organ
 * the per-app Linux nest needs, and it is verifiable here against a real temp directory rather than
 * deferred to a device.
 *
 * ### The path these operate on
 * Automate labels these "external storage", but the impls take a raw path and act on it directly.
 * On the app's own sandbox (`filesDir`, the `/data/local/tmp/<app>` Linux nest) that needs no
 * permission and works today; on a scoped-storage path the OS denies, the operation throws an
 * `IOException` which becomes a visible [Outcome.Fail] — never a silent success. That is the honest
 * boundary: a block that cannot touch a path *says so*, it does not report a copy that never
 * happened.
 *
 * ### Failure, not silence
 * Every block here fails visibly on a missing source, an unreadable path, or an I/O error. A file
 * operation that quietly does nothing is the precise failure the whole plane exists to remove: the
 * flow would proceed as if the file were written and every downstream block would be wrong.
 */

/** Resolve a required text path argument, or the block's own [Outcome.Fail] describing what is missing. */
private fun requirePath(args: Map<String, Value>, key: String, label: String): Result<File> {
    val raw = args[key].asTextOrNull()?.takeIf { it.isNotBlank() }
        ?: return Result.failure(IllegalArgumentException("$label needs a $key."))
    return Result.success(File(raw))
}

/**
 * `File read` — load a text file's whole content onto `varContent`.
 *
 * Charset defaults to UTF-8 (the catalog's "automatic detection" default is not real detection in
 * this build, and pretending otherwise would be a lie about what the bytes decoded as). A missing
 * file, a directory, or an unreadable path is a visible failure.
 */
internal class FileReadBlock : BlockImpl {
    override val specId = "file_read"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val file = requirePath(args, "path", "File read").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val charset = charsetOrDefault(args["charset"].asTextOrNull())
            ?: return Outcome.Fail("File read does not know the charset '${args["charset"].asTextOrNull()}'.")
        if (!file.exists()) return Outcome.Fail("File read: '${file.path}' does not exist.")
        if (file.isDirectory) return Outcome.Fail("File read: '${file.path}' is a directory.")
        val text = try {
            file.readText(charset)
        } catch (e: IOException) {
            return Outcome.Fail("File read failed for '${file.path}': ${e.message}")
        }
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varContent"]?.let { writes[it] = Value.Text(text) }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `File write` — write or append text to a file, creating parent directories.
 *
 * `decode` beyond "no decoding" (base64/hex in the donor) is not implemented, so a request for it
 * fails rather than writing the un-decoded text and claiming success. `append` is read through
 * [asFlag] so a cleared checkbox truncates as the user intended.
 */
internal class FileWriteBlock : BlockImpl {
    override val specId = "file_write"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val file = requirePath(args, "path", "File write").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val charset = charsetOrDefault(args["charset"].asTextOrNull())
            ?: return Outcome.Fail("File write does not know the charset '${args["charset"].asTextOrNull()}'.")
        val decode = args["decode"].asTextOrNull()?.trim()?.lowercase()
        if (decode != null && decode.isNotEmpty() && !decode.startsWith("no decoding")) {
            return Outcome.Fail("File write decode mode '$decode' is not implemented in this build.")
        }
        val content = (args["content"] ?: Value.Null).asText()
        val append = args["append"].asFlag(default = false)
        try {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content, charset) else file.writeText(content, charset)
        } catch (e: IOException) {
            return Outcome.Fail("File write failed for '${file.path}': ${e.message}")
        }
        return Outcome.Proceed(Port.OK)
    }
}

/**
 * `File exists` — the condition form. YES when the path exists, NO otherwise, reporting the entry's
 * type/size/last-modified on the way. The catalog marks it a WATCH-capable trigger; the watching
 * form needs the monitor subsystem this build does not have, so the one-shot condition is what runs
 * (and is what a decision in a running flow asks for).
 */
internal class FileExistsBlock : BlockImpl {
    override val specId = "file_exists"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val file = requirePath(args, "path", "File exists").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        if (!file.exists()) return Outcome.Proceed(Port.NO)
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varType"]?.let { writes[it] = Value.Text(if (file.isDirectory) "directory" else "file") }
        node.outputs["varSize"]?.let { writes[it] = Value.Num(file.length().toDouble()) }
        node.outputs["varLastModified"]?.let { writes[it] = Value.Num(file.lastModified().toDouble()) }
        return Outcome.Proceed(Port.YES, writes)
    }
}

/**
 * `File make directory` — create a directory and any missing parents. Idempotent: an existing
 * directory is success; an existing *file* at the path is a failure, because silently treating a
 * file as a made directory would strand every write that followed.
 */
internal class FileMakeDirectoryBlock : BlockImpl {
    override val specId = "file_make_directory"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val dir = requirePath(args, "path", "File make directory").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        if (dir.isDirectory) return Outcome.Proceed(Port.OK)
        if (dir.exists()) return Outcome.Fail("File make directory: '${dir.path}' exists and is a file.")
        return if (dir.mkdirs()) Outcome.Proceed(Port.OK)
        else Outcome.Fail("File make directory failed for '${dir.path}'.")
    }
}

/**
 * `File delete` — remove a file, or a directory when `recursive` is set. Deleting a non-empty
 * directory without `recursive` fails rather than half-deleting; deleting something that is not
 * there is success (the post-condition "it is gone" already holds).
 */
internal class FileDeleteBlock : BlockImpl {
    override val specId = "file_delete"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val file = requirePath(args, "path", "File delete").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val recursive = args["recursive"].asFlag(default = false)
        if (!file.exists()) return Outcome.Proceed(Port.OK)
        if (file.isDirectory && !recursive && file.list()?.isNotEmpty() == true) {
            return Outcome.Fail("File delete: '${file.path}' is a non-empty directory; set Recursive to delete it.")
        }
        val ok = if (recursive) file.deleteRecursively() else file.delete()
        return if (ok) Outcome.Proceed(Port.OK) else Outcome.Fail("File delete failed for '${file.path}'.")
    }
}

/**
 * `File copy` — copy a file, or a tree when `recursive` is set. `onlyNewerFiles` skips a
 * destination that is newer than or as new as the source (Automate's "Update"). A missing source is
 * a visible failure; copying a directory without `recursive` fails rather than silently copying
 * nothing.
 */
internal class FileCopyBlock : BlockImpl {
    override val specId = "file_copy"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val src = requirePath(args, "sourcePath", "File copy").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val dst = requirePath(args, "targetPath", "File copy").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val recursive = args["recursive"].asFlag(default = false)
        val onlyNewer = args["onlyNewerFiles"].asFlag(default = false)
        if (!src.exists()) return Outcome.Fail("File copy: source '${src.path}' does not exist.")
        if (src.isDirectory && !recursive) {
            return Outcome.Fail("File copy: '${src.path}' is a directory; set Recursive to copy it.")
        }
        return try {
            if (src.isDirectory) copyTree(src, dst, onlyNewer) else copyOne(src, dst, onlyNewer)
            Outcome.Proceed(Port.OK)
        } catch (e: IOException) {
            Outcome.Fail("File copy failed: ${e.message}")
        }
    }
}

/**
 * `File move` — move a file or tree. Tries an atomic rename first; when that cannot span the two
 * locations (different volumes), falls back to copy-then-delete so the move still completes.
 */
internal class FileMoveBlock : BlockImpl {
    override val specId = "file_move"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val src = requirePath(args, "sourcePath", "File move").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val dst = requirePath(args, "targetPath", "File move").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val recursive = args["recursive"].asFlag(default = false)
        if (!src.exists()) return Outcome.Fail("File move: source '${src.path}' does not exist.")
        if (src.isDirectory && !recursive) {
            return Outcome.Fail("File move: '${src.path}' is a directory; set Recursive to move it.")
        }
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return Outcome.Proceed(Port.OK)
        return try {
            if (src.isDirectory) copyTree(src, dst, onlyNewer = false) else copyOne(src, dst, onlyNewer = false)
            if (!src.deleteRecursively()) {
                return Outcome.Fail("File move copied '${src.path}' but could not remove the original.")
            }
            Outcome.Proceed(Port.OK)
        } catch (e: IOException) {
            Outcome.Fail("File move failed: ${e.message}")
        }
    }
}

/**
 * `File list` — the entries under a directory onto `varFiles` as absolute paths (a bare name cannot
 * be chained into `File read`; the full path can). `recursive` walks the tree; `types` narrows to
 * files or directories; `modifiedSince` keeps only entries modified at/after an epoch-millis
 * threshold. Listing a path that is not a directory is a visible failure.
 */
internal class FileListBlock : BlockImpl {
    override val specId = "file_list"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val dir = requirePath(args, "path", "File list").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        if (!dir.exists()) return Outcome.Fail("File list: '${dir.path}' does not exist.")
        if (!dir.isDirectory) return Outcome.Fail("File list: '${dir.path}' is not a directory.")
        val recursive = args["recursive"].asFlag(default = false)
        val since = args["modifiedSince"].asNumOrNull()?.toLong()
        val types = args["types"].asTextOrNull()?.trim()?.lowercase()
        val wantFiles = types == null || types.startsWith("all") || types.contains("file")
        val wantDirs = types == null || types.startsWith("all") || types.contains("dir") || types.contains("folder")

        val out = ArrayList<Value>()
        val walk = if (recursive) dir.walkTopDown().filter { it != dir } else (dir.listFiles()?.asSequence() ?: emptySequence())
        for (entry in walk) {
            val typeOk = if (entry.isDirectory) wantDirs else wantFiles
            val timeOk = since == null || entry.lastModified() >= since
            if (typeOk && timeOk) out.add(Value.Text(entry.absolutePath))
        }
        out.sortBy { (it as Value.Text).value }
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varFiles"]?.let { writes[it] = Value.ArrayV(out) }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `Zip compress` — build a zip from a file or (with `recursive`) a directory tree. Entries are
 * placed under the optional in-zip `targetPath` folder. `compressionMethod` chooses DEFLATED (the
 * default) or STORED when the value names "store"/"none". `update` (append into an existing zip) is
 * not implemented — requested against an existing archive it fails rather than overwriting it and
 * calling that an update.
 */
internal class ZipCompressBlock : BlockImpl {
    override val specId = "zip_compress"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val zip = requirePath(args, "zipFile", "Zip compress").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val src = requirePath(args, "sourcePath", "Zip compress").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        if (!src.exists()) return Outcome.Fail("Zip compress: source '${src.path}' does not exist.")
        val recursive = args["recursive"].asFlag(default = false)
        val update = args["update"].asFlag(default = false)
        if (update && zip.exists()) {
            return Outcome.Fail("Zip compress: Update (append into an existing zip) is not implemented in this build.")
        }
        if (src.isDirectory && !recursive) {
            return Outcome.Fail("Zip compress: '${src.path}' is a directory; set Recursive to compress it.")
        }
        val prefix = args["targetPath"].asTextOrNull()?.trim()?.trim('/')
            ?.takeIf { it.isNotEmpty() && !it.startsWith("zip root") }
        val method = args["compressionMethod"].asTextOrNull()?.lowercase().orEmpty()
        val stored = method.contains("store") || method.contains("none")
        return try {
            zip.parentFile?.mkdirs()
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.setMethod(if (stored) ZipOutputStream.STORED else ZipOutputStream.DEFLATED)
                if (!stored) zos.setLevel(Deflater.DEFAULT_COMPRESSION)
                val roots = if (src.isDirectory) src.walkTopDown().filter { it.isFile }.toList() else listOf(src)
                for (f in roots) {
                    val rel = if (src.isDirectory) src.toURI().relativize(f.toURI()).path else f.name
                    val name = if (prefix != null) "$prefix/$rel" else rel
                    val entry = ZipEntry(name)
                    if (stored) {
                        val bytes = f.readBytes()
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                        zos.putNextEntry(entry); zos.write(bytes); zos.closeEntry()
                    } else {
                        zos.putNextEntry(entry); f.inputStream().use { it.copyTo(zos) }; zos.closeEntry()
                    }
                }
            }
            Outcome.Proceed(Port.OK)
        } catch (e: IOException) {
            Outcome.Fail("Zip compress failed: ${e.message}")
        }
    }
}

/**
 * `Zip extract` — extract entries under the in-zip `sourcePath` into `targetPath`.
 *
 * ### Zip-slip is refused, not sanitized-away silently
 * A crafted archive whose entry name resolves outside the destination (`../../etc/…`) is a real
 * attack, and this is a security suite. Every entry's resolved path is checked to lie under the
 * destination; one that escapes fails the whole block with a named error rather than being quietly
 * skipped — a partial extraction that dropped the malicious entry would still leave the flow
 * believing it unpacked a clean archive.
 */
internal class ZipExtractBlock : BlockImpl {
    override val specId = "zip_extract"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val zip = requirePath(args, "zipFile", "Zip extract").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        val dest = requirePath(args, "targetPath", "Zip extract").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        if (!zip.isFile) return Outcome.Fail("Zip extract: '${zip.path}' is not a file.")
        val inPrefix = args["sourcePath"].asTextOrNull()?.trim()?.trim('/')
            ?.takeIf { it.isNotEmpty() && !it.startsWith("zip root") }
        val destCanon = dest.canonicalFile
        return try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (inPrefix != null && !name.startsWith("$inPrefix/") && name != inPrefix) continue
                    val rel = if (inPrefix != null) name.removePrefix("$inPrefix/") else name
                    if (rel.isEmpty()) continue
                    val target = File(destCanon, rel).canonicalFile
                    if (!target.path.startsWith(destCanon.path + File.separator) && target.path != destCanon.path) {
                        return Outcome.Fail("Zip extract refused entry '$name': it escapes the destination (zip-slip).")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            Outcome.Proceed(Port.OK)
        } catch (e: IOException) {
            Outcome.Fail("Zip extract failed: ${e.message}")
        }
    }
}

/**
 * `Zip list` — the entry names under the in-zip `sourcePath` onto `varFiles`. `types` narrows to
 * files or directories; `modifiedSince` keeps entries at/after an epoch-millis threshold. A path
 * that is not a readable zip is a visible failure.
 */
internal class ZipListBlock : BlockImpl {
    override val specId = "zip_list"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val zip = requirePath(args, "zipFile", "Zip list").getOrElse { return Outcome.Fail(it.message ?: "bad path") }
        if (!zip.isFile) return Outcome.Fail("Zip list: '${zip.path}' is not a file.")
        val inPrefix = args["sourcePath"].asTextOrNull()?.trim()?.trim('/')
            ?.takeIf { it.isNotEmpty() && !it.startsWith("zip root") }
        val since = args["modifiedSince"].asNumOrNull()?.toLong()
        val types = args["types"].asTextOrNull()?.trim()?.lowercase()
        val wantFiles = types == null || types.startsWith("all") || types.contains("file")
        val wantDirs = types == null || types.startsWith("all") || types.contains("dir") || types.contains("folder")
        return try {
            val out = ArrayList<Value>()
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (inPrefix != null && !name.startsWith("$inPrefix/") && name != inPrefix) continue
                    val typeOk = if (entry.isDirectory) wantDirs else wantFiles
                    val timeOk = since == null || entry.time < 0 || entry.time >= since
                    if (typeOk && timeOk) out.add(Value.Text(name))
                }
            }
            out.sortBy { (it as Value.Text).value }
            val writes = LinkedHashMap<String, Value>()
            node.outputs["varFiles"]?.let { writes[it] = Value.ArrayV(out) }
            Outcome.Proceed(Port.OK, writes)
        } catch (e: IOException) {
            Outcome.Fail("Zip list failed: ${e.message}")
        }
    }
}

// --------------------------------------------------------------------------- shared helpers

/** A named charset, UTF-8 for the catalog's "automatic detection"/UTF-8 defaults, or null if unknown. */
private fun charsetOrDefault(name: String?): Charset? {
    val n = name?.trim()
    if (n.isNullOrEmpty() || n.equals("UTF-8", true) || n.startsWith("automatic", true)) return Charsets.UTF_8
    return try {
        Charset.forName(n)
    } catch (_: Exception) {
        null
    }
}

/** Copy a single file, honouring the only-newer skip; creates parent directories. */
private fun copyOne(src: File, dst: File, onlyNewer: Boolean) {
    val target = if (dst.isDirectory) File(dst, src.name) else dst
    if (onlyNewer && target.exists() && target.lastModified() >= src.lastModified()) return
    target.parentFile?.mkdirs()
    src.copyTo(target, overwrite = true)
}

/** Copy a directory tree file-by-file, honouring the only-newer skip on each file. */
private fun copyTree(src: File, dst: File, onlyNewer: Boolean) {
    for (f in src.walkTopDown()) {
        val rel = src.toURI().relativize(f.toURI()).path
        val target = File(dst, rel)
        if (f.isDirectory) {
            target.mkdirs()
        } else {
            if (onlyNewer && target.exists() && target.lastModified() >= f.lastModified()) continue
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
    }
}
