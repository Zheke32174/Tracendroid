package dev.pleiades.masamune.flow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.Requirement
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The searchable, categorized palette of all 418 blocks — n8n's node menu over Automate's
 * sixteen groups, in Automate's own order.
 *
 * ### Honest gating is the whole point of this surface
 * A block is placeable only when every [Requirement] in [BlockSpec.requires] is in [satisfied].
 * A block that is *not* placeable renders disabled and carries a sentence naming exactly what is
 * missing (from [BlockCatalog.missingRequirements]). It is never rendered as an enabled, tappable
 * control the runtime cannot back — in a flow plane a dead block does not fail visibly, it silently
 * makes every downstream block wrong, so the palette refuses to offer one. Only a placeable block
 * is clickable, and clicking it adds it to the canvas.
 */
@Composable
fun BlockPalette(
    satisfied: Set<Requirement>,
    onAddBlock: (BlockSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            label = { Text("Search ${BlockCatalog.size} blocks") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
        )

        if (query.isBlank()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                BlockCatalog.byCategory.forEach { (category, specs) ->
                    val open = expanded[category.id] == true
                    item(key = "hdr_${category.id}") {
                        CategoryHeader(
                            category = category,
                            placeable = specs.count { BlockCatalog.isPlaceable(it, satisfied) },
                            total = specs.size,
                            open = open,
                            onToggle = { expanded[category.id] = !open },
                        )
                    }
                    if (open) {
                        items(specs, key = { it.id }) { spec ->
                            PaletteRow(spec, satisfied, onAddBlock)
                        }
                    }
                }
            }
        } else {
            val results = BlockCatalog.search(query)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "count") {
                    Text(
                        if (results.isEmpty()) "No block matches \"$query\"." else "${results.size} match(es)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MasamuneTheme.semantic.dim,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                items(results, key = { it.id }) { spec ->
                    PaletteRow(spec, satisfied, onAddBlock)
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: BlockCategory,
    placeable: Int,
    total: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(category.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$placeable of $total placeable now",
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Collapse ${category.label}" else "Expand ${category.label}",
            )
        }
    }
}

@Composable
private fun PaletteRow(
    spec: BlockSpec,
    satisfied: Set<Requirement>,
    onAddBlock: (BlockSpec) -> Unit,
) {
    val missing = BlockCatalog.missingRequirements(spec, satisfied)
    val placeable = missing.isEmpty()

    // The load-bearing line: a clickable modifier is attached ONLY when the block is placeable.
    // A blocked block has no tap target at all — it cannot be added, and it says why.
    val base = Modifier
        .fillMaxWidth()
        .then(if (placeable) Modifier.clickable { onAddBlock(spec) } else Modifier)
        .padding(horizontal = 12.dp, vertical = 10.dp)

    Row(base, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (placeable) Icons.Filled.AddCircleOutline else Icons.Filled.Lock,
            contentDescription = null,
            tint = if (placeable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    spec.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (placeable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    maxLines = 1,
                )
                ShapeTag(spec.shape)
            }
            Text(
                spec.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
                maxLines = 2,
            )
            if (!placeable) {
                Text(
                    "Unavailable — needs ${missing.joinToString(", ") { it.label }}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** A one-glance shape marker: an action has one exit, a decision two. */
@Composable
private fun ShapeTag(shape: BlockShape) {
    val (label, color) = when (shape) {
        BlockShape.ACTION -> "OK" to MaterialTheme.colorScheme.primary
        BlockShape.DECISION -> "YES · NO" to MasamuneTheme.semantic.warning
    }
    Surface(color = color.copy(alpha = 0.16f), shape = MaterialTheme.shapes.extraSmall) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
