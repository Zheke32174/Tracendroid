package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.ConnectivityReader
import dev.pleiades.masamune.apps.MobileNetworkGeneration
import dev.pleiades.masamune.apps.NetworkStatus
import dev.pleiades.masamune.apps.WifiConnection
import dev.pleiades.masamune.apps.WifiConnectionInfo
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The Connectivity category's **unprivileged one-shot read/decision** slice — the organ an AI phone
 * operator needs to know how the device is connected right now: whether each radio (Wi-Fi, Bluetooth,
 * NFC, mobile data) is on, whether airplane mode is engaged, whether an internet network is up and of
 * what type, which Wi-Fi network is joined and how strong its signal, and which Bluetooth devices are
 * connected.
 *
 * ### Why this subset and not the whole (large) category
 * `CatalogConnectivity` is one of the biggest categories, and it mixes unprivileged state reads with
 * radio *writes*, device *connects*, *scans*, interactive *pickers*, network *actions* and privileged
 * `SHELL` reads/writes. Only the read/state subset can be expressed through the read-only
 * [ConnectivityReader] seam, and only those run here:
 *  - **Registered (12):** `airplane_mode_enabled`, `wifi_enabled`, `wifi_ap_enabled`,
 *    `wifi_network_connected`, `wifi_signal_level`, `bluetooth_enabled`, `bluetooth_device_connected`,
 *    `nfc_enabled`, `mobile_data_enabled`, `mobile_data_network_type`, `network_connected` and
 *    `network_type`. Each is a single read or a single computed decision over device state.
 *  - Everything else — every `*_set_state` toggle, `bluetooth_device_bond_create`/`_remove`/`_connect`/
 *    `_disconnect`, the Wi-Fi/Bluetooth *scans* and *picks*, the HTTP/ping/NSD/WoL/infrared/data-usage
 *    *network actions*, the `SHELL`-gated reads/writes, and the tethering/USB/hotspot-client reads with no
 *    honest public read API — is gated by omission (see [connectivityLookup]).
 *
 * ### The seam, copied from the Apps, Settings, Battery&Power, Sensor and Location blocks
 * Every device call lives behind the injected [ConnectivityReader] — a narrow, `android.*`-free contract,
 * the exact shape [dev.pleiades.masamune.apps.AppInspector], [dev.pleiades.masamune.apps.SystemSettings],
 * [dev.pleiades.masamune.apps.PowerState], [dev.pleiades.masamune.apps.SensorReader] and
 * [dev.pleiades.masamune.apps.LocationReader] give their categories. Two consequences, both deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole file
 *     is unit-testable against a fake on an ordinary JVM — a device is needed to run these, never to test
 *     their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [ConnectivityReader] provider and fails with
 *     [CONNECTIVITY_ABSENT] when there is no seam (the app process is not wired in, or it dropped
 *     mid-run). A read that returns `null` becomes a named [Outcome.Fail] ("could not be read") — **never**
 *     a fabricated `false`/`0`/empty connection or a silent NO. A real `false` / `Disconnected` / empty
 *     device list is a successful read routed to NO; only an unreadable state Fails.
 *
 * ### WATCH / WATCH_VALUE collapse to their one-shot form
 * The catalog marks most of these decisions WATCH-capable (test now, or suspend until the state changes).
 * The watching form needs the monitor subsystem this build does not have, so the one-shot condition — "is
 * Wi-Fi on *now*", "is a network connected *now*" — is what runs, which is exactly what a decision in a
 * running flow evaluates. This mirrors the Sensor band decisions in [ScalarBandSensorBlock], the Location
 * decisions in [dev.pleiades.masamune.flow.runtime.impl.LocationProviderEnabledBlock] and the
 * Battery&Power reads in [dev.pleiades.masamune.flow.runtime.impl.BatteryLevelBlock].
 *
 * ### The `networkTypes` / `subscriptionId` filter arguments are not modelled
 * `network_connected`, `network_type` and `mobile_data_network_type` carry a `networkTypes` selection and
 * a `subscriptionId`; several of the Automate multi-selects are declared here as a bare `flag`, and this
 * bounded one-shot slice does not reconstruct their selection semantics. Each block instead reads and
 * reports the device's **real active** network / default-subscription state and routes on it — the honest
 * "what is connected now", never a fabricated match. This is the same honest simplification by which the
 * Location blocks ignore `responsiveness`/`minDistance`: an argument with no faithful one-shot meaning is
 * documented as ignored rather than guessed.
 *
 * The composition helper [connectivityLookup] mirrors [locationLookup], [sensorLookup], [powerLookup],
 * [settingsLookup] and [appsLookup]: it returns the impls keyed by spec id so a caller composes
 * `connectivityLookup(provider)[id] ?: base.lookup(id)`.
 */

