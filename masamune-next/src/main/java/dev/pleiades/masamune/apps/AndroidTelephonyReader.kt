package dev.pleiades.masamune.apps

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real, device-backed [TelephonyReader] — the Android glue that turns the plain-data contract into
 * reads of `TelephonyManager` and `SubscriptionManager`.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit tests'
 * point of view: the blocks never see it, they see [TelephonyReader]. Keeping every framework call on this
 * side of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.TelephonyBlocks] stay JVM-testable
 * against a fake.
 *
 * ### STATE READS ONLY
 * There is deliberately no way to place/answer/end a call, send an SMS or USSD, dial, play a DTMF tone, or
 * set any preferred-network / default-subscription / subscription state from here. This class reads
 * unprivileged cellular *state* and nothing else; every mutating and messaging Telephony block is gated by
 * omission in [dev.pleiades.masamune.flow.runtime.impl.telephonyLookup] and has no glue here.
 *
 * ### Honest boundaries — a missing reading is `null`, never a fabricated state
 *  - **No `TelephonyManager`/`SubscriptionManager` is `null`, not a guess.** A device with no telephony
 *    stack returns `null`, which the block routes to a named Fail — never a fabricated "idle" / "not
 *    roaming" / "no operator".
 *  - **A permission-refused read is `null`.** Reads guarded by `READ_PHONE_STATE` (the call state on newer
 *    Android, the service state, the SIM-slot mapping) catch the `SecurityException` and return `null` —
 *    the block Fails "by name" on a missing grant rather than pretending to know the state.
 *  - **A real "no / absent" is the value, not `null`.** A device genuinely idle is [CallState.IDLE]; out of
 *    service is [MobileServiceState.OUT_OF_SERVICE]; not registered on any operator is
 *    [MobileOperator.NotRegistered]; not roaming is a real `false`. These are successful reads the block
 *    routes to NO, kept distinct from the unreadable `null`.
 */
class AndroidTelephonyReader(private val context: Context) : TelephonyReader {

    private val telephonyManager: TelephonyManager?
        get() = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val subscriptionManager: SubscriptionManager?
        get() = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    override suspend fun callState(): CallState? {
        val tm = telephonyManager ?: return null
        return try {
            @Suppress("DEPRECATION")
            when (tm.callState) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFF_HOOK
                else -> null
            }
        } catch (_: SecurityException) {
            null // READ_PHONE_STATE not granted on newer Android — honest null, the block Fails by name
        }
    }

    override suspend fun cellSignalLevel(): Int? {
        val tm = telephonyManager ?: return null
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            tm.signalStrength?.level
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun mobileOperator(): MobileOperator? {
        val tm = telephonyManager ?: return null
        return try {
            val name = tm.networkOperatorName?.takeIf { it.isNotBlank() }
            val code = tm.networkOperator?.takeIf { it.isNotBlank() }
            val country = tm.networkCountryIso?.takeIf { it.isNotBlank() }
            // An empty MCC+MNC and empty name mean the device is registered on no operator (no service).
            if (name == null && code == null && country == null) {
                MobileOperator.NotRegistered
            } else {
                MobileOperator.Registered(name = name, code = code, countryCode = country)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun mobileServiceState(): MobileServiceState? {
        val tm = telephonyManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            val state = tm.serviceState ?: return null
            when (state.state) {
                android.telephony.ServiceState.STATE_IN_SERVICE -> MobileServiceState.IN_SERVICE
                android.telephony.ServiceState.STATE_OUT_OF_SERVICE -> MobileServiceState.OUT_OF_SERVICE
                android.telephony.ServiceState.STATE_EMERGENCY_ONLY -> MobileServiceState.EMERGENCY_ONLY
                android.telephony.ServiceState.STATE_POWER_OFF -> MobileServiceState.POWER_OFF
                else -> null
            }
        } catch (_: SecurityException) {
            null // READ_PHONE_STATE not granted — honest null, the block Fails by name
        }
    }

    override suspend fun defaultSubscription(usage: SubscriptionUsage): SubscriptionRef? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext null
            val subId = when (usage) {
                SubscriptionUsage.GENERIC -> SubscriptionManager.getDefaultSubscriptionId()
                SubscriptionUsage.VOICE -> SubscriptionManager.getDefaultVoiceSubscriptionId()
                SubscriptionUsage.SMS -> SubscriptionManager.getDefaultSmsSubscriptionId()
                SubscriptionUsage.DATA -> SubscriptionManager.getDefaultDataSubscriptionId()
            }
            // INVALID_SUBSCRIPTION_ID means there is no valid default to report — an unreadable Fail.
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return@withContext null
            val slot = try {
                subscriptionManager?.getActiveSubscriptionInfo(subId)?.simSlotIndex
            } catch (_: SecurityException) {
                null // READ_PHONE_STATE not granted for the slot mapping — omit the slot, keep the id.
            }
            SubscriptionRef(subscriptionId = subId, simSlotIndex = slot)
        }

    override suspend fun isRoaming(): Boolean? {
        val tm = telephonyManager ?: return null
        return try {
            tm.isNetworkRoaming
        } catch (_: SecurityException) {
            null
        }
    }
}
