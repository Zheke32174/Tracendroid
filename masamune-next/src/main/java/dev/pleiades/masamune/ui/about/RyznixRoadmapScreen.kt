package dev.pleiades.masamune.ui.about

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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * ryznix / second OS. About → ryznix roadmap.
 *
 * DESIGN ONLY. This screen exists so the idea is written down somewhere honest, and it says in
 * its own UI that none of it is built. There is deliberately no button on this screen that
 * starts, installs, downloads or prepares anything — if there were, it would be a lie.
 *
 * The reporting discipline here comes from docs/REBUILD-CHARTER.md: three states that must
 * never be conflated — verified working / compiles-unproven / known broken. Every row below is
 * explicitly labelled with the third, or with "does not exist".
 */
@Composable
fun RyznixRoadmapScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Notice(
            title = "This is not a feature. Nothing here is implemented.",
            body = "ryznix is a design for running a second operating system alongside Android " +
                "on the same device. No part of it ships in this app, no part of it can be " +
                "built in this environment, and this screen has no controls because there is " +
                "nothing to control.",
            tone = NoticeTone.BLOCKED,
        )

        SectionCard(
            title = "The design, in one paragraph",
            subtitle = "Recorded so it is not lost, not so it is mistaken for progress.",
        ) {
            Text(
                "A guest system image runs under a hypervisor or a namespaced container on the " +
                    "same hardware. Android stays the host and keeps the display and radios. " +
                    "The guest gets its own init, its own package set and its own filesystem, " +
                    "which the Masamune explorer would browse as just another FileSystem " +
                    "implementation — a ContainerFileSystem, the local node treated as though " +
                    "it were a remote host. The harness would drive the guest the same way it " +
                    "drives a shell today: over a framed, authenticated, capability-tagged " +
                    "request protocol, not by shelling out.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard(title = "What each piece would need, and why it is absent") {
            BlockedRow(
                "Guest VM / hypervisor",
                "Requires either the Android Virtualization Framework (pKVM, vendor-gated on " +
                    "most retail devices) or a userspace emulator. Neither is present and " +
                    "neither can be produced from this repository.",
            )
            BlockedRow(
                "KSU guest kernel",
                "A kernel with KernelSU patches has to be compiled for the specific device and " +
                    "flashed. That is a per-device kernel build, not an app concern, and " +
                    "nothing in this tree builds a kernel.",
            )
            BlockedRow(
                "Nested zygote",
                "Running a second Android userspace means a second zygote under a different " +
                    "root. It does not exist even as a prototype here.",
            )
            BlockedRow(
                "Native bridge / PTY",
                "Every native source tree in this repository (quickjs, llama.cpp, MNN, ncnn, " +
                    "sherpa) is an uninitialised submodule with zero files, so this module " +
                    "declares no externalNativeBuild at all. Nothing native compiles here.",
            )
        }

        Notice(
            title = "What the app does today instead",
            body = "The Shell surface drives an installed Termux over its public RUN_COMMAND " +
                "contract. That is one real shell, on the host, with the host's privileges — " +
                "not a guest OS, and the Shell screen says so too.",
            tone = NoticeTone.INFO,
        )
    }
}

@Composable
private fun BlockedRow(title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "does not exist",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )
        }
    }
}
