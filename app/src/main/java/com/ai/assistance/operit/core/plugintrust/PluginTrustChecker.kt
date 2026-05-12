package com.ai.assistance.operit.core.plugintrust

/**
 * Orchestrates the install / update trust check for a plugin manifest
 * (`AUDIT_PLAN.md § 1.1`, `THREAT_MODEL.md § 4.3`).
 *
 * Composes [PluginSignatureVerifier] and [PluginPublisherTofuStore] into the four
 * outcomes the install flow needs to handle:
 *
 *  - [Decision.NewPublisher]  — first install for this pluginId. The caller surfaces
 *    a TOFU prompt; on accept, calls [confirmAccept].
 *  - [Decision.SamePublisher] — update from the same publisher. Safe to install
 *    silently (no prompt required for the trust check; capability grants still apply
 *    per § 4.2).
 *  - [Decision.PublisherMismatch] — the manifest's publisher fingerprint differs from
 *    the recorded TOFU. Refuse the install. The caller offers the user the option to
 *    explicitly forget the existing record (and re-TOFU) but that is an out-of-band
 *    user action, not part of this checker.
 *  - [Decision.SignatureRejected] — the signature did not verify. Always refuses
 *    regardless of whether a TOFU record exists.
 */
class PluginTrustChecker(
    private val tofuStore: PluginPublisherTofuStore,
    private val verifier: PluginSignatureVerifier = PluginSignatureVerifier(),
) {

    sealed class Decision {
        data class NewPublisher(
            val pluginId: String,
            val publisherKeyFingerprint: String,
            val publisherName: String,
        ) : Decision()

        data class SamePublisher(
            val pluginId: String,
            val publisherKeyFingerprint: String,
            val publisherName: String,
        ) : Decision()

        data class PublisherMismatch(
            val pluginId: String,
            val recordedFingerprint: String,
            val recordedPublisherName: String,
            val incomingFingerprint: String,
            val incomingPublisherName: String,
        ) : Decision()

        data class SignatureRejected(val reason: String) : Decision()

        data class Malformed(val reason: String) : Decision()
    }

    /**
     * Run the full check. Reads the manifest body and signature blob; returns a
     * [Decision]. Does **not** mutate the TOFU store on its own — call [confirmAccept]
     * after a [Decision.NewPublisher] result if the user accepts the prompt.
     */
    fun check(manifestBytes: ByteArray, signatureBytes: ByteArray): Decision {
        val sigResult = verifier.verify(manifestBytes, signatureBytes)
        when (sigResult) {
            is PluginSignatureVerifier.Result.MalformedManifest ->
                return Decision.Malformed("manifest body is not valid JSON")
            is PluginSignatureVerifier.Result.InvalidPublicKey ->
                return Decision.Malformed("manifest publisher key did not parse: ${sigResult.reason}")
            is PluginSignatureVerifier.Result.InvalidSignature ->
                return Decision.SignatureRejected(
                    "Ed25519 signature did not verify against the manifest's inline key"
                )
            is PluginSignatureVerifier.Result.Error ->
                return Decision.SignatureRejected(
                    "verifier error: ${sigResult.cause.message ?: sigResult.cause::class.simpleName}"
                )
            is PluginSignatureVerifier.Result.Ok -> Unit
        }

        val manifest = PluginManifest.parse(manifestBytes)
            ?: return Decision.Malformed("manifest body is not valid JSON")
        val incomingFingerprint = (sigResult as PluginSignatureVerifier.Result.Ok)
            .publisherKeyFingerprint

        val recorded = tofuStore.get(manifest.pluginId)
        return if (recorded == null) {
            Decision.NewPublisher(
                pluginId = manifest.pluginId,
                publisherKeyFingerprint = incomingFingerprint,
                publisherName = manifest.publisherName,
            )
        } else if (recorded.publisherKeyFingerprint == incomingFingerprint) {
            Decision.SamePublisher(
                pluginId = manifest.pluginId,
                publisherKeyFingerprint = incomingFingerprint,
                publisherName = manifest.publisherName,
            )
        } else {
            Decision.PublisherMismatch(
                pluginId = manifest.pluginId,
                recordedFingerprint = recorded.publisherKeyFingerprint,
                recordedPublisherName = recorded.publisherName,
                incomingFingerprint = incomingFingerprint,
                incomingPublisherName = manifest.publisherName,
            )
        }
    }

    /**
     * Records the TOFU pairing for a [Decision.NewPublisher] the user accepted. After
     * this returns, subsequent [check] calls for the same pluginId will route through
     * the SamePublisher / PublisherMismatch branches.
     */
    fun confirmAccept(decision: Decision.NewPublisher) {
        tofuStore.recordFirstInstall(
            pluginId = decision.pluginId,
            fingerprint = decision.publisherKeyFingerprint,
            publisherName = decision.publisherName,
        )
    }
}
