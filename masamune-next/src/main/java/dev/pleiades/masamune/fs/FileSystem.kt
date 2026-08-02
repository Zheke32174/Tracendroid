package dev.pleiades.masamune.fs

import android.net.Uri

/**
 * The one filesystem abstraction, shaped per docs/donors/RE-total-commander.md §4.1.
 *
 * Total Commander's design lesson worth stealing: *a filesystem is a plugin*. Local storage,
 * SFTP, WebDAV, SMB, installed-apps and a privileged broker path are all the same interface,
 * so the browser UI is written once and a new backend ships without touching it.
 *
 * What is actually implemented in this build: [LocalFileSystem] (java.io) and [SafFileSystem]
 * (Storage Access Framework document trees). SFTP / WebDAV / SMB / installed-apps /
 * PrivilegedFileSystem-via-Yojimbo are NOT implemented — they would be additional
 * implementations of this interface, and the UI does not need to change to accept them.
 *
 * `path` is opaque to the UI. A backend defines its own meaning (an absolute java.io path, a
 * SAF document id, later a remote URL). The UI only ever moves between paths the backend
 * itself handed back, via [list], [parentOf] and [rootPath].
 *
 * In-process today. Nothing in the interface assumes in-process: every method is `suspend`,
 * every payload is a plain data class, and errors are a sealed exception type, so an
 * implementation can move behind IPC without an interface change.
 */
interface FileSystem {

    /** Stable identifier used to persist "where the user was". */
    val id: String

    /** Human label for the storage picker. */
    val displayName: String

    /** Where a fresh browse starts. */
    val rootPath: String

    /** What this backend can actually do. The UI hides controls for anything absent. */
    val capabilities: Set<FsOp>

    /** A one-line, honest statement of this backend's limits, rendered in the storage picker. */
    val boundaryNote: String

    suspend fun list(path: String): List<FsEntry>

    suspend fun stat(path: String): FsEntry?

    /** Reads at most [maxBytes]; the result reports whether it was cut short. */
    suspend fun readText(path: String, maxBytes: Int = DEFAULT_READ_LIMIT): TextRead

    suspend fun writeText(path: String, content: String)

    /** Creates a directory named [name] under [parent]; returns the new path. */
    suspend fun mkdir(parent: String, name: String): String

    /** Creates an empty file named [name] under [parent]; returns the new path. */
    suspend fun createFile(parent: String, name: String): String

    suspend fun delete(path: String)

    /** Renames in place; returns the (possibly new) path. */
    suspend fun rename(path: String, newName: String): String

    /**
     * Copies [src] (file or directory, recursively) into directory [destParent].
     * [onProgress] is called with the entry name as each item completes.
     */
    suspend fun copy(src: String, destParent: String, onProgress: (String) -> Unit = {}): String

    /** Moves [src] into directory [destParent]. Default is copy-then-delete. */
    suspend fun move(src: String, destParent: String, onProgress: (String) -> Unit = {}): String {
        val moved = copy(src, destParent, onProgress)
        delete(src)
        return moved
    }

    /**
     * Recursive name search under [root]. Emits matches through [onMatch] as they are found
     * so the UI can stream results; returns the number of entries examined.
     */
    suspend fun find(
        root: String,
        query: String,
        maxResults: Int = 500,
        onMatch: (FsEntry) -> Unit,
    ): Int

    /** Parent of [path], or null at the root of this backend. */
    fun parentOf(path: String): String?

    /** What to show in the location bar. */
    fun displayPath(path: String): String

    /**
     * The real java.io path behind [path], or null when this backend is not java.io-backed.
     *
     * A shell working directory, an archive create/extract target and a raw-file hand-off all need
     * a concrete on-disk path. A [LocalFileSystem] returns one; a [SafFileSystem] (document ids) and
     * any future remote backend return null, and every surface that needs a real path gates on that
     * null rather than fabricating one. Default is null so a new backend opts in only when it can.
     */
    fun localPathOf(path: String): String? = null

    /**
     * A system-usable `content://` URI for [path] that another app can read, or null when this
     * backend cannot hand one out.
     *
     * A [SafFileSystem] already addresses documents by content URI and returns it directly, so
     * Open-with and Share work against SAF mounts with a per-intent read grant. A [LocalFileSystem]
     * would need a `FileProvider` (authority + `file_paths.xml`) declared in the app manifest; this
     * build declares none, so it returns null and the external-hand-off controls gate honestly on
     * that absence instead of throwing `FileUriExposedException` at an intent. Default is null.
     */
    fun externalUri(path: String): Uri? = null

    companion object {
        const val DEFAULT_READ_LIMIT = 512 * 1024
    }
}

/** Operations a backend may or may not support. */
enum class FsOp { LIST, READ, WRITE, CREATE, DELETE, RENAME, COPY, MOVE, FIND }

data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String? = null,
    /** Which backend produced this entry. */
    val fsId: String,
)

data class TextRead(val text: String, val truncated: Boolean, val totalBytes: Long)

/** Every backend failure surfaces as this, with a message safe to render verbatim. */
class FsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where the user currently is: which backend, and where inside it. */
data class FsLocation(val fsId: String, val path: String)
