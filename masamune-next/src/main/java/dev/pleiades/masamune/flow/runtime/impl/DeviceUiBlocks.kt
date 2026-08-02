package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.DeviceUi
import dev.pleiades.masamune.apps.ScreenOrientation
import dev.pleiades.masamune.apps.UiWrite
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The Interface category's **unprivileged device-UI state / simple device-I/O effect** slice — the organ
 * an AI phone operator needs to know and lightly drive the device's UI-adjacent state right now: what the
 * clipboard holds, whether the device is secure / unlocked, whether a display is on, the night/car UI
 * mode, the screen orientation, a display's metrics, whether a hardware keyboard is extended — and the
 * simple device-I/O effects that set the clipboard, show a toast, and post/cancel a notification. It reads
 * and lightly nudges UI *state*; it does not inspect the on-screen layout, inject touches or keys, show
 * dialogs/windows, drive pickers, or set any state behind a privileged grant.
 *
 * ### Why this subset and not the whole (large) category
 * `CatalogInterface` is the largest category and is dominated by *grant-gated* blocks — the
 * AccessibilityService interaction/inspection family (`inspect_layout`, `interact`, `interact_touch`,
 * `inspect_text_edit`, `key_send`, `key_send_characters`, which are **operator-owned** and registered in
 * `OperatorLoop`), the NotificationListener family (posted/interact/snooze), device-admin locking and
 * login-failure, dialogs and pickers (need an Activity/UI surface), custom-interface surfaces,
 * screensavers/wallpapers/widgets, and a raft of awaits/callbacks. Only the unprivileged UI state reads
 * and the simple clipboard/toast/notification effects can be expressed through the [DeviceUi] seam, and
 * only those run here:
 *  - **Reads / decisions (9):** `clipboard_get` (binds `varContent`), `device_secure`, `device_unlocked`,
 *    `display_on`, `night_mode_enabled`, `car_mode_enabled`, `screen_orientation`, `display_metrics_get`
 *    (binds `varBounds`/`varDensity`/`varRotation`/`varRefreshRate`) and `hardware_keyboard_visible`.
 *  - **Effects / actions (4):** `clipboard_set`, `toast_show`, `notification_show` (binds `varKey`) and
 *    `notification_cancel`. Each applies one device change and routes a [UiWrite]: OK on accept, a named
 *    Fail carrying the reason on refusal — never a fabricated OK.
 *  - Everything else is gated by omission (see [deviceUiLookup]).
 *
 * ### The seam, copied from the ten prior categories
 * Every device call lives behind the injected [DeviceUi] — a narrow, `android.*`-free contract, the exact
 * shape [dev.pleiades.masamune.apps.AudioController] and the other nine readers give their categories. Two
 * consequences, both deliberate:
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole file
 *     is unit-testable against a fake on an ordinary JVM — a device is needed to run these, never to test
 *     their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [DeviceUi] provider and fails with
 *     [DEVICE_UI_ABSENT] when there is no seam (the app process is not wired in, or it dropped mid-run). A
 *     read that returns `null` becomes a named [Outcome.Fail] ("could not be read") — **never** a
 *     fabricated `false`/`0`/empty or a silent NO. A device really not secure / a display really off / a
 *     hardware keyboard really hidden is a successful read routed to NO; only an unreadable state Fails. An
 *     effect the device refuses ([UiWrite] `ok = false`) Fails **by name** carrying the reason — never a
 *     fabricated OK.
 *
 * ### WATCH / AWAIT tenses collapse to their one-shot form
 * The catalog marks the reads WATCH-capable (test now, or suspend until the state changes) and
 * `clipboard_get` WATCH_VALUE (read now, or suspend until it changes); `toast_show` and `notification_show`
 * are AWAIT (fire and continue, or suspend until finished). The watching/awaiting forms need the monitor
 * subsystem this build does not have, so the one-shot condition — "is the device unlocked *now*", "show
 * the toast *now* and continue" — is what runs, which is exactly what a decision/action in a running flow
 * evaluates. This mirrors the Audio, Connectivity and Telephony one-shot collapses.
 *
 * ### Honest simplifications of over-rich effect arguments (documented, not faked)
 * `clipboard_set` carries `htmlText`/`uri`/`mimeType` rich-content forms; this slice sets plain `text`
 * (honoring `label` and `sensitive`) and does not model the rich MIME/URI clip forms. `notification_show`
 * carries a large layout/style surface (custom RemoteViews XML, picture/person/group/progress, accent
 * color, category, ongoing/cancellable); this headless slice posts a standard `title`/`message`
 * notification on the given channel and binds `varKey`, and does not drive a custom-interface surface
 * (so `varInterfaceUri` is left unbound). `notification_cancel`'s documented "cancel any notification the
 * fiber posted" blank-key default needs per-fiber notification tracking this build lacks, so it requires an
 * explicit `key`. This is the same honest simplification by which Audio ignores `showPopup`/`playSound`
 * and Telephony ignores `subscriptionId`: an argument with no faithful headless meaning is documented as
 * not-modelled, not faked.
 *
 * The composition helper [deviceUiLookup] mirrors [audioLookup], [contentLookup] and the eight below it:
 * it returns the impls keyed by spec id so a caller composes `deviceUiLookup(provider)[id] ?: base[id]`.
 */

