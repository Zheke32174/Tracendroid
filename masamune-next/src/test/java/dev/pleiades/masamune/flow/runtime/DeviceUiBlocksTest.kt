package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.DeviceUi
import dev.pleiades.masamune.apps.DisplayMetricsInfo
import dev.pleiades.masamune.apps.ScreenOrientation
import dev.pleiades.masamune.apps.UiWrite
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.deviceUiLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Interface device-UI state/effect blocks branch, bind and apply effects correctly —
 * run against a [FakeDeviceUi] on the JVM, never a device, which is exactly what the `android.*`-free
 * [DeviceUi] seam buys (the same seam shape the Apps, Settings, Battery&Power, Sensor, Location,
 * Connectivity, Telephony, CameraAndSound-audio and Content blocks use). Each test drives a block the way
 * the runtime does — an args map of resolved [Value]s and a [FlowNode] carrying the output bindings — and
 * asserts on the [Outcome], its writes and (for effects) the recorded seam call. The honest failure shape
 * is the point of the coverage: a UI state the device cannot read is a visible [Outcome.Fail], never a
 * fabricated `false`/`0`/empty; a refused effect ([UiWrite] `ok = false`) is a visible Fail carrying the
 * reason, never a fabricated OK; a required arg missing is a Fail with NO seam call; a real "no / off"
 * is a NO, distinct from an unreadable state (a Fail). The absent-seam path is checked for all thirteen
 * blocks.
 */
class DeviceUiBlocksTest {

