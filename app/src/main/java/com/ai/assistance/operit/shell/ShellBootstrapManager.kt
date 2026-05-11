package com.ai.assistance.operit.shell

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Orchestrates the rootfs bootstrap state machine (PR 2/N).
 *
 * Holds a [StateFlow] of [ShellBootstrapState] that the UI observes. All file-system and
 * network work happens off the main thread via [withContext].
 *
 * The manager is not a singleton — the bootstrap screen owns one. The proot launcher
 * (PR 2/N follow-up) reads the rootfs status through [inspect] without instantiating one,
 * since it's a pure disk-state check.
 */
class ShellBootstrapManager(
    private val context: Context,
    private val downloader: ShellRootfsDownloader = ShellRootfsDownloader(),
    private val verifier: ShellRootfsSignatureVerifier = ShellRootfsSignatureVerifier(),
    private val extractor: ShellRootfsExtractor = ShellRootfsExtractor(),
) {
    companion object {
        private const val TAG = "ShellBootstrapManager"

        /**
         * Pure disk-state check. Returns Installed when the rootfs at the expected version
         * is already extracted on disk; null otherwise.
         */
        fun inspect(context: Context): ShellBootstrapState.Installed? {
            val manifest = ShellRootfsManifest.readFrom(ShellRootfsLayout.manifestFile(context))
                ?: return null
            if (manifest.version != ShellRootfsRelease.EXPECTED_VERSION) return null
            if (ShellRootfsRelease.EXPECTED_SHA256.isNotBlank() &&
                !manifest.sha256.equals(ShellRootfsRelease.EXPECTED_SHA256, ignoreCase = true)
            ) {
                return null
            }
            return ShellBootstrapState.Installed(
                version = manifest.version,
                sha256 = manifest.sha256,
            )
        }
    }

    private val _state = MutableStateFlow<ShellBootstrapState>(ShellBootstrapState.Idle)
    val state: StateFlow<ShellBootstrapState> = _state.asStateFlow()

    /** Inspect on-disk state and transition to Ready or AwaitingConfirmation. */
    suspend fun inspectAndPropose() {
        _state.value = ShellBootstrapState.Inspecting
        val installed = withContext(Dispatchers.IO) { inspect(context) }
        _state.value = if (installed != null) {
            ShellBootstrapState.Ready(installed.version, installed.sha256)
        } else {
            ShellBootstrapState.AwaitingConfirmation(
                expectedVersion = ShellRootfsRelease.EXPECTED_VERSION,
                artifactUrl = ShellRootfsRelease.artifactUrl(),
            )
        }
    }

    /**
     * Runs the full bootstrap pipeline: download → verify digest → verify signature →
     * extract → write manifest. Each phase updates [state] and a terminal failure stops
     * the pipeline (no fallback path).
     */
    suspend fun runBootstrap() {
        val staging = ShellRootfsLayout.stagingDir(context)
        try {
            staging.mkdirs()
            _state.value = ShellBootstrapState.Downloading(0L, null)

            val downloadResult = withContext(Dispatchers.IO) {
                downloader.download(
                    destinationDir = staging,
                    progress = { downloaded, total ->
                        _state.value = ShellBootstrapState.Downloading(downloaded, total)
                    },
                )
            }
            when (downloadResult) {
                is ShellRootfsDownloader.Result.PinNotConfigured -> {
                    fail(
                        ShellBootstrapState.Failed.Phase.CONFIGURATION,
                        "Rootfs SHA-256 pin is empty in this build (version " +
                            "${downloadResult.expectedVersion}). The pin must be wired " +
                            "in the same commit that publishes the rootfs release."
                    )
                    return
                }
                is ShellRootfsDownloader.Result.HttpError -> {
                    fail(
                        ShellBootstrapState.Failed.Phase.DOWNLOAD,
                        "HTTP ${downloadResult.code}: ${downloadResult.message}"
                    )
                    return
                }
                is ShellRootfsDownloader.Result.DigestMismatch -> {
                    fail(
                        ShellBootstrapState.Failed.Phase.DIGEST,
                        "SHA-256 mismatch.\nexpected: ${downloadResult.expected}\n" +
                            "actual:   ${downloadResult.actual}"
                    )
                    return
                }
                is ShellRootfsDownloader.Result.IoFailure -> {
                    fail(
                        ShellBootstrapState.Failed.Phase.DOWNLOAD,
                        downloadResult.cause.message ?: downloadResult.cause::class.simpleName
                            ?: "I/O error"
                    )
                    return
                }
                is ShellRootfsDownloader.Result.Ok -> {
                    // Digest already matched inside the downloader; continue.
                    _state.value = ShellBootstrapState.VerifyingDigest
                    val artifact = downloadResult.artifact
                    val sha256 = downloadResult.sha256

                    _state.value = ShellBootstrapState.VerifyingSignature
                    val sigResult = withContext(Dispatchers.IO) {
                        verifier.verify(
                            artifact = artifact,
                            signature = File(staging, "rootfs.tar.zst.sig"),
                            publicKeyPem = ShellRootfsLayout.publicKeyFile(context),
                        )
                    }
                    when (sigResult) {
                        is ShellRootfsSignatureVerifier.Result.Ok -> Unit
                        is ShellRootfsSignatureVerifier.Result.PublicKeyMissing -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.SIGNATURE,
                                "Public key not installed: ${sigResult.path}.\n" +
                                    "The key ships with the app and is provisioned on " +
                                    "first launch — reinstall the app if this persists."
                            )
                            return
                        }
                        is ShellRootfsSignatureVerifier.Result.SignatureMissing -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.SIGNATURE,
                                "Detached signature ${sigResult.path} was not present " +
                                    "with the release."
                            )
                            return
                        }
                        is ShellRootfsSignatureVerifier.Result.Invalid -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.SIGNATURE,
                                sigResult.reason
                            )
                            return
                        }
                        is ShellRootfsSignatureVerifier.Result.Error -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.SIGNATURE,
                                sigResult.cause.message
                                    ?: sigResult.cause::class.simpleName
                                    ?: "verifier error"
                            )
                            return
                        }
                    }

                    val rootDir = ShellRootfsLayout.rootDir(context)
                    _state.value = ShellBootstrapState.Extracting(0, 0)
                    val extractResult = withContext(Dispatchers.IO) {
                        extractor.extract(
                            artifact = artifact,
                            destination = rootDir,
                            progress = { entries, bytes ->
                                _state.value = ShellBootstrapState.Extracting(entries, bytes)
                            },
                        )
                    }
                    when (extractResult) {
                        is ShellRootfsExtractor.Result.Ok -> Unit
                        is ShellRootfsExtractor.Result.TarSlip -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.EXTRACTION,
                                "Archive contained a path that escapes the rootfs root: " +
                                    extractResult.entryPath
                            )
                            return
                        }
                        is ShellRootfsExtractor.Result.AbsolutePath -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.EXTRACTION,
                                "Archive contained an absolute path: ${extractResult.entryPath}"
                            )
                            return
                        }
                        is ShellRootfsExtractor.Result.Failed -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.EXTRACTION,
                                extractResult.cause.message
                                    ?: extractResult.cause::class.simpleName
                                    ?: "extraction failed"
                            )
                            return
                        }
                    }

                    val manifest = ShellRootfsManifest(
                        version = ShellRootfsRelease.EXPECTED_VERSION,
                        abi = ShellRootfsRelease.EXPECTED_ABI,
                        sha256 = sha256,
                        installedAtMillis = System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.IO) {
                        runCatching {
                            manifest.writeTo(ShellRootfsLayout.manifestFile(context))
                        }
                    }.onFailure { e ->
                        fail(
                            ShellBootstrapState.Failed.Phase.MANIFEST,
                            e.message ?: e::class.simpleName ?: "manifest write failed"
                        )
                        return
                    }

                    _state.value = ShellBootstrapState.Installed(manifest.version, manifest.sha256)
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "bootstrap pipeline failed", t)
            fail(
                ShellBootstrapState.Failed.Phase.UNKNOWN,
                t.message ?: t::class.simpleName ?: "unknown error"
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun fail(phase: ShellBootstrapState.Failed.Phase, reason: String) {
        _state.value = ShellBootstrapState.Failed(phase, reason)
    }
}
