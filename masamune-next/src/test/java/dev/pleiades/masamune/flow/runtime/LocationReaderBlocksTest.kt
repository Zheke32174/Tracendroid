package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.ForwardGeocode
import dev.pleiades.masamune.apps.GeocodedAddress
import dev.pleiades.masamune.apps.LocationFix
import dev.pleiades.masamune.apps.LocationProvider
import dev.pleiades.masamune.apps.LocationReader
import dev.pleiades.masamune.apps.ReverseGeocode
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.GeocodingBlock
import dev.pleiades.masamune.flow.runtime.impl.GeocodingReverseBlock
import dev.pleiades.masamune.flow.runtime.impl.LocationAtBlock
import dev.pleiades.masamune.flow.runtime.impl.LocationGetBlock
import dev.pleiades.masamune.flow.runtime.impl.LocationProviderEnabledBlock
import dev.pleiades.masamune.flow.runtime.impl.locationLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Location one-shot blocks branch and bind correctly — run against a
 * [FakeLocationReader] on the JVM, never a device, which is exactly what the `android.*`-free
 * [LocationReader] seam buys (the same seam shape the Apps, Settings, Battery&Power and Sensor blocks
 * use). Each test drives a block the way the runtime does — an args map of resolved [Value]s and a
 * [FlowNode] carrying the output bindings — and asserts on the [Outcome] and its writes. The honest
 * failure shape is the point of the coverage: a place the device cannot read is a visible
 * [Outcome.Fail], never a fabricated `0,0` and never a silent NO; a geocoder that cleanly finds nothing
 * is a NO, distinct from a geocoder that could not be reached (a Fail). The absent-seam path is checked
 * for all five blocks.
 */
class LocationReaderBlocksTest {

