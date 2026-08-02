package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.CallState
import dev.pleiades.masamune.apps.MobileOperator
import dev.pleiades.masamune.apps.MobileServiceState
import dev.pleiades.masamune.apps.SubscriptionUsage
import dev.pleiades.masamune.apps.TelephonyReader
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The Telephony category's **unprivileged one-shot read/decision** slice — the organ an AI phone operator
 * needs to know the device's cellular state right now: whether a call is idle/ringing/off-hook, how strong
 * the cellular signal is, which mobile operator the device is on and in what country, whether there is
 * mobile service, which SIM/subscription is the default for a usage, and whether the device is roaming.
 *
 * ### Why this subset and not the whole (large) category
 * `CatalogTelephony` mixes a few unprivileged state reads with the category's real weight — *placing,
 * answering and ending calls*, *sending SMS and USSD*, *dialing*, *playing DTMF tones*, *awaiting* incoming
 * and outgoing calls, privileged `SHELL` reads/writes, *setting* the preferred network / default
 * subscription / subscription state, and cell-tower/subscription *scans and pickers*. Only the read/state
 * subset can be expressed through the read-only [TelephonyReader] seam, and only those run here:
 *  - **Registered (6):** `call_state`, `cell_signal_level`, `mobile_operator`, `mobile_service_state`,
 *    `subscription_default_get` and `roaming`. Each is a single read or a single computed decision over
 *    device state.
 *  - Everything else — every `call_*` place/answer/end/screen ACTION, `dial_number`, `dtmf_tone_*`,
 *    `ussd_request`, the AWAIT triggers (`call_incoming`, `call_outgoing`, `call_screening`), every SET
 *    (`mobile_network_preferred_set`, `subscription_default_set`, `subscription_set_state`), the `SHELL`
 *    reads/writes, `ringer_silence`, and the cell-tower/subscription scans and pickers (`cell_site_near`,
 *    `cell_site_pick`, `subscription_pick`) — is gated by omission (see [telephonyLookup]).
 *
 * ### The seam, copied from the Apps, Settings, Battery&Power, Sensor, Location and Connectivity blocks
 * Every device call lives behind the injected [TelephonyReader] — a narrow, `android.*`-free contract, the
 * exact shape [dev.pleiades.masamune.apps.AppInspector], [dev.pleiades.masamune.apps.SystemSettings],
 * [dev.pleiades.masamune.apps.PowerState], [dev.pleiades.masamune.apps.SensorReader],
 * [dev.pleiades.masamune.apps.LocationReader] and [dev.pleiades.masamune.apps.ConnectivityReader] give their
 * categories. Two consequences, both deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole file
 *     is unit-testable against a fake on an ordinary JVM — a device is needed to run these, never to test
 *     their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [TelephonyReader] provider and fails with
 *     [TELEPHONY_ABSENT] when there is no seam (the app process is not wired in, or it dropped mid-run). A
 *     read that returns `null` becomes a named [Outcome.Fail] ("could not be read") — **never** a fabricated
 *     `false`/`0`/empty operator or a silent NO. A real "not roaming" / [MobileServiceState.OUT_OF_SERVICE]
 *     / [MobileOperator.NotRegistered] is a successful read routed to NO; only an unreadable state Fails.
 *
 * ### WATCH / WATCH_VALUE collapse to their one-shot form
 * The catalog marks these decisions WATCH-capable and `subscription_default_get` WATCH_VALUE (test/read
 * now, or suspend until the state changes). The watching form needs the monitor subsystem this build does
 * not have, so the one-shot condition — "is the call idle *now*", "is the device roaming *now*", "what is
 * the default subscription *now*" — is what runs, which is exactly what a decision or getter in a running
 * flow evaluates. This mirrors the Connectivity reads in [BooleanStateDecisionBlock], the Sensor bands in
 * [ScalarBandSensorBlock] and the Location reads in [LocationProviderEnabledBlock].
 *
 * ### The `subscriptionId` filter and the `serviceStates` multi-select are not modelled
 * Every registered block carries a `subscriptionId` argument selecting *which* SIM to read, and
 * `mobile_service_state` carries a `serviceStates` multi-select of which states count as a match. This
 * bounded one-shot slice does not reconstruct multi-SIM selection or multi-select match semantics: each
 * block reads and reports the device's **default-subscription** cellular state and routes on it — the
 * honest "what is true on the default SIM now", never a fabricated match. This is the same honest
 * simplification by which the Connectivity blocks ignore `networkTypes`/`subscriptionId`: an argument with
 * no faithful one-shot meaning is documented as ignored rather than guessed.
 *
 * The composition helper [telephonyLookup] mirrors [connectivityLookup], [locationLookup], [sensorLookup],
 * [powerLookup], [settingsLookup] and [appsLookup]: it returns the impls keyed by spec id so a caller
 * composes `telephonyLookup(provider)[id] ?: base.lookup(id)`.
 */

