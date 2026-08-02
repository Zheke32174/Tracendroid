package dev.pleiades.masamune.ui.editor

import android.content.Context
import java.io.File

/**
 * The syntax-highlighting gate (DONOR-SURFACES section 5; DONOR-ASSETS marks the tree-sitter
 * grammar `.so` OPEN, so highlighting is in scope rather than exempt).
 *
 * Highlighting a language needs its tree-sitter grammar compiled to a native library. This probe
 * answers one question honestly: *is that grammar present in this build?* It looks in the two
 * places a bundled grammar could live — the app's native library directory and the `grammars/`
 * assets folder — for a `libtree-sitter-<lang>.so` or `<lang>.so`. This build ships neither, so
 * the probe returns absent for every language, and the editor falls back to plain text while
 * naming exactly what is missing (PRIME DIRECTIVE: an absent grammar reports absent, it does not
 * silently render plain).
 *
 * The probe is a real filesystem/asset check, not a hard-coded `false`: the day a grammar `.so` is
 * added to the packaged output, [grammarPresent] flips to true for that language with no code
 * change here.
 */
class SyntaxGrammarProbe(private val appContext: Context) {

    /** Grammar id → whether its native library is present. Cached per process. */
    private val cache = HashMap<String, Boolean>()

    /** Maps a file name to a language and its grammar-presence gate. */
    fun languageFor(fileName: String): EditorLanguage {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val entry = EXTENSION_TABLE[ext]
            ?: return EditorLanguage(EditorLanguage.PLAIN, grammarPresent = false)
        return EditorLanguage(entry.display, grammarPresent = grammarPresent(entry.grammar))
    }

    /** True only if a native grammar library for [grammar] is actually packaged. */
    fun grammarPresent(grammar: String): Boolean = cache.getOrPut(grammar) {
        val libDir = appContext.applicationInfo.nativeLibraryDir
        val nativeHit = libDir != null && sequenceOf(
            "libtree-sitter-$grammar.so",
            "libtreesitter_$grammar.so",
            "lib$grammar.so",
        ).any { File(libDir, it).exists() }
        if (nativeHit) return@getOrPut true
        // Fall back to a grammars/ assets probe; absent in this build.
        runCatching {
            appContext.assets.list("grammars")?.any { it.equals("$grammar.so", ignoreCase = true) }
        }.getOrNull() == true
    }

    private data class LangEntry(val display: String, val grammar: String)

    private companion object {
        /**
         * Extension → (display name, tree-sitter grammar id). Kept small and factual; unmatched
         * extensions map to plain text. Grammar ids follow the tree-sitter package convention so a
         * later `libtree-sitter-<id>.so` drop is discovered automatically.
         */
        val EXTENSION_TABLE: Map<String, LangEntry> = mapOf(
            "kt" to LangEntry("Kotlin", "kotlin"),
            "kts" to LangEntry("Kotlin", "kotlin"),
            "java" to LangEntry("Java", "java"),
            "c" to LangEntry("C", "c"),
            "h" to LangEntry("C", "c"),
            "cpp" to LangEntry("C++", "cpp"),
            "cc" to LangEntry("C++", "cpp"),
            "hpp" to LangEntry("C++", "cpp"),
            "py" to LangEntry("Python", "python"),
            "rs" to LangEntry("Rust", "rust"),
            "go" to LangEntry("Go", "go"),
            "js" to LangEntry("JavaScript", "javascript"),
            "mjs" to LangEntry("JavaScript", "javascript"),
            "ts" to LangEntry("TypeScript", "typescript"),
            "tsx" to LangEntry("TypeScript", "typescript"),
            "json" to LangEntry("JSON", "json"),
            "xml" to LangEntry("XML", "xml"),
            "html" to LangEntry("HTML", "html"),
            "htm" to LangEntry("HTML", "html"),
            "css" to LangEntry("CSS", "css"),
            "sh" to LangEntry("Shell", "bash"),
            "bash" to LangEntry("Shell", "bash"),
            "md" to LangEntry("Markdown", "markdown"),
            "markdown" to LangEntry("Markdown", "markdown"),
            "toml" to LangEntry("TOML", "toml"),
            "yml" to LangEntry("YAML", "yaml"),
            "yaml" to LangEntry("YAML", "yaml"),
            "gradle" to LangEntry("Gradle", "groovy"),
        )
    }
}
