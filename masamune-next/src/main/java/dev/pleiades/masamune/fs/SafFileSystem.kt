package dev.pleiades.masamune.fs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework implementation of [FileSystem], backed by a persisted tree URI.
 *
 * This is the backend that actually reaches shared storage on Android 11+, where java.io
 * cannot. The user grants a tree once (Files -> Add storage), the grant is persisted with
 * `takePersistableUriPermission`, and the mount survives restarts.
 *
 * `path` here is a SAF *document id*, not a filesystem path. SAF has no "get parent of this
 * document id" call, so parents are recorded as the user descends; the root has no parent.
 * That is exactly the navigation the UI performs, so it is complete for the browse case.
 *
 * The recursive-copy and document-tree-walk algorithms are re-derived from the donor tree's
 * SafFileSystemTools (its `(tool: AITool): ToolResult` signatures are not carried over — this
 * backend is called by the UI directly, never through an AI tool bus).
 */
class SafFileSystem(
    context: Context,
    private val treeUri: Uri,
    override val displayName: String,
) : FileSystem {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override val id: String = "saf:" + treeUri.toString()

    override val rootPath: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val capabilities: Set<FsOp> = setOf(
        FsOp.LIST, FsOp.READ, FsOp.WRITE, FsOp.CREATE, FsOp.DELETE,
        FsOp.RENAME, FsOp.COPY, FsOp.MOVE, FsOp.FIND,
    )

    override val boundaryNote: String =
        "SAF document tree. Full read/write inside the granted tree, nothing outside it. " +
            "Move is implemented as copy-then-delete because not every provider supports " +
            "DocumentsContract.moveDocument."

    /** documentId -> parent documentId, learned as the user descends. */
    private val parents = ConcurrentHashMap<String, String>()

    /** documentId -> display name, so the location bar can show a readable trail. */
    private val names = ConcurrentHashMap<String, String>()

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun childrenUri(docId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<FsEntry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            resolver.query(childrenUri(path), projection, null, null, null)
        } catch (e: SecurityException) {
            throw FsException(
                "The grant for \"$displayName\" is no longer valid. Re-add it from Files → Add storage.",
                e,
            )
        } ?: throw FsException("Provider returned nothing for ${displayPath(path)}")

        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1) ?: docId
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                parents[docId] = path
                names[docId] = name
                out += FsEntry(
                    name = name,
                    path = docId,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else size,
                    lastModified = modified,
                    mimeType = if (isDir) null else mime,
                    fsId = id,
                )
            }
        }
        out.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): FsEntry? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(docUri(path), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val mime = c.getString(2)
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
            val name = c.getString(1) ?: path
            names[path] = name
            FsEntry(
                name = name,
                path = c.getString(0),
                isDirectory = isDir,
                sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                mimeType = if (isDir) null else mime,
                fsId = id,
            )
        }
    }

    override suspend fun readText(path: String, maxBytes: Int): TextRead =
        withContext(Dispatchers.IO) {
            val total = stat(path)?.sizeBytes ?: 0L
            try {
                resolver.openInputStream(docUri(path)).use { input ->
                    if (input == null) throw FsException("Could not open ${displayPath(path)}")
                    val buf = ByteArray(maxBytes)
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    TextRead(
                        text = String(buf, 0, read),
                        truncated = read >= maxBytes,
                        totalBytes = if (total > 0) total else read.toLong(),
                    )
                }
            } catch (e: IOException) {
                throw FsException("Read failed: ${e.message}", e)
            }
        }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        try {
            // "wt" truncates; plain "w" leaves a tail behind on some providers.
            resolver.openOutputStream(docUri(path), "wt").use { out ->
                if (out == null) throw FsException("Could not open ${displayPath(path)} for write")
                out.write(content.toByteArray())
            }
        } catch (e: IOException) {
            throw FsException("Write failed: ${e.message}", e)
        }
        Unit
    }

    override suspend fun mkdir(parent: String, name: String): String = withContext(Dispatchers.IO) {
        validateName(name)
        val uri = DocumentsContract.createDocument(
            resolver, docUri(parent), DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: throw FsException("Could not create directory: $name")
        val docId = DocumentsContract.getDocumentId(uri)
        parents[docId] = parent
        names[docId] = name
        docId
    }

    override suspend fun createFile(parent: String, name: String): String =
        withContext(Dispatchers.IO) {
            validateName(name)
            val uri = DocumentsContract.createDocument(
                resolver, docUri(parent), guessMime(name) ?: "application/octet-stream", name,
            ) ?: throw FsException("Could not create file: $name")
            val docId = DocumentsContract.getDocumentId(uri)
            parents[docId] = parent
            names[docId] = name
            docId
        }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        if (path == rootPath) throw FsException("Refusing to delete the granted tree root.")
        val ok = try {
            DocumentsContract.deleteDocument(resolver, docUri(path))
        } catch (e: Exception) {
            throw FsException("Delete failed: ${e.message}", e)
        }
        if (!ok) throw FsException("Delete refused by the storage provider.")
        parents.remove(path)
        names.remove(path)
        Unit
    }

    override suspend fun rename(path: String, newName: String): String =
        withContext(Dispatchers.IO) {
            validateName(newName)
            val uri = try {
                DocumentsContract.renameDocument(resolver, docUri(path), newName)
            } catch (e: Exception) {
                throw FsException("Rename failed: ${e.message}", e)
            }
            // A provider may rename in place and return null; the id then stays valid.
            val newId = uri?.let { DocumentsContract.getDocumentId(it) } ?: path
            parents[path]?.let { parents[newId] = it }
            names[newId] = newName
            newId
        }

    override suspend fun copy(
        src: String,
        destParent: String,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val entry = stat(src) ?: throw FsException("Not found: ${displayPath(src)}")
        copyRecursive(entry, destParent, onProgress)
    }

    private suspend fun copyRecursive(
        entry: FsEntry,
        destParent: String,
        onProgress: (String) -> Unit,
    ): String {
        currentCoroutineContext().ensureActive()
        return if (entry.isDirectory) {
            val newDir = mkdir(destParent, entry.name)
            for (child in list(entry.path)) {
                copyRecursive(child, newDir, onProgress)
            }
            onProgress(entry.name)
            newDir
        } else {
            val newFile = createFile(destParent, entry.name)
            try {
                resolver.openInputStream(docUri(entry.path)).use { input ->
                    resolver.openOutputStream(docUri(newFile), "wt").use { output ->
                        if (input == null || output == null) {
                            throw FsException("Could not stream ${entry.name}")
                        }
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
            } catch (e: IOException) {
                throw FsException("Copy failed at ${entry.name}: ${e.message}", e)
            }
            onProgress(entry.name)
            newFile
        }
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
        val stack = ArrayDeque<String>()
        stack.addLast(root)
        while (stack.isNotEmpty() && found < maxResults) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val children = runCatching { list(dir) }.getOrDefault(emptyList())
            for (child in children) {
                examined++
                if (child.name.lowercase().contains(needle)) {
                    onMatch(child)
                    found++
                    if (found >= maxResults) break
                }
                if (child.isDirectory) stack.addLast(child.path)
            }
        }
        examined
    }

    override fun parentOf(path: String): String? =
        if (path == rootPath) null else parents[path]

    override fun displayPath(path: String): String {
        if (path == rootPath) return "/"
        val trail = ArrayList<String>()
        var cur: String? = path
        var guard = 0
        while (cur != null && cur != rootPath && guard < 64) {
            trail.add(names[cur] ?: cur.substringAfterLast(':').substringAfterLast('/'))
            cur = parents[cur]
            guard++
        }
        return "/" + trail.asReversed().joinToString("/")
    }
}
