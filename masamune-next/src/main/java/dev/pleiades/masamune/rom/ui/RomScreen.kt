package dev.pleiades.masamune.rom.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.pleiades.masamune.rom.Availability
import dev.pleiades.masamune.rom.RomArch
import dev.pleiades.masamune.rom.RomBackendProbe
import dev.pleiades.masamune.rom.RomChainResult
import dev.pleiades.masamune.rom.RomImage
import dev.pleiades.masamune.ui.components.KeyValueRow
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The ROM-launcher surface (docs/ROM-LAUNCH.md).
 *
 * ### What this screen refuses to do is the point of it
 * A ROM boots a second kernel, which needs one of AVF, KVM or QEMU-TCG; on a stock sideloaded
 * build all three are closed. So the backend chain reports ABSENT, and the Launch control on every
 * image is **disabled**, naming exactly which paths are missing and why. There is no faked boot, no
 * progress bar for a VM that is not running, no "booting…" that leads nowhere — a disabled control
 * that explains itself is the correct and only acceptable state here (docs/ROM-LAUNCH.md, and the
 * same honest-gating rule the flow palette and the operator surface follow).
 *
 * Adding an image is a *real* operation — a SAF-picked file is copied into app-scoped external
 * storage — so that path is enabled. Launching one is not, because no backend can back it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomScreen() {
    val vm = masamuneViewModel { RomViewModel(it) }

    val chain by vm.chain.collectAsState()
    val images by vm.images.collectAsState()
    val importing by vm.importing.collectAsState()
    val notice by vm.notice.collectAsState()

    // Re-probe on entry so returning after a reinstall (AVF) or a prefix QEMU install (TCG) is seen.
    LaunchedEffect(Unit) { vm.refreshChain() }

    var pendingArch by remember { mutableStateOf(RomArch.AARCH64) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importImage(uri, pendingArch) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.rom_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.rom_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Keep the ROM firmly apart from the subsystem — it is the one thing this surface must not
        // cannibalise (docs/ROM-LAUNCH.md "Not to be confused with the Linux subsystem").
        Notice(
            title = stringResource(R.string.rom_not_subsystem_title),
            body = stringResource(R.string.rom_not_subsystem_body),
            tone = NoticeTone.INFO,
        )

        BackendChainCard(chain = chain, onRefresh = vm::refreshChain)

        if (notice != null) {
            Notice(
                title = "Result",
                body = notice!!,
                tone = NoticeTone.WARNING,
                actionLabel = stringResource(R.string.rom_dismiss),
                onAction = vm::dismissNotice,
            )
        }

        ImagesCard(
            images = images,
            hostArch = vm.hostArch,
            launchEnabled = !chain.isAbsent,
            onLaunch = vm::launch,
            onRemove = vm::removeImage,
        )

        AddImageCard(
            pendingArch = pendingArch,
            onArchSelected = { pendingArch = it },
            importing = importing,
            onPick = { picker.launch(arrayOf("*/*")) },
        )
    }
}

/**
 * The chain readout: one row per backend with its live/closed status, and — when the chain is
 * ABSENT — every closed path's reason spelled out. This is where "name the one in use, report
 * ABSENT rather than pretend" is rendered.
 */
@Composable
private fun BackendChainCard(chain: RomChainResult, onRefresh: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.rom_backends_title),
        subtitle = stringResource(R.string.rom_backends_subtitle),
    ) {
        val live = chain.live
        if (live != null) {
            Notice(
                title = stringResource(R.string.rom_live_title, live.label),
                body = if (live.nativeSpeed) {
                    stringResource(R.string.rom_backend_native_speed)
                } else {
                    stringResource(R.string.rom_backend_emulated)
                },
                tone = NoticeTone.SUCCESS,
            )
        } else {
            Notice(
                title = stringResource(R.string.rom_absent_title),
                body = stringResource(R.string.rom_absent_body),
                tone = NoticeTone.ERROR,
            )
        }

        chain.probes.forEach { probe -> BackendRow(probe) }

        OutlinedButton(onClick = onRefresh) {
            Text(stringResource(R.string.rom_refresh))
        }
    }
}

/** One backend's status line: its label, a state tag, and — when unavailable — the naming sentence. */
@Composable
private fun BackendRow(probe: RomBackendProbe) {
    val (tag, tone) = when (probe.availability) {
        is Availability.Available -> stringResource(R.string.rom_backend_available) to MasamuneTheme.semantic.success
        is Availability.Unavailable -> stringResource(R.string.rom_backend_unavailable) to MaterialTheme.colorScheme.error
        is Availability.Unknown -> stringResource(R.string.rom_backend_unknown) to MasamuneTheme.semantic.warning
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                probe.backend.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(tag, style = MaterialTheme.typography.labelMedium, color = tone)
        }
        (probe.availability as? Availability.Unavailable)?.let {
            Text(
                it.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The registry: each added image, its arch/speed characterisation, and a Launch control. */
@Composable
private fun ImagesCard(
    images: List<RomImage>,
    hostArch: RomArch,
    launchEnabled: Boolean,
    onLaunch: (RomImage) -> Unit,
    onRemove: (RomImage) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.rom_images_title),
        subtitle = stringResource(R.string.rom_images_subtitle),
    ) {
        if (images.isEmpty()) {
            Text(
                stringResource(R.string.rom_images_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MasamuneTheme.semantic.dim,
            )
        } else {
            images.forEach { image ->
                ImageRow(
                    image = image,
                    hostArch = hostArch,
                    launchEnabled = launchEnabled,
                    onLaunch = { onLaunch(image) },
                    onRemove = { onRemove(image) },
                )
            }
        }

        // The one line the whole surface exists to earn: why Launch is disabled, stated plainly.
        if (!launchEnabled) {
            Text(
                stringResource(R.string.rom_launch_disabled_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ImageRow(
    image: RomImage,
    hostArch: RomArch,
    launchEnabled: Boolean,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyValueRow(image.name, stringResource(R.string.rom_image_size, formatBytes(image.sizeBytes)))
        Text(
            image.speedNote(hostArch),
            style = MaterialTheme.typography.bodySmall,
            color = MasamuneTheme.semantic.dim,
        )
        Text(
            image.path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MasamuneTheme.semantic.dim,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onLaunch, enabled = launchEnabled) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.rom_launch), modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.rom_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Pick the guest arch, then pick a file. The copy is real; [importing] reports it honestly. */
@Composable
private fun AddImageCard(
    pendingArch: RomArch,
    onArchSelected: (RomArch) -> Unit,
    importing: Boolean,
    onPick: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.rom_add_title)) {
        Text(
            stringResource(R.string.rom_add_arch_label),
            style = MaterialTheme.typography.labelMedium,
            color = MasamuneTheme.semantic.dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RomArch.entries.forEach { arch ->
                FilterChip(
                    selected = pendingArch == arch,
                    onClick = { onArchSelected(arch) },
                    label = { Text(arch.label) },
                    leadingIcon = if (pendingArch == arch) {
                        { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
        }

        OutlinedButton(onClick = onPick, enabled = !importing) {
            Text(stringResource(R.string.rom_add_button))
        }

        if (importing) {
            Text(
                stringResource(R.string.rom_importing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Human-readable byte size, so "measured in gigabytes" is shown, not implied. */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return String.format("%.1f %s", value, units[i])
}