/** The sentence shown whenever a device-UI block cannot reach a UI seam. Modelled on [AUDIO_ABSENT]. */
internal val DEVICE_UI_ABSENT: String =
    "This interface block cannot act: no device-UI seam is available, so Masamune cannot read the " +
        "clipboard, lock/display/UI-mode/orientation/keyboard state or display metrics, nor set the " +
        "clipboard, show a toast or post/cancel a notification. The seam is wired only inside the " +
        "Android app process; when it is absent the block fails by name rather than reporting a UI " +
        "state that never was read or an effect that never was applied."

// --------------------------------------------------------------------------- clipboard_get

/**
 * `clipboard_get` (Clipboard get) — read the clipboard text and bind it.
 *
 * GETTER: the one-shot form of the catalog's WATCH_VALUE getter. It reads the clipboard primary-clip text
 * through the seam and binds it to `varContent`, then proceeds OK. A clipboard with no text content (or no
 * clipboard service) reads as `null` and Fails **by name** — never a fabricated empty `varContent` a
 * downstream block would mistake for real content.
 */
internal class ClipboardGetBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "clipboard_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val text = ui.clipboardText()
            ?: return Outcome.Fail("clipboard_get: the clipboard has no readable text content.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varContent"]?.bind(writes, Value.Text(text))
        return Outcome.Proceed(Port.OK, writes)
    }
}

// --------------------------------------------------------------------------- device_secure

/**
 * `device_secure` (Device secure) — does the device have a secure lock screen (or a PIN-locked SIM)?
 *
 * DECISION: it reads the `ignoreSimLock` flag (default: do *not* ignore the SIM lock, per the catalog),
 * asks the seam, and routes YES when secure, NO otherwise. A `false` is a *real* "not secure" routed to
 * NO; a state the seam cannot read (no `KeyguardManager`) Fails **by name**, never a silent NO.
 */
