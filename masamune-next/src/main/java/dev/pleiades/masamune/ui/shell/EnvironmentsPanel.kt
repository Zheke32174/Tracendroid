package dev.pleiades.masamune.ui.shell

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.shell.EnvironmentProbes
import dev.pleiades.masamune.ui.components.KeyValueRow
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * Environments panel (DONOR-SURFACES §4 lines 83-90): the checklist, packages, health check,
 * installed rootfs, boot tasks, and backup/restore/SAF — in the donor's order.
 *
 * Every readout is a parsed probe result. Where a capability is not backable the section shows a
 * blocked/gated notice naming exactly what is missing (proot-distro not installed; Termux:Boot not
 * detectable; SAF restore not streamable over one-shot RUN_COMMAND) rather than a live control.
 */
@Composable
fun EnvironmentsPanel(
    vm: ShellViewModel,
    env: EnvState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MasamuneTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.md),
    ) {
        ChecklistCard(env, onRecheck = vm::recheckChecklist)
        PackagesCard(env, vm)
        HealthCard(env, onRun = vm::runHealthCheck)
        RootfsCard(env, onProbe = vm::probeProotDistro)
        BootTasksCard(env, vm)
        BackupCard(env, onBackup = vm::runBackup)
    }
}

// -------------------------------------------------------------------------------------------------
// Start configuration checklist  (§4 line 86)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ChecklistCard(env: EnvState, onRecheck: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.terminal_checklist_title),
        subtitle = stringResource(R.string.terminal_checklist_subtitle),
    ) {
        val notDetected = stringResource(R.string.terminal_not_detected)
        env.checklist.forEach { tool ->
            KeyValueRow(
                key = tool.key,
                value = if (tool.detected) tool.version ?: "" else notDetected,
                mono = true,
            )
        }
        env.checklistError?.let { Notice("Probe failed", it, NoticeTone.ERROR) }
        BusyButton(
            label = stringResource(R.string.terminal_checklist_recheck),
            loading = env.checklistLoading,
            onClick = onRecheck,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Packages  (§4 line 88)
// -------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackagesCard(env: EnvState, vm: ShellViewModel) {
    var installField by remember { mutableStateOf("") }
    SectionCard(
        title = stringResource(R.string.terminal_packages_title),
        subtitle = stringResource(R.string.terminal_packages_subtitle),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
        ) {
            OutlinedTextField(
                value = installField,
                onValueChange = { installField = it },
                label = { Text(stringResource(R.string.terminal_packages_install_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            TextButton(
                onClick = { vm.installPackage(installField); installField = "" },
                enabled = installField.isNotBlank() && !env.packageOpRunning,
            ) { Text(stringResource(R.string.terminal_packages_install)) }
        }

        Text(
            stringResource(R.string.terminal_packages_quick_install),
            style = MaterialTheme.typography.labelSmall,
            color = MasamuneTheme.semantic.dim,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs)) {
            EnvironmentProbes.QUICK_INSTALL.forEach { pkg ->
                AssistChip(onClick = { installField = pkg }, label = { Text(pkg) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
            BusyButton(stringResource(R.string.terminal_packages_upgrade), env.packageOpRunning, vm::upgradeAllPackages)
            BusyButton(stringResource(R.string.terminal_packages_refresh), env.packagesLoading, vm::loadPackages)
        }

        env.diskUsage?.let {
            KeyValueRow(stringResource(R.string.terminal_packages_disk_usage), it, mono = true)
        }
        env.packageOpResult?.let { Notice("pkg", it, NoticeTone.INFO) }
        env.packagesError?.let { Notice("List failed", it, NoticeTone.ERROR) }

        if (env.packages.isEmpty() && !env.packagesLoading) {
            Text(
                stringResource(R.string.terminal_packages_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )
        } else {
            env.packages.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(p.version, style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                    }
                    TextButton(onClick = { vm.removePackage(p.name) }, enabled = !env.packageOpRunning) {
                        Text(stringResource(R.string.terminal_packages_remove))
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Health check  (§4 line 89)
// -------------------------------------------------------------------------------------------------

@Composable
private fun HealthCard(env: EnvState, onRun: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.terminal_health_title),
        subtitle = stringResource(R.string.terminal_health_subtitle),
    ) {
        val pass = stringResource(R.string.terminal_health_pass)
        val fail = stringResource(R.string.terminal_health_fail)
        env.health.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (h.passed) pass else fail,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (h.passed) MasamuneTheme.semantic.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = MasamuneTheme.spacing.sm),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(h.key, style = MaterialTheme.typography.bodyMedium)
                    Text(h.detail, style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
                }
            }
        }
        env.healthError?.let { Notice("Health probe failed", it, NoticeTone.ERROR) }
        BusyButton(stringResource(R.string.terminal_health_run), env.healthLoading, onRun)
    }
}

// -------------------------------------------------------------------------------------------------
// Installed rootfs  (proot-distro; §4 lines 84-85)
// -------------------------------------------------------------------------------------------------

@Composable
private fun RootfsCard(env: EnvState, onProbe: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.terminal_rootfs_title),
        subtitle = stringResource(R.string.terminal_rootfs_subtitle),
    ) {
        when (env.prootDistroPresent) {
            null -> Unit
            false -> Notice(
                title = stringResource(R.string.terminal_rootfs_title),
                body = stringResource(R.string.terminal_rootfs_absent),
                tone = NoticeTone.BLOCKED,
            )
            true -> {
                val installed = stringResource(R.string.terminal_rootfs_installed)
                val available = stringResource(R.string.terminal_rootfs_available)
                env.rootfs.forEach { d ->
                    KeyValueRow(d.alias, if (d.installed) installed else available, mono = true)
                }
                // Mutating rootfs ops are reserved and stay off-screen as live controls.
                Text(
                    stringResource(R.string.terminal_rootfs_reserved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        }
        BusyButton(stringResource(R.string.terminal_rootfs_probe), env.rootfsLoading, onProbe)
    }
}

// -------------------------------------------------------------------------------------------------
// Boot tasks  (Termux:Boot; §4 line 87)
// -------------------------------------------------------------------------------------------------

@Composable
private fun BootTasksCard(env: EnvState, vm: ShellViewModel) {
    SectionCard(
        title = stringResource(R.string.terminal_boot_title),
        subtitle = stringResource(R.string.terminal_boot_subtitle),
    ) {
        val tasks = env.bootTasks
        when {
            !env.bootProbed -> Unit
            tasks == null -> Text(
                stringResource(R.string.terminal_boot_none_dir),
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )
            tasks.isEmpty() -> Text(
                stringResource(R.string.terminal_boot_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )
            else -> tasks.forEach { task ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = task.enabled,
                            onCheckedChange = { vm.setBootTaskEnabled(task.name, it) },
                        )
                    }
                    Text(
                        stringResource(R.string.terminal_boot_enabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MasamuneTheme.semantic.dim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
                        TextButton(onClick = { vm.runBootTask(task.name) }) {
                            Text(stringResource(R.string.terminal_boot_start_now))
                        }
                        TextButton(onClick = { vm.beginEditBootTask(task.name) }) {
                            Text(stringResource(R.string.terminal_boot_edit))
                        }
                        TextButton(onClick = { vm.deleteBootTask(task.name) }) {
                            Text(stringResource(R.string.terminal_boot_delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        env.bootError?.let { Notice("Boot probe failed", it, NoticeTone.ERROR) }
        Row(horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
            BusyButton(stringResource(R.string.terminal_boot_load), env.bootLoading, vm::loadBootTasks)
            TextButton(onClick = { vm.beginNewBootTask() }) { Text(stringResource(R.string.terminal_boot_new)) }
        }
    }

    env.bootEdit?.let { edit ->
        BootTaskEditor(
            edit = edit,
            onDismiss = { vm.cancelBootEdit() },
            onSave = { name, body ->
                val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                vm.saveBootTask(name, b64)
            },
        )
    }
}

@Composable
private fun BootTaskEditor(
    edit: BootEditState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(edit) { mutableStateOf(edit.name) }
    var body by remember(edit) { mutableStateOf(edit.body) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (edit.existing) R.string.terminal_boot_edit else R.string.terminal_boot_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.terminal_boot_name_hint)) },
                    singleLine = true,
                    enabled = !edit.existing,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.terminal_boot_body_hint)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, body) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.terminal_boot_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) } },
    )
}

// -------------------------------------------------------------------------------------------------
// Backup / Restore / Expose over SAF  (§4 line 90)
// -------------------------------------------------------------------------------------------------

@Composable
private fun BackupCard(env: EnvState, onBackup: () -> Unit) {
    var exposedTree by remember { mutableStateOf<String?>(null) }
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> exposedTree = uri?.toString() }

    SectionCard(
        title = stringResource(R.string.terminal_backup_title),
        subtitle = stringResource(R.string.terminal_backup_subtitle),
    ) {
        BusyButton(stringResource(R.string.terminal_backup_run), env.backupRunning, onBackup)
        env.backupResult?.let { Notice("Backup", it, NoticeTone.INFO) }

        OutlinedButton(onClick = { safLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.terminal_backup_expose_saf))
        }
        exposedTree?.let {
            KeyValueRow(
                key = stringResource(R.string.terminal_backup_expose_saf),
                value = stringResource(R.string.terminal_backup_saf_picked, it),
                mono = true,
            )
        }

        // Restore is not backable over one-shot RUN_COMMAND: gated with a naming sentence.
        Notice(
            title = stringResource(R.string.terminal_backup_restore),
            body = stringResource(R.string.terminal_backup_restore_gated),
            tone = NoticeTone.BLOCKED,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Shared
// -------------------------------------------------------------------------------------------------

@Composable
private fun BusyButton(label: String, loading: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !loading) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(label)
    }
}
