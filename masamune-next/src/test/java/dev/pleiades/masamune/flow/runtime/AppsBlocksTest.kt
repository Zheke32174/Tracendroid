package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.AppInfo
import dev.pleiades.masamune.apps.AppInspector
import dev.pleiades.masamune.apps.LaunchResult
import dev.pleiades.masamune.apps.LaunchSpec
import dev.pleiades.masamune.apps.ResolvedActivity
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.ActivityStartBlock
import dev.pleiades.masamune.flow.runtime.impl.ActivityStartResultBlock
import dev.pleiades.masamune.flow.runtime.impl.AppInstalledBlock
import dev.pleiades.masamune.flow.runtime.impl.AppListBlock
import dev.pleiades.masamune.flow.runtime.impl.ResolveActivityBlock
import dev.pleiades.masamune.flow.runtime.impl.appsLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Apps inspect-and-launch blocks branch and bind correctly — run against a
 * [FakeAppInspector] on the JVM, never a device, which is exactly what the `android.*`-free
 * [AppInspector] seam buys (the same seam the operator blocks use). Each test drives a block the way
 * the runtime does — an args map of resolved [Value]s and a [FlowNode] carrying the output bindings
 * — and asserts on the [Outcome] and its writes. The absent-provider path is checked for all five
 * blocks: a missing inspector is a visible [Outcome.Fail], never a silent no-op or fabricated result.
 */
class AppsBlocksTest {

    /** A fully scriptable fake standing in for the real package manager. Records every launch. */
    private class FakeAppInspector(
        private val installed: Map<String, AppInfo> = emptyMap(),
        private val all: List<AppInfo> = emptyList(),
        private val uninstalled: List<AppInfo> = emptyList(),
        private val resolvesTo: ResolvedActivity? = null,
        private val launchResult: LaunchResult = LaunchResult(started = true),
        private val launchThrows: Boolean = false,
    ) : AppInspector {
        val launches = mutableListOf<LaunchSpec>()
        var listedIncludeUninstalled: Boolean? = null

        override suspend fun infoFor(packageName: String): AppInfo? = installed[packageName]

        override suspend fun listInstalled(includeUninstalled: Boolean): List<AppInfo> {
            listedIncludeUninstalled = includeUninstalled
            return if (includeUninstalled) all + uninstalled else all
        }

        override suspend fun resolve(spec: LaunchSpec): ResolvedActivity? = resolvesTo

        override suspend fun launch(spec: LaunchSpec): LaunchResult {
            launches += spec
            if (launchThrows) throw IllegalStateException("boom")
            return launchResult
        }
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    private val chrome = AppInfo(
        packageName = "com.android.chrome",
        displayName = "Chrome",
        versionCode = 123L,
        versionName = "1.2.3",
        sourceDirs = listOf("/data/app/chrome/base.apk"),
    )

    // ------------------------------------------------------------------ app_installed

    @Test fun appInstalledYesBindsIdentityOutputs() = runTest {
        val insp = FakeAppInspector(installed = mapOf("com.android.chrome" to chrome))
        val outcome = AppInstalledBlock({ insp }).run(
            fiber(),
            node(
                "app_installed",
                "varPackageName" to "p", "varDisplayName" to "d",
                "varVersionCode" to "vc", "varVersionName" to "vn", "varSourceDirs" to "sd",
            ),
            mapOf("packageName" to Value.Text("com.android.chrome")),
        )
        assertTrue(outcome is Outcome.Proceed)
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("com.android.chrome"), proceed.writes["p"])
        assertEquals(Value.Text("Chrome"), proceed.writes["d"])
        assertEquals(Value.Num(123.0), proceed.writes["vc"])
        assertEquals(Value.Text("1.2.3"), proceed.writes["vn"])
        assertEquals(Value.ArrayV(listOf(Value.Text("/data/app/chrome/base.apk"))), proceed.writes["sd"])
    }

