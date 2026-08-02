package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.AppInspector
import dev.pleiades.masamune.apps.LaunchResult
import dev.pleiades.masamune.apps.LaunchSpec
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The Apps category's **inspect-and-launch** slice — the organ an AI phone operator needs to know
 * what is installed and to open it.
 *
 * ### Why this subset and not the other forty
 * Automate's Apps category is the catalog's largest reach into the rest of the device. Most of it
 * drives hidden system APIs no ordinary app process may touch (`app_notifications_*`, `app_op_*`,
 * `app_kill*`, `app_clear_cache`) or is the shell tier itself (`shell_command*`, `adb_shell_command`)
 * — every one of those carries `Requirement.SHELL` and stays behind the scheduler's gate. What is
 * left, and what runs here, is the part the plain package manager already answers: is a package
 * installed, what is installed, does an activity resolve, and launch one. That is exactly the app
 * inspection + launch vocabulary the operator loop composes on top of the Interface actions.
 *
 * ### The seam, copied from the operator blocks
 * Every device call lives behind the injected [AppInspector] — a narrow, `android.*`-free contract,
 * the exact shape [dev.pleiades.masamune.operator.a11y.ScreenActuator] gives the operator blocks.
 * Two consequences, both deliberate:
 *
 *  1. **JVM-testable.** Each block builds its request as *plain data* ([LaunchSpec]) from the args
 *     map, then calls the inspector, so the whole file is unit-testable against a fake inspector on
 *     an ordinary JVM — a device is needed to run these, never to test their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves [inspectorProvider] and fails with
 *     [APPS_ABSENT] when there is no inspector (the app process is not wired in, or it dropped
 *     mid-run). A launch that throws or reports `started = false` becomes a visible [Outcome.Fail]
 *     or [Port.NO] — never a fabricated success. A block that cannot act *says so*; it never no-ops.
 *
 * The composition helper [appsLookup] mirrors `OperatorLoop.interfaceBlocks`: it returns the five
 * impls keyed by spec id so a caller composes `appsLookup(provider)[id] ?: baseRegistry.lookup(id)`.
 */

/** The sentence shown whenever an Apps block cannot reach an app inspector. */
internal val APPS_ABSENT: String =
    "This app block cannot act: no app inspector is available, so Masamune cannot read the package " +
        "manager or launch activities. The inspector is wired only inside the Android app process; " +
        "when it is absent the block fails by name rather than reporting an action that never ran."

// --------------------------------------------------------------------------- shared arg readers

/** A text argument, trimmed to null when blank — distinct from the empty string a user typed. */
private fun Value?.asNonBlank(): String? = this.asTextOrNull()?.takeIf { it.isNotBlank() }

/** An `array(...)` argument as a list of strings; a lone scalar is read as a one-element list. */
private fun Value?.asStringList(): List<String> = when (this) {
    is Value.ArrayV -> items.mapNotNull { it.asNonBlank() }
    null, Value.Null -> emptyList()
    else -> asNonBlank()?.let { listOf(it) } ?: emptyList()
}

/** A `dictionary(...)` argument as a string→string map; anything else reads as empty. */
private fun Value?.asStringMap(): Map<String, String> = when (this) {
    is Value.DictV -> entries.mapValues { it.value.asText() }
    else -> emptyMap()
}

/**
 * Build a [LaunchSpec] as plain data from a block's args. [activityKey] differs across the catalog:
 * `activity_start*` name the class arg `activityClass`, `resolve_activity` names it `className`.
 * Flags arrive as `any(...)`; only a numeric value is a real Intent flag mask, so a non-numeric
 * flags input is honestly dropped rather than guessed at.
 */
private fun launchSpecFrom(args: Map<String, Value>, activityKey: String = "activityClass"): LaunchSpec =
    LaunchSpec(
        packageName = args["packageName"].asNonBlank(),
        activityClass = args[activityKey].asNonBlank(),
        action = args["action"].asNonBlank(),
        uri = args["uri"].asNonBlank(),
        mimeType = args["mimeType"].asNonBlank(),
        categories = args["category"].asStringList(),
        extras = args["extras"].asStringMap(),
        flags = args["flags"].asNumOrNull()?.toInt(),
        chooser = args["chooser"].asFlag(default = false),
    )

// --------------------------------------------------------------------------- the five blocks