    /**
     * A fully scriptable fake standing in for the real location subsystem. A `null` reading is exactly
     * what a device with no fix / no permission / an unreachable geocoder would answer, and the block
     * turns that `null` into a named Fail. [lastProvider]/[lastMaxFixAge] record the last
     * [currentLocation] call so a test can assert `location_get` forwards its parsed arguments.
     */
    private class FakeLocationReader(
        private val fix: LocationFix? = null,
        private val providerEnabled: Map<LocationProvider, Boolean> = emptyMap(),
        private val reverse: ReverseGeocode? = null,
        private val forward: ForwardGeocode? = null,
    ) : LocationReader {
        var lastProvider: LocationProvider? = null
        var lastMaxFixAge: Long? = null

        override suspend fun currentLocation(
            provider: LocationProvider,
            maxFixAgeMillis: Long?,
        ): LocationFix? {
            lastProvider = provider
            lastMaxFixAge = maxFixAgeMillis
            return fix
        }

        override suspend fun isProviderEnabled(provider: LocationProvider): Boolean? =
            providerEnabled[provider]

        override suspend fun reverseGeocode(
            latitude: Double,
            longitude: Double,
            languageTag: String?,
        ): ReverseGeocode? = reverse

        override suspend fun forwardGeocode(
            locationName: String,
            languageTag: String?,
        ): ForwardGeocode? = forward
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    // ------------------------------------------------------------------ geocoding_reverse

    @Test fun geocodingReverseBindsEveryPresentFieldAndYes() = runTest {
        val address = GeocodedAddress(
            locationName = "Statue of Liberty",
            addressLines = listOf("Liberty Island", "New York, NY 10004"),
            featureName = "Statue of Liberty",
            thoroughfare = "Liberty Island",
            subThoroughfare = "1",
            locality = "New York",
            subLocality = "Manhattan",
            adminArea = "New York",
            subAdminArea = "New York County",
            postalCode = "10004",
            countryName = "United States",
            countryCode = "US",
        )
        val seam = FakeLocationReader(reverse = ReverseGeocode.Matched(address))
        val outcome = GeocodingReverseBlock { seam }.run(
            fiber(),
            node(
                "geocoding_reverse",
                "varLocationName" to "name",
                "varAddressLines" to "lines",
                "varFeatureName" to "feat",
                "varThoroughfare" to "thr",
                "varSubThoroughfare" to "sthr",
                "varLocality" to "loc",
                "varSubLocality" to "sloc",
                "varAdminArea" to "adm",
                "varSubAdminArea" to "sadm",
                "varPostalCode" to "zip",
                "varCountryName" to "cn",
                "varCountryCode" to "cc",
            ),
            mapOf("latitude" to Value.Num(40.6892), "longitude" to Value.Num(-74.0445)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("Statue of Liberty"), proceed.writes["name"])
        assertEquals(
            Value.ArrayV(listOf(Value.Text("Liberty Island"), Value.Text("New York, NY 10004"))),
            proceed.writes["lines"],
        )
        assertEquals(Value.Text("New York"), proceed.writes["loc"])
        assertEquals(Value.Text("10004"), proceed.writes["zip"])
        assertEquals(Value.Text("US"), proceed.writes["cc"])
    }

    @Test fun geocodingReverseLeavesAbsentFieldsUnbound() = runTest {
        // Only a country is known: every other output must stay unbound, never a fabricated blank.
        val seam = FakeLocationReader(reverse = ReverseGeocode.Matched(GeocodedAddress(countryCode = "FR")))
        val outcome = GeocodingReverseBlock { seam }.run(
            fiber(),
            node("geocoding_reverse", "varCountryCode" to "cc", "varLocality" to "loc"),
            mapOf("latitude" to Value.Num(48.85), "longitude" to Value.Num(2.35)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Value.Text("FR"), proceed.writes["cc"])
        assertNull("an unknown field binds nothing", proceed.writes["loc"])
    }

    @Test fun geocodingReverseNoMatchRoutesNo() = runTest {
        val seam = FakeLocationReader(reverse = ReverseGeocode.NoMatch)
        val outcome = GeocodingReverseBlock { seam }.run(
            fiber(),
            node("geocoding_reverse", "varLocationName" to "name"),
            mapOf("latitude" to Value.Num(0.0), "longitude" to Value.Num(0.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("a clean no-match is NO, not a Fail", Port.NO, proceed.port)
        assertNull(proceed.writes["name"])
    }

    @Test fun geocodingReverseFailsWhenGeocoderUnreachable() = runTest {
        // A null seam reading is "the geocoder could not be reached" — a Fail, distinct from NoMatch.
        val outcome = GeocodingReverseBlock { FakeLocationReader(reverse = null) }.run(
            fiber(),
            node("geocoding_reverse", "varLocationName" to "name"),
            mapOf("latitude" to Value.Num(0.0), "longitude" to Value.Num(0.0)),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["name"])
    }

    @Test fun geocodingReverseFailsWithoutCoordinates() = runTest {
        val outcome = GeocodingReverseBlock { FakeLocationReader(reverse = ReverseGeocode.NoMatch) }.run(
            fiber(),
            node("geocoding_reverse"),
            emptyMap(), // no latitude/longitude to reverse
        )
        assertTrue("a missing target coordinate Fails by name", outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ geocoding

    @Test fun geocodingBindsCoordinateAndYes() = runTest {
        val seam = FakeLocationReader(forward = ForwardGeocode.Matched(51.5074, -0.1278))
        val outcome = GeocodingBlock { seam }.run(
            fiber(),
            node("geocoding", "varDecodedLatitude" to "lat", "varDecodedLongitude" to "lon"),
            mapOf("locationName" to Value.Text("London")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(51.5074), proceed.writes["lat"])
        assertEquals(Value.Num(-0.1278), proceed.writes["lon"])
    }

    @Test fun geocodingNoMatchRoutesNo() = runTest {
        val seam = FakeLocationReader(forward = ForwardGeocode.NoMatch)
        val outcome = GeocodingBlock { seam }.run(
            fiber(),
            node("geocoding", "varDecodedLatitude" to "lat"),
            mapOf("locationName" to Value.Text("Nowherecityville")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        assertNull(proceed.writes["lat"])
    }

    @Test fun geocodingFailsWhenGeocoderUnreachable() = runTest {
        val outcome = GeocodingBlock { FakeLocationReader(forward = null) }.run(
            fiber(),
            node("geocoding", "varDecodedLatitude" to "lat"),
            mapOf("locationName" to Value.Text("London")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    @Test fun geocodingFailsWithBlankName() = runTest {
        val outcome = GeocodingBlock { FakeLocationReader(forward = ForwardGeocode.NoMatch) }.run(
            fiber(),
            node("geocoding"),
            mapOf("locationName" to Value.Text("   ")),
        )
        assertTrue("a blank location name Fails by name", outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ location_at

    @Test fun locationAtYesWhenWithinRadius() = runTest {
        // Device exactly at the target → distance 0 → within the default 250 m radius → YES.
        val seam = FakeLocationReader(fix = LocationFix(latitude = 40.0, longitude = -73.0))
        val outcome = LocationAtBlock { seam }.run(
            fiber(),
            node("location_at"),
            mapOf("latitude" to Value.Num(40.0), "longitude" to Value.Num(-73.0)),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun locationAtNoWhenOutsideRadius() = runTest {
        // One degree of longitude at the equator is ~111 km — far outside the default 250 m radius → NO.
        val seam = FakeLocationReader(fix = LocationFix(latitude = 0.0, longitude = 0.0))
        val outcome = LocationAtBlock { seam }.run(
            fiber(),
            node("location_at"),
            mapOf("latitude" to Value.Num(0.0), "longitude" to Value.Num(1.0)),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun locationAtHonorsExplicitRadius() = runTest {
        // ~111 km apart: NO under a 1 km radius, YES under a 200 km radius.
        val seam = FakeLocationReader(fix = LocationFix(latitude = 0.0, longitude = 0.0))
        val tight = LocationAtBlock { seam }.run(
            fiber(), node("location_at"),
            mapOf("latitude" to Value.Num(0.0), "longitude" to Value.Num(1.0), "radius" to Value.Num(1_000.0)),
        )
        assertEquals(Port.NO, (tight as Outcome.Proceed).port)
        val loose = LocationAtBlock { seam }.run(
            fiber(), node("location_at"),
            mapOf("latitude" to Value.Num(0.0), "longitude" to Value.Num(1.0), "radius" to Value.Num(200_000.0)),
        )
        assertEquals(Port.YES, (loose as Outcome.Proceed).port)
    }

    @Test fun locationAtFailsWhenFixUnreadable() = runTest {
        // No fix (permission absent / no cached location) must Fail by name, never a silent NO.
        val outcome = LocationAtBlock { FakeLocationReader(fix = null) }.run(
            fiber(),
            node("location_at"),
            mapOf("latitude" to Value.Num(40.0), "longitude" to Value.Num(-73.0)),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    @Test fun locationAtFailsWithoutTarget() = runTest {
        val outcome = LocationAtBlock { FakeLocationReader(fix = LocationFix(1.0, 2.0)) }.run(
            fiber(),
            node("location_at"),
            emptyMap(), // no target latitude/longitude
        )
        assertTrue("a missing target Fails by name", outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ location_get

    @Test fun locationGetBindsPresentFixFieldsAndOk() = runTest {
        val fix = LocationFix(
            latitude = 37.4219,
            longitude = -122.0840,
            altitudeMeters = 12.0,
            bearingDegrees = 90.0,
            speedMetersPerSecond = 1.5,
            accuracyMeters = 5.0,
            timestampMillis = 1_700_000_000_000L,
            provider = "gps",
        )
        val seam = FakeLocationReader(fix = fix)
        val outcome = LocationGetBlock { seam }.run(
            fiber(),
            node(
                "location_get",
                "varFixLatitude" to "lat",
                "varFixLongitude" to "lon",
                "varFixAltitude" to "alt",
                "varFixBearing" to "brg",
                "varFixSpeed" to "spd",
                "varFixAccuracy" to "acc",
                "varFixTimestamp" to "ts",
                "varFixProvider" to "prov",
            ),
            mapOf("provider" to Value.Text("gps"), "maxFixAge" to Value.Num(60_000.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.OK, proceed.port)
        assertEquals(Value.Num(37.4219), proceed.writes["lat"])
        assertEquals(Value.Num(-122.0840), proceed.writes["lon"])
        assertEquals(Value.Num(12.0), proceed.writes["alt"])
        assertEquals(Value.Num(90.0), proceed.writes["brg"])
        assertEquals(Value.Num(1.5), proceed.writes["spd"])
        assertEquals(Value.Num(5.0), proceed.writes["acc"])
        assertEquals(Value.Num(1_700_000_000_000.0), proceed.writes["ts"])
        assertEquals(Value.Text("gps"), proceed.writes["prov"])
        // The parsed provider and maxFixAge are forwarded to the seam, not silently dropped.
        assertEquals(LocationProvider.GPS, seam.lastProvider)
        assertEquals(60_000L, seam.lastMaxFixAge)
    }

    @Test fun locationGetLeavesAbsentOptionalsAndFlagsUnbound() = runTest {
        // A coarse fix: only lat/lon. Every optional output, and varFixFlags, must stay unbound.
        val seam = FakeLocationReader(fix = LocationFix(latitude = 1.0, longitude = 2.0))
        val outcome = LocationGetBlock { seam }.run(
            fiber(),
            node(
                "location_get",
                "varFixLatitude" to "lat",
                "varFixBearing" to "brg",
                "varFixSpeed" to "spd",
                "varFixFlags" to "flags",
            ),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Value.Num(1.0), proceed.writes["lat"])
        assertNull("no bearing on a coarse fix binds nothing", proceed.writes["brg"])
        assertNull("no speed on a coarse fix binds nothing", proceed.writes["spd"])
        assertNull("varFixFlags is unmodelled and stays unbound", proceed.writes["flags"])
        // Default provider is Balanced when the arg is absent.
        assertEquals(LocationProvider.BALANCED, seam.lastProvider)
        assertNull(seam.lastMaxFixAge)
    }

    @Test fun locationGetFailsWhenFixUnreadable() = runTest {
        val outcome = LocationGetBlock { FakeLocationReader(fix = null) }.run(
            fiber(),
            node("location_get", "varFixLatitude" to "lat"),
            emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["lat"])
    }

    @Test fun locationGetFailsOnUnrecognizedProvider() = runTest {
        val outcome = LocationGetBlock { FakeLocationReader(fix = LocationFix(1.0, 2.0)) }.run(
            fiber(),
            node("location_get", "varFixLatitude" to "lat"),
            mapOf("provider" to Value.Text("teleport")),
        )
        assertTrue("an unrecognized provider Fails by name", outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ location_provider_enabled

    @Test fun providerEnabledYesWhenOn() = runTest {
        val seam = FakeLocationReader(providerEnabled = mapOf(LocationProvider.GPS to true))
        val outcome = LocationProviderEnabledBlock { seam }.run(
            fiber(),
            node("location_provider_enabled"),
            emptyMap(), // default provider is GPS
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun providerEnabledNoWhenOff() = runTest {
        val seam = FakeLocationReader(providerEnabled = mapOf(LocationProvider.NETWORK to false))
        val outcome = LocationProviderEnabledBlock { seam }.run(
            fiber(),
            node("location_provider_enabled"),
            mapOf("provider" to Value.Text("network")),
        )
        assertEquals("a real disabled provider is NO, not a Fail", Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun providerEnabledFailsWhenUnreadable() = runTest {
        // An empty map means the provider state could not be read → Fail, never a silent NO.
        val outcome = LocationProviderEnabledBlock { FakeLocationReader() }.run(
            fiber(),
            node("location_provider_enabled"),
            mapOf("provider" to Value.Text("gps")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    @Test fun providerEnabledFailsOnUnrecognizedProvider() = runTest {
        val seam = FakeLocationReader(providerEnabled = mapOf(LocationProvider.GPS to true))
        val outcome = LocationProviderEnabledBlock { seam }.run(
            fiber(),
            node("location_provider_enabled"),
            mapOf("provider" to Value.Text("galileo")),
        )
        assertTrue("an unrecognized provider Fails by name", outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ absent seam (all five)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val absent: () -> LocationReader? = { null }
        val blocks = listOf(
            GeocodingReverseBlock(absent) to node("geocoding_reverse"),
            GeocodingBlock(absent) to node("geocoding"),
            LocationAtBlock(absent) to node("location_at"),
            LocationGetBlock(absent) to node("location_get"),
            LocationProviderEnabledBlock(absent) to node("location_provider_enabled"),
        )
        for ((block, flowNode) in blocks) {
            val outcome = block.run(
                fiber(),
                flowNode,
                mapOf(
                    "latitude" to Value.Num(1.0),
                    "longitude" to Value.Num(2.0),
                    "locationName" to Value.Text("London"),
                ),
            )
            assertTrue("${block.specId} must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("location seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun locationLookupExposesTheFiveRegisteredBlocksBySpecId() {
        val lookup = locationLookup { null }
        assertEquals(
            setOf(
                "geocoding_reverse",
                "geocoding",
                "location_at",
                "location_get",
                "location_provider_enabled",
            ),
            lookup.keys,
        )
        // Gated by omission — a privileged write (SHELL), a mock-provider write, map-UI, a network
        // service, and the over-time geofence form are not registered.
        assertNull(lookup["location_provider_set_state"]) // requires = SHELL
        assertNull(lookup["location_mock"])
        assertNull(lookup["location_pick"])
        assertNull(lookup["location_show"])
        assertNull(lookup["weather"])
        // Mirrors the layers below: composes over a base registry via `locationLookup(...)[id] ?: base`.
        assertNull(lookup["ambient_light"])
        assertNull(lookup["battery_level"])
        assertNull(lookup["app_installed"])
        assertEquals("location_get", lookup["location_get"]!!.specId)
    }
}
