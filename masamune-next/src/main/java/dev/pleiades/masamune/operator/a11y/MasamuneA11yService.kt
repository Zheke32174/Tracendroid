package dev.pleiades.masamune.operator.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Masamune's own accessibility service — the eyes and hands the AI operator drives the phone
 * with, and the concrete [ScreenActuator].
 *
 * This is a faithful port of Operit's `AccessibilityUITools` mechanism (getPageInfo →
 * simplifyLayout to see, `dispatchGesture` + node actions + `performGlobalAction` to touch), but
 * it is written from scratch against the framework `AccessibilityService` rather than depending
 * on the `:app` module or Operit's out-of-process AIDL provider. The port keeps what matters —
 * the compact-tree observation and the gesture/node-action repertoire — and drops the remote
 * plumbing, which existed only because Operit ran its provider in a separate installed app.
 *
 * ### The enable gate lives in the service's own lifecycle
 * The service publishes itself to [A11yServiceHolder] on connect and withdraws on disconnect.
 * That is the whole gate: with the service off there is no instance, [A11yServiceHolder.actuator]
 * is null, and every operator surface reports the accessibility service as missing. Nothing here
 * fakes a screen read or a tap; an action that cannot reach a live service does not happen.
 *
 * The service does no work on its own — it posts no notifications, listens for no events, drives
 * nothing. It acts only when the operator loop, running as a fiber, calls one of these methods
 * through the [ScreenActuator] seam. That passivity is deliberate: an always-listening service
 * that "helpfully" reacts is exactly the unauditable behaviour docs/AI-OPERATOR.md forbids.
 */
class MasamuneA11yService : AccessibilityService(), ScreenActuator {

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yServiceHolder.attach(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        A11yServiceHolder.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yServiceHolder.detach(this)
        super.onDestroy()
    }

    /**
     * Required override, intentionally empty. The operator pulls the screen on demand from its
     * observe block; it does not react to a push of events. Reacting here would be action taken
     * outside the flow graph — the one thing the operator design rules out.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- seeing

    override suspend fun dumpLayout(): SimplifiedNode? = withContext(Dispatchers.Main.immediate) {
        val root = rootInActiveWindow ?: return@withContext null
        build(root, 0)
    }

    /** Walk one `AccessibilityNodeInfo` into a [SimplifiedNode], bounded so a runaway tree cannot hang the read. */
    private fun build(node: AccessibilityNodeInfo, depth: Int): SimplifiedNode {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val children = if (depth < MAX_TREE_DEPTH) {
            (0 until node.childCount).mapNotNull { i ->
                node.getChild(i)?.let { build(it, depth + 1) }
            }
        } else {
            emptyList()
        }
        return SimplifiedNode(
            className = node.className?.toString()?.substringAfterLast('.'),
            text = node.text?.toString()?.replace('\n', ' ')?.takeIf { it.isNotBlank() },
            contentDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            resourceId = node.viewIdResourceName,
            bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
            clickable = node.isClickable,
            editable = node.isEditable,
            children = children,
        )
    }

    // --------------------------------------------------------------- touching

    override suspend fun tap(x: Int, y: Int): Boolean =
        dispatchStroke(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, startMs = 0, durationMs = TAP_MS)

    override suspend fun longPress(x: Int, y: Int): Boolean =
        dispatchStroke(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, startMs = 0, durationMs = LONG_PRESS_MS)

