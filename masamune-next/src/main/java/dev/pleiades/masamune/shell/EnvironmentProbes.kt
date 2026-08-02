package dev.pleiades.masamune.shell

/**
 * Command builders and output parsers for the Terminal ▸ Environments surface.
 *
 * Every probe here is a plain line handed to [TermuxShellBackend] as `bash -c`, so the whole
 * Environments panel is honest by construction: a tool that is not installed literally reports
 * "not detected", a package that is not present is simply absent from the parsed list, and a
 * probe that cannot run at all degrades to the same blocked empty state the rest of the Shell
 * surface uses when Termux is missing.
 *
 * Nothing in this file touches Android or Compose — it is pure string in, structured out — so it
 * is unit-tested directly (see EnvironmentProbesTest). The UI layer only ever renders what the
 * parsers return; it never invents a status.
 */
object EnvironmentProbes {

    /** Sentinel a probe prints when `command -v <tool>` fails. Never shown to the user verbatim. */
    const val ABSENT = "__MASAMUNE_ABSENT__"

    /** Field separator inside a probe line. A tab cannot appear in the version strings we keep. */
    private const val SEP = "\t"

    // ---------------------------------------------------------------------------------------------
    // Start configuration checklist  (§4 line 86)
    // ---------------------------------------------------------------------------------------------

    /** One row of the checklist: the binary to look for and the argument that prints its version. */
    data class ToolProbe(val key: String, val versionArg: String)

    /** The checklist, in the donor's order (DONOR-SURFACES §4 line 86). */
    val CHECKLIST_TOOLS: List<ToolProbe> = listOf(
        ToolProbe("node", "--version"),
        ToolProbe("npm", "--version"),
        ToolProbe("git", "--version"),
        ToolProbe("python", "--version"),
        ToolProbe("pip", "--version"),
        ToolProbe("codex", "--version"),
        ToolProbe("claude-code", "--version"),
        ToolProbe("opencode", "--version"),
        ToolProbe("ssh", "-V"),
        ToolProbe("sshpass", "-V"),
        ToolProbe("sshd", "-V"),
    )

    /** Detected version of a checklist tool, or absent. */
    data class ToolStatus(val key: String, val detected: Boolean, val version: String?)

    /**
     * A single `bash -c` line that probes every checklist tool. Each tool emits exactly one
     * `key<TAB>version` line, or `key<TAB>ABSENT` when `command -v` fails, so one RUN_COMMAND
     * covers the whole checklist.
     */
    fun checklistScript(): String = CHECKLIST_TOOLS.joinToString("; ") { t ->
        val k = sq(t.key)
        val arg = sq(t.versionArg)
        "if command -v ${sq(t.key)} >/dev/null 2>&1; then " +
            "printf '%s${SEP}%s\\n' $k \"\$(${sq(t.key)} $arg 2>&1 | head -n1 | tr -d '\\t')\"; " +
            "else printf '%s${SEP}%s\\n' $k '$ABSENT'; fi"
    }