/** The sentence shown whenever a Telephony block cannot reach a telephony seam. Modelled on [CONNECTIVITY_ABSENT]. */
internal val TELEPHONY_ABSENT: String =
    "This telephony block cannot act: no telephony seam is available, so Masamune cannot read the " +
        "device's call state, signal, operator, service state, subscriptions or roaming. The seam is " +
        "wired only inside the Android app process; when it is absent the block fails by name rather than " +
        "reporting a cellular state that never was read."

// --------------------------------------------------------------------------- call state

/**
 * `call_state` (Call state) — is the device's call state the requested one right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It parses the `state` argument (default
 * Idle) to a [CallState], reads the device's actual call state through the seam, and routes YES when they
 * match, NO otherwise. A state the seam cannot read (no `TelephonyManager`, or the `READ_PHONE_STATE` grant
 * the newer API needs is absent) Fails **by name**, never a silent NO. An unrecognized `state` string Fails
 * by name, exactly as `location_get` fails on an unrecognized provider. The `subscriptionId` filter is not
 * modelled (see file KDoc) — the block reads the default subscription's call state.
 *
 * Carries `READ_PHONE_STATE` in the catalog; that is honored at run by the seam returning `null` (→ a named
 * Fail), not by leaving the block unregistered — the whole category assumes the app may hold it.
 */
