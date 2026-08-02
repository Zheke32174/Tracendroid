package dev.pleiades.masamune.apps

/**
 * The seam between the Apps-category block impls and the real package manager.
 *
 * Every way an Apps block can *inspect* the installed app set or *launch* an activity is one method
 * here, and — exactly like [dev.pleiades.masamune.operator.a11y.ScreenActuator] does for the
 * accessibility service — there is deliberately nothing `android.*` on this interface. That single
 * constraint is what buys the whole slice its JVM-testability: [dev.pleiades.masamune.flow.runtime.
 * impl.AppsBlocks] depend on this plain-data contract, never on `PackageManager` or `Intent`, so the
 * five blocks and all their branch logic can be exercised against a fake on an ordinary unit-test
 * JVM. A device is needed to *run* these blocks, never to *test* their logic.
 *
 * It also gives the honest gate one clean shape. When the app process (which is the only thing that
 * can hand out a real [dev.pleiades.masamune.apps.PackageManagerAppInspector]) is not wired in,
 * there is simply no inspector, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.APPS_ABSENT]) rather than reporting a launch that never
 * happened. Nothing here returns a fabricated success: a not-installed package is `null`, a launch
 * that did not start is `started = false`, and both propagate to a visible NO/Fail.
 */
interface AppInspector {

    /**
     * The record for [packageName], or `null` when no such package is installed.
     *
     * `null` is a real answer — "the package manager has no entry for this name" — and is what the
     * `app_installed` decision routes NO on. It is kept distinct from the absent-inspector case,
     * which never reaches here because there is no inspector to call at all.
     */
    suspend fun infoFor(packageName: String): AppInfo?

    /**
     * Every installed app as an [AppInfo]. When [includeUninstalled] is set, packages retained
     * only as data (uninstalled for this user but not fully removed) are included too — the one
     * filter the donor's flag/state options can honestly drive against the package manager.
     */
    suspend fun listInstalled(includeUninstalled: Boolean): List<AppInfo>

    /**
     * Resolve the single best activity for [spec] (package/class/action/data/type/categories), or
     * `null` when nothing on the device handles it. This is the one-shot form; the awaiting form
     * the catalog marks needs a monitor subsystem this build lacks (see [dev.pleiades.masamune.flow.
     * runtime.impl.ResolveActivityBlock]).
     */
    suspend fun resolve(spec: LaunchSpec): ResolvedActivity?

    /**
     * Launch the activity described by [spec]. A [LaunchResult] with `started = false` (nothing on
     * the device handled the intent) is a real answer the blocks route NO/Fail on — never coerced
     * into a fabricated success. Implementations that cannot capture an activity *result* (a launch
     * from a bare `Context` cannot) leave `resultUri`/`resultExtras` empty rather than inventing one.
     */
    suspend fun launch(spec: LaunchSpec): LaunchResult
}

/**
 * The subset of a package's metadata the Apps blocks reason about — plain data, no `android.*`.
 *
 * The three size fields are nullable and default to `null` on purpose: real cache/data/code sizes
 * come only from `StorageStatsManager`, which needs the `PACKAGE_USAGE_STATS` special access. An
 * inspector that cannot obtain them leaves them `null`, and `app_installed` then simply does not
 * bind those outputs — honest omission, never a fabricated `0` a downstream block would trust.
 */
data class AppInfo(
    val packageName: String,
    val displayName: String,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val sourceDirs: List<String> = emptyList(),
    val cacheSize: Long? = null,
    val dataSize: Long? = null,
    val codeSize: Long? = null,
)

/**
 * A launch/resolve request as plain data — the args the `activity_start*`/`resolve_activity` blocks
 * build from their [dev.pleiades.masamune.flow.expr.Value] inputs, before any `Intent` exists.
 *
 * Keeping this a plain Kotlin type (rather than an already-built `Intent`) is what lets the blocks
 * assemble it and be tested for it on the JVM; the real [PackageManagerAppInspector] turns it into
 * an `Intent` only at the device boundary.
 */
data class LaunchSpec(
    val packageName: String? = null,
    val activityClass: String? = null,
    val action: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
    val flags: Int? = null,
    val chooser: Boolean = false,
)

/** The activity a [LaunchSpec] resolved to: its package, its class, and a human display name. */
data class ResolvedActivity(
    val packageName: String,
    val className: String,
    val displayName: String,
)

/**
 * The outcome of a launch. [started] is the honest "did anything take this intent" — `false` when
 * no activity resolved. [resultUri]/[resultExtras] carry an activity result when one is available;
 * a `Context`-level launch cannot collect one, so they stay empty rather than being invented.
 */
data class LaunchResult(
    val started: Boolean,
    val resultUri: String? = null,
    val resultExtras: Map<String, String> = emptyMap(),
)
