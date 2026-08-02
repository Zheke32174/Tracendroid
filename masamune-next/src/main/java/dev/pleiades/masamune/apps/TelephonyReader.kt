package dev.pleiades.masamune.apps

/**
 * The seam between the Telephony-category block impls and the real device radio/SIM/telephony stack.
 *
 * Every way an unprivileged, one-shot Telephony block can *read* the device's cellular state — what the
 * current call state is, how strong the cellular signal is, which mobile operator the device is registered
 * on, what the mobile service (SIM) state is, which SIM/subscription is the default for a usage, and
 * whether the device is roaming — is one method here, and — exactly like [AppInspector] does for the Apps
 * blocks, [SystemSettings] for the Settings blocks, [PowerState] for the Battery&Power blocks,
 * [SensorReader] for the Sensor blocks, [LocationReader] for the Location blocks and [ConnectivityReader]
 * for the Connectivity blocks — there is deliberately nothing `android.*` on this interface. That single
 * constraint is what buys the whole slice its JVM-testability:
 * [dev.pleiades.masamune.flow.runtime.impl.TelephonyBlocks] depend on this plain-data contract, never on
 * `TelephonyManager` or `SubscriptionManager`, so every block and all its branch logic can be exercised
 * against a fake on an ordinary unit-test JVM. A device is needed to *run* these blocks, never to *test*
 * their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidTelephonyReader]) is not wired in,
 * there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.TELEPHONY_ABSENT]) rather than reporting a cellular state it
 * never actually read.
 *
 * ### Honest failure shapes: not-readable vs. a real "no / absent" state
 * The reads model two genuinely different "negative" cases, and keeping them distinct is the whole point
 * of the honest-gating rule:
 *  - **`null` means "could not be read".** There is no `TelephonyManager` on the device, or the read needs
 *    a runtime permission the process was not granted (the real impl catches the `SecurityException` and
 *    returns `null`). The block routes `null` to a visible [dev.pleiades.masamune.flow.runtime.Outcome.Fail]
 *    **by name** — it never fabricates a `false`/`0`/an empty operator a downstream block would trust as a
 *    real reading.
 *  - **A real "no operator" / [MobileServiceState.OUT_OF_SERVICE] / `isRoaming == false` means "read fine,
 *    and the answer is off / not registered".** A device that is genuinely out of service, or not roaming,
 *    is a successful read with a NO answer, distinct from a device whose state could not be determined at
 *    all. A `Boolean?` here is a real three-valued read: `false` routes NO, `null` routes a named Fail;
 *    [MobileOperator.NotRegistered] is a real NO, [MobileOperator]'s method returning `null` is a Fail.
 *
 * ### Runtime permissions shape the *run-time* failure, never keep a read unregistered
 * Several of these reads carry the ordinary (if dangerous) `READ_PHONE_STATE` runtime permission the whole
 * Telephony category is predicated on. Exactly as `location_get`/`location_at` are registered despite
 * carrying `ACCESS_FINE_LOCATION`, and as Connectivity's `mobile_data_network_type` is registered despite
 * carrying `READ_PHONE_STATE`, these reads *are* registered: the honest gate for a missing grant is the
 * seam returning `null` and the block failing **by name**, not a fabricated value and not leaving the block
 * unregistered. A `Requirement.Uid2000` (SHELL) tag is the opposite — those blocks (the preferred-network
 * read/write, the subscription default/enable writes) have no method here and are gated by omission (see
 * [dev.pleiades.masamune.flow.runtime.impl.telephonyLookup]'s KDoc), exactly as the SHELL blocks are gated
 * in the Connectivity and Battery&Power slices.
 *
 * This slice is entirely read-only: everything it can touch is unprivileged cellular *state*, so there is
 * no write, no call, no SMS and no USSD result-type here. Every catalog block that *places/answers/ends a
 * call*, *sends an SMS or a USSD request*, *dials a number*, *plays a DTMF tone*, *sets* a preferred
 * network / default subscription / subscription state, *awaits* an incoming/outgoing call, or *scans/picks*
 * cell towers or subscriptions has no method here and is gated by omission — a read-only seam does not place
 * calls or send messages.
 *
 * Every method is `suspend` because a telephony read can touch a blocking system service; the real impl
 * does so off the caller's thread without the contract changing shape, and the fake simply returns.
 */
interface TelephonyReader {

    /**
     * The device's current call state — [CallState.IDLE] (no call), [CallState.RINGING] (an incoming call
     * is ringing) or [CallState.OFF_HOOK] (a call is dialing or connected) — or `null` when it cannot be
     * read (no `TelephonyManager`, or the `READ_PHONE_STATE` grant the newer API needs is absent). A real
     * state routes the `call_state` decision YES/NO against the requested state; `null` routes a named Fail.
     */
    suspend fun callState(): CallState?

    /**
     * The current cellular signal strength as the 0..4 band level the `cell_signal_level` band compares, or
     * `null` when there is no signal to read or the level cannot be determined. `null` routes a named Fail;
     * a real level is bound to `varLevel` and compared against the requested band.
     */
    suspend fun cellSignalLevel(): Int?

