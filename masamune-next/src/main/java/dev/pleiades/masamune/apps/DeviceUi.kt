package dev.pleiades.masamune.apps

/**
 * The seam between the Interface category's **unprivileged device-UI state reads** and **simple
 * device-I/O effect actions** (clipboard, toast, notification) and the real device UI stack.
 *
 * Every way a one-shot Interface block can *read* an unprivileged UI-adjacent state — the clipboard
 * text, whether the device has a secure lock screen, whether it is unlocked, whether a display is on,
 * the night/car UI mode, the screen orientation, a display's metrics, whether a hardware keyboard is
 * extended — and every *simple device-I/O effect* it can apply — set the clipboard, show a toast, post
 * a notification, cancel one — is one method here, and — exactly like [AudioController] does for the
 * CameraAndSound audio slice, [AppInspector] for Apps, [SystemSettings] for Settings, [PowerState] for
 * Battery&Power, [SensorReader] for Sensor, [LocationReader] for Location, [ConnectivityReader] for
 * Connectivity, [TelephonyReader] for Telephony and [ContentReader] for Content — there is deliberately
 * nothing `android.*` on this interface. That single constraint is what buys the whole slice its
 * JVM-testability: [dev.pleiades.masamune.flow.runtime.impl.deviceUiLookup]'s blocks depend on this
 * plain-data contract, never on `ClipboardManager`/`Toast`/`NotificationManager`/`KeyguardManager`, so
 * every block and all its branch logic can be exercised against a fake on an ordinary unit-test JVM. A
 * device is needed to *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidDeviceUi]) is not wired in,
 * there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.DEVICE_UI_ABSENT]) rather than reporting a UI state it never
 * actually read or claiming an effect it never applied.
 *
 * ### Honest failure shapes: not-readable vs. a real "no", and refused vs. applied
 * The reads model two genuinely different "negative" cases, and the effects model a third:
 *  - **A read's `null` means "could not be read".** There is no clipboard service, no `KeyguardManager`,
 *    the display does not exist, or the read needs an API level this device predates. The block routes
 *    `null` to a visible [dev.pleiades.masamune.flow.runtime.Outcome.Fail] **by name** — it never
 *    fabricates a `false`/`0`/empty a downstream block would trust as a real reading.
 *  - **A read's real `false` means "read fine, and the answer is no".** A device genuinely not secure, a
 *    display really off, night mode really not set, a hardware keyboard really hidden — each is a
 *    successful read with a NO answer, distinct from a state that could not be determined at all.
 *  - **An effect's [UiWrite] carries `ok` + a `reason`.** A set the device refuses — no clipboard
 *    service, notifications disabled by the user, the toast could not be posted — returns
 *    `UiWrite(ok = false, reason = …)`, exactly like Audio's `AudioWrite` and Settings' `SettingWrite`,
 *    and the block Fails **by name** carrying that reason rather than fabricating success.
 *
 * ### Permissions shape the *run-time* failure, never keep a block unregistered
 * The registered reads rest on no permission (clipboard, keyguard state, UI-mode, display metrics,
 * configuration are all readable unprivileged), and the notification effects rest on
 * `POST_NOTIFICATIONS` — a runtime grant the seam checks honestly: when notifications are disabled the
 * effect returns [UiWrite] `ok = false` and the block Fails **by name**, never a fabricated OK and never
 * left unregistered. Every Interface block that needs an AccessibilityService, a NotificationListener, a
 * device-admin receiver, a `WRITE_SECURE_SETTINGS`/SHELL grant, an Activity/window surface, a picker, or
 * an AWAIT/callback has no method here and is gated by omission (see [deviceUiLookup]'s KDoc).
 *
 * This slice touches unprivileged UI *state* reads and simple *device-I/O effects* only: there is no UI
 * inspection, no touch/gesture injection, no key injection, no dialog/window, no picker, no custom
 * interface surface, and no state-setter that would need a privileged grant.
 *
 * Every method is `suspend` because a UI read/effect can touch a blocking system service or must hop to
 * the main thread (a toast); the real impl does so without the contract changing shape, and the fake
 * simply returns.
 */
interface DeviceUi {

    // ---- reads / decisions -------------------------------------------------

    /**
     * The current clipboard primary-clip text, or `null` when it cannot be read (no clipboard service)
     * or the clipboard holds no text content. `null` routes a named Fail; real text is bound to
     * `varContent`. There is deliberately no "empty text" success here — an empty/absent clip is a
     * `null` the block Fails on, never a bound empty string a downstream block would mistake for content.
     */
    suspend fun clipboardText(): String?

    /**
     * Whether the device has a secure lock screen configured (a PIN/pattern/password), or — unless
     * [ignoreSimLock] — a currently locked SIM that requires a PIN. `null` when there is no
     * `KeyguardManager` to ask; `false` is a real "not secure" (NO); `null` routes a named Fail.
     */
    suspend fun isDeviceSecure(ignoreSimLock: Boolean): Boolean?