internal class DeviceSecureBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "device_secure"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val ignoreSimLock = args["ignoreSimLock"].asFlag()
        val secure = ui.isDeviceSecure(ignoreSimLock)
            ?: return Outcome.Fail("device_secure: the secure-lock state could not be read.")
        return Outcome.Proceed(if (secure) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- device_unlocked

/**
 * `device_unlocked` (Device unlocked) — is the device unlocked right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the unlock state through the seam
 * and routes YES when unlocked, NO otherwise. A `false` is a *real* "locked" routed to NO; only a `null`
 * (no `KeyguardManager`) Fails **by name** — never a fabricated `false`.
 */
internal class DeviceUnlockedBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "device_unlocked"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val unlocked = ui.isDeviceUnlocked()
            ?: return Outcome.Fail("device_unlocked: the lock state could not be read.")
        return Outcome.Proceed(if (unlocked) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- display_on

/**
 * `display_on` (Display on) — is the display turned on right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It parses the optional `displayId` (the
 * primary display when absent), reads the display's on/off state through the seam, and routes YES when on,
 * NO otherwise. A `false` is a *real* "off" routed to NO; a state the seam cannot read (no
 * `PowerManager`/`DisplayManager`, or the display does not exist) Fails **by name**, never a silent NO.
 */
internal class DisplayOnBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "display_on"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val on = ui.isDisplayOn(args["displayId"].asDisplayIdOrNull())
            ?: return Outcome.Fail("display_on: the display on/off state could not be read.")
        return Outcome.Proceed(if (on) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- night_mode_enabled

/**
 * `night_mode_enabled` (Night mode enabled) — is the night UI mode in use right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the night-mode state through the
 * seam and routes YES when on, NO otherwise. A `false` is a *real* "not night" routed to NO; only a `null`
 * (the UI-mode configuration could not be read) Fails **by name**.
 */
internal class NightModeEnabledBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "night_mode_enabled"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val night = ui.isNightModeEnabled()
            ?: return Outcome.Fail("night_mode_enabled: the night UI-mode state could not be read.")
        return Outcome.Proceed(if (night) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- car_mode_enabled

/**
 * `car_mode_enabled` (Car mode enabled) — is the car UI mode in use right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the car-mode state through the seam
 * and routes YES when on, NO otherwise. A `false` is a *real* "not car mode" routed to NO; only a `null`
 * (no `UiModeManager`) Fails **by name**.
 */
internal class CarModeEnabledBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "car_mode_enabled"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val car = ui.isCarModeEnabled()
            ?: return Outcome.Fail("car_mode_enabled: the car UI-mode state could not be read.")
        return Outcome.Proceed(if (car) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- screen_orientation

/**
 * `screen_orientation` (Screen orientation) — is the screen in the requested orientation right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It parses the `orientation` argument
 * (default Portrait), reads the current orientation through the seam, and routes YES when the current
 * orientation matches the requested one, NO otherwise. A current orientation the seam cannot read (the
 * configuration could not be read) Fails **by name**, never a silent NO. An unrecognized `orientation`
 * string Fails by name, exactly as `audio_stream_muted` fails on an unrecognized stream.
 */
internal class ScreenOrientationBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "screen_orientation"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val requested = args["orientation"].asScreenOrientationOrDefault(ScreenOrientation.PORTRAIT)
            ?: return Outcome.Fail("screen_orientation: unrecognized orientation.")
        val current = ui.screenOrientation()
            ?: return Outcome.Fail("screen_orientation: the screen orientation could not be read.")
        return Outcome.Proceed(if (current == requested) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- display_metrics_get

/**
 * `display_metrics_get` (Display metrics get) — read a display's metrics and bind them.
 *
 * GETTER (catalog-shaped as a decision): it parses the optional `displayId` (the primary display when
 * absent), reads the display's metrics through the seam, binds `varBounds` (a `left, top, right, bottom`
 * pixel rect), `varDensity`, `varRotation` (degrees) and `varRefreshRate`, and proceeds YES on a
 * successful read. Metrics the seam cannot read (no `DisplayManager`, or the display does not exist) Fail
 * **by name** — never a fabricated `0`/empty binding and never a silent NO. Like `clipboard_get`, this is
 * fundamentally a getter, so it takes YES on a successful read and Fails (rather than NO) when unreadable.
 */
internal class DisplayMetricsGetBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "display_metrics_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val m = ui.displayMetrics(args["displayId"].asDisplayIdOrNull())
            ?: return Outcome.Fail("display_metrics_get: the display metrics could not be read.")
        val writes = LinkedHashMap<String, Value>()
        val bounds = Value.ArrayV(
            listOf(Value.Num(0.0), Value.Num(0.0), Value.Num(m.widthPx.toDouble()), Value.Num(m.heightPx.toDouble())),
        )
        node.outputs["varBounds"]?.bind(writes, bounds)
        node.outputs["varDensity"]?.bind(writes, Value.Num(m.density))
        node.outputs["varRotation"]?.bind(writes, Value.Num(m.rotationDegrees.toDouble()))
        node.outputs["varRefreshRate"]?.bind(writes, Value.Num(m.refreshRateHz))
        return Outcome.Proceed(Port.YES, writes)
    }
}

// --------------------------------------------------------------------------- hardware_keyboard_visible

/**
 * `hardware_keyboard_visible` (Hardware keyboard visible) — is a hardware keyboard extended right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads whether a physical hardware
 * keyboard is present and extended through the seam and routes YES when visible, NO otherwise. A `false`
 * is a *real* "hidden / none" routed to NO; only a `null` (the configuration could not be read) Fails
 * **by name**.
 */
internal class HardwareKeyboardVisibleBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "hardware_keyboard_visible"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val visible = ui.isHardwareKeyboardVisible()
            ?: return Outcome.Fail("hardware_keyboard_visible: the hardware-keyboard state could not be read.")
        return Outcome.Proceed(if (visible) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- clipboard_set (effect)

/**
 * `clipboard_set` (Clipboard set) — set the clipboard text.
 *
 * ACTION / EFFECT: it requires a `text` argument — a clipboard set with no text is a mistake the user must
 * see, so an absent `text` Fails **by name** and the seam is never called. It reads the optional `label`
 * (no label when blank) and the `sensitive` flag (default no), applies the change through the seam and
 * routes the [UiWrite]: OK on accept, a named Fail carrying the reason (no clipboard service) on refusal —
 * never a fabricated OK. The `htmlText`/`uri`/`mimeType` rich-content forms are not modelled (see file
 * KDoc).
 */
internal class ClipboardSetBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "clipboard_set"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val text = args["text"].asTextOrNull()
            ?: return Outcome.Fail("clipboard_set needs text content to place on the clipboard.")
        val label = args["label"].asTextOrNull()
        val sensitive = args["sensitive"].asFlag()
        return ui.setClipboard(text, label, sensitive).asOutcome("clipboard_set")
    }
}

// --------------------------------------------------------------------------- toast_show (effect)

/**
 * `toast_show` (Toast show) — briefly show a toast message.
 *
 * ACTION / EFFECT: it requires a `message` argument — a toast with no message is a mistake the user must
 * see, so an absent `message` Fails **by name** and the seam is never called. It parses the optional
 * `duration` (Short/Long, defaulting per the catalog to Long for messages of 30+ characters and Short
 * otherwise), shows the toast through the seam and routes the [UiWrite]: OK on accept, a named Fail on
 * refusal — never a fabricated OK. The AWAIT (suspend-until-dismissed) tense is collapsed to fire-and-
 * continue (see file KDoc).
 */
internal class ToastShowBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "toast_show"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val message = args["message"].asTextOrNull()
            ?: return Outcome.Fail("toast_show needs a message to display.")
        val long = args["duration"].asToastLong(defaultLong = message.length >= TOAST_LONG_THRESHOLD)
        return ui.showToast(message, long).asOutcome("toast_show")
    }
}

// --------------------------------------------------------------------------- notification_show (effect)

/**
 * `notification_show` (Notification show) — post a status-bar notification.
 *
 * ACTION / EFFECT (catalog-shaped as a decision): it requires a `title` or `message` — an empty
 * notification is a mistake the user must see, so an absent-both Fails **by name** and the seam is never
 * called. It reads the optional `channelId`, posts through the seam keyed by this node's id, and on accept
 * binds `varKey` (the key `notification_cancel` cancels by) and proceeds YES. A refusal ([UiWrite]
 * `ok = false` — notifications disabled, or no `NotificationManager`) Fails **by name** carrying the
 * reason, never a fabricated OK for a notification the user never saw. The large layout/style surface and
 * `varInterfaceUri` are not modelled (see file KDoc).
 */
internal class NotificationShowBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "notification_show"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val title = args["title"].asTextOrNull()
        val message = args["message"].asTextOrNull()
        if (title == null && message == null) {
            return Outcome.Fail("notification_show needs a title or a message to post.")
        }
        val channelId = args["channelId"].asTextOrNull()
        val key = node.id
        val write = ui.showNotification(key, title, message, channelId)
        if (!write.ok) {
            return Outcome.Fail("notification_show: effect refused — ${write.reason ?: "no reason given"}.")
        }
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varKey"]?.bind(writes, Value.Text(key))
        return Outcome.Proceed(Port.YES, writes)
    }
}

