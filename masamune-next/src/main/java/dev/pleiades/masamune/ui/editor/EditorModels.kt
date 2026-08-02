package dev.pleiades.masamune.ui.editor

import androidx.compose.ui.text.input.TextFieldValue

/**
 * The editor's data model — everything the [EditorViewModel] holds and the [EditorScreen] renders.
 *
 * Ported from the Xed donor (DONOR-SURFACES section 5). Xed keeps a *set of open buffers* with a
 * tab row, an in-buffer find/replace panel, a line-number gutter, undo history, a save family and
 * a command palette. This build reproduces that model over a plain Compose text field, and marks
 * — honestly, per the PRIME DIRECTIVE — every capability that would need an engine it does not
 * bundle (a code-editor with a minimap, a tree-sitter grammar, a language server, a formatter).
 */

/** A language guessed from a file's extension, and whether its highlighting grammar is present. */
data class EditorLanguage(
    /** Display name, e.g. "Kotlin". [PLAIN] for an unknown/unmatched extension. */
    val name: String,
    /** Whether the tree-sitter grammar for this language is bundled. Always false in this build. */
    val grammarPresent: Boolean,
) {
    companion object {
        const val PLAIN = "Plain text"
    }
}

/**
 * One open document. Each tab owns its text, its saved baseline (for the dirty flag), and its own
 * undo/redo stacks — closing one never disturbs another's history.
 */
data class EditorTab(
    /** Stable key: the backend id plus the file path. */
    val key: String,
    /** Backend the file lives on. */
    val fsId: String,
    /** Opaque backend path. */
    val path: String,
    /** File name shown on the tab. */
    val name: String,
    /** Current editable content plus selection/cursor. */
    val value: TextFieldValue,
    /** The content as last read or saved; [dirty] compares against this. */
    val savedText: String,
    /** Undo history (most recent last). Snapshots of prior [value]s. */
    val undo: List<TextFieldValue> = emptyList(),
    /** Redo history (most recent last). */
    val redo: List<TextFieldValue> = emptyList(),
    /** Language guessed from the extension, with its grammar-presence gate. */
    val language: EditorLanguage,
    /** True when the read was cut short by the backend's byte cap; saving is then blocked. */
    val truncated: Boolean,
    /** Total size reported by the backend, for the truncated notice. */
    val totalBytes: Long,
    /** True when the backend advertises WRITE and the read was complete. */
    val backendWritable: Boolean,
    /** True when the content looks binary (NUL / replacement runs); forces read-only. */
    val binary: Boolean,
) {
    val dirty: Boolean get() = value.text != savedText

    /** The single reason this tab cannot be edited, or null when it is editable. */
    val readOnlyReason: ReadOnlyReason?
        get() = when {
            binary -> ReadOnlyReason.BINARY
            truncated -> ReadOnlyReason.TRUNCATED
            !backendWritable -> ReadOnlyReason.BACKEND_READ_ONLY
            else -> null
        }

    val editable: Boolean get() = readOnlyReason == null
}

/** Why a tab is read-only. The UI renders the matching sentence; none of these is a silent block. */
enum class ReadOnlyReason { BINARY, TRUNCATED, BACKEND_READ_ONLY }

/** State of the find/replace panel. Off-screen until [open]. */
data class FindReplaceState(
    val open: Boolean = false,
    val find: String = "",
    val replace: String = "",
    val ignoreCase: Boolean = true,
    val regex: Boolean = false,
    val wholeWord: Boolean = false,
    /** Character ranges of the current matches in the active buffer. */
    val matches: List<IntRange> = emptyList(),
    /** Index into [matches] of the highlighted match, or -1 when there is none. */
    val current: Int = -1,
    /** Non-null when [regex] is on and the pattern does not compile. */
    val error: String? = null,
)

/**
 * Editor-surface settings (DONOR-SURFACES section F: Editor / Editor, Content, Other).
 *
 * A field is *live* only when this build's plain text field can actually honour it. The rest are
 * carried so the setting exists and reports its gate, never silently ignored.
 */
data class EditorSettings(
    // --- live: backed by the plain field ---
    val wordWrap: Boolean = true,
    val showLineNumbers: Boolean = true,
    val pinLineNumbers: Boolean = true,
    val renderWhitespace: Boolean = false,
    val tabSize: Int = 4,
    val useTabs: Boolean = false,
    val smoothTabs: Boolean = true,
    val showTabIcons: Boolean = true,
    val detectBinary: Boolean = true,
    val autoSave: Boolean = false,
    val autoSaveDelayMs: Int = 1000,
    val restoreSessions: Boolean = true,
    val autoOpenNewFiles: Boolean = true,
    // --- gated: present, disabled, each with a naming sentence ---
    val minimap: Boolean = false,
    val stickyScroll: Boolean = false,
    val suggestions: Boolean = false,
    val formatOnSave: Boolean = false,
    val editorConfig: Boolean = false,
) {
    companion object {
        const val MIN_TAB_SIZE = 1
        const val MAX_TAB_SIZE = 8
        val AUTO_SAVE_DELAYS = listOf(500, 1000, 2000, 5000)
        /** The one encoding the FileSystem backend actually reads and writes. */
        const val ENCODING = "UTF-8"
    }
}

/** A command-palette entry (editor scope). [enabled] false renders greyed with [disabledNote]. */
data class PaletteCommand(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val disabledNote: String? = null,
)

/** The whole editor UI state. */
data class EditorUiState(
    val tabs: List<EditorTab> = emptyList(),
    val activeIndex: Int = -1,
    val settings: EditorSettings = EditorSettings(),
    val find: FindReplaceState = FindReplaceState(),
    val error: String? = null,
    val notice: String? = null,
    val busy: String? = null,
    /** True while the FILE_WRITE / FILE_READ capability is granted to caller "user". */
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
) {
    val active: EditorTab? get() = tabs.getOrNull(activeIndex)
    val anyDirty: Boolean get() = tabs.any { it.dirty }
}