/** The sentence shown whenever a Connectivity block cannot reach a connectivity seam. Modelled on [LOCATION_ABSENT]. */
internal val CONNECTIVITY_ABSENT: String =
    "This connectivity block cannot act: no connectivity seam is available, so Masamune cannot read the " +
        "device's radios or network state. The seam is wired only inside the Android app process; when it " +
        "is absent the block fails by name rather than reporting a radio state that never was read."

// --------------------------------------------------------------------------- boolean is-enabled decisions

/**
 * The shared shape of the six pure "is this radio/mode on?" decisions — `airplane_mode_enabled`,
 * `wifi_enabled`, `wifi_ap_enabled`, `bluetooth_enabled`, `nfc_enabled` and `mobile_data_enabled`. Each
 * reads one `Boolean?` through the seam and routes YES when `true`, NO when `false`.
 *
 * The honest-gating rule is enforced once, here, for all six: an absent seam Fails with
 * [CONNECTIVITY_ABSENT], and a `null` reading (no such manager, or a permission-refused read) Fails **by
 * name** — never a fabricated `false` a downstream block would mistake for a real "off", and never a
 * silent NO from an unreadable radio. `false` is a *real* read routed to NO; only `null` Fails.
 */
internal class BooleanStateDecisionBlock(
    override val specId: String,
    private val connectivityProvider: () -> ConnectivityReader?,
    /** Reads the one boolean this decision keys on; `null` is the honest "could not be read". */
    private val read: suspend (ConnectivityReader) -> Boolean?,
    /** The human noun for the "…could not be read" failure sentence, e.g. "Wi-Fi state". */
    private val stateNoun: String,
) : BlockImpl {
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = connectivityProvider() ?: return Outcome.Fail(CONNECTIVITY_ABSENT)
        val enabled = read(reader)
            ?: return Outcome.Fail("$specId: the $stateNoun could not be read.")
        return Outcome.Proceed(if (enabled) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- Wi-Fi reads

/**
 * `wifi_network_connected` (Wi-Fi network connected) — is Wi-Fi joined to a network right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the association through the seam;
 * a [WifiConnection.Connected] binds every present connection-info field and routes YES, a
 * [WifiConnection.Disconnected] routes NO, and a `null` (the association could not be read — most often
 * the SSID needs `ACCESS_FINE_LOCATION` the process was not granted) Fails **by name**. A present-but-
 * absent info field is left **unbound**, never a fabricated blank SSID. The `ssid`/`bssid` *filter* args
 * are not modelled in this bounded slice (see file KDoc) — the block reports the real association.
 *
 * Carries `ACCESS_FINE_LOCATION` in the catalog; that is honored at run by the seam returning `null` (→ a
 * named Fail), not by leaving the block unregistered — the whole category assumes the app may hold it.
 */
internal class WifiNetworkConnectedBlock(
    private val connectivityProvider: () -> ConnectivityReader?,
) : BlockImpl {
    override val specId = "wifi_network_connected"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = connectivityProvider() ?: return Outcome.Fail(CONNECTIVITY_ABSENT)
        val connection = reader.wifiConnection()
            ?: return Outcome.Fail("wifi_network_connected: the Wi-Fi connection could not be read.")
        return when (connection) {
            is WifiConnection.Disconnected -> Outcome.Proceed(Port.NO)
            is WifiConnection.Connected -> Outcome.Proceed(Port.YES, connectionWrites(node, connection.info))
        }
    }

    /** Bind every present connection-info field onto its declared output; an absent field binds nothing. */
    private fun connectionWrites(node: FlowNode, info: WifiConnectionInfo): Map<String, Value> {
        val writes = LinkedHashMap<String, Value>()
        info.ssid?.let { node.outputs["varConnectedSsid"]?.bind(writes, Value.Text(it)) }
        info.bssid?.let { node.outputs["varConnectedBssid"]?.bind(writes, Value.Text(it)) }
        info.linkSpeedMbps?.let { node.outputs["varConnectedLinkSpeed"]?.bind(writes, Value.Num(it.toDouble())) }
        info.frequencyMhz?.let { node.outputs["varConnectedFrequency"]?.bind(writes, Value.Num(it.toDouble())) }
        info.capabilities?.let { node.outputs["varConnectedCapabilities"]?.bind(writes, Value.Text(it)) }
        info.ipAddress?.let { node.outputs["varConnectedIpAddress"]?.bind(writes, Value.Text(it)) }
        return writes
    }
}

/**
 * `wifi_signal_level` (Wi-Fi signal strength) — is the Wi-Fi signal within the requested band?
 *
 * DECISION: the one-shot form of the catalog's WATCH band decision, and the direct analogue of the Sensor
 * scalar bands. It reads the RSSI through the seam, **always** binds `varLevel` from it, and routes YES
 * when the level sits within `[minLevel, maxLevel]` (an unset bound is no constraint), NO otherwise. A
 * level the seam cannot read (Wi-Fi not connected, or unreadable) Fails **by name**, never a fabricated
 * `0` or a silent NO.
 */
internal class WifiSignalLevelBlock(
    private val connectivityProvider: () -> ConnectivityReader?,
) : BlockImpl {
    override val specId = "wifi_signal_level"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = connectivityProvider() ?: return Outcome.Fail(CONNECTIVITY_ABSENT)
        val level = reader.wifiSignalLevel()
            ?: return Outcome.Fail("wifi_signal_level: the Wi-Fi signal strength could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.bind(writes, Value.Num(level.toDouble()))
        val min = args["minLevel"].asNumOrNull()
        val max = args["maxLevel"].asNumOrNull()
        val within = (min == null || level >= min) && (max == null || level <= max)
        return Outcome.Proceed(if (within) Port.YES else Port.NO, writes)
    }
}

// --------------------------------------------------------------------------- Bluetooth read

/**
 * `bluetooth_device_connected` (Bluetooth device connected) — is a matching Bluetooth device connected?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the connected-device set through
 * the seam, filters it by the block's `deviceAddress`/`deviceName`/`deviceClass`/`paired` arguments, and
 * routes YES on the first match (binding its `varConnectedDevice*` outputs), NO when none match. A `null`
 * set (no adapter, or a `BLUETOOTH_CONNECT`-refused read) Fails **by name**; an **empty** set is a real
 * "nothing connected" routed to NO — the two are never conflated. The filters are compared
 * case-insensitively, and an absent filter is no constraint (the "unset bound is no constraint" rule the
 * Sensor bands and Location provider default share).
 *
 * Carries `BLUETOOTH_CONNECT` in the catalog — an ordinary runtime permission, honored at run by the seam
 * returning `null` (→ a named Fail), exactly as `ACCESS_FINE_LOCATION` is honored for the Location reads.
 */
internal class BluetoothDeviceConnectedBlock(
    private val connectivityProvider: () -> ConnectivityReader?,
) : BlockImpl {
    override val specId = "bluetooth_device_connected"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = connectivityProvider() ?: return Outcome.Fail(CONNECTIVITY_ABSENT)
        val devices = reader.connectedBluetoothDevices()
            ?: return Outcome.Fail("bluetooth_device_connected: the connected Bluetooth devices could not be read.")
        val wantAddress = args["deviceAddress"].asTextOrNull()?.takeIf { it.isNotBlank() }
        val wantName = args["deviceName"].asTextOrNull()?.takeIf { it.isNotBlank() }
        val wantClass = args["deviceClass"].asTextOrNull()?.takeIf { it.isNotBlank() }
        val pairedOnly = args["paired"].asFlag(default = false)
        val match = devices.firstOrNull { device ->
            (wantAddress == null || device.address.equalsIgnoreCase(wantAddress)) &&
                (wantName == null || device.name.equalsIgnoreCase(wantName)) &&
                (wantClass == null || device.deviceClass.equalsIgnoreCase(wantClass)) &&
                (!pairedOnly || device.paired)
        } ?: return Outcome.Proceed(Port.NO)
        val writes = LinkedHashMap<String, Value>()
        match.address?.let { node.outputs["varConnectedDeviceAddress"]?.bind(writes, Value.Text(it)) }
        match.name?.let { node.outputs["varConnectedDeviceName"]?.bind(writes, Value.Text(it)) }
        match.deviceClass?.let { node.outputs["varConnectedDeviceClass"]?.bind(writes, Value.Text(it)) }
        return Outcome.Proceed(Port.YES, writes)
    }
}

