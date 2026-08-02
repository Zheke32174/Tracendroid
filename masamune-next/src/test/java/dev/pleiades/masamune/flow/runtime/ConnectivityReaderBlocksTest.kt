package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.BluetoothDeviceInfo
import dev.pleiades.masamune.apps.ConnectivityReader
import dev.pleiades.masamune.apps.MobileNetworkGeneration
import dev.pleiades.masamune.apps.NetworkStatus
import dev.pleiades.masamune.apps.NetworkTransport
import dev.pleiades.masamune.apps.WifiConnection
import dev.pleiades.masamune.apps.WifiConnectionInfo
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.connectivityLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Connectivity one-shot blocks branch and bind correctly — run against a
 * [FakeConnectivityReader] on the JVM, never a device, which is exactly what the `android.*`-free
 * [ConnectivityReader] seam buys (the same seam shape the Apps, Settings, Battery&Power, Sensor and
 * Location blocks use). Each test drives a block the way the runtime does — an args map of resolved
 * [Value]s and a [FlowNode] carrying the output bindings — and asserts on the [Outcome] and its writes.
 * The honest failure shape is the point of the coverage: a radio/network state the device cannot read is
 * a visible [Outcome.Fail], never a fabricated `false`/`0`/empty connection and never a silent NO; a real
 * "off / not connected" is a NO, distinct from an unreadable state (a Fail). The absent-seam path is
 * checked for all twelve blocks.
 */
class ConnectivityReaderBlocksTest {