internal class CallStateBlock(
    private val telephonyProvider: () -> TelephonyReader?,
) : BlockImpl {
    override val specId = "call_state"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = telephonyProvider() ?: return Outcome.Fail(TELEPHONY_ABSENT)
        val target = args["state"].asCallStateOrDefault(CallState.IDLE)
            ?: return Outcome.Fail("call_state: unrecognized call state (expected idle/ringing/off-hook).")
        val actual = reader.callState()
            ?: return Outcome.Fail("call_state: the call state could not be read.")
        return Outcome.Proceed(if (actual == target) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- cell signal band

/**
 * `cell_signal_level` (Cell signal strength) — is the cellular signal within the requested band?
 *
 * DECISION: the one-shot form of the catalog's WATCH band decision, and the direct analogue of the
 * Connectivity `wifi_signal_level` and the Sensor scalar bands. It reads the 0..4 signal level through the
 * seam, **always** binds `varLevel` from it, and routes YES when the level sits within `[minLevel, maxLevel]`
 * (an unset bound is no constraint), NO otherwise. A level the seam cannot read (no signal, or unreadable)
 * Fails **by name**, never a fabricated `0` or a silent NO. The `subscriptionId` filter is not modelled
 * (see file KDoc).
 */
internal class CellSignalLevelBlock(
    private val telephonyProvider: () -> TelephonyReader?,
) : BlockImpl {
    override val specId = "cell_signal_level"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = telephonyProvider() ?: return Outcome.Fail(TELEPHONY_ABSENT)
        val level = reader.cellSignalLevel()
            ?: return Outcome.Fail("cell_signal_level: the cellular signal strength could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.bind(writes, Value.Num(level.toDouble()))
        val min = args["minLevel"].asNumOrNull()
        val max = args["maxLevel"].asNumOrNull()
        val within = (min == null || level >= min) && (max == null || level <= max)
        return Outcome.Proceed(if (within) Port.YES else Port.NO, writes)
    }
}

// --------------------------------------------------------------------------- mobile operator

/**
 * `mobile_operator` (Mobile operator) — is the device on a matching mobile operator right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the operator registration through
 * the seam; a [MobileOperator.Registered] binds every present operator field
 * (`varCurrentOperatorName`/`Code`/`CountryCode`) and then routes YES when the block's `operatorName` /
 * `operatorCode` filters match (an unset filter is no constraint), NO otherwise; a
 * [MobileOperator.NotRegistered] (no service / no SIM) routes NO; and a `null` (the operator could not be
 * read) Fails **by name**. The current operator is bound regardless of the YES/NO branch — the outputs
 * report what is current, exactly as `cell_signal_level` binds `varLevel` on both branches. The filters
 * compare case-insensitively. The `subscriptionId` filter is not modelled (see file KDoc).
 *
 * Carries `READ_PHONE_STATE` in the catalog — honored at run by the seam's `null` (→ named Fail), not by
 * omission.
 */
internal class MobileOperatorBlock(
    private val telephonyProvider: () -> TelephonyReader?,
) : BlockImpl {
    override val specId = "mobile_operator"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = telephonyProvider() ?: return Outcome.Fail(TELEPHONY_ABSENT)
        val operator = reader.mobileOperator()
            ?: return Outcome.Fail("mobile_operator: the mobile operator could not be read.")
        return when (operator) {
            is MobileOperator.NotRegistered -> Outcome.Proceed(Port.NO)
            is MobileOperator.Registered -> {
                val writes = LinkedHashMap<String, Value>()
                operator.name?.let { node.outputs["varCurrentOperatorName"]?.bind(writes, Value.Text(it)) }
                operator.code?.let { node.outputs["varCurrentOperatorCode"]?.bind(writes, Value.Text(it)) }
                operator.countryCode?.let {
                    node.outputs["varCurrentOperatorCountryCode"]?.bind(writes, Value.Text(it))
                }
                val wantName = args["operatorName"].asTextOrNull()?.takeIf { it.isNotBlank() }
                val wantCode = args["operatorCode"].asTextOrNull()?.takeIf { it.isNotBlank() }
                val match = (wantName == null || operator.name.equalsIgnoreCase(wantName)) &&
                    (wantCode == null || operator.code.equalsIgnoreCase(wantCode))
                Outcome.Proceed(if (match) Port.YES else Port.NO, writes)
            }
        }
    }
}

// --------------------------------------------------------------------------- mobile service (SIM) state

/**
 * `mobile_service_state` (Mobile service state) — is there mobile service right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the mobile service (SIM) state
 * through the seam, **always** binds `varCurrentServiceState` from it, and routes YES when the device is
 * [MobileServiceState.IN_SERVICE], NO for out-of-service / emergency-only / power-off. A state the seam
 * cannot read (no `TelephonyManager`, or the `READ_PHONE_STATE` grant is absent) Fails **by name**, never a
 * silent NO. The `serviceStates` multi-select is not modelled (see file KDoc) — the block reports the real
 * state and routes on whether the device has service.
 *
 * Carries `READ_PHONE_STATE` in the catalog — honored at run by the seam's `null` (→ named Fail).
 */
internal class MobileServiceStateBlock(
    private val telephonyProvider: () -> TelephonyReader?,
) : BlockImpl {
    override val specId = "mobile_service_state"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = telephonyProvider() ?: return Outcome.Fail(TELEPHONY_ABSENT)
        val state = reader.mobileServiceState()
            ?: return Outcome.Fail("mobile_service_state: the mobile service state could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varCurrentServiceState"]?.bind(writes, Value.Text(state.label))
        val inService = state == MobileServiceState.IN_SERVICE
        return Outcome.Proceed(if (inService) Port.YES else Port.NO, writes)
    }
}

// --------------------------------------------------------------------------- default subscription getter

