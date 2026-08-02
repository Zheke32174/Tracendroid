package dev.pleiades.masamune.operator.a11y

/**
 * The seam between the operator's action blocks and the real accessibility service.
 *
 * Every way the operator can *see* or *touch* the screen is one method here, and there is
 * deliberately nothing Android on the interface. Two things fall out of that. First, the
 * Interface-category block impls ([dev.pleiades.masamune.operator.OperatorBlocks]) depend on
 * this contract, not on `AccessibilityService`, so they and the whole observe→decide→act loop
 * are unit-testable on the JVM against a fake actuator — a device is needed to *run* the
 * operator, never to test its logic. Second, the honest gate has one clean shape: when the
 * service is off there is simply no actuator, and a block that cannot get one fails by naming
 * [dev.pleiades.masamune.flow.model.Requirement.Accessibility] rather than pretending to act.
 *
 * The concrete implementation is [MasamuneA11yService] itself; [A11yServiceHolder] hands out the
 * live one or null.
 */
interface ScreenActuator {

    /**
     * The current window as a compact node tree, or null if no window content is available.
     *
     * Null is a real answer — "the service is connected but there is nothing to read right now"
     * (a transition, a secure window) — kept distinct from the absent-service case, which never
     * reaches here because there is no actuator to call at all.
     */
    suspend fun dumpLayout(): SimplifiedNode?

    /** A single tap at a screen coordinate, dispatched as a gesture. */
    suspend fun tap(x: Int, y: Int): Boolean

    /** A long press at a screen coordinate. */
    suspend fun longPress(x: Int, y: Int): Boolean

    /** A straight-line swipe over [durationMs]. */
    suspend fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long): Boolean

    /**
     * Find the first node whose text, content-description or resource-id contains [query] and
     * perform its accessibility click action. This is the node-action path (not a coordinate
     * gesture): it clicks the element the framework reports as clickable, which is more robust
     * to layout shifts than a fixed point. Returns false when nothing matches.
     */
    suspend fun clickNodeMatching(query: String): Boolean

    /** Read the currently input-focused editable field, or null when none is focused. */
    suspend fun readFocusedField(): FocusedField?

    /**
     * Replace the input-focused field's text with [text] via `ACTION_SET_TEXT`. This is the
     * accessibility route Automate's `Key send characters` names; it lands text without an IME.
     * Returns false when no editable field holds input focus.
     */
    suspend fun setFocusedText(text: String): Boolean

    /** Perform one of the system-level global actions Automate's `Key send` maps to. */
    suspend fun globalKey(key: GlobalKey): Boolean

    /**
     * The screenshot hook. Pixels are for the user's audit trail or a future vision model — the
     * text-driven loop does not consume them — so this is a capability, honestly reported as
     * unavailable on API levels where the framework cannot provide it, never faked.
     */
    suspend fun screenshot(): ScreenshotResult
}

/** The subset of a focused text field the operator reasons about. */
data class FocusedField(
    val text: String,
    val packageName: String?,
    val editable: Boolean,
)

/**
 * The global actions reachable through `AccessibilityService.performGlobalAction`, named the way
 * Automate's `Key send` keycodes name them so the decide step's vocabulary maps one-to-one.
 */
enum class GlobalKey {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    POWER_DIALOG,
}

/** Outcome of the screenshot hook — a real result or an honest reason it is unavailable. */
sealed interface ScreenshotResult {
    /** Captured. [width]×[height] px, [pngBytes] the encoded size — enough to prove it was real. */
    data class Captured(val width: Int, val height: Int, val pngBytes: Int) : ScreenshotResult

    /** Not captured, with a sentence naming why (below API 30, capture returned null, timed out). */
    data class Unavailable(val reason: String) : ScreenshotResult
}
