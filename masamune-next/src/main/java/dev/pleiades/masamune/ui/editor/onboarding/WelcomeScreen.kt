package dev.pleiades.masamune.ui.editor.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R

/**
 * Welcome feature carousel (DONOR-SURFACES section 0; Xed is one of the carousel donors).
 *
 * A minimal index-stepped carousel — Back / Next, with Get started on the last page — rather than a
 * pager dependency. Three honest pages: what Masamune is, what the editor does, and the app's stance
 * on limits. Nothing here claims a capability the build does not have.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pages = listOf(
        R.string.editor_welcome_1_title to R.string.editor_welcome_1_body,
        R.string.editor_welcome_2_title to R.string.editor_welcome_2_body,
        R.string.editor_welcome_3_title to R.string.editor_welcome_3_body,
    )
    var index by remember { mutableIntStateOf(0) }
    val last = index == pages.lastIndex

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.editor_welcome_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(pages[index].first),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(pages[index].second),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { i ->
                Surface(
                    shape = CircleShape,
                    color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(4.dp).size(10.dp),
                ) {}
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { if (index > 0) index-- }, enabled = index > 0) {
                Text(stringResource(R.string.editor_welcome_back))
            }
            Button(onClick = { if (last) onDone() else index++ }) {
                Text(stringResource(if (last) R.string.editor_welcome_done else R.string.editor_welcome_next))
            }
        }
    }
}
