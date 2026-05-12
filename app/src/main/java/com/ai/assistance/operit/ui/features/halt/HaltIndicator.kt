package com.ai.assistance.operit.ui.features.halt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.halt.HaltController

/**
 * Always-reachable halt control (§ 4.7).
 *
 * Two pieces, both mounted at the OperitApp shell so they overlay every screen:
 *  - [HaltFab] — a small floating action button anchored bottom-end. One tap pops the
 *    confirmation dialog; second tap halts. Visible whenever the system is Running.
 *  - [HaltedBanner] — a top banner shown whenever the system is Halted. Carries a
 *    "Resume" button that calls [HaltController.clear] (a new session starts fresh).
 *
 * The halt itself is a HaltController action — the actual surface enforcement lives in
 * the AI/JS gates and the IPC client per § 4.7 wiring.
 */
@Composable
fun HaltControlOverlay(modifier: Modifier = Modifier) {
    val state by HaltController.state.collectAsState()
    Box(modifier = modifier.fillMaxSize()) {
        when (val s = state) {
            HaltController.State.Running -> HaltFab(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
            is HaltController.State.Halted -> HaltedBanner(
                state = s,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp, start = 16.dp, end = 16.dp)
            )
        }
    }
}

@Composable
private fun HaltFab(modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    SmallFloatingActionButton(
        onClick = { confirming = true },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Icon(
            imageVector = Icons.Default.Stop,
            contentDescription = "Halt all AI activity",
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            title = { Text("Halt all AI activity?", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Stops in-flight tool calls, refuses new tool calls (AI and plugin), " +
                            "and tears down any active shell session.",
                    )
                    Text(
                        "Halt is logged. You can resume by clearing the halt from the " +
                            "banner that appears.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        HaltController.requestHalt(
                            by = "user:halt-fab",
                            reason = "User tapped halt floating action button",
                        )
                        confirming = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Halt now") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
@Composable
private fun HaltedBanner(state: HaltController.State.Halted, modifier: Modifier = Modifier) {
    val audit by HaltController.audit.collectAsState()
    // § 4.7 — surface the AI reasoning snapshot the audit ring captured for this halt
    // event. Match by timestamp + by + reason; the most recent matching entry is the
    // one this banner instance is for.
    val snapshot = remember(audit, state.at, state.by, state.reason) {
        audit.lastOrNull { it.at == state.at && it.by == state.by && it.reason == state.reason }
            ?.context
            ?.takeIf { it.isNotBlank() }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI activity halted",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "By ${state.by} — ${state.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(
                    onClick = { HaltController.clear() },
                ) { Text("Resume") }
            }
            if (snapshot != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "AI reasoning at halt:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = snapshot.take(800),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
