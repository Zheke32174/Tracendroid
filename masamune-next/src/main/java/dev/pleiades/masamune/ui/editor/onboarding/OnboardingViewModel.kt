package dev.pleiades.masamune.ui.editor.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import dev.pleiades.masamune.ui.editor.EditorStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Consent state for the Xed-canonical onboarding (DONOR-SURFACES section 0: Disclaimer & Consent).
 *
 * The accepted flag is persisted through [EditorStore] (SharedPreferences) — the same store the
 * editor uses — so "reset consent status" genuinely clears it and the disclaimer would show again.
 * This slice records and reports consent honestly; it does not gate app start (that flow is owned
 * elsewhere), so nothing here silently blocks the rest of the app.
 */
class OnboardingViewModel(appContext: Context) : ViewModel() {

    private val store = EditorStore(appContext)

    private val _accepted = MutableStateFlow(store.consentAccepted())
    val accepted: StateFlow<Boolean> = _accepted.asStateFlow()

    fun accept() {
        store.setConsentAccepted(true)
        _accepted.value = true
    }

    fun decline() {
        store.setConsentAccepted(false)
        _accepted.value = false
    }

    /** Reset consent status — clears the flag so the disclaimer is unacknowledged again. */
    fun reset() {
        store.setConsentAccepted(false)
        _accepted.value = false
    }
}
