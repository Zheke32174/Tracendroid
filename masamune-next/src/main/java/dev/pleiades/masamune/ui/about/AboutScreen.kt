package dev.pleiades.masamune.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.BuildConfig
import dev.pleiades.masamune.nav.RouteCatalog
import dev.pleiades.masamune.ui.components.KeyValueRow
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * About. Bottom nav → About.
 *
 * Doubles as the entry point for everything that is not a primary surface: provider settings,
 * the capability matrix, the refusal log, the navigation map and the ryznix roadmap. This is
 * why those routes are reachable rather than merely declared.
 */
@Composable
fun AboutScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(
            title = "Masamune",
            subtitle = "An on-device AI harness: a file explorer, a shell surface and a chat " +
                "harness. Built from scratch as the module masamune-next; the old :app module " +
                "is left untouched as scrap reference.",
        ) {
            KeyValueRow("Application id", BuildConfig.APPLICATION_ID, mono = true)
            KeyValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValueRow("Build type", BuildConfig.BUILD_TYPE)
        }

        SectionCard(title = "Settings and logs") {
            NavRow("AI provider", "Endpoint, model and the fallback API key") { onNavigate(RouteCatalog.SETTINGS_PROVIDER) }
            NavRow("Account", "Sign in to a provider account instead of pasting a key") { onNavigate(RouteCatalog.SETTINGS_ACCOUNT) }
            NavRow("Capabilities", "The default-deny grant matrix") { onNavigate(RouteCatalog.SETTINGS_CAPABILITIES) }
            NavRow("Refusal log", "Every no this app said, classified") { onNavigate(RouteCatalog.SETTINGS_DECLINES) }
            NavRow("Navigation map", "Every route and how to reach it") { onNavigate(RouteCatalog.NAV_MAP) }
        }

        SectionCard(title = "Roadmap") {
            NavRow("ryznix / second OS", "Design only — not a feature") { onNavigate(RouteCatalog.RYZNIX) }
        }

        Notice(
            title = "What this build does not do",
            body = buildString {
                appendLine("• No tool calling. The chat model cannot read files or run commands.")
                appendLine("• No local inference. llama.cpp / MNN need native code; those source trees are empty here.")
                appendLine("• No plugin runtime. The QuickJS engine and its marketplace are not carried over.")
                appendLine("• No UI automation, no accessibility service, no companion APK.")
                appendLine("• No self-updater and no market client pointed at anyone's repository.")
                append("• No bundled Shizuku, Dhizuku, Termux or provider APK, and no installer for any of them.")
            },
            tone = NoticeTone.INFO,
        )

        Notice(
            title = "Where privilege would come from",
            body = "From YOJIMBO, the suite's elevation broker — never from Shizuku or Dhizuku " +
                "directly, and never from a companion APK this app installs. No privileged " +
                "backend is wired in this build, so nothing here runs elevated. A " +
                "PrivilegedFileSystem would be one more implementation of the same FileSystem " +
                "interface the explorer already uses.",
            tone = NoticeTone.INFO,
        )
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
