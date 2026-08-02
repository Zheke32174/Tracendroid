package dev.pleiades.masamune.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.nav.RouteCatalog
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * Navigation map. About → Navigation map.
 *
 * Renders [RouteCatalog] verbatim. The bottom bar and the NavHost are generated from this same
 * list, so what you read here is what the app actually wires — a screen missing from this page
 * is a screen that does not exist.
 */
@Composable
fun NavigationMapScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        Notice(
            title = "Generated, not written",
            body = "This page iterates the route table that also generates the bottom bar and " +
                "the navigation graph. It cannot drift from the app.",
            tone = NoticeTone.INFO,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(RouteCatalog.all) { entry ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            entry.route,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            entry.navPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MasamuneTheme.semantic.dim,
                        )
                    }
                }
            }
        }
    }
}
