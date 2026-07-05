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
    private val bundledInstaller: ShellRootfsBundledInstaller =
        ShellRootfsBundledInstaller(context, extractor),
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

    /**
     * Inspect on-disk state and transition to Ready or AwaitingConfirmation.
     *
     * When the rootfs is already extracted, go straight to Ready. Otherwise, when this
     * build ships a bundled rootfs asset, install it right here (fully offline) so the
     * user lands on Ready without a confirmation prompt or any network access. Only a
     * build with no bundled asset falls through to the (legacy) remote-download proposal.
     */
    suspend fun inspectAndPropose() {
        _state.value = ShellBootstrapState.Inspecting
        val installed = withContext(Dispatchers.IO) { inspect(context) }
        if (installed != null) {
            _state.value = ShellBootstrapState.Ready(installed.version, installed.sha256)
            return
        }

        if (withContext(Dispatchers.IO) { bundledInstaller.isBundlePresent() }) {
            // Bundled asset present: provision it immediately, no user gate, no network.
            runBootstrap()
            return
        }

        _state.value = ShellBootstrapState.AwaitingConfirmation(
            expectedVersion = ShellRootfsRelease.EXPECTED_VERSION,
            artifactUrl = ShellRootfsRelease.artifactUrl(),
        )
    }

    /**
     * Runs the full bootstrap pipeline: download → verify digest → verify signature →
     * extract → write manifest. Each phase updates [state] and a terminal failure stops
     * the pipeline (no fallback path).
     */
    suspend fun runBootstrap() {
        // Preferred path: install from the APK-bundled asset. Fully offline — no download,
        // no signature step, no retry loop. This is what fixes the "endless connection
        // retry" the remote pipeline caused against a release URL that does not exist.
        if (withContext(Dispatchers.IO) { bundledInstaller.isBundlePresent() }) {
            runBundledBootstrap()
            return
        }

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

                    // Provision the in-proot dispatcher script + auth secret. This is
                    // what the IPC bridge talks to once a proot session starts.
                    val dispatcherResult = withContext(Dispatchers.IO) {
                        ShellRootfsDispatcherInstaller(context).install(
                            rootDir = rootDir,
                            rotateSecret = true,
                        )
                    }
                    when (dispatcherResult) {
                        is ShellRootfsDispatcherInstaller.Result.Ok -> Unit
                        is ShellRootfsDispatcherInstaller.Result.RootfsMissing -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.EXTRACTION,
                                "Rootfs is unexpectedly missing after extraction at " +
                                    dispatcherResult.expected
                            )
                            return
                        }
                        is ShellRootfsDispatcherInstaller.Result.AssetMissing -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.EXTRACTION,
                                "Dispatcher asset missing from APK: " +
                                    dispatcherResult.name
                            )
                            return
                        }
                        is ShellRootfsDispatcherInstaller.Result.Failed -> {
                            fail(
                                ShellBootstrapState.Failed.Phase.EXTRACTION,
                                dispatcherResult.cause.message
                                    ?: dispatcherResult.cause::class.simpleName
                                    ?: "dispatcher install failed"
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

    /**
     * Offline bootstrap from the APK-bundled rootfs asset. Extracts the asset, verifies its
     * SHA-256 against the build pin (the APK signature is the real trust anchor), provisions
     * the dispatcher, and writes the manifest. No network, no Ed25519 step, no retry.
     */
    private suspend fun runBundledBootstrap() {
        _state.value = ShellBootstrapState.Extracting(0, 0)
        val result = withContext(Dispatchers.IO) {
            bundledInstaller.install(
                onExtractProgress = { entries, bytes ->
                    _state.value = ShellBootstrapState.Extracting(entries, bytes)
                },
            )
        }
        _state.value = when (result) {
            is ShellRootfsBundledInstaller.Result.Ok ->
                ShellBootstrapState.Installed(result.version, result.sha256)
            is ShellRootfsBundledInstaller.Result.AssetMissing ->
                ShellBootstrapState.Failed(
                    ShellBootstrapState.Failed.Phase.CONFIGURATION,
                    "Bundled rootfs asset ${result.name} is not packaged in this build.",
                )
            is ShellRootfsBundledInstaller.Result.DigestMismatch ->
                ShellBootstrapState.Failed(
                    ShellBootstrapState.Failed.Phase.DIGEST,
                    "Bundled rootfs SHA-256 mismatch.\nexpected: ${result.expected}\n" +
                        "actual:   ${result.actual}",
                )
            is ShellRootfsBundledInstaller.Result.ExtractionFailed ->
                ShellBootstrapState.Failed(
                    ShellBootstrapState.Failed.Phase.EXTRACTION,
                    result.reason,
                )
            is ShellRootfsBundledInstaller.Result.Failed ->
                ShellBootstrapState.Failed(
                    ShellBootstrapState.Failed.Phase.UNKNOWN,
                    result.cause.message ?: result.cause::class.simpleName ?: "bundled install failed",
                )
        }
    }

    private fun fail(phase: ShellBootstrapState.Failed.Phase, reason: String) {
        _state.value = ShellBootstrapState.Failed(phase, reason)
    }
}