// --------------------------------------------------------------------------- notification_cancel (effect)

/**
 * `notification_cancel` (Notification cancel) — cancel a status-bar notification by id.
 *
 * ACTION / EFFECT: it requires a `key` argument (the id bound by a prior `notification_show`) — the
 * catalog's blank-key "cancel any notification the fiber posted" default needs per-fiber notification
 * tracking this build lacks, so an absent `key` Fails **by name** and the seam is never called. It cancels
 * through the seam and routes the [UiWrite]: OK on accept, a named Fail on refusal (no
 * `NotificationManager`) — never a fabricated OK.
 */
internal class NotificationCancelBlock(
    private val provider: () -> DeviceUi?,
) : BlockImpl {
    override val specId = "notification_cancel"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ui = provider() ?: return Outcome.Fail(DEVICE_UI_ABSENT)
        val key = args["key"].asTextOrNull()
            ?: return Outcome.Fail("notification_cancel needs a notification id to cancel.")
        return ui.cancelNotification(key).asOutcome("notification_cancel")
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The thirteen registered Interface device-UI impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [audioLookup], [contentLookup] and the eight lookups below them: it always returns the map, and
 * the honest gate is the per-block gate-at-run (each fails with [DEVICE_UI_ABSENT] when the provider yields
 * no seam), so a caller composes over its base registry exactly as the other categories do:
 *
 * ```
 * val device = deviceUiLookup(deviceUi)
 * fun lookup(id: String): BlockImpl? =
 *     device[id] ?: content[id] ?: audio[id] ?: … ?: base.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's many remaining blocks are deliberately **not** here, so at run time the scheduler finds no
 * impl and gates them by the honest-by-omission mechanism the catalog's own `requires` set (or the block's
 * own shape) expresses. Because the [DeviceUi] seam is an unprivileged UI state-read + simple
 * device-I/O-effect seam wired only to an app `Context`, every gated block needs a grant, a surface, a
 * picker, an await/callback, or a state-setter this seam cannot host. They are omitted on these honest
 * grounds, grouped:
 *  - **Operator-owned AccessibilityService blocks (registered in `OperatorLoop`, never here).**
 *    `inspect_layout`, `interact`, `interact_touch`, `inspect_text_edit`, `key_send`,
 *    `key_send_characters` — registered only when a live a11y actuator exists; this lookup must not
 *    collide with them.
 *  - **Other AccessibilityService-gated blocks (`A11Y`).** `accessibility_button`, `fingerprint_gesture`,
 *    `key_pressed`, `software_keyboard_visible` (needs the a11y service to observe the IME), `toast_posted`
 *    — cannot be served by an app-`Context` seam with no accessibility grant.
 *  - **NotificationListener-gated reads/interactions (`NOTIF`).** `notification_posted` (reads other apps'
 *    notifications), `notification_interact`, `notification_snooze`, `notification_action` (interaction /
 *    await) — need notification-listener access this seam does not hold. (`notification_cancel` is
 *    registered because it cancels the notifications *Masamune itself* posted, which needs no listener.)
 *  - **Device-admin-gated (`ADMIN`).** `device_lock` (locks the device — a device-admin effect, not a
 *    keyguard state read), `password_failed` (login-failed await), `screen_lock_set_state` (disables the
 *    keyguard).
 *  - **SHELL / privileged (`SHELL`).** `attention_light`.
 *  - **State-setters (need `WRITE_SECURE_SETTINGS`/device-admin, or a surface, or are one-way sets).**
 *    `car_mode_set_state`, `night_mode_set_state`, `screen_orientation_set`, `screen_lock_set_state` —
 *    setters this read/simple-effect slice does not model.
 *  - **Dialogs / pickers (need an Activity/UI surface).** `dialog_choice`, `dialog_confirm`,
 *    `dialog_input`, `dialog_message`, `dialog_number`, `dialog_web`, `color_pick`, `icon_pick`,
 *    `notification_channel_pick` — a foreground UI surface this headless seam has no way to present.
 *  - **Custom-interface surfaces (need the custom-UI/interface subsystem).** `interface_adapter_update`,
 *    `interface_clicked`, `interface_item_request`, `interface_layout_update`, `interface_request`,
 *    `floating_button_show`, `quick_settings_tile_show`, `appwidget_configure` (widget), `dream_created`,
 *    `dream_setup` (screensaver), `wallpaper_created`, `wallpaper_setup` (wallpaper).
 *  - **Awaits / callbacks / usage-stats reads outside a one-shot state.** `assist_request`, `media_button`,
 *    `fullscreen` (awaits a system-bar visibility change and needs a window), `split_screen_mode_enabled`
 *    (`isInMultiWindowMode` is an Activity API the app-`Context` seam cannot read), `feature_usage`,
 *    `display_query`, `device_docked`, `wired_headset`, `process_text`/`process_text_result`.
 *
 * Note on collision avoidance: none of the registered ids overlaps another `*Lookup` or the operator's six
 * a11y blocks; the six operator ids above are asserted absent from this lookup in the tests.
 */
fun deviceUiLookup(provider: () -> DeviceUi?): Map<String, BlockImpl> = listOf(
    ClipboardGetBlock(provider),
    DeviceSecureBlock(provider),
    DeviceUnlockedBlock(provider),
    DisplayOnBlock(provider),
    NightModeEnabledBlock(provider),
    CarModeEnabledBlock(provider),
    ScreenOrientationBlock(provider),
    DisplayMetricsGetBlock(provider),
    HardwareKeyboardVisibleBlock(provider),
    ClipboardSetBlock(provider),
    ToastShowBlock(provider),
    NotificationShowBlock(provider),
    NotificationCancelBlock(provider),
).associateBy { it.specId }

/** A toast message of this many characters or more defaults to the Long duration, per the catalog. */
private const val TOAST_LONG_THRESHOLD = 30

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

/**
 * Turn a [UiWrite] into the block's [Outcome]: OK when the device accepted the effect, or a named
 * [Outcome.Fail] carrying the seam's honest reason (no clipboard service, notifications disabled, the post
 * threw) when it did not. This is the single place the "a refused effect is a visible Fail, never a
 * fabricated OK" rule lives for the OK-port effects — modelled on Audio's `AudioWrite.asOutcome`.
 */
private fun UiWrite.asOutcome(blockId: String): Outcome =
    if (ok) Outcome.Proceed(Port.OK)
    else Outcome.Fail("$blockId: effect refused — ${reason ?: "no reason given"}.")

/**
 * A `displayId` argument parsed to an `Int`: `null` when blank/absent (the primary display), else the
 * numeric id. A non-numeric value is treated as absent (the primary display), matching the catalog's "id
 * of the primary display" default rather than failing on a stray value.
 */
private fun Value?.asDisplayIdOrNull(): Int? = this.asNumOrNull()?.toInt()

/**
 * A `duration` argument parsed to the toast Long/Short choice: `Long` when the value names it (the text
 * "long", or a nonzero number, mirroring Automate's `1` = Long), `Short` when it names Short (the text
 * "short", or `0`), else [defaultLong] when blank/absent.
 */
private fun Value?.asToastLong(defaultLong: Boolean): Boolean {
    val text = this.asTextOrNull()?.trim()
    if (text.isNullOrEmpty()) return defaultLong
    return when (text.lowercase()) {
        "long", "1" -> true
        "short", "0" -> false
        else -> this.asNumOrNull()?.let { it != 0.0 } ?: defaultLong
    }
}

/**
 * An `orientation` argument parsed to a [ScreenOrientation]: [default] when blank/absent, the named
 * orientation when recognized (the label and the obvious synonyms), or `null` when a non-blank value names
 * no known orientation — which the caller turns into a visible Fail, never a silent default. Mirrors
 * Audio's `asAudioStreamOrDefault`.
 */
private fun Value?.asScreenOrientationOrDefault(default: ScreenOrientation): ScreenOrientation? {
    val text = this.asTextOrNull()?.trim()
    if (text.isNullOrEmpty()) return default
    return when (text.lowercase()) {
        "portrait", "port", "vertical", "1" -> ScreenOrientation.PORTRAIT
        "landscape", "land", "horizontal", "2" -> ScreenOrientation.LANDSCAPE
        else -> null
    }
}