    /** Parses the [checklistScript] output back into one [ToolStatus] per declared tool. */
    fun parseChecklist(stdout: String): List<ToolStatus> {
        val seen = stdout.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(SEP)
                if (idx < 0) return@mapNotNull null
                line.substring(0, idx) to line.substring(idx + SEP.length).trim()
            }
            .toMap()
        return CHECKLIST_TOOLS.map { t ->
            val raw = seen[t.key]
            when {
                raw == null || raw == ABSENT || raw.isBlank() -> ToolStatus(t.key, false, null)
                else -> ToolStatus(t.key, true, raw)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Health check  (§4 line 89)
    // ---------------------------------------------------------------------------------------------

    /** One health probe: a stable key and the shell snippet that decides PASS/FAIL + a detail. */
    data class HealthProbe(val key: String, val script: String)

    /**
     * Emits `key<TAB>PASS|FAIL<TAB>detail`. Each probe is a scripted readout, never an assertion
     * we could not back — e.g. the network probe reports whatever the ping actually did.
     */
    val HEALTH_PROBES: List<HealthProbe> = listOf(
        HealthProbe(
            "proot",
            "if command -v proot >/dev/null 2>&1; then " +
                "printf 'proot${SEP}PASS${SEP}%s\\n' \"\$(proot --version 2>&1 | head -n1)\"; " +
                "else printf 'proot${SEP}FAIL${SEP}proot not installed\\n'; fi",
        ),
        HealthProbe(
            "system_shell",
            "if [ -x /system/bin/sh ]; then printf 'system_shell${SEP}PASS${SEP}/system/bin/sh\\n'; " +
                "else printf 'system_shell${SEP}FAIL${SEP}/system/bin/sh not executable\\n'; fi",
        ),
        HealthProbe(
            "storage",
            "if [ -d \"\$HOME/storage\" ]; then printf 'storage${SEP}PASS${SEP}~/storage present\\n'; " +
                "else printf 'storage${SEP}FAIL${SEP}run termux-setup-storage\\n'; fi",
        ),
        HealthProbe(
            "distro",
            "if [ -f \"\$PREFIX/etc/os-release\" ]; then " +
                "printf 'distro${SEP}PASS${SEP}%s\\n' \"\$(. \"\$PREFIX/etc/os-release\"; echo \"\$PRETTY_NAME\")\"; " +
                "else printf 'distro${SEP}PASS${SEP}Termux (no /etc/os-release)\\n'; fi",
        ),
        HealthProbe(
            "network",
            "if ping -c1 -W2 1.1.1.1 >/dev/null 2>&1; then printf 'network${SEP}PASS${SEP}egress ok\\n'; " +
                "else printf 'network${SEP}FAIL${SEP}no egress to 1.1.1.1\\n'; fi",
        ),
        HealthProbe(
            "abnormalities",
            "if [ -w \"\$PREFIX\" ] && [ -w \"\$HOME\" ]; then " +
                "printf 'abnormalities${SEP}PASS${SEP}PREFIX and HOME writable\\n'; " +
                "else printf 'abnormalities${SEP}FAIL${SEP}PREFIX or HOME not writable\\n'; fi",
        ),
    )

    /** Result of one health probe. */
    data class HealthStatus(val key: String, val passed: Boolean, val detail: String)

    /** One `bash -c` line that runs every health probe in declared order. */
    fun healthScript(): String = HEALTH_PROBES.joinToString("; ") { it.script }

    /** Parses [healthScript] output into one [HealthStatus] per declared probe. */
    fun parseHealth(stdout: String): List<HealthStatus> {
        val seen = stdout.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(SEP)
                if (parts.size < 2) return@mapNotNull null
                parts[0] to (parts[1] to parts.getOrElse(2) { "" })
            }
            .toMap()
        return HEALTH_PROBES.map { p ->
            val v = seen[p.key]
            when (v) {
                null -> HealthStatus(p.key, false, "no result returned")
                else -> HealthStatus(p.key, v.first == "PASS", v.second)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Packages  (§4 line 88)
    // ---------------------------------------------------------------------------------------------

    /** `pkg list-installed` drops a "Listing..." banner on the first line; everything after is data. */
    fun installedPackagesScript(): String = "pkg list-installed 2>/dev/null"

    fun diskUsageScript(): String = "du -sh \"\$PREFIX\" 2>/dev/null | cut -f1"

    fun installScript(pkg: String): String = "pkg install -y ${sq(pkg)}"

    fun upgradeAllScript(): String = "pkg upgrade -y"

    fun removeScript(pkg: String): String = "pkg uninstall -y ${sq(pkg)}"

    /** One installed package: apt renders `name/repo,now version arch [installed...]`. */
    data class InstalledPackage(val name: String, val version: String)

    fun parseInstalledPackages(stdout: String): List<InstalledPackage> =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('/') && !it.startsWith("Listing") }
            .mapNotNull { line ->
                // name/repo,now  version  arch  [status]
                val name = line.substringBefore('/').trim()
                if (name.isEmpty()) return@mapNotNull null
                val rest = line.substringAfter('/', "").trim()
                val version = rest.split(Regex("\\s+")).getOrElse(1) { "" }
                InstalledPackage(name, version)
            }
            .distinctBy { it.name }
            .toList()

    /** Curated quick-install set surfaced as chips (donor "quick install"). */
    val QUICK_INSTALL: List<String> =
        listOf("git", "openssh", "nodejs", "python", "curl", "wget", "vim", "proot-distro")

    // ---------------------------------------------------------------------------------------------
    // Installed rootfs list  (proot-distro; §4 lines 84-85)
    // ---------------------------------------------------------------------------------------------

    fun prootDistroPresentScript(): String =
        "if command -v proot-distro >/dev/null 2>&1; then echo PRESENT; else echo ABSENT; fi"

    fun prootDistroListScript(): String = "proot-distro list 2>/dev/null"

    /** One distro row from `proot-distro list`. */
    data class DistroEntry(val alias: String, val installed: Boolean)

    /**
     * `proot-distro list` prints stanzas; each begins with an `Alias: <name>` line and, when
     * present, a later `Installed: yes/Status: installed` marker. We key off the alias lines and
     * treat the stanza as installed when it carries an installed marker before the next alias.
     */
    fun parseDistroList(stdout: String): List<DistroEntry> {
        val out = mutableListOf<DistroEntry>()
        var alias: String? = null
        var installed = false
        fun flush() { alias?.let { out.add(DistroEntry(it, installed)) } }
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("Alias:", ignoreCase = true) -> {
                    flush()
                    alias = line.substringAfter(':').trim()
                    installed = false
                }
                line.contains("installed", ignoreCase = true) &&
                    (line.startsWith("Status:", true) || line.startsWith("Installed:", true) ||
                        line.startsWith("*", false)) -> installed = true
            }
        }
        flush()
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Boot tasks  (Termux:Boot; §4 line 87)
    // ---------------------------------------------------------------------------------------------

    /** Lists ~/.termux/boot with an executable-bit marker per entry. */
    fun bootTasksScript(): String =
        "d=\"\$HOME/.termux/boot\"; if [ -d \"\$d\" ]; then " +
            "for f in \"\$d\"/*; do [ -e \"\$f\" ] || continue; " +
            "if [ -x \"\$f\" ]; then m=X; else m=-; fi; " +
            "printf '%s${SEP}%s\\n' \"\$m\" \"\$(basename \"\$f\")\"; done; " +
            "else echo '$ABSENT'; fi"

    /** One ~/.termux/boot script: its name and whether the executable bit (its "enabled") is set. */
    data class BootTask(val name: String, val enabled: Boolean)

    /** null => the ~/.termux/boot directory itself does not exist yet. */
    fun parseBootTasks(stdout: String): List<BootTask>? {
        if (stdout.trim() == ABSENT) return null
        return stdout.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(SEP)
                if (idx < 0) return@mapNotNull null
                val mark = line.substring(0, idx)
                val name = line.substring(idx + SEP.length).trim()
                if (name.isEmpty()) null else BootTask(name, mark == "X")
            }
            .toList()
    }

    fun bootTaskPath(name: String): String = "\"\$HOME/.termux/boot/\"${sq(name)}"

    fun runBootTaskScript(name: String): String = "sh ${bootTaskPath(name)}"

    fun deleteBootTaskScript(name: String): String = "rm -f ${bootTaskPath(name)}"

    fun readBootTaskScript(name: String): String = "cat ${bootTaskPath(name)} 2>/dev/null"

    fun setBootTaskEnabledScript(name: String, enabled: Boolean): String =
        if (enabled) "chmod +x ${bootTaskPath(name)}" else "chmod -x ${bootTaskPath(name)}"

    /**
     * Writes [contentBase64] (base64 of the UTF-8 script body) into the named boot script and
     * marks it executable. base64 is used so an arbitrary body survives the RUN_COMMAND argument
     * boundary without any quoting hazard.
     */
    fun writeBootTaskScript(name: String, contentBase64: String): String =
        "mkdir -p \"\$HOME/.termux/boot\" && " +
            "printf '%s' ${sq(contentBase64)} | base64 -d > ${bootTaskPath(name)} && " +
            "chmod +x ${bootTaskPath(name)}"

    // ---------------------------------------------------------------------------------------------
    // Backup  (§4 line 90)
    // ---------------------------------------------------------------------------------------------

    /** tars HOME into a timestamped archive under HOME and echoes the resulting path. */
    fun backupScript(): String =
        "ts=\$(date +%Y%m%d-%H%M%S); out=\"\$HOME/masamune-backup-\$ts.tar.gz\"; " +
            "tar czf \"\$out\" -C \"\$HOME\" . 2>/dev/null && echo \"\$out\""

    // ---------------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------------

    /** Single-quote a value for safe interpolation into a `bash -c` line. */
    fun sq(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
