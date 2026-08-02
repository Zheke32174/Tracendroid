package dev.pleiades.masamune.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The two toolbars from DONOR-SURFACES §4 line 79, split by what this build can honestly back.
 *
 * The extra-keys row (ESC/CTRL/ALT/TAB/arrows) presupposes a live terminal view fed a key stream.
 * This build dispatches one-shot in the background, so those keys have nothing to act on; they are
 * shown disabled under a notice that names the missing view, rather than omitted (the donor's
 * toolbar shape is preserved) or — worse — shown live and doing nothing.
 *
 * The text-input row IS backable: it only edits the composer text, inserting common tokens and
 * paths. Every chip there is enabled.
 */
@Composable
fun ExtraKeysToolbar(
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
    ) {
        Notice(
            title = stringResource(R.string.terminal_extrakeys_blocked_title),
            body = stringResource(R.string.terminal_extrakeys_blocked_body),
            tone = NoticeTone.BLOCKED,
        )

        // Faithful modifier-key row, disabled: the donor ships these, we cannot back them.
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MasamuneTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
        ) {
            items(EXTRA_KEYS) { key ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            key,
                            style = MaterialTheme.typography.labelMedium
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }

        // Text-input row, enabled: inserts tokens/paths into the composer.
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
            modifier = Modifier.padding(horizontal = MasamuneTheme.spacing.xs),
        ) {
            Text(
                stringResource(R.string.terminal_textinput_toolbar_label),
                style = MaterialTheme.typography.labelSmall,
                color = MasamuneTheme.semantic.dim,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MasamuneTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
        ) {
            items(TEXT_TOKENS) { token ->
                SuggestionChip(
                    onClick = { onInsert(token) },
                    label = {
                        Text(
                            token.trim().ifEmpty { "space" },
                            style = MaterialTheme.typography.labelMedium
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                    },
                )
            }
        }
    }
}

/** Donor extra-keys, shown disabled. Labels mirror Termux's default extra-keys layout. */
private val EXTRA_KEYS = listOf("ESC", "CTRL", "ALT", "TAB", "←", "↑", "↓", "→")

/** Tokens and paths the text-input row inserts into the composer. */
private val TEXT_TOKENS = listOf("~/", "/", "..", "|", "&&", ">", "-", "--", "\$", "*", "sudo ", ".sh")
