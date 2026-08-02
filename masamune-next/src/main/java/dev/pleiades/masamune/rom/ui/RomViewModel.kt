package dev.pleiades.masamune.rom.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.rom.RomArch
import dev.pleiades.masamune.rom.RomChain
import dev.pleiades.masamune.rom.RomChainResult
import dev.pleiades.masamune.rom.RomImage
import dev.pleiades.masamune.rom.RomImageStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the ROM-launcher surface: the live backend chain, the image registry, adding an image,
 * and the (in this build, never-reachable) launch. See docs/ROM-LAUNCH.md and [RomScreen].
 *
 * ### The chain is the whole enable story
 * [chain] is re-probed on demand ([refreshChain]) because a permission or payload can appear later
 * — a platform-signed reinstall grants AVF, a QEMU install into the prefix lights up TCG. On a
 * clean sideloaded build every path is closed, so [RomChainResult.isAbsent] is true and the screen
 * disables Launch and names each missing path. Nothing here fakes a boot.
 */
class RomViewModel(private val appContext: Context) : ViewModel() {

    private val store = RomImageStore.get(appContext)

    /** The host device's arch, for the cross-emulation speed note. Falls back to aarch64 (the phone case). */
    val hostArch: RomArch =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { RomArch.fromAbi(it) } ?: RomArch.AARCH64

    private val _chain = MutableStateFlow(probeChain())
    /** The folded backend-chain probe: which path is live (none, in this build) and every closed one's reason. */
    val chain: StateFlow<RomChainResult> = _chain.asStateFlow()

    /** The registered images, newest first. Empty on a clean install — nothing is bundled. */
    val images: StateFlow<List<RomImage>> =
        store.images.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    /** True while a picked image is being copied into app storage — a real file copy, not a VM boot. */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** A one-line honest outcome of the last user action (import error, or an attempted launch). Transient. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Re-run the whole chain probe. Cheap; the screen calls it on entry and after returning to it. */
    fun refreshChain() {
        _chain.value = probeChain()
    }

    private fun probeChain(): RomChainResult = RomChain.real(appContext).probe()

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * Copy a SAF-picked document into app-scoped external storage and register it.
     *
     * The copy is a genuine byte-for-byte stream into `getExternalFilesDir("roms")` — the GB image
     * leaves the picker's provider and lands beside the app's other external files, where it
     * survives a prefix rebuild (docs/ROM-LAUNCH.md). [importing] is true for the duration so the
     * UI can show that a copy is in progress; this is an honest long operation, unlike a fabricated
     * boot. On failure the partial file is deleted and [notice] carries the reason.
     */
    fun importImage(uri: Uri, arch: RomArch) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { copyIntoStorage(uri, arch) }
            _importing.value = false
            result.exceptionOrNull()?.let { e ->
                _notice.value = "Could not add the image: ${e.message ?: e.javaClass.simpleName}."
            }
        }
    }

    private suspend fun copyIntoStorage(uri: Uri, arch: RomArch): Result<Unit> = runCatching {
        val displayName = queryDisplayName(uri) ?: "rom-${System.currentTimeMillis()}.img"
        val romsDir = File(appContext.getExternalFilesDir(null), ROMS_SUBDIR).apply { mkdirs() }
        val dest = uniqueFile(romsDir, displayName)
        val copied = appContext.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("the picker returned no readable stream for the chosen file")
        store.add(name = displayName, path = dest.absolutePath, arch = arch, sizeBytes = copied)
        Unit
    }.onFailure {
        // Do not leave a half-written multi-GB file behind on a failed or cancelled copy.
        queryDisplayName(uri)?.let { name ->
            File(File(appContext.getExternalFilesDir(null), ROMS_SUBDIR), name).let { f ->
                if (f.exists() && !isRegistered(f.absolutePath)) f.delete()
            }
        }
    }

    /** Forget an image and delete its file. Both, so "remove" does not leave gigabytes orphaned. */
    fun removeImage(image: RomImage) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(image.path).takeIf { it.exists() }?.delete() }
            }
            store.remove(image.id)
        }
    }

    /**
     * Attempt to launch [image] on the live backend.
     *
     * This is reachable from the UI only when the chain is NOT absent — which never happens on a
     * clean sideloaded build. Even where a backend *were* live (AVF on a platform-signed install,
     * KVM on a rooted device), no boot engine ships in this build, so the honest thing is to say so
     * rather than start a progress bar for a VM that will never appear. It sets [notice]; it never
     * fakes a boot.
     */
    fun launch(image: RomImage) {
        val live = _chain.value.live
        _notice.value = if (live == null) {
            // Defensive: the button is disabled in this state, so this path should be unreachable.
            "No ROM backend is available, so there is nothing to launch."
        } else {
            "${live.label} is the live backend, but no boot engine ships in this build — the QEMU " +
                "TCG launch path is not yet wired, and nothing here will fabricate a boot. Adding " +
                "\"${image.name}\" registered the image; running it waits on the launch engine."
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private suspend fun isRegistered(path: String): Boolean =
        images.value.any { it.path == path }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$n$ext")
            n++
        }
        return candidate
    }

    private companion object {
        const val ROMS_SUBDIR = "roms"
    }
}
