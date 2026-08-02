package dev.pleiades.masamune.operator.a11y

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * The one place the rest of the app asks "is the operator's accessibility service live, and if
 * so, how do I drive it?".
 *
 * An `AccessibilityService` is constructed and bound by the system, not by us, so there is no
 * constructor we can hand to a ViewModel and no singleton we own. The service publishes *itself*
 * here on connect and withdraws on disconnect; everyone else reads [actuator]. The live instance
 * — not a Settings flag — is the authority: a service can be toggled on in Settings yet not have
 * called `onServiceConnected` yet, and only a connected instance can actually read or touch the
 * screen. So [actuator] returns non-null exactly when an action would really work, which is the
 * precise condition the operator surface must gate on.
 */
object A11yServiceHolder {

    @Volatile
    private var service: MasamuneA11yService? = null

    /** Called by the service from `onServiceConnected`. */
    internal fun attach(instance: MasamuneA11yService) {
        service = instance
    }

    /** Called by the service from `onUnbind` / `onDestroy`; idempotent against a stale instance. */
    internal fun detach(instance: MasamuneA11yService) {
        if (service === instance) service = null
    }

    /**
     * The live actuator, or null when no connected service exists. Null is the whole gate: every
     * operator surface treats it as "the accessibility service is not enabled" and disables
     * itself with that sentence, and the operator [dev.pleiades.masamune.operator.OperatorLoop]
     * registry omits the action blocks so the scheduler names the missing requirement.
     */
    fun actuator(): ScreenActuator? = service

    /** Convenience for UI copy: is a connected service present right now? */
    val isConnected: Boolean get() = service != null

    /**
     * Whether Masamune's service is listed in the system's enabled-accessibility-services setting.
     *
     * This is a *hint for the user-facing message only* — it lets the screen say "you enabled it,
     * give it a moment to connect" versus "it is off, here is where to turn it on". It is never
     * used to decide whether an action can run; [actuator] is. Read defensively because the
     * secure setting can be absent or malformed.
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val expected = "${context.packageName}/${MasamuneA11yService::class.java.name}"
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
