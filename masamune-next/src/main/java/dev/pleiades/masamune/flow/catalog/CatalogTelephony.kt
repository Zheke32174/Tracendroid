package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.ProceedMode

/**
 * Calls, SIM subscriptions, cell towers and the mobile network.
 *
 * `Call incoming` and `Call outgoing` carry a bespoke option rather than a [ProceedMode] list:
 * Automate spends their Proceed option on *which stage of the call* to resume at - ringing,
 * answered, missed, hung up - which is an enumeration of events, not of tense, so it is
 * modelled as what it is.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val TELEPHONY_BLOCKS: List<BlockSpec> = category(BlockCategory.TELEPHONY) {
    action(
        "call_answer", "Call answer",
        "Answers an incoming (ringing) call. Needed the privileged service only on Android 5 " +
            "through 7.",
        requires = setOf(ANSWER_PHONE_CALLS),
    )
    action(
        "call_end", "Call end",
        "Ends an ongoing or ringing call.",
        requires = setOf(ANSWER_PHONE_CALLS),
    )
    action(
        "call_incoming", "Call incoming",
        "Awaits an incoming call.",
        options = listOf(INCOMING_CALL_STAGE),
        args = listOf(
            text("phoneNumber", "Phone number", "any"),
            any("subscriptionId", "Subscription id", "any"),
        ),
        outputs = listOf(
            out("varPhoneNumber", "Caller phone number"),
            out("varSubscriptionId", "Used subscription id"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    action(
        "call_number", "Call number",
        "Initiates a phone number without user interaction.",
        args = listOf(
            text("phoneNumber", "Phone number"),
            any("subscriptionId", "Subscription id", "the system default call subscription"),
            any("simSlotIndex", "SIM slot", "the slot of system default call subscription"),
            any("flags", "Flags"),
        ),
        requires = setOf(CALL_PHONE),
    )
    action(
        "call_outgoing", "Call outgoing",
        "Awaits an outgoing call.",
        options = listOf(OUTGOING_CALL_STAGE),
        args = listOf(
            text("phoneNumber", "Phone number", "any"),
            any("subscriptionId", "Subscription id", "any"),
        ),
        outputs = listOf(
            out("varPhoneNumber", "Called phone number"),
            out("varSubscriptionId", "Used subscription id"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    action(
        "call_screening", "Call screening",
        "Awaits an incoming call for screening.",
        args = listOf(
            text("phoneNumber", "Phone number", "any"),
            any("verificationStatus", "Verification status", "any"),
        ),
        outputs = listOf(
            out("varPhoneNumber", "Caller phone number"),
            out("varVerificationStatus", "Caller verification status"),
        ),
    )
    action(
        "call_screening_response", "Call screening response",
        "Responds to a call being screened, letting it through or rejecting it.",
        args = listOf(
            flag("action", "Action", "Allow"),
            flag("silence", "Silence", "don't silence"),
        ),
    )
    decision(
        "call_state", "Call state",
        "Checks the phone call state.",
        proceed = WATCH,
        args = listOf(
            any("state", "State", "Idle"),
            any("subscriptionId", "Subscription id", "the system default call subscription"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    decision(
        "cell_signal_level", "Cell signal strength",
        "Checks the cellular signal strength.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum signal strength"),
            num("maxLevel", "Maximum signal strength"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
        ),
        outputs = listOf(
            out("varLevel", "Current signal strength"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    decision(
        "cell_site_near", "Cell tower near",
        "Checks nearby cellular towers.",
        proceed = WATCH,
        args = listOf(
            any("matchCells", "Cell towers"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
            any("connectionStatus", "Connection status", "any, including unknown, status"),
        ),
        outputs = listOf(
            out("varNearbyCells", "Nearby cell towers"),
            out("varCellRssis", "Signal strengths"),
        ),
        requires = setOf(ACCESS_FINE_LOCATION),
    )
    decision(
        "cell_site_pick", "Cell tower pick",
        "Lets the user choose nearby cellular towers.",
        args = listOf(
            any("initialCells", "Initial cell towers", "none"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varPickedCells", "Picked cell towers"),
        ),
        requires = setOf(ACCESS_FINE_LOCATION),
    )
    action(
        "dial_number", "Dial number",
        "Only enters a phone number into the Phone app without initiating the call.",
        args = listOf(
            text("phoneNumber", "Phone number"),
            any("subscriptionId", "Subscription id", "the system default call subscription"),
            any("simSlotIndex", "SIM slot", "the slot of system default call subscription"),
        ),
    )
    action(
        "dtmf_tone_play", "DTMF tone play",
        "Plays a DTMF tone in an ongoing call.",
        proceed = AWAIT,
        args = listOf(
            any("tone", "Tone", "DTMF * key"),
            num("duration", "Duration", "until stopped"),
        ),
    )
    action(
        "dtmf_tone_stop", "DTMF tone stop",
        "Stops any ongoing DTMF tone playback started by the DTMF tone play block.",
    )
    decision(
        "mobile_network_preferred", "Mobile network preferred",
        "Checks the currently preferred mobile network.",
        args = listOf(
            any("networkType", "Mobile network", "any"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
        ),
        outputs = listOf(
            out("varCurrentNetworkType", "Current mobile network"),
        ),
        requires = setOf(SHELL, READ_PHONE_STATE),
    )
    action(
        "mobile_network_preferred_set", "Mobile network preferred set",
        "Sets the preferred mobile network.",
        args = listOf(
            any("networkType", "Mobile network", "device/subscription default network type"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
        ),
        requires = setOf(SHELL, READ_PHONE_STATE),
    )
    decision(
        "mobile_operator", "Mobile operator",
        "Checks the mobile network operator.",
        proceed = WATCH,
        args = listOf(
            text("operatorName", "Operator name"),
            any("operatorCode", "Operator code"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
        ),
        outputs = listOf(
            out("varCurrentOperatorName", "Current operator name"),
            out("varCurrentOperatorCode", "Current operator code"),
            out("varCurrentOperatorCountryCode", "Current operator country code"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    decision(
        "mobile_service_state", "Mobile service state",
        "Checks the mobile service (SIM) state.",
        proceed = WATCH,
        args = listOf(
            any("serviceStates", "Mobile service states", "none"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
        ),
        outputs = listOf(
            out("varCurrentServiceState", "Current mobile server state"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    action(
        "subscription_default_get", "Subscription default get",
        "Gets the default SIM card/subscription for a particular usage.",
        proceed = WATCH_VALUE,
        args = listOf(
            any("usage", "Usage", "Generic"),
        ),
        outputs = listOf(
            out("varSubscriptionId", "Subscription id"),
            out("varSimSlotIndex", "SIM slot"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    action(
        "subscription_default_set", "Subscription default set",
        "Sets the default SIM card/subscription for a particular usage.",
        args = listOf(
            any("usage", "Usage", "Voice"),
            any("subscriptionId", "Subscription id", "the current default"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "subscription_pick", "Subscription pick",
        "Lets the user choose a SIM card/subscription.",
        args = listOf(
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varSubscriptionId", "Picked subscription id"),
            out("varSimSlotIndex", "Picked SIM slot"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    action(
        "subscription_set_state", "Subscription set state",
        "Enables or disable a SIM/carrier subscription.",
        args = listOf(
            flag("state", "Subscription state"),
            any("subscriptionId", "Subscription id", "system default subscription"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "ringer_silence", "Ringer silence",
        "Silences the \"ringer\" (ringtone) and stop vibrate if a call is ringing.",
    )
    decision(
        "roaming", "Roaming",
        "Checks if the device is roaming.",
        proceed = WATCH,
        args = listOf(
            any("subscriptionId", "Subscription id", "the system default subscription"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    action(
        "ussd_request", "USSD request",
        "Sends a USSD message to the mobile network operator and await the response.",
        args = listOf(
            text("request", "USSD request"),
            any("subscriptionId", "Subscription id", "the system default call subscription"),
        ),
        outputs = listOf(
            out("varResponse", "Response"),
        ),
        requires = setOf(CALL_PHONE),
    )
}