    /**
     * A fully scriptable fake standing in for the real radio/network stack. A `null` reading is exactly
     * what a device with no manager / a refused permission would answer, and the block turns that `null`
     * into a named Fail. Each field is independently scriptable so a test can exercise one block's read in
     * isolation.
     */
    private class FakeConnectivityReader(
        private val airplane: Boolean? = null,
        private val wifi: Boolean? = null,
        private val wifiAp: Boolean? = null,
        private val bluetooth: Boolean? = null,
        private val nfc: Boolean? = null,
        private val mobileData: Boolean? = null,
        private val wifiConnection: WifiConnection? = null,
        private val wifiSignal: Int? = null,
        private val bluetoothDevices: List<BluetoothDeviceInfo>? = null,
        private val mobileType: MobileNetworkGeneration? = null,
        private val network: NetworkStatus? = null,
    ) : ConnectivityReader {
        override suspend fun isAirplaneModeEnabled(): Boolean? = airplane
        override suspend fun isWifiEnabled(): Boolean? = wifi
        override suspend fun isWifiHotspotEnabled(): Boolean? = wifiAp
        override suspend fun wifiConnection(): WifiConnection? = wifiConnection
        override suspend fun wifiSignalLevel(): Int? = wifiSignal
        override suspend fun isBluetoothEnabled(): Boolean? = bluetooth
        override suspend fun connectedBluetoothDevices(): List<BluetoothDeviceInfo>? = bluetoothDevices
        override suspend fun isNfcEnabled(): Boolean? = nfc
        override suspend fun isMobileDataEnabled(): Boolean? = mobileData
        override suspend fun mobileDataNetworkType(): MobileNetworkGeneration? = mobileType
        override suspend fun activeNetwork(): NetworkStatus? = network
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    /** Fetch a single registered impl from the lookup composed over [seam]. */
    private fun block(specId: String, seam: ConnectivityReader?): BlockImpl =
        connectivityLookup { seam }[specId] ?: error("no registered block for $specId")

    // ------------------------------------------------------------------ boolean is-enabled decisions

    @Test fun booleanDecisionsRouteYesWhenOn() = runTest {
        val seam = FakeConnectivityReader(
            airplane = true, wifi = true, wifiAp = true, bluetooth = true, nfc = true, mobileData = true,
        )
        for (id in booleanSpecIds) {
            val outcome = block(id, seam).run(fiber(), node(id), emptyMap())
            assertEquals("$id must route YES when on", Port.YES, (outcome as Outcome.Proceed).port)
        }
    }

    @Test fun booleanDecisionsRouteNoWhenOff() = runTest {
        val seam = FakeConnectivityReader(
            airplane = false, wifi = false, wifiAp = false, bluetooth = false, nfc = false, mobileData = false,
        )
        for (id in booleanSpecIds) {
            val outcome = block(id, seam).run(fiber(), node(id), emptyMap())
            assertEquals("$id: a real off is NO, not a Fail", Port.NO, (outcome as Outcome.Proceed).port)
        }
    }

    @Test fun booleanDecisionsFailWhenUnreadable() = runTest {
        // Every field null → each boolean read is "could not be read" → a named Fail, never a silent NO.
        val seam = FakeConnectivityReader()
        for (id in booleanSpecIds) {
            val outcome = block(id, seam).run(fiber(), node(id), emptyMap())
            assertTrue("$id must Fail when unreadable", outcome is Outcome.Fail)
        }
    }

    // ------------------------------------------------------------------ wifi_network_connected

    @Test fun wifiNetworkConnectedBindsEveryPresentFieldAndYes() = runTest {
        val info = WifiConnectionInfo(
            ssid = "HomeNet",
            bssid = "aa:bb:cc:dd:ee:ff",
            linkSpeedMbps = 433,
            frequencyMhz = 5180,
            capabilities = "WPA2",
            ipAddress = "192.168.1.42",
        )
        val seam = FakeConnectivityReader(wifiConnection = WifiConnection.Connected(info))
        val outcome = block("wifi_network_connected", seam).run(
            fiber(),
            node(
                "wifi_network_connected",
                "varConnectedSsid" to "ssid",
                "varConnectedBssid" to "bssid",
                "varConnectedLinkSpeed" to "speed",
                "varConnectedFrequency" to "freq",
                "varConnectedCapabilities" to "caps",
                "varConnectedIpAddress" to "ip",
            ),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("HomeNet"), proceed.writes["ssid"])
        assertEquals(Value.Text("aa:bb:cc:dd:ee:ff"), proceed.writes["bssid"])
        assertEquals(Value.Num(433.0), proceed.writes["speed"])
        assertEquals(Value.Num(5180.0), proceed.writes["freq"])
        assertEquals(Value.Text("WPA2"), proceed.writes["caps"])
        assertEquals(Value.Text("192.168.1.42"), proceed.writes["ip"])
    }

    @Test fun wifiNetworkConnectedLeavesAbsentFieldsUnbound() = runTest {
        // Only an SSID is known: every other output stays unbound, never a fabricated blank.
        val seam = FakeConnectivityReader(wifiConnection = WifiConnection.Connected(WifiConnectionInfo(ssid = "S")))
        val outcome = block("wifi_network_connected", seam).run(
            fiber(),
            node("wifi_network_connected", "varConnectedSsid" to "ssid", "varConnectedBssid" to "bssid"),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Value.Text("S"), proceed.writes["ssid"])
        assertNull("an absent field binds nothing", proceed.writes["bssid"])
    }

    @Test fun wifiNetworkConnectedDisconnectedRoutesNo() = runTest {
        val seam = FakeConnectivityReader(wifiConnection = WifiConnection.Disconnected)
        val outcome = block("wifi_network_connected", seam).run(
            fiber(), node("wifi_network_connected", "varConnectedSsid" to "ssid"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("not joined is NO, not a Fail", Port.NO, proceed.port)
        assertNull(proceed.writes["ssid"])
    }

    @Test fun wifiNetworkConnectedFailsWhenUnreadable() = runTest {
        // Null association (SSID needs a location grant that is absent) → a Fail, distinct from Disconnected.
        val outcome = block("wifi_network_connected", FakeConnectivityReader(wifiConnection = null)).run(
            fiber(), node("wifi_network_connected", "varConnectedSsid" to "ssid"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["ssid"])
    }

    // ------------------------------------------------------------------ wifi_signal_level (band)

    @Test fun wifiSignalLevelBindsAndYesWithinBand() = runTest {
        val seam = FakeConnectivityReader(wifiSignal = -55)
        val outcome = block("wifi_signal_level", seam).run(
            fiber(),
            node("wifi_signal_level", "varLevel" to "lvl"),
            mapOf("minLevel" to Value.Num(-70.0), "maxLevel" to Value.Num(-40.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(-55.0), proceed.writes["lvl"])
    }

    @Test fun wifiSignalLevelNoOutsideBandButStillBinds() = runTest {
        val seam = FakeConnectivityReader(wifiSignal = -85)
        val outcome = block("wifi_signal_level", seam).run(
            fiber(),
            node("wifi_signal_level", "varLevel" to "lvl"),
            mapOf("minLevel" to Value.Num(-70.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("outside the band is NO", Port.NO, proceed.port)
        assertEquals("varLevel binds the real reading regardless of branch", Value.Num(-85.0), proceed.writes["lvl"])
    }

    @Test fun wifiSignalLevelFailsWhenUnreadable() = runTest {
        val outcome = block("wifi_signal_level", FakeConnectivityReader(wifiSignal = null)).run(
            fiber(), node("wifi_signal_level", "varLevel" to "lvl"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["lvl"])
    }

    // ------------------------------------------------------------------ bluetooth_device_connected

    @Test fun bluetoothDeviceConnectedYesBindsFirstMatch() = runTest {
        val devices = listOf(
            BluetoothDeviceInfo(address = "11:22:33:44:55:66", name = "Buds", deviceClass = "0x0400", paired = true),
        )
        val seam = FakeConnectivityReader(bluetoothDevices = devices)
        val outcome = block("bluetooth_device_connected", seam).run(
            fiber(),
            node(
                "bluetooth_device_connected",
                "varConnectedDeviceAddress" to "addr",
                "varConnectedDeviceName" to "name",
                "varConnectedDeviceClass" to "cls",
            ),
            mapOf("deviceName" to Value.Text("buds")), // case-insensitive filter
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("11:22:33:44:55:66"), proceed.writes["addr"])
        assertEquals(Value.Text("Buds"), proceed.writes["name"])
        assertEquals(Value.Text("0x0400"), proceed.writes["cls"])
    }

    @Test fun bluetoothDeviceConnectedNoWhenNoneMatch() = runTest {
        val devices = listOf(BluetoothDeviceInfo(address = "AA", name = "Speaker", paired = false))
        val seam = FakeConnectivityReader(bluetoothDevices = devices)
        val outcome = block("bluetooth_device_connected", seam).run(
            fiber(),
            node("bluetooth_device_connected", "varConnectedDeviceName" to "name"),
            mapOf("deviceName" to Value.Text("Watch")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("no match is NO", Port.NO, proceed.port)
        assertNull(proceed.writes["name"])
    }

    @Test fun bluetoothDeviceConnectedNoOnEmptyList() = runTest {
        // An empty list is a real read: nothing is connected → NO, never a Fail.
        val outcome = block("bluetooth_device_connected", FakeConnectivityReader(bluetoothDevices = emptyList())).run(
            fiber(), node("bluetooth_device_connected"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun bluetoothDeviceConnectedPairedFilterExcludesUnpaired() = runTest {
        val devices = listOf(BluetoothDeviceInfo(address = "AA", name = "Gadget", paired = false))
        val seam = FakeConnectivityReader(bluetoothDevices = devices)
        val outcome = block("bluetooth_device_connected", seam).run(
            fiber(), node("bluetooth_device_connected"), mapOf("paired" to Value.Text("true")),
        )
        assertEquals("an unpaired device is excluded by the paired filter", Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun bluetoothDeviceConnectedFailsWhenUnreadable() = runTest {
        // Null list = no adapter / BLUETOOTH_CONNECT refused → Fail, distinct from an empty list.
        val outcome = block("bluetooth_device_connected", FakeConnectivityReader(bluetoothDevices = null)).run(
            fiber(), node("bluetooth_device_connected"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ mobile_data_network_type

    @Test fun mobileDataNetworkTypeYesBindsGeneration() = runTest {
        val seam = FakeConnectivityReader(mobileType = MobileNetworkGeneration.GEN_5G)
        val outcome = block("mobile_data_network_type", seam).run(
            fiber(), node("mobile_data_network_type", "varNetworkType" to "type"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("5G"), proceed.writes["type"])
    }

    @Test fun mobileDataNetworkTypeUnknownRoutesNo() = runTest {
        // UNKNOWN is a real read: no active mobile data → NO (and still binds the type), never a Fail.
        val seam = FakeConnectivityReader(mobileType = MobileNetworkGeneration.UNKNOWN)
        val outcome = block("mobile_data_network_type", seam).run(
            fiber(), node("mobile_data_network_type", "varNetworkType" to "type"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        assertEquals(Value.Text("Unknown"), proceed.writes["type"])
    }

    @Test fun mobileDataNetworkTypeFailsWhenUnreadable() = runTest {
        // Null = READ_PHONE_STATE refused / no TelephonyManager → Fail, distinct from UNKNOWN.
        val outcome = block("mobile_data_network_type", FakeConnectivityReader(mobileType = null)).run(
            fiber(), node("mobile_data_network_type", "varNetworkType" to "type"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["type"])
    }

    // ------------------------------------------------------------------ network_connected

    @Test fun networkConnectedYesBindsType() = runTest {
        val seam = FakeConnectivityReader(network = NetworkStatus.Connected(NetworkTransport.WIFI, "Wi-Fi"))
        val outcome = block("network_connected", seam).run(
            fiber(), node("network_connected", "varNetworkType" to "type"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("Wi-Fi"), proceed.writes["type"])
    }

    @Test fun networkConnectedDisconnectedRoutesNo() = runTest {
        val seam = FakeConnectivityReader(network = NetworkStatus.Disconnected)
        val outcome = block("network_connected", seam).run(
            fiber(), node("network_connected", "varNetworkType" to "type"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("no active network is NO", Port.NO, proceed.port)
        assertNull(proceed.writes["type"])
    }

    @Test fun networkConnectedFailsWhenUnreadable() = runTest {
        val outcome = block("network_connected", FakeConnectivityReader(network = null)).run(
            fiber(), node("network_connected", "varNetworkType" to "type"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ network_type

    @Test fun networkTypeYesBindsTypeAndName() = runTest {
        val seam = FakeConnectivityReader(network = NetworkStatus.Connected(NetworkTransport.MOBILE, "Mobile"))
        val outcome = block("network_type", seam).run(
            fiber(),
            node("network_type", "varNetworkType" to "type", "varNetworkTypeName" to "name"),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("Mobile"), proceed.writes["type"])
        assertEquals(Value.Text("Mobile"), proceed.writes["name"])
    }

    @Test fun networkTypeDisconnectedRoutesNo() = runTest {
        val seam = FakeConnectivityReader(network = NetworkStatus.Disconnected)
        val outcome = block("network_type", seam).run(
            fiber(), node("network_type", "varNetworkType" to "type"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun networkTypeFailsWhenUnreadable() = runTest {
        val outcome = block("network_type", FakeConnectivityReader(network = null)).run(
            fiber(), node("network_type", "varNetworkType" to "type"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ absent seam (all twelve)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val lookup = connectivityLookup { null }
        for ((id, impl) in lookup) {
            val outcome = impl.run(fiber(), node(id), emptyMap())
            assertTrue("$id must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("connectivity seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun connectivityLookupExposesExactlyTheTwelveRegisteredBlocks() {
        val lookup = connectivityLookup { null }
        assertEquals(
            setOf(
                "airplane_mode_enabled",
                "wifi_enabled",
                "wifi_ap_enabled",
                "wifi_network_connected",
                "wifi_signal_level",
                "bluetooth_enabled",
                "bluetooth_device_connected",
                "nfc_enabled",
                "mobile_data_enabled",
                "mobile_data_network_type",
                "network_connected",
                "network_type",
            ),
            lookup.keys,
        )
        // Gated by omission — every radio toggle (write) is unregistered.
        assertNull(lookup["airplane_mode_set_state"])
        assertNull(lookup["wifi_set_state"])
        assertNull(lookup["wifi_ap_set_state"])
        assertNull(lookup["bluetooth_set_state"])
        assertNull(lookup["nfc_set_state"]) // also SHELL
        assertNull(lookup["mobile_data_set_state"]) // SHELL
        // Bluetooth device mutations / awaits, scans and pickers.
        assertNull(lookup["bluetooth_device_bond_create"])
        assertNull(lookup["bluetooth_device_connect"]) // AWAIT
        assertNull(lookup["bluetooth_device_disconnect"])
        assertNull(lookup["bluetooth_gatt_read"])
        assertNull(lookup["bluetooth_device_scan"]) // BLUETOOTH_SCAN
        assertNull(lookup["bluetooth_device_pick"])
        assertNull(lookup["wifi_network_scan"]) // AWAIT
        assertNull(lookup["wifi_network_connect"]) // AWAIT
        assertNull(lookup["wifi_network_pick"])
        assertNull(lookup["nfc_tag_scanned"])
        assertNull(lookup["nfc_tag_write"])
        assertNull(lookup["wifi_ap_clients_connected"])
        // Network actions, not device reads.
        assertNull(lookup["http_request"])
        assertNull(lookup["ping"])
        assertNull(lookup["nsd_discover"])
        assertNull(lookup["network_throughput"])
        assertNull(lookup["data_usage"])
        assertNull(lookup["data_network_default"])
        // SHELL read, and reads with no honest public API.
        assertNull(lookup["restrict_background_data_enabled"]) // SHELL
        assertNull(lookup["bluetooth_tether_enabled"])
        assertNull(lookup["usb_tether_enabled"])
        assertNull(lookup["usb_configured"])
        assertNull(lookup["usb_device_attached"])
        // Composes over the layers below via `connectivityLookup(...)[id] ?: base`.
        assertNull(lookup["location_get"])
        assertNull(lookup["ambient_light"])
        assertNull(lookup["battery_level"])
        assertEquals("network_type", lookup["network_type"]!!.specId)
    }

    private companion object {
        /** The six pure boolean is-enabled decisions, driven together. */
        val booleanSpecIds = listOf(
            "airplane_mode_enabled",
            "wifi_enabled",
            "wifi_ap_enabled",
            "bluetooth_enabled",
            "nfc_enabled",
            "mobile_data_enabled",
        )
    }
}
