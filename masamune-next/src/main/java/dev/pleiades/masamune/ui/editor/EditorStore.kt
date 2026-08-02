package dev.pleiades.masamune.ui.editor

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable editor state: settings, the restore-sessions tab list, and the onboarding consent flag.
 *
 * Backed by [SharedPreferences] on purpose — the module rule is "org.json + Room, NO
 * kotlinx-serialization", and none of this warrants a Room table. The session list is stored as a
 * newline-separated stream of alternating fields — fsId, then path, per tab — so no in-record
 * delimiter is needed at all; loading regroups the lines two at a time. (A newline cannot appear
 * in an fs id, and no SAF document id or java.io path this app hands out contains one.)
 *
 * Everything here is best-effort persistence; a missing or malformed value falls back to the
 * documented default rather than throwing.
 */
class EditorStore(appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- settings ----------------------------------------------------------------------

    fun loadSettings(): EditorSettings {
        val d = EditorSettings()
        return EditorSettings(
            wordWrap = prefs.getBoolean(K_WORD_WRAP, d.wordWrap),
            showLineNumbers = prefs.getBoolean(K_SHOW_LINES, d.showLineNumbers),
            pinLineNumbers = prefs.getBoolean(K_PIN_LINES, d.pinLineNumbers),
            renderWhitespace = prefs.getBoolean(K_RENDER_WS, d.renderWhitespace),
            tabSize = prefs.getInt(K_TAB_SIZE, d.tabSize)
                .coerceIn(EditorSettings.MIN_TAB_SIZE, EditorSettings.MAX_TAB_SIZE),
            useTabs = prefs.getBoolean(K_USE_TABS, d.useTabs),
            smoothTabs = prefs.getBoolean(K_SMOOTH_TABS, d.smoothTabs),
            showTabIcons = prefs.getBoolean(K_TAB_ICONS, d.showTabIcons),
            detectBinary = prefs.getBoolean(K_DETECT_BIN, d.detectBinary),
            autoSave = prefs.getBoolean(K_AUTO_SAVE, d.autoSave),
            autoSaveDelayMs = prefs.getInt(K_AUTO_SAVE_DELAY, d.autoSaveDelayMs),
            restoreSessions = prefs.getBoolean(K_RESTORE, d.restoreSessions),
            autoOpenNewFiles = prefs.getBoolean(K_AUTO_OPEN, d.autoOpenNewFiles),
            // Gated toggles are never persisted as on; they cannot be enabled in this build.
            minimap = false,
            stickyScroll = false,
            suggestions = false,
            formatOnSave = false,
            editorConfig = false,
        )
    }

    fun saveSettings(s: EditorSettings) {
        prefs.edit()
            .putBoolean(K_WORD_WRAP, s.wordWrap)
            .putBoolean(K_SHOW_LINES, s.showLineNumbers)
            .putBoolean(K_PIN_LINES, s.pinLineNumbers)
            .putBoolean(K_RENDER_WS, s.renderWhitespace)
            .putInt(K_TAB_SIZE, s.tabSize)
            .putBoolean(K_USE_TABS, s.useTabs)
            .putBoolean(K_SMOOTH_TABS, s.smoothTabs)
            .putBoolean(K_TAB_ICONS, s.showTabIcons)
            .putBoolean(K_DETECT_BIN, s.detectBinary)
            .putBoolean(K_AUTO_SAVE, s.autoSave)
            .putInt(K_AUTO_SAVE_DELAY, s.autoSaveDelayMs)
            .putBoolean(K_RESTORE, s.restoreSessions)
            .putBoolean(K_AUTO_OPEN, s.autoOpenNewFiles)
            .apply()
    }

    // --- session (restore previous tabs) -----------------------------------------------

    /** Persisted open tabs, as (fsId, path) pairs, in tab order. */
    fun loadSession(): List<Pair<String, String>> {
        if (!prefs.getBoolean(K_RESTORE, true)) return emptyList()
        val raw = prefs.getString(K_SESSION, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val lines = raw.split('\n')
        val out = ArrayList<Pair<String, String>>(lines.size / 2)
        var i = 0
        while (i + 1 < lines.size) {
            val fsId = lines[i]
            val path = lines[i + 1]
            if (fsId.isNotEmpty() && path.isNotEmpty()) out.add(fsId to path)
            i += 2
        }
        return out
    }

    fun saveSession(tabs: List<Pair<String, String>>) {
        val flat = ArrayList<String>(tabs.size * 2)
        for ((fsId, path) in tabs) {
            flat.add(fsId)
            flat.add(path)
        }
        prefs.edit().putString(K_SESSION, flat.joinToString("\n")).apply()
    }

    // --- onboarding consent ------------------------------------------------------------

    fun consentAccepted(): Boolean = prefs.getBoolean(K_CONSENT, false)

    fun setConsentAccepted(accepted: Boolean) {
        prefs.edit().putBoolean(K_CONSENT, accepted).apply()
    }

    private companion object {
        const val PREFS = "masamune.editor"
        const val K_WORD_WRAP = "word_wrap"
        const val K_SHOW_LINES = "show_line_numbers"
        const val K_PIN_LINES = "pin_line_numbers"
        const val K_RENDER_WS = "render_whitespace"
        const val K_TAB_SIZE = "tab_size"
        const val K_USE_TABS = "use_tabs"
        const val K_SMOOTH_TABS = "smooth_tabs"
        const val K_TAB_ICONS = "tab_icons"
        const val K_DETECT_BIN = "detect_binary"
        const val K_AUTO_SAVE = "auto_save"
        const val K_AUTO_SAVE_DELAY = "auto_save_delay"
        const val K_RESTORE = "restore_sessions"
        const val K_AUTO_OPEN = "auto_open_new_files"
        const val K_SESSION = "session_tabs"
        const val K_CONSENT = "onboarding_consent_accepted"
    }
}
