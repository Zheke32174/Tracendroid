package com.ai.assistance.operit.shell

import java.io.File
import org.json.JSONObject

/**
 * Metadata persisted alongside an extracted rootfs (PR 2/N).
 *
 * The manifest records what was extracted and lets the bootstrap manager decide whether
 * the on-disk rootfs matches the version this app expects without re-running signature
 * verification on every launch.
 */
data class ShellRootfsManifest(
    /** Semantic version of the rootfs, e.g. "1.0.0-bookworm-arm64". */
    val version: String,
    /** ABI the rootfs was built for; only one ABI ships per artifact in v1. */
    val abi: String,
    /** SHA-256 (lowercase hex) of the .tar.zst artifact this manifest describes. */
    val sha256: String,
    /** Wall-clock millis when the manifest was written. */
    val installedAtMillis: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("abi", abi)
        put("sha256", sha256)
        put("installedAtMillis", installedAtMillis)
    }.toString(2)

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson())
    }

    companion object {
        fun parse(text: String): ShellRootfsManifest? {
            return try {
                val obj = JSONObject(text)
                ShellRootfsManifest(
                    version = obj.getString("version"),
                    abi = obj.getString("abi"),
                    sha256 = obj.getString("sha256").lowercase(),
                    installedAtMillis = obj.getLong("installedAtMillis"),
                )
            } catch (_: Throwable) {
                null
            }
        }

        fun readFrom(file: File): ShellRootfsManifest? {
            if (!file.exists()) return null
            return parse(file.readText())
        }
    }
}
