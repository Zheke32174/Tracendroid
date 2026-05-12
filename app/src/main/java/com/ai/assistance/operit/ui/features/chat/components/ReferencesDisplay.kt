package com.ai.assistance.operit.ui.features.chat.components

// import androidx.compose.animation.AnimatedVisibility
// import androidx.compose.animation.fadeIn
// import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.aioutput.AiOutputLinkPolicy
import com.ai.assistance.operit.data.model.AiReference
import com.ai.assistance.operit.ui.common.animations.SimpleAnimatedVisibility

/**
 * Displays references found in AI responses as clickable chips.
 *
 * Reference URLs come from AI output, so § 4.6's scheme allowlist applies before any
 * link gets handed to the OS. Refused schemes show a toast rather than open silently.
 */
@Composable
fun ReferencesDisplay(
    references: List<AiReference>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    SimpleAnimatedVisibility(
        visible = references.isNotEmpty(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "References:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 16.dp)
            ) {
                items(references) { reference ->
                    ReferenceChip(
                        reference = reference,
                        onClick = {
                            if (AiOutputLinkPolicy.isAllowed(reference.url)) {
                                uriHandler.openUri(reference.url)
                            } else {
                                Toast.makeText(
                                    context,
                                    AiOutputLinkPolicy.refusalMessage(reference.url),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferenceChip(
    reference: AiReference,
    onClick: () -> Unit
) {
    SuggestionChip(
        onClick = onClick,
        label = {
            Text(
                text = reference.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = "Link",
                modifier = Modifier.size(18.dp)
            )
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
} 