    /**
     * The mobile operator the device is registered on: [MobileOperator.Registered] with the readable
     * operator fields, [MobileOperator.NotRegistered] when the device is not registered on any operator
     * (no service / no SIM), or `null` when the operator cannot be read.
     *
     * Three-valued so "registered on this operator" is YES, "not registered on any operator" is NO, and
     * "cannot read the operator" is a named Fail — distinctions a plain nullable info object could not carry.
     */
    suspend fun mobileOperator(): MobileOperator?

    /**
     * The device's mobile service (SIM) state — [MobileServiceState.IN_SERVICE],
     * [MobileServiceState.OUT_OF_SERVICE], [MobileServiceState.EMERGENCY_ONLY] or
     * [MobileServiceState.POWER_OFF] — or `null` when it cannot be read (no `TelephonyManager`, or the
     * `READ_PHONE_STATE` grant is absent). A real out-of-service/power-off is a successful NO read; `null`
     * routes a named Fail.
     */
    suspend fun mobileServiceState(): MobileServiceState?

    /**
     * The default SIM/subscription for [usage], as a plain [SubscriptionRef], or `null` when there is no
     * valid default subscription to report or the mapping cannot be read (no `SubscriptionManager`, or the
     * `READ_PHONE_STATE` grant needed for the SIM-slot mapping is absent). `null` routes a named Fail; a
     * real ref binds `varSubscriptionId` (and `varSimSlotIndex` when the slot is readable).
     */
    suspend fun defaultSubscription(usage: SubscriptionUsage): SubscriptionRef?

    /**
     * Whether the device is currently roaming on the default subscription, or `null` when there is no
     * `TelephonyManager` to ask. `false` is a real "not roaming" (NO); `null` is "could not read" (a named
     * Fail).
     */
    suspend fun isRoaming(): Boolean?
}

/**
 * The device's current call state, as plain data — a real enum rather than a leaked
 * `TelephonyManager.CALL_STATE_*` int. The mapping from an Android call-state constant to this enum lives
 * entirely in [AndroidTelephonyReader], so nothing `android.*` crosses the seam. The [label] is what the
 * `call_state` decision reports and what its `state` argument is matched against.
 */
enum class CallState(val label: String) {
    IDLE("Idle"),
    RINGING("Ringing"),
    OFF_HOOK("Off-hook"),
}

/**
 * The device's mobile service (SIM) state, as plain data — a real enum rather than a leaked
 * `ServiceState.STATE_*` int. The mapping lives entirely in [AndroidTelephonyReader]. [IN_SERVICE] is the
 * `mobile_service_state` decision's YES; every other state is a real, readable NO (distinct from the seam
 * returning `null`, which is a named Fail).
 */
enum class MobileServiceState(val label: String) {
    IN_SERVICE("In service"),
    OUT_OF_SERVICE("Out of service"),
    EMERGENCY_ONLY("Emergency only"),
    POWER_OFF("Power off"),
}

/**
 * The device's mobile-operator registration state that the seam *could* read. Distinct from the seam
 * method's `null` (the operator could not be read at all), so the `mobile_operator` decision tells "not
 * registered on any operator" (NO) apart from "cannot read" (a named Fail).
 */
sealed interface MobileOperator {
    /**
     * The device is registered on an operator — the decision's YES/NO-by-filter branch, carrying the
     * readable operator fields. Every field is nullable because a real registration fills only the fields
     * the platform exposes; the block binds each present field and leaves the absent ones **unbound**
     * rather than a fabricated blank — never an empty operator name a flow would treat as real.
     *
     *  - [name] — the registered operator's name (`varCurrentOperatorName`).
     *  - [code] — the operator's MCC+MNC numeric code (`varCurrentOperatorCode`).
     *  - [countryCode] — the operator's ISO country code (`varCurrentOperatorCountryCode`).
     */
    data class Registered(
        val name: String? = null,
        val code: String? = null,
        val countryCode: String? = null,
    ) : MobileOperator

    /** The device is not registered on any operator (no service / no SIM) — the decision's NO branch. */
    data object NotRegistered : MobileOperator
}

/**
 * Which system usage a `subscription_default_get` read asks the default subscription for, as plain data — a
 * real enum rather than a leaked `SubscriptionManager` usage constant. The mapping lives entirely in
 * [AndroidTelephonyReader]. The [label] is how the block's `usage` argument names the choice.
 */
enum class SubscriptionUsage(val label: String) {
    GENERIC("Generic"),
    VOICE("Voice"),
    SMS("SMS"),
    DATA("Data"),
}

/**
 * A default SIM/subscription, reduced to the values the catalog's `subscription_default_get` outputs use,
 * as plain data.
 *
 *  - [subscriptionId] — the subscription's id (`varSubscriptionId`), always present in a real ref.
 *  - [simSlotIndex] — the SIM slot the subscription occupies (`varSimSlotIndex`), or `null` when the slot
 *    mapping cannot be read; the block binds it only when present, never a fabricated slot.
 */
data class SubscriptionRef(
    val subscriptionId: Int,
    val simSlotIndex: Int? = null,
)