/**
 * `app_installed` (App installed) — the inventory decision. YES when [packageName] resolves to an
 * installed package, NO when it does not.
 *
 * On YES it binds the identity outputs the inspector can always answer — `varPackageName`,
 * `varDisplayName`, `varVersionCode`, `varVersionName`, `varSourceDirs`. The three size outputs
 * (`varCacheSize`/`varDataSize`/`varCodeSize`) are bound **only** when the inspector reports a real
 * value: they come from `StorageStatsManager` behind a special-access permission, and fabricating a
 * `0` a downstream block would read as "empty cache" is exactly the silent lie this plane exists to
 * remove. Absent sizes are simply left unbound (honest omission).
 *
 * The catalog marks this WATCH-capable; the watching form needs the monitor subsystem this build
 * does not have, so the one-shot condition — which is what a decision in a running flow asks — runs.
 */
internal class AppInstalledBlock(
    private val inspectorProvider: () -> AppInspector?,
) : BlockImpl {
    override val specId = "app_installed"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val insp = inspectorProvider() ?: return Outcome.Fail(APPS_ABSENT)
        val pkg = args["packageName"].asNonBlank()
            ?: return Outcome.Fail("app_installed needs a packageName.")
        val info = insp.infoFor(pkg) ?: return Outcome.Proceed(Port.NO)
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varPackageName"]?.bind(writes, Value.Text(info.packageName))
        node.outputs["varDisplayName"]?.bind(writes, Value.Text(info.displayName))
        info.versionCode?.let { node.outputs["varVersionCode"]?.bind(writes, Value.Num(it.toDouble())) }
        info.versionName?.let { node.outputs["varVersionName"]?.bind(writes, Value.Text(it)) }
        node.outputs["varSourceDirs"]?.bind(writes, Value.ArrayV(info.sourceDirs.map { Value.Text(it) }))
        // Sizes are bound only if the inspector reported a real value — never fabricated.
        info.cacheSize?.let { node.outputs["varCacheSize"]?.bind(writes, Value.Num(it.toDouble())) }
        info.dataSize?.let { node.outputs["varDataSize"]?.bind(writes, Value.Num(it.toDouble())) }
        info.codeSize?.let { node.outputs["varCodeSize"]?.bind(writes, Value.Num(it.toDouble())) }
        return Outcome.Proceed(Port.YES, writes)
    }
}

/**
 * `app_list` (App list) — the installed-app inventory.
 *
 * The catalog declares two parallel array outputs, `varPackageNames` ("Packages") and
 * `varDisplayNames` ("Display names"). This build binds `varPackageNames` to the richer primary
 * list — a [Value.ArrayV] whose every element is a [Value.DictV] carrying `packageName` and
 * `displayName` together — because a paired list is what a downstream loop over apps actually needs,
 * and two positional arrays invite index drift. `varDisplayNames` is still bound (to the plain
 * display-name array) so a flow written against the catalog's second output is not silently starved.
 *
 * The donor's flag/state/category filters mostly model package-manager internals this build cannot
 * honestly reproduce, so they are ignored rather than half-applied — except the one that maps
 * cleanly: a `states`/`flagsInclude` value asking for *uninstalled* packages is passed through to
 * [AppInspector.listInstalled].
 */
internal class AppListBlock(
    private val inspectorProvider: () -> AppInspector?,
) : BlockImpl {
    override val specId = "app_list"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val insp = inspectorProvider() ?: return Outcome.Fail(APPS_ABSENT)
        val states = args["states"].asTextOrNull()?.lowercase().orEmpty()
        val include = args["flagsInclude"].asTextOrNull()?.lowercase().orEmpty()
        val includeUninstalled = states.contains("uninstall") || include.contains("uninstall")
        val apps = insp.listInstalled(includeUninstalled)
        val paired = apps.map { app ->
            Value.DictV(
                linkedMapOf(
                    "packageName" to Value.Text(app.packageName),
                    "displayName" to Value.Text(app.displayName),
                ),
            )
        }
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varPackageNames"]?.bind(writes, Value.ArrayV(paired))
        node.outputs["varDisplayNames"]?.bind(writes, Value.ArrayV(apps.map { Value.Text(it.displayName) }))
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `resolve_activity` (Resolve activity) — the resolve decision. YES when an activity on the device
 * handles the [LaunchSpec] (package/class/action/data/type/category), NO when none does. On YES it
 * binds `varResolvedPackageName`, `varResolvedClassName`, `varDisplayName`.
 *
 * The catalog marks this `proceed = AWAIT`: the full block *waits* for a resolving activity to
 * appear. That awaiting/trigger form needs the monitor subsystem this build lacks, so the one-shot
 * resolve — "does an activity resolve right now" — is what runs, which is exactly what a decision in
 * a running flow evaluates. Its class arg is `className` (not `activityClass`), matching the catalog.
 */
internal class ResolveActivityBlock(
    private val inspectorProvider: () -> AppInspector?,
) : BlockImpl {
    override val specId = "resolve_activity"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val insp = inspectorProvider() ?: return Outcome.Fail(APPS_ABSENT)
        val resolved = insp.resolve(launchSpecFrom(args, activityKey = "className"))
            ?: return Outcome.Proceed(Port.NO)
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varResolvedPackageName"]?.bind(writes, Value.Text(resolved.packageName))
        node.outputs["varResolvedClassName"]?.bind(writes, Value.Text(resolved.className))
        node.outputs["varDisplayName"]?.bind(writes, Value.Text(resolved.displayName))
        return Outcome.Proceed(Port.YES, writes)
    }
}

