package dev.pleiades.masamune.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * The real, device-backed [AppInspector] — the Android glue that turns the plain-data contract into
 * `PackageManager` reads and `Context.startActivity` launches.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit
 * tests' point of view: the blocks never see it, they see [AppInspector]. Keeping every framework
 * call on this side of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.AppsBlocks]
 * stay JVM-testable against a fake. Held deliberately minimal and correct — it builds a real
 * [Intent] from a [LaunchSpec] and launches with [Intent.FLAG_ACTIVITY_NEW_TASK] (required because
 * a `Context` that is not an `Activity` has no task of its own to launch into).
 *
 * ### Honest boundaries
 *  - **Sizes are not fabricated.** [AppInfo.cacheSize]/`dataSize`/`codeSize` come only from
 *    `StorageStatsManager` behind the `PACKAGE_USAGE_STATS` special access this build does not
 *    request, so they are left `null` and `app_installed` omits those outputs.
 *  - **No activity result.** A launch from a bare [Context] cannot receive an activity result (that
 *    needs an `Activity` host + `startActivityForResult`), so [LaunchResult] reports only whether the
 *    launch *started*, with empty `resultUri`/`resultExtras` — never an invented result.
 */
class PackageManagerAppInspector(private val context: Context) : AppInspector {

    private val pm: PackageManager get() = context.packageManager

    override suspend fun infoFor(packageName: String): AppInfo? {
        val pkg = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val appInfo = pkg.applicationInfo
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
        return AppInfo(
            packageName = packageName,
            displayName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName,
            versionCode = versionCode,
            versionName = pkg.versionName,
            sourceDirs = sourceDirsOf(appInfo),
            // cache/data/code sizes require StorageStatsManager + PACKAGE_USAGE_STATS — left null.
        )
    }

    override suspend fun listInstalled(includeUninstalled: Boolean): List<AppInfo> {
        val flags = if (includeUninstalled) matchUninstalledFlag() else 0
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledApplications(flags)
        return installed.map { app ->
            AppInfo(
                packageName = app.packageName,
                displayName = pm.getApplicationLabel(app).toString(),
                sourceDirs = sourceDirsOf(app),
            )
        }
    }

    override suspend fun resolve(spec: LaunchSpec): ResolvedActivity? {
        val intent = intentFrom(spec)
        @Suppress("DEPRECATION")
        val info = pm.resolveActivity(intent, 0) ?: return null
        val activity = info.activityInfo ?: return null
        return ResolvedActivity(
            packageName = activity.packageName,
            className = activity.name,
            displayName = info.loadLabel(pm).toString(),
        )
    }

    override suspend fun launch(spec: LaunchSpec): LaunchResult {
        val base = intentFrom(spec).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val toStart = if (spec.chooser) {
            Intent.createChooser(base, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } else {
            base
        }
        return try {
            context.startActivity(toStart)
            // A Context-level launch cannot collect an activity result; report only that it started.
            LaunchResult(started = true)
        } catch (_: ActivityNotFoundException) {
            LaunchResult(started = false)
        }
    }

    /** Turn a [LaunchSpec] into a real [Intent], honouring an explicit component, action, data, type. */
    private fun intentFrom(spec: LaunchSpec): Intent {
        val intent = Intent()
        if (spec.action != null) intent.action = spec.action
        if (spec.packageName != null && spec.activityClass != null) {
            intent.setClassName(spec.packageName, spec.activityClass)
        } else if (spec.packageName != null) {
            intent.setPackage(spec.packageName)
        }
        val uri = spec.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        when {
            uri != null && spec.mimeType != null -> intent.setDataAndType(uri, spec.mimeType)
            uri != null -> intent.data = uri
            spec.mimeType != null -> intent.type = spec.mimeType
        }
        for (category in spec.categories) intent.addCategory(category)
        for ((key, value) in spec.extras) intent.putExtra(key, value)
        spec.flags?.let { intent.addFlags(it) }
        return intent
    }

    private fun sourceDirsOf(app: ApplicationInfo?): List<String> {
        if (app == null) return emptyList()
        val dirs = ArrayList<String>()
        app.sourceDir?.let { dirs.add(it) }
        app.splitSourceDirs?.let { dirs.addAll(it.filterNotNull()) }
        return dirs
    }

    @Suppress("DEPRECATION")
    private fun matchUninstalledFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        } else {
            PackageManager.GET_UNINSTALLED_PACKAGES
        }
}