// --------------------------------------------------------------------------- mobile / overall network reads

/**
 * `mobile_data_network_type` (Mobile data network type) — is an active mobile-data generation present?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the mobile-data generation through
 * the seam, binds `varNetworkType` from it, and routes YES when a real generation (2G/3G/4G/5G) is active,
 * NO when [MobileNetworkGeneration.UNKNOWN] (no active mobile data / an unrecognized type). A `null` (the
 * type could not be read — most often the `READ_PHONE_STATE` grant is absent) Fails **by name**. The
 * `networkTypes`/`subscriptionId` filters are not modelled (see file KDoc).
 *
 * Carries `READ_PHONE_STATE` in the catalog — honored at run by the seam's `null` (→ named Fail), not by
 * omission, exactly as `ACCESS_FINE_LOCATION` is honored for the Location reads.
 */
internal class MobileDataNetworkTypeBlock(
    private val connectivityProvider: () -> ConnectivityReader?,
) : BlockImpl {
    override val specId = "mobile_data_network_type"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = connectivityProvider() ?: return Outcome.Fail(CONNECTIVITY_ABSENT)
        val generation = reader.mobileDataNetworkType()
            ?: return Outcome.Fail("mobile_data_network_type: the mobile data network type could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varNetworkType"]?.bind(writes, Value.Text(generation.label))
        val active = generation != MobileNetworkGeneration.UNKNOWN
        return Outcome.Proceed(if (active) Port.YES else Port.NO, writes)
    }
}

