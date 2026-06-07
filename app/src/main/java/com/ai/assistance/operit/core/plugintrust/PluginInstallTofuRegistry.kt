package com.ai.assistance.operit.core.plugintrust

import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-side broker for plugin install TOFU prompts (`AUDIT_PLAN.md § 1.1`, `THREAT_MODEL.md § 4.3`).
 *
 * Install flows that hit a [PluginTrustChecker.Decision.NewPublisher] call
 * [requestApproval]; the suspend function returns true iff the user accepts the TOFU
 * prompt the overlay surfaces. The overlay observes [active] to render the dialog and
 * invokes [approveActive] / [rejectActive] when the user picks.
 *
 * One pending decision at a time. If a second install flow requests approval while one
 * is active, the second request is rejected with `false` immediately — installs are
 * sequential, not concurrent.
 */
object PluginInstallTofuRegistry {

    private const val TAG = "PluginInstallTofuRegistry"

    /** One pending TOFU prompt the overlay should render. */
    data class Pending(val decision: PluginTrustChecker.Decision.NewPublisher)

    private val _active = MutableStateFlow<Pending?>(null)
    val active: StateFlow<Pending?> = _active.asStateFlow()

    @Volatile
    private var pendingDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Surface the TOFU prompt and suspend until the user resolves it. Returns true on
     * accept, false on reject (or if a prompt is already in flight — concurrent install
     * attempts are not supported in v1).
     */
    suspend fun requestApproval(decision: PluginTrustChecker.Decision.NewPublisher): Boolean {
        if (pendingDeferred != null) {
            AppLogger.w(
                TAG,
                "requestApproval refused: another TOFU prompt is in flight (pluginId=${decision.pluginId})"
            )
            return false
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred = deferred
        _active.value = Pending(decision)
        AppLogger.d(TAG, "TOFU prompt opened: pluginId=${decision.pluginId} fp=${decision.publisherKeyFingerprint}")
        return deferred.await()
    }

    /** Called by the overlay when the user taps "Trust this publisher". */
    fun approveActive() {
        val deferred = pendingDeferred ?: return
        pendingDeferred = null
        _active.value = null
        deferred.complete(true)
    }

    /** Called by the overlay when the user taps "Refuse". */
    fun rejectActive() {
        val deferred = pendingDeferred ?: return
        pendingDeferred = null
        _active.value = null
        deferred.complete(false)
    }
}
