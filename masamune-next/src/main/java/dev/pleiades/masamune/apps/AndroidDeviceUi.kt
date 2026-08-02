package dev.pleiades.masamune.apps

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The real, device-backed [DeviceUi] — the Android glue that turns the plain-data contract into reads on
 * `ClipboardManager`/`KeyguardManager`/`PowerManager`/`UiModeManager`/`DisplayManager`/`Configuration` and
 * effects on `ClipboardManager`/`Toast`/`NotificationManager`.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit tests'
 * point of view: the blocks never see it, they see [DeviceUi]. Keeping every framework call on this side of
 * the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.deviceUiLookup]'s blocks stay
 * JVM-testable against a fake.
 *
 * ### STATE READS + SIMPLE DEVICE-I/O EFFECTS ONLY
 * There is deliberately no way to inspect the on-screen UI, inject touches or keys, show a dialog/window,
 * drive a picker, present a custom interface, or set any state behind a privileged grant from here. This
 * class reads unprivileged UI-adjacent *state* and applies simple clipboard/toast/notification *effects*
 * and nothing else; every a11y, notification-listener, device-admin, SHELL, dialog, picker, custom-surface
 * and state-setter block is gated by omission in [dev.pleiades.masamune.flow.runtime.impl.deviceUiLookup]
 * and has no glue here.
 *
 * ### Honest boundaries — a missing reading is `null`, a refused effect is `UiWrite(ok = false)`
 *  - **An absent system service is `null` for a read / `UiWrite(ok = false)` for an effect, never a
 *    guess.** A device with no clipboard/keyguard/UI-mode service returns `null`/a refused write, which the
 *    block routes to a named Fail — never a fabricated `0`/`false`/empty/OK.
 *  - **Notifications disabled is `UiWrite(ok = false, reason = …)`.** When the user has turned Masamune's
 *    notifications off, `showNotification` refuses honestly and the block Fails by name rather than
 *    claiming a notification the user never saw.
 *  - **A real "no / off / 0" is the value, not `null`.** A device genuinely not secure, a display really
 *    off, night mode really unset, a hardware keyboard really hidden — these are successful reads the block
 *    routes to NO, kept distinct from the unreadable `null`.
 */
class AndroidDeviceUi(private val context: Context) : DeviceUi {

    private val clipboard: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val keyguard: KeyguardManager?
        get() = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private val powerManager: PowerManager?
        get() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val uiModeManager: UiModeManager?
        get() = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager

    private val displayManager: DisplayManager?
        get() = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    // ---- reads -------------------------------------------------------------

    override suspend fun clipboardText(): String? {
        val cm = clipboard ?: return null
        return try {
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val text = clip.getItemAt(0).coerceToText(context)?.toString()
            text?.takeIf { it.isNotEmpty() }
        } catch (_: RuntimeException) {
            null
        }
    }

    override suspend fun isDeviceSecure(ignoreSimLock: Boolean): Boolean? {
        val km = keyguard ?: return null
        val secureLock = km.isDeviceSecure
        if (ignoreSimLock) return secureLock
        return secureLock || isSimPinLocked()
    }

    private fun isSimPinLocked(): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return when (tm.simState) {
            TelephonyManager.SIM_STATE_PIN_REQUIRED,
            TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED,
            -> true

            else -> false
        }
    }

    override suspend fun isDeviceUnlocked(): Boolean? {
        val km = keyguard ?: return null
        return !km.isDeviceLocked
    }

    override suspend fun isDisplayOn(displayId: Int?): Boolean? {
        if (displayId == null) {
            val pm = powerManager ?: return null
            return pm.isInteractive
        }
        val display = displayManager?.getDisplay(displayId) ?: return null
        return display.state == Display.STATE_ON
    }

    override suspend fun isNightModeEnabled(): Boolean? {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    override suspend fun isCarModeEnabled(): Boolean? {
        val uim = uiModeManager ?: return null
        return uim.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    override suspend fun screenOrientation(): ScreenOrientation? {
        return when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ScreenOrientation.LANDSCAPE
            Configuration.ORIENTATION_PORTRAIT -> ScreenOrientation.PORTRAIT
            else -> null
        }
    }

    override suspend fun displayMetrics(displayId: Int?): DisplayMetricsInfo? {
        val display = displayManager?.getDisplay(displayId ?: Display.DEFAULT_DISPLAY) ?: return null
        return try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val rotationDegrees = when (display.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            DisplayMetricsInfo(
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
                density = metrics.density.toDouble(),
                rotationDegrees = rotationDegrees,
                refreshRateHz = display.refreshRate.toDouble(),
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    override suspend fun isHardwareKeyboardVisible(): Boolean? {
        val config = context.resources.configuration
        val hasHardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS
        val extended = config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        return hasHardKeyboard && extended
    }

    // ---- effects -----------------------------------------------------------

    override suspend fun setClipboard(text: String, label: String?, sensitive: Boolean): UiWrite {
        val cm = clipboard ?: return UiWrite(ok = false, reason = "no clipboard service")
        return try {
            val clip = ClipData.newPlainText(label ?: "", text)
            if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            cm.setPrimaryClip(clip)
            UiWrite(ok = true)
        } catch (e: RuntimeException) {
            UiWrite(ok = false, reason = e.message ?: "the clipboard could not be set")
        }
    }

    override suspend fun showToast(message: String, longDuration: Boolean): UiWrite {
        val length = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        return try {
            // A toast must be posted on the main thread; hop to it and return fire-and-continue.
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, length).show()
            }
            UiWrite(ok = true)
        } catch (e: RuntimeException) {
            UiWrite(ok = false, reason = e.message ?: "the toast could not be shown")
        }
    }

    override suspend fun showNotification(
        key: String,
        title: String?,
        message: String?,
        channelId: String?,
    ): UiWrite {
        val nm = notificationManager ?: return UiWrite(ok = false, reason = "no notification service")
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) {
            return UiWrite(ok = false, reason = "notifications are disabled for this app")
        }
        return try {
            val channel = channelId?.takeIf { it.isNotBlank() } ?: DEFAULT_CHANNEL_ID
            ensureChannel(nm, channel)
            val notification = NotificationCompat.Builder(context, channel)
                .setContentTitle(title ?: "")
                .setContentText(message ?: "")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            compat.notify(key, key.hashCode(), notification)
            UiWrite(ok = true)
        } catch (e: SecurityException) {
            UiWrite(ok = false, reason = e.message ?: "not permitted to post notifications")
        } catch (e: RuntimeException) {
            UiWrite(ok = false, reason = e.message ?: "the notification could not be posted")
        }
    }

    override suspend fun cancelNotification(key: String): UiWrite {
        val nm = notificationManager ?: return UiWrite(ok = false, reason = "no notification service")
        return try {
            nm.cancel(key, key.hashCode())
            UiWrite(ok = true)
        } catch (e: RuntimeException) {
            UiWrite(ok = false, reason = e.message ?: "the notification could not be cancelled")
        }
    }

    private fun ensureChannel(nm: NotificationManager, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(channelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Flow", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private companion object {
        const val DEFAULT_CHANNEL_ID = "flow_default"
    }
}
