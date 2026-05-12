package com.ai.assistance.operit.core.plugintrust

import org.json.JSONArray
import org.json.JSONObject

/**
 * Manifest carried by every plugin package per `AUDIT_PLAN.md § 1.1`.
 *
 * Lives next to the plugin's payload as `manifest.json`. A detached `manifest.sig` (raw
 * Ed25519 bytes) signs the canonical bytes of the manifest under the publisher's private
 * key; the matching public key is inlined here so the device can verify the signature
 * without consulting any external registry.
 *
 * The trust anchor is the user's TOFU decision on first install — see
 * [PluginPublisherTofuStore]. Manifests don't reference any CA or signing authority.
 *
 * The capability list mirrors `JsCapabilityClass` names; it advertises what the plugin
 * intends to use. The runtime gate (`JsPluginGate`, § 4.2) is still authoritative — a
 * declared capability is not the same as a granted one.
 */
data class PluginManifest(
    /** Stable plugin identifier — the key the user grants capabilities against. */
    val pluginId: String,

    /** Semantic version. Free-form; the platform doesn't compare or order. */
    val version: String,

    /**
     * Publisher-chosen display name. Surfaced verbatim in the install / update prompt.
     * Not unique; the trust anchor is the public-key fingerprint, not this string.
     */
    val publisherName: String,

    /** PEM-wrapped X.509 SubjectPublicKeyInfo for the publisher's Ed25519 key. */
    val publisherKeyPem: String,

    /** Declared capability classes the plugin intends to call (informative). */
    val declaredCapabilities: List<String>,
) {
    /**
     * Canonical bytes signed by `manifest.sig`. JSON encoded with sorted keys + no
     * extra whitespace so two builds of the same manifest produce identical bytes for
     * the signature to verify against.
     */
    fun canonicalBytes(): ByteArray {
        val obj = JSONObject().apply {
            put("declaredCapabilities", JSONArray(declaredCapabilities.sorted()))
            put("pluginId", pluginId)
            put("publisherKeyPem", publisherKeyPem)
            put("publisherName", publisherName)
            put("version", version)
        }
        // JSONObject's toString sorts keys alphabetically when constructed with put()
        // calls in alphabetical order; this is stable enough for the v1 wire format.
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** Parse a manifest from raw bytes (JSON). Returns null on malformed input. */
        fun parse(bytes: ByteArray): PluginManifest? {
            return try {
                val obj = JSONObject(bytes.toString(Charsets.UTF_8))
                val capabilitiesArray = obj.optJSONArray("declaredCapabilities")
                val capabilities = if (capabilitiesArray != null) {
                    (0 until capabilitiesArray.length()).map { capabilitiesArray.getString(it) }
                } else {
                    emptyList()
                }
                PluginManifest(
                    pluginId = obj.getString("pluginId").trim(),
                    version = obj.getString("version").trim(),
                    publisherName = obj.getString("publisherName").trim(),
                    publisherKeyPem = obj.getString("publisherKeyPem"),
                    declaredCapabilities = capabilities,
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
