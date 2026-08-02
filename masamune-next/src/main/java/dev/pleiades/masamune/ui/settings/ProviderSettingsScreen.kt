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
import androidx.compose.ui.res.stringResource
import dev.pleiades.masamune.R
import dev.pleiades.masamune.ai.AiServiceFactory
import dev.pleiades.masamune.ai.ProviderKind
import dev.pleiades.masamune.ai.ProviderStore
import dev.pleiades.masamune.ai.auth.AccountStore
import dev.pleiades.masamune.ai.auth.AuthMode
import dev.pleiades.masamune.ai.auth.OAuthCatalog
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * Provider configuration. About → AI provider (also the gear on the Chat screen).
 *
 * Exactly two provider kinds ship. Every additional provider in the donor tree was a surface
 * that got claimed and never tested, so there are no others here.
 *
 * The API key lives here and still works, but it is no longer the only way in: the sign-in
 * method selector at the top of this screen switches the whole app between an account token
 * and a pasted key, and the key block visibly steps back when the account path is chosen. The
 * framing follows App Manager's VirusTotal key preference, which describes what a key
 * *enables* rather than treating it as a prerequisite.
 */
@Composable
fun ProviderSettingsScreen(onOpenAccount: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProviderStore.get(context) }
    val accounts = remember { AccountStore.get(context) }
    val saved by store.config.collectAsState()
    val sessions by accounts.sessions.collectAsState()
    val scope = rememberCoroutineScope()

    var authMode by remember(saved.authMode) { mutableStateOf(saved.authMode) }
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
            title = stringResource(R.string.account_cat_method),
            subtitle = stringResource(R.string.account_cat_method_desc),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = authMode == candidate,
                        onClick = {
                            authMode = candidate
                            // Written straight through: a mode selector that only takes effect
                            // after a separate Save is a control that appears to do nothing.
                            store.setAuthMode(candidate)
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (candidate == AuthMode.SUBSCRIPTION) {
                                        R.string.account_mode_subscription
                                    } else {
                                        R.string.account_mode_api_key
                                    }
                                )
                            )
                        },
                    )
                }
            }
            Text(
                stringResource(
                    if (authMode == AuthMode.SUBSCRIPTION) {
                        R.string.account_mode_subscription_desc
                    } else {
                        R.string.account_mode_api_key_desc
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            val session = sessions[saved.oauthProfileId]
            val profileLabel = OAuthCatalog.byId(saved.oauthProfileId)?.label
            Text(
                when {
                    authMode == AuthMode.API_KEY -> "Chat sends the key below."
                    session != null -> "Chat signs as ${session.identity?.display ?: "the " +
                        "connected account"} on ${profileLabel ?: "the selected provider"}."
                    else -> "Subscription mode is selected but no account is connected, so " +
                        "chat will refuse to send."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenAccount) { Text("Open Account") }
        }

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
                label = { Text("API key (fallback)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (authMode == AuthMode.SUBSCRIPTION) {
                Text(
                    "Subscription mode is selected, so this key is stored but NOT sent. " +
                        "Switch the sign-in method above to use it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
                        authMode = authMode,
                    )
                )
                testResult = "Saved."
                testOk = true
            }) { Text("Save") }

            val canTest = baseUrl.isNotBlank() && model.isNotBlank() && when (authMode) {
                AuthMode.API_KEY -> apiKey.isNotBlank()
                AuthMode.SUBSCRIPTION -> sessions[saved.oauthProfileId] != null
            }
            OutlinedButton(
                enabled = !testing && canTest,
                onClick = {
                    val candidate = saved.copy(
                        kind = kind,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        systemPrompt = systemPrompt,
                        authMode = authMode,
                    )
                    testing = true
                    testResult = null
                    scope.launch {
                        val result = AiServiceFactory.create(context, candidate).testConnection()
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

        if (!canTestReason(authMode, apiKey, baseUrl, model, sessions.containsKey(saved.oauthProfileId)).isNullOrBlank()) {
            Text(
                canTestReason(authMode, apiKey, baseUrl, model, sessions.containsKey(saved.oauthProfileId)).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Notice(
            title = "Where the key is stored",
            body = "App-private SharedPreferences, not hardware-backed keystore, and this " +
                "screen does not pretend otherwise. `allowBackup` is off so a cloud backup " +
                "cannot carry it off the device, but root on the device can read it. Account " +
                "tokens are held elsewhere and sealed — see About → Account.",
            tone = NoticeTone.INFO,
        )
    }
}

/** Why "Test connection" is disabled, or null when it will actually run. */
private fun canTestReason(
    mode: AuthMode,
    apiKey: String,
    baseUrl: String,
    model: String,
    hasSession: Boolean,
): String? = when {
    baseUrl.isBlank() -> "Test is disabled: the base URL is empty."
    model.isBlank() -> "Test is disabled: no model is set."
    mode == AuthMode.API_KEY && apiKey.isBlank() ->
        "Test is disabled: API key mode is selected and no key is set."
    mode == AuthMode.SUBSCRIPTION && !hasSession ->
        "Test is disabled: subscription mode is selected and no account is signed in. " +
            "Open Account to sign in."
    else -> null
}