    /**
     * A fully scriptable fake standing in for the real device-UI stack. A `null` reading is exactly what a
     * device with no clipboard/keyguard/UI-mode service would answer, and the block turns that `null` into
     * a named Fail. Effects return a scripted [UiWrite] and record their call so a test can assert both the
     * [Outcome] and that the seam was asked to apply the change (never a fabricated OK).
     */
    private class FakeDeviceUi(
        private val clipboard: String? = null,
        private val deviceSecure: Boolean? = null,
        private val deviceUnlocked: Boolean? = null,
        private val displayOn: Boolean? = null,
        private val nightMode: Boolean? = null,
        private val carMode: Boolean? = null,
        private val orientation: ScreenOrientation? = null,
        private val metrics: DisplayMetricsInfo? = null,
        private val hardwareKeyboard: Boolean? = null,
        private val setClipboardResult: UiWrite = UiWrite(ok = true),
        private val toastResult: UiWrite = UiWrite(ok = true),
        private val notificationResult: UiWrite = UiWrite(ok = true),
        private val cancelResult: UiWrite = UiWrite(ok = true),
    ) : DeviceUi {
        val clipboardSets = mutableListOf<Triple<String, String?, Boolean>>()
        val toastShows = mutableListOf<Pair<String, Boolean>>()
        val notificationShows = mutableListOf<NotificationCall>()
        val cancels = mutableListOf<String>()

        data class NotificationCall(
            val key: String,
            val title: String?,
            val message: String?,
            val channelId: String?,
        )

        override suspend fun clipboardText(): String? = clipboard
        override suspend fun isDeviceSecure(ignoreSimLock: Boolean): Boolean? = deviceSecure
        override suspend fun isDeviceUnlocked(): Boolean? = deviceUnlocked
        override suspend fun isDisplayOn(displayId: Int?): Boolean? = displayOn
        override suspend fun isNightModeEnabled(): Boolean? = nightMode
        override suspend fun isCarModeEnabled(): Boolean? = carMode
        override suspend fun screenOrientation(): ScreenOrientation? = orientation
        override suspend fun displayMetrics(displayId: Int?): DisplayMetricsInfo? = metrics
        override suspend fun isHardwareKeyboardVisible(): Boolean? = hardwareKeyboard

        override suspend fun setClipboard(text: String, label: String?, sensitive: Boolean): UiWrite {
            clipboardSets += Triple(text, label, sensitive)
            return setClipboardResult
        }

        override suspend fun showToast(message: String, longDuration: Boolean): UiWrite {
            toastShows += message to longDuration
            return toastResult
        }

        override suspend fun showNotification(
            key: String,
            title: String?,
            message: String?,
            channelId: String?,
        ): UiWrite {
            notificationShows += NotificationCall(key, title, message, channelId)
            return notificationResult
        }

        override suspend fun cancelNotification(key: String): UiWrite {
            cancels += key
            return cancelResult
        }
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    /** Fetch a single registered impl from the lookup composed over [seam]. */
    private fun block(specId: String, seam: DeviceUi?): BlockImpl =
        deviceUiLookup { seam }[specId] ?: error("no registered block for $specId")

    // ------------------------------------------------------------------ clipboard_get

    @Test fun clipboardGetBindsContent() = runTest {
        val seam = FakeDeviceUi(clipboard = "hello")
        val outcome = block("clipboard_get", seam).run(
            fiber(), node("clipboard_get", "varContent" to "c"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.OK, proceed.port)
        assertEquals(Value.Text("hello"), proceed.writes["c"])
    }

    @Test fun clipboardGetFailsWhenNoText() = runTest {
        // No text content reads as null → Fail, never a bound empty string.
        val seam = FakeDeviceUi(clipboard = null)
        val outcome = block("clipboard_get", seam).run(
            fiber(), node("clipboard_get", "varContent" to "c"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["c"])
    }

    // ------------------------------------------------------------------ device_secure

    @Test fun deviceSecureYesWhenSecure() = runTest {
        val outcome = block("device_secure", FakeDeviceUi(deviceSecure = true)).run(
            fiber(), node("device_secure"), emptyMap(),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun deviceSecureNoWhenNotSecure() = runTest {
        // A real false is NO, not a Fail.
        val outcome = block("device_secure", FakeDeviceUi(deviceSecure = false)).run(
            fiber(), node("device_secure"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun deviceSecureFailsWhenUnreadable() = runTest {
        val outcome = block("device_secure", FakeDeviceUi(deviceSecure = null)).run(
            fiber(), node("device_secure"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ device_unlocked

    @Test fun deviceUnlockedYesWhenUnlocked() = runTest {
        val outcome = block("device_unlocked", FakeDeviceUi(deviceUnlocked = true)).run(
            fiber(), node("device_unlocked"), emptyMap(),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun deviceUnlockedNoWhenLocked() = runTest {
        val outcome = block("device_unlocked", FakeDeviceUi(deviceUnlocked = false)).run(
            fiber(), node("device_unlocked"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun deviceUnlockedFailsWhenUnreadable() = runTest {
        val outcome = block("device_unlocked", FakeDeviceUi(deviceUnlocked = null)).run(
            fiber(), node("device_unlocked"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ display_on

    @Test fun displayOnYesWhenOn() = runTest {
        val outcome = block("display_on", FakeDeviceUi(displayOn = true)).run(
            fiber(), node("display_on"), emptyMap(),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun displayOnNoWhenOff() = runTest {
        val outcome = block("display_on", FakeDeviceUi(displayOn = false)).run(
            fiber(), node("display_on"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun displayOnFailsWhenUnreadable() = runTest {
        val outcome = block("display_on", FakeDeviceUi(displayOn = null)).run(
            fiber(), node("display_on"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ night_mode_enabled

    @Test fun nightModeYesAndNoAndFail() = runTest {
        assertEquals(
            Port.YES,
            (block("night_mode_enabled", FakeDeviceUi(nightMode = true))
                .run(fiber(), node("night_mode_enabled"), emptyMap()) as Outcome.Proceed).port,
        )
        assertEquals(
            Port.NO,
            (block("night_mode_enabled", FakeDeviceUi(nightMode = false))
                .run(fiber(), node("night_mode_enabled"), emptyMap()) as Outcome.Proceed).port,
        )
        assertTrue(
            block("night_mode_enabled", FakeDeviceUi(nightMode = null))
                .run(fiber(), node("night_mode_enabled"), emptyMap()) is Outcome.Fail,
        )
    }

    // ------------------------------------------------------------------ car_mode_enabled

    @Test fun carModeYesAndNoAndFail() = runTest {
        assertEquals(
            Port.YES,
            (block("car_mode_enabled", FakeDeviceUi(carMode = true))
                .run(fiber(), node("car_mode_enabled"), emptyMap()) as Outcome.Proceed).port,
        )
        assertEquals(
            Port.NO,
            (block("car_mode_enabled", FakeDeviceUi(carMode = false))
                .run(fiber(), node("car_mode_enabled"), emptyMap()) as Outcome.Proceed).port,
        )
        assertTrue(
            block("car_mode_enabled", FakeDeviceUi(carMode = null))
                .run(fiber(), node("car_mode_enabled"), emptyMap()) is Outcome.Fail,
        )
    }

    // ------------------------------------------------------------------ screen_orientation

    @Test fun screenOrientationYesWhenMatchesDefaultPortrait() = runTest {
        // Default requested orientation is Portrait; a portrait screen → YES.
        val seam = FakeDeviceUi(orientation = ScreenOrientation.PORTRAIT)
        val outcome = block("screen_orientation", seam).run(
            fiber(), node("screen_orientation"), emptyMap(),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun screenOrientationNoWhenMismatch() = runTest {
        // Requesting Landscape while the screen is Portrait → NO (a real read, not a Fail).
        val seam = FakeDeviceUi(orientation = ScreenOrientation.PORTRAIT)
        val outcome = block("screen_orientation", seam).run(
            fiber(), node("screen_orientation"), mapOf("orientation" to Value.Text("landscape")),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun screenOrientationFailsOnUnrecognized() = runTest {
        val seam = FakeDeviceUi(orientation = ScreenOrientation.PORTRAIT)
        val outcome = block("screen_orientation", seam).run(
            fiber(), node("screen_orientation"), mapOf("orientation" to Value.Text("diagonal")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    @Test fun screenOrientationFailsWhenUnreadable() = runTest {
        val outcome = block("screen_orientation", FakeDeviceUi(orientation = null)).run(
            fiber(), node("screen_orientation"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ display_metrics_get

    @Test fun displayMetricsBindsAllOutputs() = runTest {
        val seam = FakeDeviceUi(
            metrics = DisplayMetricsInfo(
                widthPx = 1080, heightPx = 2340, density = 2.75, rotationDegrees = 90, refreshRateHz = 120.0,
            ),
        )
        val outcome = block("display_metrics_get", seam).run(
            fiber(),
            node(
                "display_metrics_get",
                "varBounds" to "b", "varDensity" to "d", "varRotation" to "r", "varRefreshRate" to "hz",
            ),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(
            Value.ArrayV(listOf(Value.Num(0.0), Value.Num(0.0), Value.Num(1080.0), Value.Num(2340.0))),
            proceed.writes["b"],
        )
        assertEquals(Value.Num(2.75), proceed.writes["d"])
        assertEquals(Value.Num(90.0), proceed.writes["r"])
        assertEquals(Value.Num(120.0), proceed.writes["hz"])
    }

    @Test fun displayMetricsFailsWhenUnreadable() = runTest {
        val outcome = block("display_metrics_get", FakeDeviceUi(metrics = null)).run(
            fiber(), node("display_metrics_get", "varDensity" to "d"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["d"])
    }

    // ------------------------------------------------------------------ hardware_keyboard_visible

    @Test fun hardwareKeyboardYesAndNoAndFail() = runTest {
        assertEquals(
            Port.YES,
            (block("hardware_keyboard_visible", FakeDeviceUi(hardwareKeyboard = true))
                .run(fiber(), node("hardware_keyboard_visible"), emptyMap()) as Outcome.Proceed).port,
        )
        assertEquals(
            Port.NO,
            (block("hardware_keyboard_visible", FakeDeviceUi(hardwareKeyboard = false))
                .run(fiber(), node("hardware_keyboard_visible"), emptyMap()) as Outcome.Proceed).port,
        )
        assertTrue(
            block("hardware_keyboard_visible", FakeDeviceUi(hardwareKeyboard = null))
                .run(fiber(), node("hardware_keyboard_visible"), emptyMap()) is Outcome.Fail,
        )
    }

    // ------------------------------------------------------------------ clipboard_set (effect)

    @Test fun clipboardSetAppliesAndOk() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("clipboard_set", seam).run(
            fiber(), node("clipboard_set"),
            mapOf(
                "text" to Value.Text("copied"),
                "label" to Value.Text("note"),
                "sensitive" to Value.Text("true"),
            ),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf(Triple("copied", "note", true)), seam.clipboardSets)
    }

    @Test fun clipboardSetFailsWhenRefused() = runTest {
        val seam = FakeDeviceUi(setClipboardResult = UiWrite(ok = false, reason = "no clipboard service"))
        val outcome = block("clipboard_set", seam).run(
            fiber(), node("clipboard_set"), mapOf("text" to Value.Text("x")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("no clipboard service"))
        assertEquals(listOf(Triple("x", null, false)), seam.clipboardSets)
    }

    @Test fun clipboardSetFailsWithoutText() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("clipboard_set", seam).run(
            fiber(), node("clipboard_set"), mapOf("label" to Value.Text("note")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue("no fabricated set is issued", seam.clipboardSets.isEmpty())
    }

    // ------------------------------------------------------------------ toast_show (effect)

    @Test fun toastShowAppliesAndOk() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("toast_show", seam).run(
            fiber(), node("toast_show"),
            mapOf("message" to Value.Text("hi"), "duration" to Value.Text("long")),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf("hi" to true), seam.toastShows)
    }

    @Test fun toastShowDefaultsDurationByLength() = runTest {
        // A short message (< 30 chars) with no duration defaults to Short (false).
        val seam = FakeDeviceUi()
        block("toast_show", seam).run(
            fiber(), node("toast_show"), mapOf("message" to Value.Text("hi")),
        )
        assertEquals(listOf("hi" to false), seam.toastShows)
    }

    @Test fun toastShowFailsWhenRefused() = runTest {
        val seam = FakeDeviceUi(toastResult = UiWrite(ok = false, reason = "the toast could not be shown"))
        val outcome = block("toast_show", seam).run(
            fiber(), node("toast_show"), mapOf("message" to Value.Text("hi")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("could not be shown"))
        assertEquals(listOf("hi" to false), seam.toastShows)
    }

    @Test fun toastShowFailsWithoutMessage() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("toast_show", seam).run(
            fiber(), node("toast_show"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue("no fabricated toast is issued", seam.toastShows.isEmpty())
    }

    // ------------------------------------------------------------------ notification_show (effect)

    @Test fun notificationShowAppliesBindsKeyAndYes() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("notification_show", seam).run(
            fiber(), node("notification_show", "varKey" to "k"),
            mapOf(
                "title" to Value.Text("Title"),
                "message" to Value.Text("Body"),
                "channelId" to Value.Text("alerts"),
            ),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        // The bound key is this node's id, and it is exactly what the seam was asked to post under.
        assertEquals(Value.Text("n"), proceed.writes["k"])
        assertEquals(
            listOf(FakeDeviceUi.NotificationCall("n", "Title", "Body", "alerts")),
            seam.notificationShows,
        )
    }

    @Test fun notificationShowFailsWhenRefused() = runTest {
        val seam = FakeDeviceUi(
            notificationResult = UiWrite(ok = false, reason = "notifications are disabled for this app"),
        )
        val outcome = block("notification_show", seam).run(
            fiber(), node("notification_show", "varKey" to "k"),
            mapOf("title" to Value.Text("Title")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("notifications are disabled"))
        assertNull(outcome.writes["k"]) // no fabricated key for a notification the user never saw
        assertEquals(1, seam.notificationShows.size)
    }

    @Test fun notificationShowFailsWithoutTitleOrMessage() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("notification_show", seam).run(
            fiber(), node("notification_show"), mapOf("channelId" to Value.Text("alerts")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue("no fabricated post is issued", seam.notificationShows.isEmpty())
    }

    // ------------------------------------------------------------------ notification_cancel (effect)

    @Test fun notificationCancelAppliesAndOk() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("notification_cancel", seam).run(
            fiber(), node("notification_cancel"), mapOf("key" to Value.Text("n")),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf("n"), seam.cancels)
    }

    @Test fun notificationCancelFailsWhenRefused() = runTest {
        val seam = FakeDeviceUi(cancelResult = UiWrite(ok = false, reason = "no notification service"))
        val outcome = block("notification_cancel", seam).run(
            fiber(), node("notification_cancel"), mapOf("key" to Value.Text("n")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("no notification service"))
        assertEquals(listOf("n"), seam.cancels)
    }

    @Test fun notificationCancelFailsWithoutKey() = runTest {
        val seam = FakeDeviceUi()
        val outcome = block("notification_cancel", seam).run(
            fiber(), node("notification_cancel"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue("no fabricated cancel is issued", seam.cancels.isEmpty())
    }

    // ------------------------------------------------------------------ absent seam (all thirteen)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val lookup = deviceUiLookup { null }
        for ((id, impl) in lookup) {
            val outcome = impl.run(fiber(), node(id), emptyMap())
            assertTrue("$id must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("device-UI seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun deviceUiLookupExposesExactlyTheThirteenRegisteredBlocks() {
        val lookup = deviceUiLookup { null }
        assertEquals(
            setOf(
                "clipboard_get",
                "device_secure",
                "device_unlocked",
                "display_on",
                "night_mode_enabled",
                "car_mode_enabled",
                "screen_orientation",
                "display_metrics_get",
                "hardware_keyboard_visible",
                "clipboard_set",
                "toast_show",
                "notification_show",
                "notification_cancel",
            ),
            lookup.keys,
        )
        // OPERATOR-OWNED a11y blocks (registered in OperatorLoop, never here) — must be absent.
        assertNull(lookup["inspect_layout"])
        assertNull(lookup["interact"])
        assertNull(lookup["interact_touch"])
        assertNull(lookup["inspect_text_edit"])
        assertNull(lookup["key_send"])
        assertNull(lookup["key_send_characters"])
        // Other A11Y-gated Interface blocks — gated by omission.
        assertNull(lookup["accessibility_button"])
        assertNull(lookup["fingerprint_gesture"])
        assertNull(lookup["key_pressed"])
        assertNull(lookup["software_keyboard_visible"]) // needs the a11y service to observe the IME
        assertNull(lookup["toast_posted"])
        // NotificationListener-gated reads/interactions.
        assertNull(lookup["notification_posted"])
        assertNull(lookup["notification_interact"])
        assertNull(lookup["notification_snooze"])
        assertNull(lookup["notification_action"])
        // Device-admin-gated.
        assertNull(lookup["device_lock"]) // locks the device — ADMIN, not a keyguard read
        assertNull(lookup["password_failed"])
        assertNull(lookup["screen_lock_set_state"])
        // SHELL / privileged.
        assertNull(lookup["attention_light"])
        // State-setters.
        assertNull(lookup["car_mode_set_state"])
        assertNull(lookup["night_mode_set_state"])
        assertNull(lookup["screen_orientation_set"])
        // Dialogs / pickers.
        assertNull(lookup["dialog_confirm"])
        assertNull(lookup["dialog_input"])
        assertNull(lookup["color_pick"])
        assertNull(lookup["icon_pick"])
        assertNull(lookup["notification_channel_pick"])
        // Custom-interface surfaces / widgets / screensavers / wallpapers.
        assertNull(lookup["interface_request"])
        assertNull(lookup["floating_button_show"])
        assertNull(lookup["quick_settings_tile_show"])
        assertNull(lookup["appwidget_configure"])
        assertNull(lookup["dream_setup"])
        assertNull(lookup["wallpaper_setup"])
        // Awaits / callbacks / non-one-shot reads.
        assertNull(lookup["fullscreen"]) // awaits a system-bar visibility change, needs a window
        assertNull(lookup["split_screen_mode_enabled"]) // isInMultiWindowMode is an Activity API
        assertNull(lookup["feature_usage"])
        assertNull(lookup["display_query"])
        assertNull(lookup["device_docked"])
        assertNull(lookup["process_text"])
        // Composes over the layers below via `deviceUiLookup(...)[id] ?: base`.
        assertNull(lookup["audio_volume"]) // CameraAndSound audio
        assertNull(lookup["roaming"]) // Telephony
        assertNull(lookup["battery_level"]) // Battery & power
        assertEquals("clipboard_get", lookup["clipboard_get"]!!.specId)
    }
}