/**
 * `activity_start` (App start) — launch an activity. OK when it started; a launch that throws or
 * that nothing on the device handled (`started = false`) is a visible [Outcome.Fail], never a
 * silent OK. The [LaunchSpec] is built as plain data from the args, so the block is fake-testable.
 */
internal class ActivityStartBlock(
    private val inspectorProvider: () -> AppInspector?,
) : BlockImpl {
    override val specId = "activity_start"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val insp = inspectorProvider() ?: return Outcome.Fail(APPS_ABSENT)
        val spec = launchSpecFrom(args)
        val result = try {
            insp.launch(spec)
        } catch (e: Exception) {
            return Outcome.Fail("activity_start failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return if (result.started) {
            Outcome.Proceed(Port.OK)
        } else {
            Outcome.Fail("activity_start: nothing on the device handled ${describe(spec)}.")
        }
    }
}

/**
 * `activity_start_result` (App decision) — launch and branch on whether it started. YES when the
 * activity started, binding `varResultUri` and `varResultExtras` from any returned result; NO when
 * nothing handled the intent. A launch that *throws* is a visible [Outcome.Fail] — the branch is
 * only ever taken on a real started/not-started answer, never a fabricated one.
 *
 * (The catalog's full block awaits the launched activity's *result*; capturing an activity result
 * needs an `Activity` host this slice does not have, so when the inspector cannot supply one the
 * result outputs are simply left unbound — honest omission — while the started/not-started branch,
 * which the package manager can answer, still runs.)
 */
internal class ActivityStartResultBlock(
    private val inspectorProvider: () -> AppInspector?,
) : BlockImpl {
    override val specId = "activity_start_result"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val insp = inspectorProvider() ?: return Outcome.Fail(APPS_ABSENT)
        val spec = launchSpecFrom(args)
        val result: LaunchResult = try {
            insp.launch(spec)
        } catch (e: Exception) {
            return Outcome.Fail("activity_start_result failed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!result.started) return Outcome.Proceed(Port.NO)
        val writes = LinkedHashMap<String, Value>()
        result.resultUri?.let { node.outputs["varResultUri"]?.bind(writes, Value.Text(it)) }
        if (result.resultExtras.isNotEmpty()) {
            node.outputs["varResultExtras"]?.bind(
                writes,
                Value.DictV(result.resultExtras.mapValues { Value.Text(it.value) }),
            )
        }
        return Outcome.Proceed(Port.YES, writes)
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The five Apps inspect-and-launch impls, keyed by spec id, all sharing one [inspectorProvider].
 *
 * Mirrors `OperatorLoop.interfaceBlocks`: it always returns the map, and the honest gate is the
 * per-block gate-at-run (each fails with [APPS_ABSENT] when the provider yields no inspector), so a
 * caller composes over its base registry exactly as the operator loop does:
 *
 * ```
 * val apps = appsLookup(inspectorProvider)
 * fun lookup(id: String): BlockImpl? = apps[id] ?: baseRegistry.lookup(id)
 * ```
 */
fun appsLookup(provider: () -> AppInspector?): Map<String, BlockImpl> = listOf(
    AppInstalledBlock(provider),
    AppListBlock(provider),
    ResolveActivityBlock(provider),
    ActivityStartBlock(provider),
    ActivityStartResultBlock(provider),
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

/** A short human phrase for a launch spec, for the failure message when nothing handled it. */
private fun describe(spec: LaunchSpec): String = buildList {
    spec.packageName?.let { add("package '$it'") }
    spec.activityClass?.let { add("activity '$it'") }
    spec.action?.let { add("action '$it'") }
    spec.uri?.let { add("data '$it'") }
}.ifEmpty { listOf("the requested activity") }.joinToString(", ")