    @Test fun appInstalledOmitsSizeOutputsWhenInspectorHasNoSizes() = runTest {
        // The honest-omission rule: sizes need StorageStatsManager; unbound rather than fabricated 0.
        val insp = FakeAppInspector(installed = mapOf("com.android.chrome" to chrome))
        val outcome = AppInstalledBlock({ insp }).run(
            fiber(),
            node("app_installed", "varCacheSize" to "c", "varDataSize" to "da", "varCodeSize" to "co"),
            mapOf("packageName" to Value.Text("com.android.chrome")),
        )
        val writes = (outcome as Outcome.Proceed).writes
        assertFalse(writes.containsKey("c"))
        assertFalse(writes.containsKey("da"))
        assertFalse(writes.containsKey("co"))
    }

    @Test fun appInstalledNoWhenNotInstalled() = runTest {
        val insp = FakeAppInspector(installed = emptyMap())
        val outcome = AppInstalledBlock({ insp }).run(
            fiber(),
            node("app_installed", "varPackageName" to "p"),
            mapOf("packageName" to Value.Text("com.absent.app")),
        )
        assertTrue(outcome is Outcome.Proceed)
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
        assertTrue(outcome.writes.isEmpty())
    }

    // ------------------------------------------------------------------ app_list

    @Test fun appListBindsDictVArray() = runTest {
        val insp = FakeAppInspector(
            all = listOf(chrome, AppInfo("com.example.notes", "Notes")),
        )
        val outcome = AppListBlock({ insp }).run(
            fiber(),
            node("app_list", "varPackageNames" to "pkgs", "varDisplayNames" to "names"),
            emptyMap(),
        )
        val writes = (outcome as Outcome.Proceed).writes
        val pkgs = writes["pkgs"] as Value.ArrayV
        assertEquals(2, pkgs.items.size)
        val first = pkgs.items.first() as Value.DictV
        assertEquals(Value.Text("com.android.chrome"), first.entries["packageName"])
        assertEquals(Value.Text("Chrome"), first.entries["displayName"])
        // Parallel display-name array is still bound to the catalog's second output.
        assertEquals(
            Value.ArrayV(listOf(Value.Text("Chrome"), Value.Text("Notes"))),
            writes["names"],
        )
        assertEquals(false, insp.listedIncludeUninstalled)
    }

    @Test fun appListPassesUninstalledStateThrough() = runTest {
        val insp = FakeAppInspector(all = listOf(chrome), uninstalled = listOf(AppInfo("com.gone.app", "Gone")))
        val outcome = AppListBlock({ insp }).run(
            fiber(),
            node("app_list", "varPackageNames" to "pkgs"),
            mapOf("states" to Value.Text("installed, uninstalled")),
        )
        assertEquals(true, insp.listedIncludeUninstalled)
        assertEquals(2, ((outcome as Outcome.Proceed).writes["pkgs"] as Value.ArrayV).items.size)
    }

    // ------------------------------------------------------------------ resolve_activity