/**
 * `network_connected` (Network connected) — is an internet network active right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the active-network status through
 * the seam; a [NetworkStatus.Connected] binds `varNetworkType` (the transport's display name) and routes
 * YES, a [NetworkStatus.Disconnected] routes NO, and a `null` (the network state could not be read) Fails
 * **by name** — never a silent NO from an unreadable stack. The `networkTypes` filter is not modelled (see
 * file KDoc); the block reports the real active network.
 */
internal class NetworkConnectedBlock(
    private val connectivityProvider: () -> ConnectivityReader?,
) : BlockImpl {
    override val specId = "network_connected"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = connectivityProvider() ?: return Outcome.Fail(CONNECTIVITY_ABSENT)
        val status = reader.activeNetwork()
            ?: return Outcome.Fail("network_connected: the network state could not be read.")
        return when (status) {
            is NetworkStatus.Disconnected -> Outcome.Proceed(Port.NO)
            is NetworkStatus.Connected -> {
                val writes = LinkedHashMap<String, Value>()
                node.outputs["varNetworkType"]?.bind(writes, Value.Text(status.transport.displayName))
                Outcome.Proceed(Port.YES, writes)
            }
        }
    }
}

/**
 * `network_type` (Network type) — read the active network type.
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the active-network status through
 * the seam; a [NetworkStatus.Connected] binds `varNetworkType` (the transport display name) and
 * `varNetworkTypeName` (the human name) and routes YES, a [NetworkStatus.Disconnected] routes NO (no active
 * type), and a `null` Fails **by name**. The `networkTypes` filter is not modelled (see file KDoc); the
 * block reports the real active type.
 */
