package dev.pleiades.masamune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.ai.AiServiceFactory
import dev.pleiades.masamune.ai.ProviderKind
import dev.pleiades.masamune.ai.ProviderStore
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * BYOK provider configuration. About → AI provider (also the gear on the Chat screen).
 *
 * Exactly two provider kinds ship. Every additional provider in the donor tree was a surface
 * that got claimed and never tested, so there are no others here.
 */
@Composable
fun ProviderSettingsScreen() {
    val context = LocalContext.current
    val store = remember { ProviderStore.get(context) }
    val saved by store.config.collectAsState()
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(saved.kind) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var systemPrompt by remember { mutableStateOf(saved.systemPrompt) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(
            title = "Provider",
            subtitle = "Both speak to a user-supplied endpoint. Nothing is bundled and no key " +
                "ships in the APK.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderKind.entries.forEach { candidate ->
                    FilterChip(
                        selected = kind == candidate,
                        onClick = {
                            kind = candidate
                            if (baseUrl.isBlank() || ProviderKind.entries.any { it.defaultBaseUrl == baseUrl }) {
                                baseUrl = candidate.defaultBaseUrl
                            }
                            if (model.isBlank() || ProviderKind.entries.any { it.defaultModel == model }) {
                                model = candidate.defaultModel
                            }
                        },
                        label = { Text(candidate.label) },
                    )
                }
            }
        }

        SectionCard(title = "Endpoint") {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when (kind) {
                    ProviderKind.OPENAI_COMPATIBLE ->
                        "POSTs to <base>/chat/completions with stream=true."
                    ProviderKind.ANTHROPIC ->
                        "POSTs to <base>/v1/messages with stream=true and anthropic-version 2023-06-01."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = "System prompt") {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("Sent with every request") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                store.save(
                    saved.copy(
                        kind = kind,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        systemPrompt = systemPrompt,
                    )
                )
                testResult = "Saved."
                testOk = true
            }) { Text("Save") }

            OutlinedButton(
                enabled = !testing && apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                onClick = {
                    val candidate = saved.copy(
                        kind = kind,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        systemPrompt = systemPrompt,
                    )
                    testing = true
                    testResult = null
                    scope.launch {
                        val result = AiServiceFactory.create(candidate).testConnection()
                        testing = false
                        testOk = result.isSuccess
                        testResult = result.getOrElse { it.message ?: it.javaClass.simpleName }
                    }
                },
            ) { Text("Test connection") }

            OutlinedButton(onClick = {
                store.clearKey()
                apiKey = ""
                testResult = "API key cleared."
                testOk = true
            }) { Text("Clear key") }
        }

        if (testing) CircularProgressIndicator()

        testResult?.let {
            Notice(
                title = if (testOk) "OK" else "Failed",
                body = it,
                tone = if (testOk) NoticeTone.SUCCESS else NoticeTone.ERROR,
            )
        }

        Notice(
            title = "Where the key is stored",
            body = "App-private SharedPreferences, not hardware-backed keystore, and this " +
                "screen does not pretend otherwise. `allowBackup` is off so a cloud backup " +
                "cannot carry it off the device, but root on the device can read it.",
            tone = NoticeTone.INFO,
        )
    }
}
