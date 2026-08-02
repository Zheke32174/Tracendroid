package dev.pleiades.masamune.operator.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.flow.ui.FiberMonitor
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.masamuneViewModel
import kotlinx.coroutines.delay

/**
 * The AI operator surface: a goal, a Run/Stop control, and a live view of what the operator is
 * doing — its reasoning transcript above, and the [FiberMonitor] (the operator's actual current
 * block) below. See docs/AI-OPERATOR.md.
 *
 * The screen's most important behaviour is the one it refuses to perform. When no connected
 * accessibility service exists, the whole surface is disabled and says precisely what is missing;
 * the Run control cannot start a thing. That is the accessibility gate rendered honestly — the
 * operator "silently tapping around your banking app" is the failure this design exists to
 * prevent, and a Run button that appears to work with no way to see or touch the screen would be
 * a lie of exactly that shape.
 */
@Composable
fun OperatorScreen() {
    val vm = masamuneViewModel { OperatorViewModel(it) }
    val context = LocalContext.current

    val goal by vm.goal.collectAsState()
    val running by vm.running.collectAsState()
    val a11yConnected by vm.a11yConnected.collectAsState()
    val a11yEnabledInSettings by vm.a11yEnabledInSettings.collectAsState()
    val fibers by vm.fibers.collectAsState()
    val transcript by vm.transcript.collectAsState()

    // Poll the accessibility gate while idle so toggling the service in Settings and returning
    // updates the surface without a manual refresh. The poll stops during a run.
    LaunchedEffect(running) {
        while (!running) {
            vm.refreshGate()
            delay(GATE_POLL_MS)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AI operator", style = MaterialTheme.typography.titleMedium)
            Text(
                "The operator runs as a fiber on the flow runtime: it observes the screen, decides " +
                    "the next action with the LLM, and acts — each step a visible block. Provider: " +
                    vm.providerLabel + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!a11yConnected) {
                Notice(
                    title = "Operator disabled — accessibility service not connected",
                    body = if (a11yEnabledInSettings) {
                        "Masamune is enabled in Settings but its accessibility service has not " +
                            "connected yet. Give it a moment, or toggle it off and on. Until it " +
                            "connects the operator cannot see or touch the screen, so it will not run."
                    } else {
                        "The operator needs Masamune's accessibility service to see and touch the " +
                            "screen. It is not enabled. Turn it on in Settings → Accessibility → " +
                            "Masamune. Nothing runs until it is."
                    },
                    tone = NoticeTone.ERROR,
                    actionLabel = "Open accessibility settings",
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            } else if (!vm.providerUsable) {
                Notice(
                    title = "No LLM provider configured",
                    body = "The operator's decide step calls the same provider the chat surface " +
                        "uses. None is set up, so a run would fail on its first decision. Configure " +
                        "one at About → AI provider or sign in at About → Account.",
                    tone = NoticeTone.WARNING,
                )
            }

            OutlinedTextField(
                value = goal,
                onValueChange = vm::setGoal,
                modifier = Modifier.fillMaxWidth(),
                enabled = a11yConnected && !running,
                label = { Text("Goal — what should the operator do?") },
                placeholder = { Text("e.g. open Settings and turn on Wi-Fi") },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    Button(onClick = vm::stop) { Text("Stop") }
                    Text(
                        "Running — Stop halts it between blocks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Button(
                        onClick = vm::run,
                        enabled = a11yConnected && goal.isNotBlank(),
                    ) { Text("Run operator") }
                }
            }
        }

        if (transcript.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Operator log", style = MaterialTheme.typography.labelLarge)
                transcript.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FiberMonitor(
            fibers = fibers,
            modifier = Modifier.fillMaxSize(),
            resolveBlockName = { vm.blockNameOf(it) },
        )
    }
}

private const val GATE_POLL_MS = 1_500L
