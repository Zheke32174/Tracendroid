package dev.pleiades.masamune.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import dev.pleiades.masamune.R
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The terminal context menu (DONOR-SURFACES §4 lines 80-82), overflow-anchored.
 *
 * Split by backability, per the prime directive:
 *   - Enabled, plain-intent actions: Select URL, Share transcript, Share selected text, Copy,
 *     Reset, Keep screen on, Help, Report Issue. None of these need a PTY.
 *   - Disabled, capability-naming actions: Style, Settings, Kill process, Autofill username,
 *     Autofill password. Each renders as a dead-lettered row that, when tapped, states which
 *     channel (terminal-settings route, live pid, live input field) is missing — never a live
 *     control that silently does nothing, and no longer silently omitted.
 */
@Composable
fun TerminalContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    transcriptText: String,
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var urlDialog by remember { mutableStateOf(false) }
    var shareSelectedDialog by remember { mutableStateOf(false) }
    var gatedNotice by remember { mutableStateOf<String?>(null) }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_action_select_url)) },
            onClick = { onDismiss(); urlDialog = true },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_action_share_transcript)) },
            onClick = {
                onDismiss()
                shareText(context, transcriptText, context.getString(R.string.terminal_share_chooser))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_action_share_selected_text)) },
            onClick = { onDismiss(); shareSelectedDialog = true },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_action_copy)) },
            onClick = {
                onDismiss()
                copyText(context, transcriptText, R.string.terminal_copied_transcript)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_action_reset)) },
            onClick = { onDismiss(); onReset() },
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.terminal_action_keep_screen_on))
                }
            },
            trailingIcon = {
                Switch(checked = keepScreenOn, onCheckedChange = { onToggleKeepScreenOn(it) })
            },
            onClick = { onToggleKeepScreenOn(!keepScreenOn) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_action_help)) },
            onClick = {
                onDismiss()
                openUrl(context, context.getString(R.string.terminal_help_url))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_action_report_issue)) },
            onClick = {
                onDismiss()
                shareText(context, reportText(transcriptText), context.getString(R.string.terminal_action_report_issue))
            },
        )

        HorizontalDivider()

        // Gated rows: present but disabled, naming the missing channel on tap.
        GatedMenuItem(stringResource(R.string.terminal_action_style)) { gatedNotice = context.getString(R.string.terminal_gated_style) }
        GatedMenuItem(stringResource(R.string.terminal_action_settings)) { gatedNotice = context.getString(R.string.terminal_gated_settings) }
        GatedMenuItem(stringResource(R.string.terminal_action_kill_process)) { gatedNotice = context.getString(R.string.terminal_gated_kill) }
        GatedMenuItem(stringResource(R.string.terminal_action_autofill_username)) { gatedNotice = context.getString(R.string.terminal_gated_autofill) }
        GatedMenuItem(stringResource(R.string.terminal_action_autofill_password)) { gatedNotice = context.getString(R.string.terminal_gated_autofill) }
    }

    if (urlDialog) {
        SelectUrlDialog(transcriptText = transcriptText, onDismiss = { urlDialog = false })
    }
    if (shareSelectedDialog) {
        ShareSelectedDialog(initial = transcriptText, onDismiss = { shareSelectedDialog = false })
    }
    gatedNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { gatedNotice = null },
            confirmButton = { TextButton(onClick = { gatedNotice = null }) { Text(stringResource(R.string.terminal_close)) } },
            icon = { Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.terminal_menu_title)) },
            text = { Text(message) },
        )
    }
}

@Composable
private fun GatedMenuItem(label: String, onExplain: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(label, color = MasamuneTheme.semantic.dim)
        },
        leadingIcon = {
            Icon(Icons.Filled.Block, contentDescription = null, tint = MasamuneTheme.semantic.dim)
        },
        // Not truly disabled at the Compose level so the tap can explain WHY it is inert.
        onClick = onExplain,
    )
}

@Composable
private fun SelectUrlDialog(transcriptText: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val urls = remember(transcriptText) { extractUrls(transcriptText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_close)) } },
        title = { Text(stringResource(R.string.terminal_select_url_dialog_title)) },
        text = {
            if (urls.isEmpty()) {
                Text(stringResource(R.string.terminal_select_url_none))
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
                ) {
                    urls.forEach { url ->
                        Text(
                            url,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { copyText(context, url, R.string.terminal_url_copied) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ShareSelectedDialog(initial: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(MasamuneTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
            ) {
                Text(
                    stringResource(R.string.terminal_share_selected_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) }
                    TextButton(
                        onClick = {
                            shareText(context, text, context.getString(R.string.terminal_action_share_selected_text))
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.terminal_action_share_selected_text)) }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Intent / clipboard helpers — plain Android, no PTY involved.
// -------------------------------------------------------------------------------------------------

private fun extractUrls(text: String): List<String> {
    val matcher = Patterns.WEB_URL.matcher(text)
    val out = LinkedHashSet<String>()
    while (matcher.find()) out.add(matcher.group())
    return out.toList()
}

private fun shareText(context: Context, text: String, chooserTitle: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

private fun copyText(context: Context, text: String, toastRes: Int? = null) {
    if (text.isBlank()) return
    val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("masamune-terminal", text))
    toastRes?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show() }
}

private fun openUrl(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(view) }
}

private fun reportText(transcript: String): String = buildString {
    appendLine("Masamune — Terminal issue report")
    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
    appendLine()
    appendLine("Recent transcript:")
    append(transcript.takeLast(4000))
}