/**
 * `subscription_default_get` (Subscription default get) — read the default SIM/subscription for a usage.
 *
 * ACTION: the one-shot form of the catalog's WATCH_VALUE getter. It parses `usage` (default Generic), reads
 * the default subscription through the seam, binds `varSubscriptionId` (always present in a real ref) and
 * `varSimSlotIndex` (only when the slot mapping is readable), then leaves by OK. A default subscription the
 * seam cannot read (no `SubscriptionManager`, no valid default, or the `READ_PHONE_STATE` grant the slot
 * mapping needs is absent) Fails **by name**, never a fabricated `-1` id or `0` slot. An unrecognized
 * `usage` string Fails by name, exactly as `location_get` does on an unrecognized provider.
 *
 * Carries `READ_PHONE_STATE` in the catalog — honored at run by the seam's `null` (→ named Fail).
 */
internal class SubscriptionDefaultGetBlock(
    private val telephonyProvider: () -> TelephonyReader?,
) : BlockImpl {
    override val specId = "subscription_default_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = telephonyProvider() ?: return Outcome.Fail(TELEPHONY_ABSENT)
        val usage = args["usage"].asUsageOrDefault(SubscriptionUsage.GENERIC)
            ?: return Outcome.Fail("subscription_default_get: unrecognized usage (expected generic/voice/sms/data).")
        val ref = reader.defaultSubscription(usage)
            ?: return Outcome.Fail("subscription_default_get: the default subscription could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varSubscriptionId"]?.bind(writes, Value.Num(ref.subscriptionId.toDouble()))
        ref.simSlotIndex?.let { node.outputs["varSimSlotIndex"]?.bind(writes, Value.Num(it.toDouble())) }
        return Outcome.Proceed(Port.OK, writes)
    }
}

// --------------------------------------------------------------------------- roaming

/**
 * `roaming` (Roaming) — is the device roaming right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision, and the direct analogue of the Connectivity
 * boolean is-enabled decisions. It reads the roaming state through the seam and routes YES when roaming, NO
 * when not. A `false` is a *real* "not roaming" routed to NO; only a `null` (no `TelephonyManager`) Fails
 * **by name** — never a fabricated `false` a downstream block would mistake for a real "not roaming". The
 * `subscriptionId` filter is not modelled (see file KDoc).
 *
 * Carries `READ_PHONE_STATE` in the catalog — honored at run by the seam's `null` (→ named Fail).
 */