    @Test fun resolveActivityYesBindsResolvedOutputs() = runTest {
        val insp = FakeAppInspector(
            resolvesTo = ResolvedActivity("com.android.chrome", "com.android.chrome.Main", "Chrome"),
        )
        val outcome = ResolveActivityBlock({ insp }).run(
            fiber(),
            node(
                "resolve_activity",
                "varResolvedPackageName" to "rp", "varResolvedClassName" to "rc", "varDisplayName" to "dn",
            ),
            mapOf("action" to Value.Text("android.intent.action.VIEW"), "uri" to Value.Text("https://x.test")),
        )
        assertTrue(outcome is Outcome.Proceed)
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("com.android.chrome"), proceed.writes["rp"])
        assertEquals(Value.Text("com.android.chrome.Main"), proceed.writes["rc"])
        assertEquals(Value.Text("Chrome"), proceed.writes["dn"])
    }

    @Test fun resolveActivityNoWhenNothingResolves() = runTest {
        val insp = FakeAppInspector(resolvesTo = null)
        val outcome = ResolveActivityBlock({ insp }).run(
            fiber(),
            node("resolve_activity", "varResolvedPackageName" to "rp"),
            mapOf("action" to Value.Text("com.nothing.HANDLES")),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    // ------------------------------------------------------------------ activity_start

    @Test fun activityStartOkOnSuccess() = runTest {
        val insp = FakeAppInspector(launchResult = LaunchResult(started = true))
        val outcome = ActivityStartBlock({ insp }).run(
            fiber(),
            node("activity_start"),
            mapOf("packageName" to Value.Text("com.android.chrome")),
        )
        assertTrue(outcome is Outcome.Proceed)
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals("com.android.chrome", insp.launches.single().packageName)
    }

    @Test fun activityStartFailsWhenNothingStarts() = runTest {
        val insp = FakeAppInspector(launchResult = LaunchResult(started = false))
        val outcome = ActivityStartBlock({ insp }).run(
            fiber(), node("activity_start"),
            mapOf("packageName" to Value.Text("com.absent.app")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    @Test fun activityStartFailsWhenLaunchThrows() = runTest {
        val insp = FakeAppInspector(launchThrows = true)
        val outcome = ActivityStartBlock({ insp }).run(
            fiber(), node("activity_start"),
            mapOf("packageName" to Value.Text("com.android.chrome")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("boom"))
    }

    // ------------------------------------------------------------------ activity_start_result

    @Test fun activityStartResultYesBindsResultOutputs() = runTest {
        val insp = FakeAppInspector(
            launchResult = LaunchResult(
                started = true,
                resultUri = "content://out/1",
                resultExtras = mapOf("code" to "ok"),
            ),
        )
        val outcome = ActivityStartResultBlock({ insp }).run(
            fiber(),
            node("activity_start_result", "varResultUri" to "ru", "varResultExtras" to "re"),
            mapOf("packageName" to Value.Text("com.android.chrome")),
        )
        assertTrue(outcome is Outcome.Proceed)
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("content://out/1"), proceed.writes["ru"])
        assertEquals(Value.DictV(mapOf("code" to Value.Text("ok"))), proceed.writes["re"])
    }

    @Test fun activityStartResultNoWhenNotStarted() = runTest {
        val insp = FakeAppInspector(launchResult = LaunchResult(started = false))
        val outcome = ActivityStartResultBlock({ insp }).run(
            fiber(), node("activity_start_result", "varResultUri" to "ru"),
            mapOf("packageName" to Value.Text("com.absent.app")),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    // ------------------------------------------------------------------ absent provider (all five)

    @Test fun allBlocksFailByNameWhenInspectorAbsent() = runTest {
        val absent: () -> AppInspector? = { null }
        val blocks = listOf(
            AppInstalledBlock(absent) to node("app_installed"),
            AppListBlock(absent) to node("app_list"),
            ResolveActivityBlock(absent) to node("resolve_activity"),
            ActivityStartBlock(absent) to node("activity_start"),
            ActivityStartResultBlock(absent) to node("activity_start_result"),
        )
        for ((block, flowNode) in blocks) {
            val outcome = block.run(fiber(), flowNode, mapOf("packageName" to Value.Text("com.x")))
            assertTrue("${block.specId} must Fail when the inspector is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("app inspector"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun appsLookupExposesTheFiveBlocksBySpecId() {
        val lookup = appsLookup { null }
        assertEquals(
            setOf("app_installed", "app_list", "resolve_activity", "activity_start", "activity_start_result"),
            lookup.keys,
        )
        // Mirrors OperatorLoop: composes over a base registry via `appsLookup(...)[id] ?: base`.
        assertNull(lookup["file_read"])
        assertEquals("app_installed", lookup["app_installed"]!!.specId)
    }
}
