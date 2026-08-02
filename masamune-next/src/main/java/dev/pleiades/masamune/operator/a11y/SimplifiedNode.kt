package dev.pleiades.masamune.operator.a11y

/**
 * The compact node tree the operator observes the screen through — the single highest-value
 * organ ported from Operit's `AccessibilityUITools.simplifyLayout` (see docs/AI-OPERATOR.md).
 *
 * The reason this type exists, rather than the operator reading a raw `AccessibilityNodeInfo`
 * tree or a full uiautomator XML dump, is the LLM context window. A live app's accessibility
 * hierarchy is thousands of nodes carrying layout-only wrappers, redundant bounds and framework
 * class names; handing that to the decide step verbatim spends the whole budget on noise the
 * model cannot act on. Operit's answer — and ours — is to keep only the five attributes an
 * operator actually chooses an action from (what a thing is, what it says, how it is identified,
 * where it is, whether it can be tapped), and to render them one node per line. That is what
 * makes the observe step affordable enough to run on *every* iteration of the loop.
 *
 * It is a plain immutable value with no Android types on it, so the block layer and the loop can
 * be unit-tested on the JVM without a device, and so a captured observation is trivially
 * serializable into a fiber variable for persist-and-resume.
 */
data class SimplifiedNode(
    /** The framework class, already reduced to its leaf (`Button`, not `android.widget.Button`). */
    val className: String?,
    /** Visible label text, newline-normalised. Null when the node shows none. */
    val text: String?,
    /** `content-desc` — the accessibility label, often the only text on an icon button. */
    val contentDesc: String?,
    /** `resource-id` / `viewIdResourceName`, the stable handle a flow author would target. */
    val resourceId: String?,
    /** Screen bounds as Operit spells them, `[left,top][right,bottom]`, for a coordinate tap. */
    val bounds: String?,
    val clickable: Boolean,
    /** An editable text field — surfaced so the decide step knows where typing can land. */
    val editable: Boolean,
    val children: List<SimplifiedNode>,
) {
    /** True when this node carries something the model can reason about or act on directly. */
    private val isInformative: Boolean
        get() = !text.isNullOrBlank() || !contentDesc.isNullOrBlank() ||
            !resourceId.isNullOrBlank() || clickable || editable

    /**
     * Render the subtree as an indented, one-line-per-node text block for the decide prompt.
     *
     * Pure layout wrappers — a node with no text, no description, no id and no interactivity —
     * are dropped and their informative descendants hoisted up a level. This is the compaction
     * that keeps the observation readable: it removes the `FrameLayout`-holding-a-`LinearLayout`
     * scaffolding without ever removing a node the operator might tap. A node is kept if it is
     * itself informative or if it has any kept descendant; the tree's *shape* is preserved for
     * everything that survives, so "the button inside the toolbar" still reads as nested.
     */
    fun render(): String = StringBuilder().also { renderInto(it, 0) }.toString().trimEnd('\n')

    private fun renderInto(sb: StringBuilder, depth: Int) {
        val keptChildren = children.filter { it.hasInformativeSubtree() }
        if (isInformative) {
            sb.append("  ".repeat(depth)).append(describe()).append('\n')
            keptChildren.forEach { it.renderInto(sb, depth + 1) }
        } else {
            // Skip this uninformative wrapper: hoist its kept children to the current depth so
            // the tree gets shallower rather than accreting empty indentation levels.
            keptChildren.forEach { it.renderInto(sb, depth) }
        }
    }

    private fun hasInformativeSubtree(): Boolean =
        isInformative || children.any { it.hasInformativeSubtree() }

    /** One node on one line: the attributes that are present, in a form a model parses at a glance. */
    private fun describe(): String = buildString {
        append('<').append(className ?: "View").append('>')
        text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it.trim().take(80)).append('"') }
        contentDesc?.takeIf { it.isNotBlank() }?.let { append(" desc=\"").append(it.trim().take(60)).append('"') }
        resourceId?.takeIf { it.isNotBlank() }?.let { append(" #").append(it.substringAfterLast('/')) }
        if (editable) append(" [editable]")
        if (clickable) append(" [clickable]")
        bounds?.let { append(' ').append(it) }
    }

    companion object {
        /** An empty screen dump — a real, non-null "nothing to see", distinct from "no service". */
        val EMPTY = SimplifiedNode(
            className = null, text = null, contentDesc = null, resourceId = null,
            bounds = null, clickable = false, editable = false, children = emptyList(),
        )
    }
}