    /**
     * Whether the device is currently unlocked, or `null` when there is no `KeyguardManager` to ask.
     * `false` is a real "locked" (NO); `null` routes a named Fail.
     */
    suspend fun isDeviceUnlocked(): Boolean?

    /**
     * Whether [displayId] (or the primary display when `null`) is turned on, or `null` when it cannot be
     * read (no `PowerManager`/`DisplayManager`, or the display does not exist). `false` is a real "off"
     * (NO); `null` routes a named Fail.
     */
    suspend fun isDisplayOn(displayId: Int?): Boolean?

    /**
     * Whether the night UI mode is in use, or `null` when the UI-mode configuration cannot be read.
     * `false` is a real "not night" (NO); `null` routes a named Fail.
     */
    suspend fun isNightModeEnabled(): Boolean?

    /**
     * Whether the car UI mode is in use, or `null` when there is no `UiModeManager` to ask. `false` is a
     * real "not car mode" (NO); `null` routes a named Fail.
     */
    suspend fun isCarModeEnabled(): Boolean?

    /**
     * The current screen orientation as plain data, or `null` when the configuration cannot be read.
     * `null` routes a named Fail; the block compares the real reading against the requested orientation.
     */
    suspend fun screenOrientation(): ScreenOrientation?

    /**
     * The metrics of [displayId] (or the primary display when `null`) as plain data, or `null` when they
     * cannot be read (no `DisplayManager`, or the display does not exist). `null` routes a named Fail;
     * real metrics are bound to the block's outputs.
     */
    suspend fun displayMetrics(displayId: Int?): DisplayMetricsInfo?

    /**
     * Whether a physical hardware keyboard is present and extended/visible, or `null` when the
     * configuration cannot be read. `false` is a real "hidden / none" (NO); `null` routes a named Fail.
     */
    suspend fun isHardwareKeyboardVisible(): Boolean?

    // ---- effects / actions -------------------------------------------------

    /**
     * Set the clipboard to plain [text] under [label] (or none when `null`), marking it [sensitive] when
     * asked. Returns [UiWrite]: `ok = false` with a `reason` when there is no clipboard service or the
     * set throws, so the block Fails **by name** rather than faking OK.
     */
    suspend fun setClipboard(text: String, label: String?, sensitive: Boolean): UiWrite

    /**
     * Briefly show a toast of [message], long when [longDuration]. Returns [UiWrite]: `ok = false` with a
     * `reason` when it could not be posted, so the block Fails **by name** rather than faking OK.
     */
    suspend fun showToast(message: String, longDuration: Boolean): UiWrite

    /**
     * Post a status-bar notification identified by [key], with [title]/[message] on channel [channelId]
     * (a default channel when `null`). Returns [UiWrite]: `ok = false` with a `reason` when notifications
     * are disabled or there is no `NotificationManager`, so the block Fails **by name** rather than
     * fabricating an OK for a notification the user never saw.
     */
    suspend fun showNotification(
        key: String,
        title: String?,
        message: String?,
        channelId: String?,
    ): UiWrite

    /**
     * Cancel the notification previously posted under [key]. Returns [UiWrite]: `ok = false` with a
     * `reason` when there is no `NotificationManager`, so the block Fails **by name** rather than faking
     * OK.
     */
    suspend fun cancelNotification(key: String): UiWrite
}

/**
 * The screen orientation the `screen_orientation` decision compares, as plain data — a real enum rather
 * than a leaked `Configuration.ORIENTATION_*` int. The mapping from an Android configuration int to this
 * enum lives entirely in [AndroidDeviceUi], so nothing `android.*` crosses the seam. The [label] is how
 * the block's `orientation` argument names the choice and is matched against (case-insensitively, with
 * the obvious synonyms), mirroring how [AudioStream]'s label names the audio `stream` choice.
 */
enum class ScreenOrientation(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

/**
 * A connected display's metrics as plain data — what the `display_metrics_get` block binds to its
 * outputs. Everything here is a bare number so nothing `android.*` (no `DisplayMetrics`, no `Rect`)
 * crosses the seam: [widthPx]/[heightPx] are the display bounds in pixels (the block binds them as a
 * `left, top, right, bottom` rect), [density] is the logical-density scaling factor, [rotationDegrees]
 * is the display rotation (0/90/180/270), and [refreshRateHz] is the refresh rate.
 */
data class DisplayMetricsInfo(
    val widthPx: Int,
    val heightPx: Int,
    val density: Double,
    val rotationDegrees: Int,
    val refreshRateHz: Double,
)

/**
 * The result of a device-UI *effect* (set clipboard / show toast / post or cancel a notification), as
 * plain data — modelled on Audio's `AudioWrite` and Settings' `SettingWrite`. `ok = true` is a
 * device-accepted effect; `ok = false` with a [reason] is an honest refusal (no clipboard service,
 * notifications disabled, the post threw) the block turns into a visible Fail carrying [reason] — never a
 * fabricated success.
 */
data class UiWrite(val ok: Boolean, val reason: String? = null)
