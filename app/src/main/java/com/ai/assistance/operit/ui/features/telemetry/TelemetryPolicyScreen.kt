package com.ai.assistance.operit.ui.features.telemetry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * § 4.12 — surface the project's no-telemetry stance verbatim, in the app.
 *
 * The screen is read-only: there is no toggle to opt into collection, because there is
 * no collection. If a future build ever adds an opt-in surface, that work also
 * rewrites this screen — see docs/TELEMETRY_POLICY.md.
 *
 * The text mirrors the policy doc so the user can see, in the app, exactly what they
 * can read in the repo.
 */
@Composable
fun TelemetryPolicyScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("Position")
                Text(
                    "This app collects no telemetry. No background metrics, no install " +
                        "counts, no feature-usage analytics, no error-rate dashboards. " +
                        "There is no opt-in toggle on this screen because there is no " +
                        "collection to opt into."
                )
                Text(
                    "Adding any telemetry endpoint would require rewriting this screen " +
                        "and updating docs/THREAT_MODEL.md § 4.12 in the same change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle("Crashes")
                Text(
                    "On crash, a separate process shows the stack trace and offers three " +
                        "buttons: copy to clipboard, save to a file, or restart. No " +
                        "network call. No upload prompt. Sharing a crash with the project " +
                        "is always a manual paste."
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle("What network requests this app does make")
                Text(
                    "AI API calls to the providers you configure. Web searches you ask " +
                        "for. Browser sessions you open. Rootfs downloads when you set " +
                        "up the shell environment. MCP servers you connect to. Every " +
                        "request is a direct consequence of something you asked the app " +
                        "to do."
                )
                Text(
                    "Installing the app produces zero outbound traffic. Uninstalling " +
                        "produces zero outbound traffic. Crashes produce zero outbound " +
                        "traffic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = "Read the full policy at docs/TELEMETRY_POLICY.md " +
                "(中文: docs/TELEMETRY_POLICY.zh.md).",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = "Telemetry policy (§ 4.12)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "No telemetry. No analytics. No crash auto-upload.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}
