package com.ai.assistance.operit.core.plugintrust

import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.zip.ZipFile

/**
 * Trust-verifying entry point for plugin install (`AUDIT_PLAN.md § 1.1`,
 * `THREAT_MODEL.md § 4.3`).
 *
 * Given a `.toolpkg` zip on disk, this helper:
 *  1. Extracts `manifest.json` and `manifest.sig` from the archive root. Refuses if
 *     either is missing — § 4.3 requires every plugin package to ship a signature.
 *  2. Runs [PluginTrustChecker] on the bytes.
 *  3. For a [PluginTrustChecker.Decision.NewPublisher], surfaces the TOFU prompt
 *     overlay via [PluginInstallTofuRegistry] and suspends until the user resolves it.
 *     On accept, calls [PluginTrustChecker.confirmAccept] to record the TOFU pair.
 *  4. Returns a [Result] sealed-class that the caller renders.
 *
 * The actual extraction of the plugin payload is **not** in scope — this helper
 * answers "should we proceed with installing this archive?" only. The caller wires the
 * post-approval install through their existing path (e.g. `PackageManager
 * .addPackageFileFromExternalStorage`).
 */
class TrustedPluginInstaller(
    private val checker: PluginTrustChecker,
) {

    companion object {
        private const val TAG = "TrustedPluginInstaller"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val SIGNATURE_ENTRY = "manifest.sig"

        /** 1 MiB ceiling on manifest size — well past any realistic manifest. */
        private const val MAX_MANIFEST_BYTES = 1 shl 20
    }

    sealed class Result {
        /** Trust check passed; caller proceeds with the payload install. */
        data class Approved(
            val pluginId: String,
            val publisherKeyFingerprint: String,
            val publisherName: String,
            val firstInstall: Boolean,
        ) : Result()

        /** User saw the TOFU prompt and refused. */
        data object UserRefused : Result()

        /** Incoming publisher fingerprint differs from the recorded TOFU. */
        data class PublisherMismatch(
            val pluginId: String,
            val recordedFingerprint: String,
            val incomingFingerprint: String,
        ) : Result()

        /** Signature did not verify. Always refused. */
        data class SignatureRejected(val reason: String) : Result()

        /** Manifest body or signature blob is missing / malformed. */
        data class Malformed(val reason: String) : Result()

        /** I/O reading the archive itself failed. */
        data class IoFailure(val cause: Throwable) : Result()
    }

    /**
     * Verify the trust state for the plugin in [packageFile] and, when a TOFU prompt is
     * needed, suspend until the user responds.
     */
    suspend fun verifyAndApprove(packageFile: File): Result {
        val (manifestBytes, signatureBytes) = try {
            readManifestAndSignature(packageFile)
        } catch (e: MissingEntryException) {
            return Result.Malformed("missing required archive entry: ${e.entryName}")
        } catch (e: ManifestTooLargeException) {
            return Result.Malformed("manifest exceeds ${MAX_MANIFEST_BYTES} bytes")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "verifyAndApprove read failed: ${t.message}")
            return Result.IoFailure(t)
        }

        return when (val decision = checker.check(manifestBytes, signatureBytes)) {
            is PluginTrustChecker.Decision.SamePublisher -> {
                Result.Approved(
                    pluginId = decision.pluginId,
                    publisherKeyFingerprint = decision.publisherKeyFingerprint,
                    publisherName = decision.publisherName,
                    firstInstall = false,
                )
            }
            is PluginTrustChecker.Decision.NewPublisher -> {
                val approved = PluginInstallTofuRegistry.requestApproval(decision)
                if (!approved) {
                    Result.UserRefused
                } else {
                    checker.confirmAccept(decision)
                    Result.Approved(
                        pluginId = decision.pluginId,
                        publisherKeyFingerprint = decision.publisherKeyFingerprint,
                        publisherName = decision.publisherName,
                        firstInstall = true,
                    )
                }
            }
            is PluginTrustChecker.Decision.PublisherMismatch -> {
                Result.PublisherMismatch(
                    pluginId = decision.pluginId,
                    recordedFingerprint = decision.recordedFingerprint,
                    incomingFingerprint = decision.incomingFingerprint,
                )
            }
            is PluginTrustChecker.Decision.SignatureRejected ->
                Result.SignatureRejected(decision.reason)
            is PluginTrustChecker.Decision.Malformed ->
                Result.Malformed(decision.reason)
        }
    }

    private fun readManifestAndSignature(file: File): Pair<ByteArray, ByteArray> {
        ZipFile(file).use { archive ->
            val manifestEntry = archive.getEntry(MANIFEST_ENTRY)
                ?: throw MissingEntryException(MANIFEST_ENTRY)
            val signatureEntry = archive.getEntry(SIGNATURE_ENTRY)
                ?: throw MissingEntryException(SIGNATURE_ENTRY)

            if (manifestEntry.size > MAX_MANIFEST_BYTES) {
                throw ManifestTooLargeException()
            }

            val manifestBytes = archive.getInputStream(manifestEntry).use { it.readBytes() }
            val signatureBytes = archive.getInputStream(signatureEntry).use { it.readBytes() }
            return manifestBytes to signatureBytes
        }
    }

    private class MissingEntryException(val entryName: String) :
        RuntimeException("archive missing entry: $entryName")

    private class ManifestTooLargeException :
        RuntimeException("manifest entry exceeds $MAX_MANIFEST_BYTES bytes")
}
