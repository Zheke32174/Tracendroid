package dev.pleiades.masamune.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.ai.PromptTurnKind
import dev.pleiades.masamune.data.MessageEntity
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * Chat surface. Bottom nav → Chat.
 *
 * What it does: streams a reply from one BYOK provider (OpenAI-compatible or Anthropic) and
 * persists the conversation in Room. What it does NOT do, stated on screen: call tools. The
 * model cannot reach the filesystem or the shell from here.
 */
@Composable
fun ChatScreen(
    onOpenProviderSettings: () -> Unit,
    onOpenCapabilities: () -> Unit,
) {
    val vm = masamuneViewModel { ctx -> ChatViewModel(ctx) }
    val state by vm.state.collectAsState()
    val chats by vm.chats.collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.refreshGrants() }
    LaunchedEffect(state.messages.size, state.streaming) {
        val count = state.messages.size + if (state.streaming != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.providerConfigured) state.providerModel else "No provider configured",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.providerConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        "No tool calling in this build — the model cannot read files or run commands.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MasamuneTheme.semantic.dim,
                    )
                }
                IconButton(onClick = { historyOpen = true }) {
                    Icon(Icons.Filled.History, contentDescription = "Chat history")
                }
                IconButton(onClick = { vm.newChat() }) {
                    Icon(Icons.Filled.Add, contentDescription = "New chat")
                }
                IconButton(onClick = onOpenProviderSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Provider settings")
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.providerConfigured) {
                Notice(
                    title = "Bring your own key",
                    body = "This build ships no keys and no default endpoint credentials. Set a " +
                        "base URL, API key and model to send anything.",
                    tone = NoticeTone.WARNING,
                    actionLabel = "Open AI provider settings",
                    onAction = onOpenProviderSettings,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!state.networkGranted) {
                Notice(
                    title = "NETWORK capability not granted",
                    body = "The capability gate denies outbound requests for caller \"user\" by " +
                        "default. Nothing is sent until this is granted.",
                    tone = NoticeTone.BLOCKED,
                    actionLabel = "Grant NETWORK to \"user\"",
                    onAction = { vm.grantNetwork() },
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onOpenCapabilities) { Text("Open the full capability matrix") }
            }
            if (state.halted) {
                Notice(
                    title = "Halted",
                    body = "The global halt is engaged. Every gated call — chat, shell, file " +
                        "writes — refuses until it is cleared with the stop button in the app bar.",
                    tone = NoticeTone.BLOCKED,
                )
            }
            state.error?.let {
                Notice(
                    title = "Request failed",
                    body = it,
                    tone = NoticeTone.ERROR,
                    actionLabel = "Dismiss",
                    onAction = { vm.dismissError() },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                if (state.busy && message.content.isBlank() && message.error == null) {
                    // The in-flight assistant row is rendered from `streaming` instead.
                } else {
                    MessageBubble(message)
                }
            }
            if (state.streaming != null) {
                item {
                    StreamingBubble(state.streaming!!)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Message") },
                    enabled = !state.busy,
                    maxLines = 5,
                )
                if (state.busy) {
                    IconButton(onClick = { vm.stop() }) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    if (historyOpen) {
        AlertDialog(
            onDismissRequest = { historyOpen = false },
            title = { Text("Conversations") },
            text = {
                if (chats.isEmpty()) {
                    Text("No conversations stored yet.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(chats, key = { it.id }) { chat ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        vm.openChat(chat.id)
                                        historyOpen = false
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(chat.title.ifBlank { "Untitled" }, maxLines = 1)
                                }
                                TextButton(onClick = { vm.deleteChat(chat.id) }) { Text("Delete") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyOpen = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val isUser = message.kind == PromptTurnKind.USER.name
    Surface(
        color = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (isUser) "you" else "assistant",
                style = MaterialTheme.typography.labelSmall,
                color = MasamuneTheme.semantic.dim,
            )
            if (message.content.isNotBlank()) {
                SelectionContainer {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
            message.error?.let { err ->
                Notice(title = "This turn failed", body = err, tone = NoticeTone.ERROR)
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
                AssistChip(onClick = {}, enabled = false, label = { Text("streaming") })
            }
            Text(
                text.ifBlank { "…" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