    override suspend fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long): Boolean =
        dispatchStroke(
            Path().apply {
                moveTo(x0.toFloat(), y0.toFloat())
                lineTo(x1.toFloat(), y1.toFloat())
            },
            startMs = 0,
            durationMs = durationMs.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS),
        )

    /**
     * Dispatch one gesture stroke and suspend until the framework reports completion or
     * cancellation. The callback fires on the main thread; wrapping it in a cancellable
     * continuation is what lets an operator action `await` the real gesture result rather than
     * fire-and-hope — so the act block only proceeds once the tap has actually landed.
     */
    private suspend fun dispatchStroke(path: Path, startMs: Long, durationMs: Long): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, startMs, durationMs))
                .build()
            suspendCancellableCoroutine { cont ->
                val ok = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(description: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(description: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
                // dispatchGesture returns false when it could not even be queued (no gesture
                // capability): resume now, because no callback will ever come.
                if (!ok && cont.isActive) cont.resume(false)
            }
        }

    override suspend fun clickNodeMatching(query: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val root = rootInActiveWindow ?: return@withContext false
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext false
        val match = findNode(root) { node -> nodeMatches(node, needle) } ?: return@withContext false
        // Click the match, or the nearest clickable ancestor: a label is frequently a
        // non-clickable child of the row that actually handles the tap.
        var target: AccessibilityNodeInfo? = match
        while (target != null && !target.isClickable) target = target.parent
        (target ?: match).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override suspend fun readFocusedField(): FocusedField? = withContext(Dispatchers.Main.immediate) {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@withContext null
        FocusedField(
            text = focus.text?.toString().orEmpty(),
            packageName = focus.packageName?.toString(),
            editable = focus.isEditable,
        )
    }

    override suspend fun setFocusedText(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: return@withContext false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override suspend fun globalKey(key: GlobalKey): Boolean {
        val action = when (key) {
            GlobalKey.BACK -> GLOBAL_ACTION_BACK
            GlobalKey.HOME -> GLOBAL_ACTION_HOME
            GlobalKey.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalKey.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            GlobalKey.POWER_DIALOG -> GLOBAL_ACTION_POWER_DIALOG
        }
        return performGlobalAction(action)
    }

    // Our sealed result is fully qualified here because the framework's nested
    // AccessibilityService.ScreenshotResult shadows the bare name inside this class body.
    override suspend fun screenshot(): dev.pleiades.masamune.operator.a11y.ScreenshotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return dev.pleiades.masamune.operator.a11y.ScreenshotResult.Unavailable(
                "Screenshot capture needs Android 11 (API 30); this device is API ${Build.VERSION.SDK_INT}.",
            )
        }
        val bitmap = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { takeScreenshotBitmap() }
            ?: return dev.pleiades.masamune.operator.a11y.ScreenshotResult.Unavailable(
                "The screenshot request timed out or returned no image.",
            )
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.size()
        val (w, h) = bitmap.width to bitmap.height
        bitmap.recycle()
        return dev.pleiades.masamune.operator.a11y.ScreenshotResult.Captured(width = w, height = h, pngBytes = bytes)
    }

    /** Bridge the API-30 callback screenshot API into a suspend call; null on any failure the framework signals. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult0) {
                    // Copy the hardware bitmap into a software one before releasing the buffer, so
                    // the later PNG encode reads valid pixels rather than a closed buffer.
                    val bitmap = result.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
                            val software = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            software
                        }
                    }
                    if (cont.isActive) cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, needle: String): Boolean {
        fun hit(s: CharSequence?) = s?.toString()?.contains(needle, ignoreCase = true) == true
        return hit(node.text) || hit(node.contentDescription) ||
            node.viewIdResourceName?.substringAfterLast('/')?.contains(needle, ignoreCase = true) == true
    }

    /** Depth-first search for the first node satisfying [predicate], bounded by [MAX_TREE_DEPTH]. */
    private fun findNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        if (depth >= MAX_TREE_DEPTH) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, depth + 1, predicate)?.let { return it }
        }
        return null
    }

    private companion object {
        /** A hard bound on tree walks so a pathological hierarchy cannot hang an observe or a search. */
        const val MAX_TREE_DEPTH = 60
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 600L
        const val MIN_GESTURE_MS = 20L
        const val MAX_GESTURE_MS = 5_000L
        const val SCREENSHOT_TIMEOUT_MS = 4_000L
    }
}

/** Aliases so the API-30 screenshot types read clearly above without colliding with our own [ScreenshotResult]. */
private typealias ScreenshotResult0 = AccessibilityService.ScreenshotResult
private typealias TakeScreenshotCallback = AccessibilityService.TakeScreenshotCallback