internal class NetworkTypeBlock(
    private val connectivityProvider: () -> ConnectivityReader?,
) : BlockImpl {
    override val specId = "network_type"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = connectivityProvider() ?: return Outcome.Fail(CONNECTIVITY_ABSENT)
        val status = reader.activeNetwork()
            ?: return Outcome.Fail("network_type: the network state could not be read.")
        return when (status) {
            is NetworkStatus.Disconnected -> Outcome.Proceed(Port.NO)
            is NetworkStatus.Connected -> {
                val writes = LinkedHashMap<String, Value>()
                node.outputs["varNetworkType"]?.bind(writes, Value.Text(status.transport.displayName))
                node.outputs["varNetworkTypeName"]?.bind(writes, Value.Text(status.typeName))
                Outcome.Proceed(Port.YES, writes)
            }
        }
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The twelve registered Connectivity one-shot impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [locationLookup], [sensorLookup], [powerLookup], [settingsLookup] and [appsLookup]: it always
 * returns the map, and the honest gate is the per-block gate-at-run (each fails with [CONNECTIVITY_ABSENT]
 * when the provider yields no seam), so a caller composes over its base registry exactly as the other
 * categories do:
 *
 * ```
 * val connectivity = connectivityLookup(connectivityReader)
 * fun lookup(id: String): BlockImpl? =
 *     connectivity[id] ?: location[id] ?: sensors[id] ?: power[id] ?: settings[id] ?: apps[id] ?: base.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's many remaining blocks are deliberately **not** here, so at run time the scheduler finds
 * no impl and gates them by the honest-by-omission mechanism the catalog's own `requires` set (or the
 * block's own shape) expresses. Because the [ConnectivityReader] seam is read-only, every gated block is a
 * *write*, a *device connect/scan*, a *UI picker*, a *network action*, a `SHELL`-gated call or a read with
 * no honest public API — none of which a read-only seam can host, so there is nothing to
 * build-but-not-register here. They are omitted on these honest grounds:
 *  - **Radio writes (toggles).** `airplane_mode_set_state`, `wifi_set_state`, `wifi_ap_set_state`,
 *    `bluetooth_set_state`, `nfc_set_state`, `mobile_data_set_state` (also `SHELL`), and the tethering
 *    writes `bluetooth_tether_set_state`, `usb_tether_set_state` (`SHELL`), `ethernet_tether_set_state`,
 *    `restrict_background_data_set_state` (`SHELL`) and `usb_function_set` (`SHELL`). Turning a radio on/off
 *    is a mutation a read-only seam cannot honestly model — registering a no-op impl would fake a write
 *    that would always be refused, exactly as the Battery&Power and Location writes are gated.
 *  - **Bluetooth device mutations / awaits.** `bluetooth_device_bond_create`, `bluetooth_device_bond_remove`,
 *    `bluetooth_device_connect` (AWAIT), `bluetooth_device_disconnect` and `bluetooth_gatt_read` pair,
 *    connect, disconnect or write/read a remote device — writes and awaits, not state reads.
 *  - **Scans and pickers (triggers / UI).** `bluetooth_device_scan` (`BLUETOOTH_SCAN`),
 *    `bluetooth_device_pick`, `wifi_network_scan` (AWAIT), `wifi_network_pick`, `wifi_network_connect`
 *    (AWAIT), `nfc_tag_scanned`, `nfc_tag_write` and `wifi_ap_clients_connected` start a scan, wait for a
 *    connect, or drive user-facing UI — none is a one-shot state read.
 *  - **Network actions, not device reads.** `http_request`, `http_accept_tcp`, `http_response`, `ping`,
 *    `nsd_discover`, `network_throughput` (a live over-time measurement), `wake_on_lan_send`,
 *    `infrared_transmit`, `data_usage` and `data_network_default` perform network I/O or measurements over
 *    time rather than reading a single connection-state value.
 *  - **`SHELL`-gated read.** `restrict_background_data_enabled` carries `Requirement.Uid2000`; a privileged
 *    read a read-only unprivileged seam cannot honestly serve, gated exactly as `device_reboot` etc. are.
 *  - **No honest public read API.** `bluetooth_tether_enabled`, `usb_tether_enabled`, `usb_configured` and
 *    `usb_device_attached` have no stable unprivileged public API to read their state through this seam and
 *    are left gated rather than faked. (`wifi_ap_enabled` *is* registered because the catalog declares it an
 *    unprivileged read; its real glue reads best-effort and returns `null` — an honest Fail — when it can't.)
 */
fun connectivityLookup(provider: () -> ConnectivityReader?): Map<String, BlockImpl> = listOf(
    BooleanStateDecisionBlock(
        "airplane_mode_enabled", provider, { it.isAirplaneModeEnabled() }, "airplane mode state",
    ),
    BooleanStateDecisionBlock("wifi_enabled", provider, { it.isWifiEnabled() }, "Wi-Fi state"),
    BooleanStateDecisionBlock("wifi_ap_enabled", provider, { it.isWifiHotspotEnabled() }, "Wi-Fi hotspot state"),
    BooleanStateDecisionBlock("bluetooth_enabled", provider, { it.isBluetoothEnabled() }, "Bluetooth state"),
    BooleanStateDecisionBlock("nfc_enabled", provider, { it.isNfcEnabled() }, "NFC state"),
    BooleanStateDecisionBlock("mobile_data_enabled", provider, { it.isMobileDataEnabled() }, "mobile data state"),
    WifiNetworkConnectedBlock(provider),
    WifiSignalLevelBlock(provider),
    BluetoothDeviceConnectedBlock(provider),
    MobileDataNetworkTypeBlock(provider),
    NetworkConnectedBlock(provider),
    NetworkTypeBlock(provider),
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

/** Case-insensitive equality that treats a null receiver as "no value to match" (never equal). */
private fun String?.equalsIgnoreCase(other: String): Boolean = this != null && this.equals(other, ignoreCase = true)
