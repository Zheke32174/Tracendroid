package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * Radios, tethering, and the network stack up to HTTP.
 *
 * Two gates dominate. Reading a Wi-Fi SSID or scanning for access points has counted as
 * location access since Android 8, so those blocks carry [ACCESS_FINE_LOCATION] however
 * little they look like location blocks; and toggling a radio lost its public API version by
 * version, so the `set state` blocks that still work at all carry [SHELL].
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val CONNECTIVITY_BLOCKS: List<BlockSpec> = category(BlockCategory.CONNECTIVITY) {
    airplaneModeAndBluetooth()
    dataNetworksHttpAndNfc()
    pingUsbTetheringAndWifi()
}

/** Airplane mode, Bluetooth devices, bonding, GATT and Bluetooth tethering. */
private fun Blocks.airplaneModeAndBluetooth() {
    decision(
        "airplane_mode_enabled", "Airplane mode enabled",
        "Checks if airplane mode is enabled (mobile radio is off).",
        proceed = WATCH,
    )
    action(
        "airplane_mode_set_state", "Airplane mode set state",
        "Enables or disables airplane mode. Needed the privileged service only before " +
            "Android 6, so it is not gated on it here.",
        args = listOf(
            flag("state", "Airplane mode", "off"),
        ),
    )
    decision(
        "bluetooth_device_bond_create", "Bluetooth device pair",
        "Pairs with another Bluetooth device. The NO path is followed if the other device " +
            "was not found, pairing failed or was cancelled.",
        args = listOf(
            text("deviceAddress", "Device address", "first paired device found"),
            text("deviceName", "Device name", "first paired device found"),
        ),
        requires = setOf(BLUETOOTH_CONNECT),
    )
    decision(
        "bluetooth_device_bond_remove", "Bluetooth device unpair",
        "Forgets/unpairs a paired Bluetooth device. The NO path is followed if the other " +
            "device was not found, or unpairing failed.",
        args = listOf(
            text("deviceAddress", "Device address", "first paired device found"),
            text("deviceName", "Device name", "first paired device found"),
        ),
        requires = setOf(BLUETOOTH_CONNECT),
    )
    decision(
        "bluetooth_device_connect", "Bluetooth device connect",
        "Connects to a Bluetooth device. The NO path is executed if the device is not found, " +
            "or the system failed connect for some other unknown reason.",
        proceed = AWAIT,
        args = listOf(
            text("deviceAddress", "Device address", "first paired device found"),
            text("deviceName", "Device name", "first paired device found"),
            any("profile", "Device profile", "Headset"),
        ),
        requires = setOf(BLUETOOTH_CONNECT),
    )
    decision(
        "bluetooth_device_connected", "Bluetooth device connected",
        "Checks if a Bluetooth device is connected, or disconnected.",
        proceed = WATCH,
        args = listOf(
            text("deviceAddress", "Device address", "any"),
            text("deviceName", "Device name", "any"),
            any("deviceClass", "Device type", "any"),
            flag("paired", "Paired"),
        ),
        outputs = listOf(
            out("varConnectedDeviceAddress", "Connected device address"),
            out("varConnectedDeviceName", "Connected device name"),
            out("varConnectedDeviceClass", "Connected device type"),
        ),
        requires = setOf(BLUETOOTH_CONNECT),
    )
    action(
        "bluetooth_device_disconnect", "Bluetooth device disconnect",
        "Disconnects a Bluetooth device.",
        args = listOf(
            text("deviceAddress", "Device address", "first paired device found"),
            text("deviceName", "Device name", "first paired device found"),
            any("profile", "Device profile", "Headset"),
        ),
        requires = setOf(BLUETOOTH_CONNECT),
    )
    decision(
        "bluetooth_device_pick", "Bluetooth device pick",
        "Lets the user choose a nearby Bluetooth device.",
        args = listOf(
            any("deviceClass", "Device type", "any"),
            flag("pairedOnly", "Paired devices", "no"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varDeviceAddress", "Picked device address"),
            out("varDeviceName", "Picked device name"),
            out("varDeviceClass", "Picked device type"),
        ),
    )
    action(
        "bluetooth_device_scan", "Bluetooth device scan",
        "Scans for nearby Bluetooth devices.",
        args = listOf(
            any("mode", "Scan mode", "Active discovery"),
            any("deviceClass", "Device type", "any"),
            flag("pairedOnly", "Paired devices", "no"),
            flag("connectableOnly", "Connectable only"),
        ),
        outputs = listOf(
            out("varDeviceAddresses", "Device addresses"),
            out("varDeviceNames", "Device names"),
            out("varDeviceAdvertisements", "Device advertisements"),
            out("varDeviceRssis", "Signal strengths"),
        ),
        requires = setOf(BLUETOOTH_SCAN),
    )
    decision(
        "bluetooth_enabled", "Bluetooth enabled",
        "Checks if Bluetooth is enabled.",
        proceed = WATCH,
    )
    action(
        "bluetooth_set_state", "Bluetooth set state",
        "Enables or disables Bluetooth.",
        args = listOf(
            flag("state", "Bluetooth"),
        ),
    )
    decision(
        "bluetooth_gatt_read", "Bluetooth GATT read",
        "Reads a characteristic value from a Bluetooth GATT service hosted on a remote " +
            "device. The NO path is followed if the remote device, service or characteristic was " +
            "not found.",
        proceed = WATCH,
        args = listOf(
            text("deviceAddress", "Device address", "first paired device found"),
            text("deviceName", "Device name", "first paired device found"),
            any("serviceUuid", "Service UUID"),
            any("serviceInstanceId", "Service instance ID", "lowest id present"),
            any("characteristicUuid", "Characteristic UUID"),
            any("characteristicInstanceId", "Characteristic instance ID", "lowest id present"),
            any("valueFormat", "Value format"),
            any("valueOffset", "Value offset", "0"),
        ),
        outputs = listOf(
            out("varResult", "Characteristic value"),
        ),
        requires = setOf(BLUETOOTH_CONNECT),
    )
    decision(
        "bluetooth_tether_enabled", "Bluetooth tethering enabled",
        "Checks if Bluetooth tethering is enabled.",
    )
    action(
        "bluetooth_tether_set_state", "Bluetooth tethering set state",
        "Enables or disables Bluetooth tethering.",
        args = listOf(
            flag("state", "Bluetooth tethering", "disable"),
        ),
    )
}

/** Mobile data, network capability and type, HTTP client and server, and NFC. */
private fun Blocks.dataNetworksHttpAndNfc() {
    decision(
        "data_network_default", "Data network default",
        "Checks the capabilities of the default data network.",
        proceed = WATCH,
        args = listOf(
            any("transports", "Network interfaces", "any"),
            any("capabilities", "Network capabilities", "none"),
        ),
        outputs = listOf(
            out("varIpAddresses", "IP addresses"),
            out("varInterfaceName", "Interface name"),
            out("varDownloadBandwidth", "Download link speed"),
            out("varUploadBandwidth", "Upload link speed"),
        ),
    )
    action(
        "data_usage", "Data usage",
        "Gets the network data usage statistics. Needed the privileged service only before " +
            "Android 6, so it is not gated on it here.",
        args = listOf(
            any("networkInterface", "Network interface", "Mobile"),
            any("minTimestamp", "Minimum timestamp", "first recorded usage"),
            any("maxTimestamp", "Maximum timestamp", "last recorded usage"),
            text("packageName", "Package", "for all apps"),
            any("subscriptionId", "Subscription id", "all subscriptions"),
            any("ssid", "Network name", "all networks"),
        ),
        outputs = listOf(
            out("varTransferred", "Bytes transferred"),
            out("varDownloaded", "Bytes downloaded"),
            out("varUploaded", "Bytes uploaded"),
        ),
    )
    action(
        "ethernet_tether_set_state", "Ethernet tethering set state",
        "Enables or disables Ethernet tethering.",
        args = listOf(
            flag("state", "Ethernet tethering", "disable"),
        ),
    )
    action(
        "http_accept_tcp", "HTTP accept",
        "Accepts an incoming HTTP request, download its content.",
        args = listOf(
            any("networkInterface", "Network interface", "Loopback, i"),
            num("port", "Port", "8443 if an Keychain alias (HTTPS) is used, 8080 otherwise"),
            any("alias", "Keychain alias"),
            text("uri", "Request URI", "/"),
            any("method", "Request method", "GET"),
            any("contentType", "Request content type", "Any"),
            any("saveBody", "Save request", "Don't save"),
            any("bodyPath", "Request content path", "a file in the \"Download\" directory"),
        ),
        outputs = listOf(
            out("varRequestUri", "Request URI"),
            out("varRequestHeaders", "Request headers"),
            out("varRequestBody", "Request content"),
        ),
    )
    action(
        "http_request", "HTTP request",
        "Performs an HTTP request, download content from the internet.",
        args = listOf(
            text("url", "Request URL"),
            any("method", "Request method", "GET, or POST when a body is supplied"),
            any("contentType", "Request content type"),
            arr("bodyPart", "Request content body", "no content"),
            arr("bodyPath", "Request content path", "no file upload"),
            text("account", "Basic authorization account", "no authorization"),
            dict("headers", "Request headers"),
            any("networkInterface", "Network interface", "the default data network interface"),
            num("timeout", "Timeout", "15 seconds"),
            any("alias", "Keychain alias"),
            flag("trust", "Certificate"),
            flag("dontRedirect", "Redirect", "to follow redirects"),
            any("saveResponse", "Save response", "Don't save"),
            any("responsePath", "Response path", "a file in the \"Download\" directory"),
        ),
        outputs = listOf(
            out("varResponseCode", "Response status code"),
            out("varResponseBody", "Response content"),
            out("varResponseHeaders", "Response headers"),
        ),
    )
    action(
        "http_response", "HTTP response",
        "Sends a response to an incoming HTTP request.",
        args = listOf(
            any("statusCode", "Response status code", "OK"),
            any("contentType", "Response content type"),
            arr("bodyPart", "Response content body", "no content"),
            arr("bodyPath", "Response content path", "no file upload"),
            dict("headers", "Response headers"),
        ),
    )
    action(
        "infrared_transmit", "Infrared transmit",
        "Transmits an IR command.",
        args = listOf(
            num("carrierFrequency", "Carrier frequency", "38 000 (38 kHz)"),
            arr("pattern", "Command pattern"),
        ),
    )
    decision(
        "mobile_data_enabled", "Mobile data enabled",
        "Checks if mobile data is enabled.",
        proceed = WATCH,
        args = listOf(
            any("subscriptionId", "Subscription id", "the system default data subscription"),
        ),
    )
    action(
        "mobile_data_set_state", "Mobile data set state",
        "Enables or disables mobile data.",
        args = listOf(
            flag("state", "Mobile data"),
            any("subscriptionId", "Subscription id", "the system default data subscription"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "mobile_data_network_type", "Mobile data network type",
        "Checks the active mobile data network type.",
        proceed = WATCH,
        args = listOf(
            flag("networkTypes", "Network types"),
            any("subscriptionId", "Subscription id", "the system default subscription"),
        ),
        outputs = listOf(
            out("varNetworkType", "Network type"),
        ),
        requires = setOf(READ_PHONE_STATE),
    )
    decision(
        "network_connected", "Network connected",
        "Checks if a network is connected, the network used for internet connections.",
        proceed = WATCH,
        args = listOf(
            any("networkTypes", "Network types"),
        ),
        outputs = listOf(
            out("varNetworkType", "Network type"),
        ),
    )
    decision(
        "network_throughput", "Network throughput",
        "Checks the current network throughput.",
        proceed = WATCH,
        args = listOf(
            any("direction", "Data direction", "Both"),
            num("minLevel", "Minimum throughput"),
            num("maxLevel", "Maximum throughput"),
            any("networkInterface", "Network interface", "all network interfaces"),
            text("packageName", "Package", "all apps"),
        ),
        outputs = listOf(
            out("varLevel", "Current throughput"),
        ),
    )
    action(
        "nsd_discover", "Network service discover",
        "Scans the network to discover application services. Currently only supports DNS " +
            "Service Discovery (DNS-SD) on a local network over Multicast DNS (mDNS).",
        args = listOf(
            any("serviceType", "Service type"),
            text("serviceName", "Service name", "any name, may contain glob pattern"),
            num("resultLimit", "Maximum results", "no limit, i"),
            num("duration", "Maximum duration", "3 seconds"),
        ),
        outputs = listOf(
            out("varFoundServiceNames", "Service names"),
            out("varResolvedHosts", "Service hosts"),
            out("varResolvedPorts", "Service ports"),
            out("varResolvedAttributes", "Service attributes"),
        ),
    )
    decision(
        "network_type", "Network type",
        "Checks the active network type.",
        proceed = WATCH,
        args = listOf(
            flag("networkTypes", "Network types"),
        ),
        outputs = listOf(
            out("varNetworkType", "Network type"),
            out("varNetworkTypeName", "Network type name"),
        ),
    )
    decision(
        "nfc_enabled", "NFC enabled",
        "Checks if NFC is enabled.",
        proceed = WATCH,
    )
    action(
        "nfc_set_state", "NFC set state",
        "Enables or disables NFC.",
        args = listOf(
            flag("state", "NFC"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "nfc_tag_scanned", "NFC tag scanned",
        "Waits for an NFC tag to be scanned by the user.",
        args = listOf(
            any("tagType", "Tag type", "Automate"),
        ),
        outputs = listOf(
            out("varScannedId", "Scanned tag ID"),
            out("varScannedContent", "Scanned tag content"),
        ),
    )
    decision(
        "nfc_tag_write", "NFC tag write",
        "Lets the user write content to NFC tags.",
        args = listOf(
            text("content", "Content"),
            any("ndefType", "NDEF type", "Automate"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varScannedId", "Written tag ID"),
        ),
    )
}

/** Reachability, background-data restriction, USB, and the Wi-Fi family. */
private fun Blocks.pingUsbTetheringAndWifi() {
    decision(
        "ping", "Ping",
        "Checks if a host is reachable.",
        args = listOf(
            text("host", "Host or IP address"),
            any("protocol", "Protocol", "IPv4"),
            any("networkInterface", "Network interface", "the default data network interface"),
            any("ttl", "Maximum hops", "system dependant"),
            num("timeout", "Timeout", "3 seconds"),
        ),
    )
    decision(
        "restrict_background_data_enabled", "Restrict background data enabled",
        "Checks if restrict background data is enabled.",
        requires = setOf(SHELL),
    )
    action(
        "restrict_background_data_set_state", "Restrict background data set state",
        "Enables or disables restrict background data.",
        args = listOf(
            flag("state", "Restrict background data", "disable"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "usb_function_set", "USB configuration set",
        "Sets the current USB configuration, e.g. to use MTP or PTP.",
        args = listOf(
            any("functions", "Configuration", "No data transfer"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "usb_configured", "USB configured",
        "Checks if and how USB is currently configured, e.g. for MTP or PTP. The YES path is " +
            "followed if USB is configured and configured as configurations.",
        proceed = WATCH,
        args = listOf(
            any("functions", "Configurations", "any"),
        ),
        outputs = listOf(
            out("varCurrentFunctions", "Current configurations"),
        ),
    )
    decision(
        "usb_device_attached", "USB device attached",
        "Checks if a USB device is currently attached.",
        proceed = WATCH,
        args = listOf(
            any("deviceProductId", "Device product id", "any"),
            text("deviceProductName", "Device product name", "any"),
            any("deviceVendorId", "Device vendor id", "any"),
            text("deviceManufacturerName", "Device manufacturer name", "any"),
            any("deviceClass", "Device type", "any"),
            any("deviceSubclass", "Device subtype", "any"),
        ),
        outputs = listOf(
            out("varAttachedDeviceProductId", "Attached device product id"),
            out("varAttachedDeviceProductName", "Attached device product name"),
            out("varAttachedDeviceVendorId", "Attached device vendor id"),
            out("varAttachedDeviceManufacturerName", "Attached device manufacturer name"),
            out("varAttachedDeviceClass", "Attached device type"),
            out("varAttachedDeviceSubclass", "Attached device subtype"),
        ),
    )
    decision(
        "usb_tether_enabled", "USB tethering enabled",
        "Checks if USB tethering is enabled.",
    )
    action(
        "usb_tether_set_state", "USB tethering set state",
        "Enables or disables USB tethering.",
        args = listOf(
            flag("state", "Bluetooth tethering", "disable"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "wake_on_lan_send", "Wake-on-LAN send",
        "Sends a Wake-on-LAN packet (UDP) to awake a remote device.",
        args = listOf(
            num("macAddress", "MAC address"),
            text("account", "SecureOn account"),
            text("host", "Host or IP address", "to broadcast to network interface"),
            num("port", "Port", "9"),
            any("networkInterface", "Network interface", "the default data network interface"),
        ),
    )
    decision(
        "wifi_enabled", "Wi-Fi enabled",
        "Checks if Wi-FI is enabled.",
        proceed = WATCH,
    )
    action(
        "wifi_ap_clients_connected", "Wi-Fi hotspot clients connected",
        "Gets information about the clients currently connected to the Wi-Fi hotspot.",
        proceed = WATCH_VALUE,
        outputs = listOf(
            out("varClientCount", "Client count"),
            out("varClientMacAccesses", "Clients MAC addresses"),
        ),
    )
    decision(
        "wifi_ap_enabled", "Wi-Fi hotspot enabled",
        "Checks if Wi-Fi hotspot is enabled.",
        proceed = WATCH,
    )
    action(
        "wifi_ap_set_state", "Wi-Fi hotspot set state",
        "Enables or disables Wi-Fi hotspot. The privileged service is an optional workaround " +
            "for Android 7.1+ DHCP problems, not a requirement, so it is not gated on it here.",
        args = listOf(
            flag("state", "Wi-Fi hotspot"),
        ),
    )
    decision(
        "wifi_network_connect", "Wi-Fi network connect",
        "Connects to a Wi-Fi network (access point). The NO path is executed if there's a " +
            "failure to configure or connect to the specified network.",
        proceed = AWAIT,
        args = listOf(
            any("ssid", "Network name"),
            text("bssid", "Network address"),
            text("account", "Passkey account", "an open network"),
            flag("addNetwork", "Add network"),
            flag("disableOthers", "Exclusive"),
        ),
    )
    decision(
        "wifi_network_connected", "Wi-Fi network connected",
        "Checks if Wi-Fi is connected to a network (access point).",
        proceed = WATCH,
        args = listOf(
            text("ssid", "Network name"),
            text("bssid", "Network address"),
        ),
        outputs = listOf(
            out("varConnectedSsid", "Network name"),
            out("varConnectedBssid", "Network address"),
            out("varConnectedLinkSpeed", "Network link speed"),
            out("varConnectedFrequency", "Network frequency"),
            out("varConnectedCapabilities", "Network security"),
            out("varConnectedIpAddress", "IP address"),
        ),
        requires = setOf(ACCESS_FINE_LOCATION),
    )
    action(
        "wifi_network_scan", "Wi-Fi network scan",
        "Scans for nearby Wi-Fi networks (access points).",
        proceed = AWAIT,
        args = listOf(
            any("security", "Security", "to include all networks"),
            flag("configuredOnly", "Configured networks"),
            flag("passive", "Passive"),
        ),
        outputs = listOf(
            out("varNetworkSsids", "Network names"),
            out("varNetworkBssids", "Network addresses"),
            out("varNetworkCapabilities", "Network security"),
            out("varNetworkRssis", "Signal strengths"),
        ),
        requires = setOf(ACCESS_FINE_LOCATION),
    )
    decision(
        "wifi_network_pick", "Wi-Fi network pick",
        "Lets the user choose a nearby Wi-Fi network (access point).",
        args = listOf(
            any("security", "Security", "to show all networks"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varSsid", "Network name"),
            out("varBssid", "Network address"),
        ),
    )
    action(
        "wifi_set_state", "Wi-Fi set state",
        "Enables or disables Wi-Fi.",
        args = listOf(
            flag("state", "Wi-Fi"),
        ),
    )
    decision(
        "wifi_signal_level", "Wi-Fi signal strength",
        "Checks Wi-Fi signal strength.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum signal strength"),
            num("maxLevel", "Maximum signal strength"),
        ),
        outputs = listOf(
            out("varLevel", "Current signal strength"),
        ),
    )
}
