package dev.pleiades.masamune.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Renders spaces and tabs as visible symbols (DONOR-SURFACES: `render_whitespace`).
 *
 * Each space becomes `·` (U+00B7 MIDDLE DOT) and each tab becomes `→` (U+2192 RIGHTWARDS ARROW).
 * Both replacements are one glyph for one character, so the transformed text is exactly as long as
 * the original and [OffsetMapping.Identity] is correct — the cursor and selection never drift.
 *
 * Line breaks are deliberately not marked: showing a symbol *and* keeping the break would change
 * the character count and break the offset mapping in an editable field. The `render_whitespace`
 * scope string states this limit rather than hiding it.
 */
object WhitespaceTransformation : VisualTransformation {

    private const val SPACE_DOT = '·'
    private const val TAB_ARROW = '→'

    override fun filter(text: AnnotatedString): TransformedText {
        val shown = buildString(text.length) {
            for (ch in text.text) {
                when (ch) {
                    ' ' -> append(SPACE_DOT)
                    '\t' -> append(TAB_ARROW)
                    else -> append(ch)
                }
            }
        }
        return TransformedText(AnnotatedString(shown), OffsetMapping.Identity)
    }
}