internal class RoamingBlock(
    private val telephonyProvider: () -> TelephonyReader?,
) : BlockImpl {
    override val specId = "roaming"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = telephonyProvider() ?: return Outcome.Fail(TELEPHONY_ABSENT)
        val roaming = reader.isRoaming()
            ?: return Outcome.Fail("roaming: the roaming state could not be read.")
        return Outcome.Proceed(if (roaming) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The six registered Telephony one-shot impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [connectivityLookup], [locationLookup], [sensorLookup], [powerLookup], [settingsLookup] and
 * [appsLookup]: it always returns the map, and the honest gate is the per-block gate-at-run (each fails with
 * [TELEPHONY_ABSENT] when the provider yields no seam), so a caller composes over its base registry exactly
 * as the other categories do:
 *
 * ```
 * val telephony = telephonyLookup(telephonyReader)
 * fun lookup(id: String): BlockImpl? =
 *     telephony[id] ?: connectivity[id] ?: location[id] ?: … ?: base.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's many remaining blocks are deliberately **not** here, so at run time the scheduler finds no
 * impl and gates them by the honest-by-omission mechanism the catalog's own `requires` set (or the block's
 * own shape) expresses. Because the [TelephonyReader] seam is read-only, every gated block is a *call*, an
 * *SMS/USSD send*, a *dial*, a *DTMF tone*, an *await*, a *write*, a `SHELL`-gated read/write, or a
 * *scan/picker* — none of which a read-only seam can host, so there is nothing to build-but-not-register
 * here. They are omitted on these honest grounds, grouped:
 *  - **Place / answer / end / screen a call (actions).** `call_answer` (ANSWER_PHONE_CALLS), `call_end`
 *    (ANSWER_PHONE_CALLS), `call_number` (CALL_PHONE), `call_screening_response`, `ringer_silence`,
 *    `dtmf_tone_play` (AWAIT) and `dtmf_tone_stop` place, control or terminate a live call — mutations a
 *    read-only seam cannot honestly model, exactly as the Connectivity radio toggles are gated.
 *  - **Dial (action).** `dial_number` enters a number into the Phone app — a UI action, not a state read.
 *  - **Send SMS / USSD (sensitive actions).** `ussd_request` (CALL_PHONE) sends a USSD request to the
 *    operator. No `sms_*`/`mms_*` block is implemented at all in this slice — message sending is sensitive
 *    and is left entirely gated by omission.
 *  - **Await an incoming/outgoing call (triggers).** `call_incoming` (READ_PHONE_STATE, AWAIT),
 *    `call_outgoing` (READ_PHONE_STATE, AWAIT) and `call_screening` (AWAIT) suspend until a call event —
 *    over-time awaits the missing monitor subsystem cannot run, not one-shot reads.
 *  - **Set state (writes).** `mobile_network_preferred_set` (SHELL, READ_PHONE_STATE),
 *    `subscription_default_set` (SHELL) and `subscription_set_state` (SHELL) mutate the preferred network,
 *    default subscription or subscription enablement — privileged writes a read-only seam cannot serve.
 *  - **`SHELL`-gated read.** `mobile_network_preferred` (SHELL, READ_PHONE_STATE) reads the preferred
 *    network through a privileged shell — a read an unprivileged read-only seam cannot honestly serve,
 *    gated exactly as the Connectivity/Battery&Power SHELL reads are.
 *  - **Scans and pickers (location / UI).** `cell_site_near` (ACCESS_FINE_LOCATION) scans nearby cell
 *    towers, and `cell_site_pick` (ACCESS_FINE_LOCATION) and `subscription_pick` (READ_PHONE_STATE) drive
 *    user-facing pickers — none is a one-shot state read.
 */
fun telephonyLookup(provider: () -> TelephonyReader?): Map<String, BlockImpl> = listOf(
    CallStateBlock(provider),
    CellSignalLevelBlock(provider),
    MobileOperatorBlock(provider),
    MobileServiceStateBlock(provider),
    SubscriptionDefaultGetBlock(provider),
    RoamingBlock(provider),
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

/** Case-insensitive equality that treats a null receiver as "no value to match" (never equal). */
private fun String?.equalsIgnoreCase(other: String): Boolean = this != null && this.equals(other, ignoreCase = true)

/**
 * A `state` argument parsed to a [CallState]: [default] when blank/absent, the named/numeric state when
 * recognized (Automate's own `CALL_STATE_*` constants, 0/1/2, plus the obvious names), or `null` when a
 * non-blank value names no known state — which the caller turns into a visible Fail, never a silent default.
 */
private fun Value?.asCallStateOrDefault(default: CallState): CallState? {
    val text = this.asTextOrNull()?.trim()
    if (text.isNullOrEmpty()) return default
    return when (text.lowercase()) {
        "idle", "0" -> CallState.IDLE
        "ringing", "1" -> CallState.RINGING
        "off-hook", "off_hook", "offhook", "off hook", "2" -> CallState.OFF_HOOK
        else -> null
    }
}

/**
 * A `usage` argument parsed to a [SubscriptionUsage]: [default] when blank/absent, the named usage when
 * recognized, or `null` when a non-blank value names no known usage — which the caller turns into a visible
 * Fail, never a silent default.
 */
private fun Value?.asUsageOrDefault(default: SubscriptionUsage): SubscriptionUsage? {
    val text = this.asTextOrNull()?.trim()
    if (text.isNullOrEmpty()) return default
    return when (text.lowercase()) {
        "generic" -> SubscriptionUsage.GENERIC
        "voice" -> SubscriptionUsage.VOICE
        "sms" -> SubscriptionUsage.SMS
        "data" -> SubscriptionUsage.DATA
        else -> null
    }
}
