package dev.pleiades.masamune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/** Tone of an inline notice. Drives colour only — the text always says the whole truth. */
enum class NoticeTone { INFO, WARNING, ERROR, SUCCESS, BLOCKED }

/**
 * The honesty primitive.
 *
 * Every surface in this app that is limited, gated, unimplemented or failing renders one of
 * these, naming the limit in its own words. Pattern taken from the donor tree's
 * ShellBootstrapScreen — the one screen there that reported a broken subsystem honestly
 * instead of spinning.
 */
@Composable
fun Notice(
    title: String,
    body: String,
    tone: NoticeTone = NoticeTone.INFO,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent: Color = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.primary
        NoticeTone.WARNING -> MasamuneTheme.semantic.warning
        NoticeTone.ERROR -> MaterialTheme.colorScheme.error
        NoticeTone.SUCCESS -> MasamuneTheme.semantic.success
        NoticeTone.BLOCKED -> MaterialTheme.colorScheme.error
    }
    val icon = when (tone) {
        NoticeTone.INFO -> Icons.Filled.Info
        NoticeTone.WARNING -> Icons.Filled.WarningAmber
        NoticeTone.ERROR -> Icons.Filled.ErrorOutline
        NoticeTone.SUCCESS -> Icons.Filled.CheckCircle
        NoticeTone.BLOCKED -> Icons.Filled.Block
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, accent.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                .padding(MasamuneTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** A titled card. Used for every grouped block so spacing stays on the 4dp grid. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(MasamuneTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
            content()
        }
    }
}

/** Alias so [SectionCard]'s content lambda keeps Column scope without importing it everywhere. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
fun KeyValueRow(key: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.labelMedium,
            color = MasamuneTheme.semantic.dim,
        )
        Text(
            value,
            style = if (mono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(MasamuneTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MasamuneTheme.semantic.dim,
            )
        }
    }
}
