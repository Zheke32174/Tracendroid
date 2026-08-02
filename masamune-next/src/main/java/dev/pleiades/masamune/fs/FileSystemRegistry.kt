package dev.pleiades.masamune.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The set of mounted filesystems.
 *
 * Built-in mounts are java.io ones that are always reachable without any permission: the app's
 * own private storage and its external files dir. Shared storage is offered too, honestly
 * labelled, because it works below Android 11 and fails loudly above it — the fix being a SAF
 * mount added by the user, which is persisted here across restarts.
 *
 * A new backend (SFTP, WebDAV, SMB, installed-apps, a Yojimbo-brokered privileged path) is
 * registered by adding an implementation of [FileSystem] to this registry. The browser UI
 * reads only the interface and needs no change.
 */
class FileSystemRegistry private constructor(private val appContext: Context) {

    private val prefs =
        appContext.getSharedPreferences("masamune_fs_mounts", Context.MODE_PRIVATE)

    private val _mounts = MutableStateFlow<List<FileSystem>>(emptyList())
    val mounts: StateFlow<List<FileSystem>> = _mounts.asStateFlow()

    init {
        _mounts.value = builtIns() + restoreSafMounts()
    }

    fun byId(id: String): FileSystem? = _mounts.value.firstOrNull { it.id == id }

    fun default(): FileSystem = _mounts.value.first()

    private fun builtIns(): List<FileSystem> = buildList {
        add(
            LocalFileSystem(
                id = "local:app",
                displayName = "App storage",
                root = appContext.filesDir,
                boundaryNote = "This app's private directory. Always readable and writable, " +
                    "no permission required, removed when the app is uninstalled.",
            )
        )
        appContext.getExternalFilesDir(null)?.let { ext ->
            add(
                LocalFileSystem(
                    id = "local:app-external",
                    displayName = "App storage (external)",
                    root = ext,
                    boundaryNote = "This app's directory on shared storage. Visible to a USB " +
                        "file transfer, still removed on uninstall.",
                )
            )
        }
        val shared = Environment.getExternalStorageDirectory()
        if (shared != null && shared.exists()) {
            add(
                LocalFileSystem(
                    id = "local:shared",
                    displayName = "Shared storage (java.io)",
                    root = shared,
                    writable = false,
                    boundaryNote = "Direct java.io view of /sdcard. Works below Android 11; on " +
                        "Android 11+ scoped storage blocks it and listing fails loudly. " +
                        "Use Add storage for a SAF mount instead. Mounted read-only.",
                )
            )
        }
    }

    // --- SAF mounts --------------------------------------------------------------------

    /** The intent to launch for a new SAF tree grant. */
    fun openTreeIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

    /**
     * Called with the tree URI the picker returned. Takes a persistable grant and mounts it.
     * Returns the new mount, or throws [FsException] with a renderable message.
     */
    fun addSafTree(treeUri: Uri): FileSystem {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            throw FsException("Android refused to persist that grant: ${e.message}", e)
        }
        val fs = SafFileSystem(appContext, treeUri, safLabel(treeUri))
        if (_mounts.value.any { it.id == fs.id }) return _mounts.value.first { it.id == fs.id }
        persistSafUris(persistedSafUris() + treeUri.toString())
        _mounts.value = _mounts.value + fs
        return fs
    }

    fun removeSafTree(fsId: String) {
        val fs = _mounts.value.firstOrNull { it.id == fsId } as? SafFileSystem ?: return
        val uriString = fsId.removePrefix("saf:")
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        persistSafUris(persistedSafUris() - uriString)
        _mounts.value = _mounts.value - fs
    }

    private fun restoreSafMounts(): List<FileSystem> {
        val live = appContext.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val stored = persistedSafUris()
        val usable = stored.filter { it in live }
        if (usable.size != stored.size) persistSafUris(usable.toSet())
        return usable.mapNotNull { s ->
            runCatching {
                val uri = Uri.parse(s)
                SafFileSystem(appContext, uri, safLabel(uri))
            }.getOrNull()
        }
    }

    private fun persistedSafUris(): Set<String> =
        prefs.getStringSet(KEY_SAF_URIS, emptySet())?.toSet() ?: emptySet()

    private fun persistSafUris(values: Set<String>) {
        prefs.edit().putStringSet(KEY_SAF_URIS, values).apply()
    }

    private fun safLabel(treeUri: Uri): String {
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return "Storage tree"
        val tail = docId.substringAfterLast(':').trimEnd('/')
        val readable = tail.ifBlank { docId }.substringAfterLast('/')
        return if (readable.isBlank()) "Storage tree" else readable
    }

    companion object {
        private const val KEY_SAF_URIS = "saf_tree_uris"

        @Volatile
        private var instance: FileSystemRegistry? = null

        fun get(context: Context): FileSystemRegistry =
            instance ?: synchronized(this) {
                instance ?: FileSystemRegistry(context.applicationContext).also { instance = it }
            }

        /** Only used for the honest note on the storage picker. */
        fun scopedStorageActive(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var i = 0
            while (value >= 1024 && i < units.size - 1) {
                value /= 1024
                i++
            }
            return String.format("%.1f %s", value, units[i])
        }

        fun freeSpaceOf(fs: FileSystem): String? {
            if (fs !is LocalFileSystem) return null
            return runCatching { humanSize(File(fs.rootPath).usableSpace) }.getOrNull()
        }
    }
}
