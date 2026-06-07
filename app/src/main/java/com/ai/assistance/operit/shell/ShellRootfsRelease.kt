package com.ai.assistance.operit.shell

/**
 * The rootfs release this build pins against (PR 2/N).
 *
 * Per docs/SHELL_REBUILD.md the app embeds the SHA-256 + version of the rootfs it was
 * built against. The downloader refuses anything whose digest doesn't match. Updating
 * the rootfs means updating this constant and shipping a new app build — there is no
 * runtime "trust this digest now" path.
 *
 * The artifact URL is built at runtime against a fixed GitHub Release host. Substitution
 * of host or path is not supported; per AGENTS.md and SECURITY.md there is no mirror
 * fallback.
 */
object ShellRootfsRelease {

    /** Repository hosting the rootfs releases. Sibling of the app source. */
    private const val REPO_OWNER = "zheke32174"
    private const val REPO_NAME = "tracendroid"

    /** Release tag the app expects. Updated atomically with [EXPECTED_SHA256]. */
    const val EXPECTED_VERSION: String = "1.0.0-bookworm-arm64-v1"

    /** ABI of the rootfs artifact this build expects. v1 ships arm64-v8a only. */
    const val EXPECTED_ABI: String = "arm64-v8a"

    /**
     * Lowercase hex SHA-256 of the expected .tar.zst artifact.
     *
     * **Pin update flow.** When the rootfs build pipeline (see [debian/build.sh]) emits
     * a new artifact, the CI logs the SHA-256. That value goes into this constant in the
     * same commit that updates [EXPECTED_VERSION]. The two values are an atomic pair —
     * any commit that touches one without the other fails review.
     *
     * Empty string means the pin has not been wired to a real release yet. Bootstrap
     * refuses to run until this is set.
     */
    const val EXPECTED_SHA256: String = ""

    /** File name of the .tar.zst asset attached to the release. */
    private fun artifactFileName(): String = "operit-rootfs-$EXPECTED_VERSION.tar.zst"

    /** URL of the .tar.zst artifact. Built from REPO_OWNER + REPO_NAME + version. */
    fun artifactUrl(): String =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/" +
            "rootfs/$EXPECTED_VERSION/${artifactFileName()}"

    /** URL of the detached Ed25519 signature alongside the artifact. */
    fun signatureUrl(): String = "${artifactUrl()}.sig"
}
