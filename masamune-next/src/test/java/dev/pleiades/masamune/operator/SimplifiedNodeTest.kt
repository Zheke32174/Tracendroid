package dev.pleiades.masamune.operator

import dev.pleiades.masamune.operator.a11y.SimplifiedNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the compaction that makes the observe step affordable: uninformative layout wrappers are
 * dropped and their informative children hoisted, while every clickable/text/id node survives.
 * This is the load-bearing organ — a full hierarchy would blow the LLM context.
 */
class SimplifiedNodeTest {

    private fun n(
        className: String,
        text: String? = null,
        contentDesc: String? = null,
        resourceId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        children: List<SimplifiedNode> = emptyList(),
    ) = SimplifiedNode(className, text, contentDesc, resourceId, "[0,0][1,1]", clickable, editable, children)

    @Test fun dropsWrappersButKeepsInformativeDescendants() {
        val tree = n(
            "FrameLayout",
            children = listOf(
                n(
                    "LinearLayout",
                    children = listOf(
                        n("TextView", text = "Sign in", clickable = true),
                        n("EditText", resourceId = "com.app:id/user", editable = true),
                    ),
                ),
            ),
        )
        val rendered = tree.render()
        // The two empty wrappers vanish; the button and field remain.
        assertFalse(rendered.contains("FrameLayout"))
        assertFalse(rendered.contains("LinearLayout"))
        assertTrue(rendered.contains("<TextView> \"Sign in\""))
        assertTrue(rendered.contains("[clickable]"))
        assertTrue(rendered.contains("<EditText>"))
        assertTrue(rendered.contains("[editable]"))
        assertTrue(rendered.contains("#user"))
    }

    @Test fun anEmptyTreeRendersEmpty() {
        assertTrue(SimplifiedNode.EMPTY.render().isEmpty())
        assertTrue(n("FrameLayout", children = listOf(n("LinearLayout"))).render().isEmpty())
    }
}
